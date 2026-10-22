package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * World packs (".praline-world"): one world's painted map, trail, spots, stories, recipes and library, as the map
 * editor writes them (format: tools/map-editor/WORLD-FORMAT.md). The map scene plays them.
 *
 * Bundled worlds live unzipped in assets/worlds/<id>/; worlds opened from a file are kept as they came in
 * filesDir/worlds/<id>.praline-world and read entry by entry. A pack is untrusted input, like a recipe file:
 * names, sizes, counts and numbers are checked, and anything odd is dropped or the pack is refused.
 */
object WorldPack {
    const val EXT = ".praline-world"
    const val FORMAT = "praline-world"
    const val V = 1
    const val PACK_MAX = 25L * 1024 * 1024
    const val ENTRY_MAX = 8L * 1024 * 1024
    const val ENTRIES_MAX = 400
    private const val JSON_MAX = 1L * 1024 * 1024
    /** Pictures larger than this on a side are refused (a slice is 1236 × 1575, a sheet at most 2048 × 2048). */
    const val PICTURE_SIDE_MAX = 2048
    private val ID = Regex("[a-z0-9-]{1,32}")
    private val SPOT_ID = Regex("[a-z0-9-]{1,24}")
    private val NAME = Regex("(world\\.json|(slices|recipes|library)/[A-Za-z0-9._-]{1,80})")

    class BadPack(msg: String) : Exception(msg)

    /** Where a pack's files come from. */
    interface Source {
        fun open(name: String): InputStream?
        fun close() {}
    }

    class AssetSource(private val c: Context, val dir: String) : Source {
        override fun open(name: String): InputStream? = try { c.assets.open("$dir/$name") } catch (e: Exception) { null }
    }

    class ZipSource(val file: File) : Source {
        private var zip: ZipFile? = null
        @Synchronized private fun zip(): ZipFile { val z = zip ?: ZipFile(file); zip = z; return z }
        @Synchronized override fun open(name: String): InputStream? {
            if (!NAME.matches(name)) return null
            return try { val z = zip(); val e = z.getEntry(name) ?: return null; if (e.size > ENTRY_MAX) null else z.getInputStream(e) } catch (e: Exception) { null }
        }
        @Synchronized override fun close() { try { zip?.close() } catch (e: Exception) { }; zip = null }
    }

    // ---- the world, as read ---------------------------------------------------------------------
    class Slice(val file: String, val top: Float, val h: Float)
    class Title(val text: String, val x: Float, val y: Float, val size: Float, val color: Int, val rotate: Float, val script: Boolean)
    class Reward(val kind: String, val name: String, val item: String)
    class Spot(val id: String, val story: Boolean, val name: String, val along: Float, val x: Float, val y: Float, val left: Boolean,
               val xp: Int, val text: String, val recipe: String, val icon: String, val reward: Reward?)
    class Lib(val id: String, val kind: String, val file: String, val w: Int, val h: Int, val ax: Float, val ay: Float, val ppu: Float,
              val frameW: Int, val frameH: Int, val cols: Int, val frames: Int, val fps: Float, val loop: String, val add: Boolean, val fx: String)
    class Item(val id: String, val lib: String, val x: Float, val y: Float, val scale: Float, val rot: Float, val flip: Boolean,
               val over: Boolean, val z: Int, val alpha: Float, val bobAmp: Float, val bobPeriod: Float, val params: JSONObject)

    class World(
        val id: String, val kind: String, val name: String, val intro: String,
        val width: Float, val height: Float, val scale: Float, val title: Title?,
        val slices: List<Slice>, val trailX: FloatArray, val trailY: FloatArray,
        val spots: List<Spot>, val library: Map<String, Lib>, val items: List<Item>,
        val endTitle: String, val endStory: String, val source: Source
    )

    // ---- reading ---------------------------------------------------------------------------------
    fun load(src: Source): World {
        val raw = src.open("world.json")?.use { readCapped(it, JSON_MAX) } ?: throw BadPack("no world.json")
        val o = try { JSONObject(String(raw)) } catch (e: Exception) { throw BadPack("world.json isn't JSON") }
        return parse(o, src)
    }

    fun parse(o: JSONObject, src: Source): World {
        if (o.optString("format") != FORMAT) throw BadPack("not a world pack")
        val v = o.optInt("v", 0)
        if (v > V) throw BadPack("newer")
        val id = o.optString("id")
        if (!ID.matches(id)) throw BadPack("bad world id")
        val width = num(o, "width", 412f, 100f, 2000f)
        val height = num(o, "height", 3150f, 200f, 20000f)

        val slices = ArrayList<Slice>()
        val sl = o.optJSONArray("slices") ?: JSONArray()
        for (i in 0 until Math.min(sl.length(), 64)) {
            val s = sl.optJSONObject(i) ?: continue
            val f = s.optString("file")
            if (!f.startsWith("slices/") || !NAME.matches(f)) continue
            slices.add(Slice(f, num(s, "top", 0f, 0f, height), num(s, "height", 525f, 1f, height)))
        }

        val tr = o.optJSONObject("trail")?.optJSONArray("points") ?: JSONArray()
        val n = Math.min(tr.length(), 8000)
        val tx = FloatArray(n); val ty = FloatArray(n)
        for (i in 0 until n) {
            val p = tr.optJSONArray(i)
            tx[i] = clamp(p?.optDouble(0, 0.0) ?: 0.0, 0f, width); ty[i] = clamp(p?.optDouble(1, 0.0) ?: 0.0, 0f, height)
        }

        val spots = ArrayList<Spot>()
        val seen = HashSet<String>()
        val sp = o.optJSONArray("spots") ?: JSONArray()
        var lastAlong = -1f
        for (i in 0 until Math.min(sp.length(), 60)) {
            val s = sp.optJSONObject(i) ?: continue
            val sid = s.optString("id")
            if (!SPOT_ID.matches(sid) || !seen.add(sid)) throw BadPack("bad spot id")
            val story = s.optString("kind", "recipe") == "story"
            val along = num(s, "along", 0f, 0f, 1f)
            if (along < lastAlong) throw BadPack("spots out of order")
            lastAlong = along
            val rec = s.optString("recipe")
            if (!story && !(rec.startsWith("recipes/") && NAME.matches(rec))) throw BadPack("recipe spot without a recipe")
            val rw = s.optJSONObject("reward")
            val reward = if (story && rw != null) Reward(rw.optString("kind", "decoration"), rw.optString("name").take(40), rw.optString("item")) else null
            spots.add(Spot(sid, story, s.optString("name").take(60), along, num(s, "x", 0f, 0f, width), num(s, "y", 0f, 0f, height),
                s.optString("side") == "left", num(s, "xp", 100f, 0f, 100000f).toInt(), s.optString("story").take(600),
                if (story) "" else rec, s.optString("icon"), reward))
        }

        val lib = HashMap<String, Lib>()
        val lo = o.optJSONObject("library") ?: JSONObject()
        var count = 0
        for (k in lo.keys()) {
            if (++count > 300) break
            val e = lo.optJSONObject(k) ?: continue
            val kind = e.optString("kind")
            val file = e.optString("file")
            if (file.isNotEmpty() && !(file.startsWith("library/") && NAME.matches(file))) continue
            if (kind != "fx" && file.isEmpty()) continue
            val frames = num(e, "frames", 1f, 1f, 48f).toInt()
            lib[k] = Lib(k, kind, file, num(e, "w", 0f, 0f, 2048f).toInt(), num(e, "h", 0f, 0f, 2048f).toInt(),
                num(e, "ax", 0f, -4096f, 4096f), num(e, "ay", 0f, -4096f, 4096f), num(e, "ppu", 3f, 0.5f, 8f),
                num(e, "frameW", 1f, 1f, 2048f).toInt(), num(e, "frameH", 1f, 1f, 2048f).toInt(), num(e, "cols", 1f, 1f, 48f).toInt(),
                frames, num(e, "fps", 12f, 1f, 30f), e.optString("loop", "loop"), e.optString("blend") == "add", e.optString("fx"))
        }

        val items = ArrayList<Item>()
        val it = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until Math.min(it.length(), 200)) {
            val e = it.optJSONObject(i) ?: continue
            val l = e.optString("lib")
            if (!lib.containsKey(l)) continue
            val bob = e.optJSONObject("bob")
            items.add(Item(e.optString("id"), l, num(e, "x", 0f, -width, 2 * width), num(e, "y", 0f, -height, 2 * height),
                num(e, "scale", 1f, 0.05f, 20f), num(e, "rot", 0f, -360f, 360f), e.optBoolean("flip"), e.optString("layer") == "over",
                num(e, "z", 0f, -1000f, 1000f).toInt(), num(e, "alpha", 1f, 0f, 1f),
                if (bob != null) num(bob, "amp", 0f, 0f, 200f) else 0f, if (bob != null) num(bob, "period", 0f, 0f, 120f) else 0f,
                e.optJSONObject("params") ?: JSONObject()))
        }
        // lower z first, then the ones further up the map first, so nearer things overlap
        items.sortWith(Comparator { a, b -> if (a.z != b.z) a.z - b.z else java.lang.Float.compare(a.y, b.y) })

        val t = o.optJSONObject("title")
        val title = if (t != null && t.optString("text").isNotEmpty()) Title(t.optString("text").take(60), num(t, "x", width / 2, 0f, width),
            num(t, "y", 96f, 0f, height), num(t, "size", 38f, 6f, 200f), color(t.optString("color"), 0xFF3B2414.toInt()),
            num(t, "rotate", 0f, -45f, 45f), t.optString("font", "title") != "body") else null
        val end = o.optJSONObject("ending")
        return World(id, o.optString("kind", "mission"), o.optString("name").take(60).ifEmpty { id }, o.optString("intro").take(600),
            width, height, num(o, "scale", 3f, 0.5f, 8f), title, slices, tx, ty, spots, lib, items,
            end?.optString("title")?.take(60) ?: "", end?.optString("story")?.take(600) ?: "", src)
    }

    private fun num(o: JSONObject, k: String, def: Float, lo: Float, hi: Float): Float {
        val d = o.optDouble(k, def.toDouble())
        return if (d.isNaN() || d.isInfinite()) def else clamp(d, lo, hi)
    }
    private fun clamp(d: Double, lo: Float, hi: Float): Float = Math.max(lo.toDouble(), Math.min(hi.toDouble(), d)).toFloat()

    /** "#RRGGBB" or "#AARRGGBB", else [def]. */
    fun color(s: String?, def: Int): Int {
        if (s == null || !s.startsWith("#") || (s.length != 7 && s.length != 9)) return def
        val v = try { java.lang.Long.parseLong(s.substring(1), 16) } catch (e: Exception) { return def }
        return if (s.length == 7) (0xFF000000L or v).toInt() else v.toInt()
    }

    fun readCapped(input: InputStream, max: Long): ByteArray? {
        val bo = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val k = input.read(buf); if (k < 0) break
            if (bo.size() + k > max) return null
            bo.write(buf, 0, k)
        }
        return bo.toByteArray()
    }

    /** Decodes a picture of the pack, shrunk by powers of two until it is no wider than [maxW] px (0 = full size). */
    fun picture(src: Source, name: String, maxW: Int, opaque: Boolean): Bitmap? {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        // decoding bounds only always returns null; the size lands in [o]
        val input = src.open(name) ?: return null
        try { input.use { BitmapFactory.decodeStream(it, null, o) } } catch (e: Throwable) { return null }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (o.outWidth / sample > PICTURE_SIDE_MAX || o.outHeight / sample > PICTURE_SIDE_MAX * 4) sample *= 2
        if (maxW > 0) while (o.outWidth / (sample * 2) >= maxW) sample *= 2
        val d = BitmapFactory.Options(); d.inSampleSize = sample
        if (opaque) d.inPreferredConfig = Bitmap.Config.RGB_565
        return try { src.open(name)?.use { BitmapFactory.decodeStream(it, null, d) } } catch (e: Throwable) { null }
    }

    /** The first recipe of a spot's .praline file, with its pictures' bytes (by name); null if it can't be read. */
    class SpotRecipe(val json: JSONObject, val pictures: Map<String, ByteArray>)

    fun spotRecipe(src: Source, entry: String): SpotRecipe? {
        val input = src.open(entry) ?: return null
        var head: JSONObject? = null
        val pics = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { z ->
                var count = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++count > 60) return null
                    if (e.isDirectory) continue
                    if (e.name == "praline.json") {
                        val b = readCapped(z, JSON_MAX) ?: return null
                        head = JSONObject(String(b))
                    } else if (e.name.startsWith("photos/")) {
                        val n = e.name.substring(7)
                        if (!Regex("[A-Za-z0-9._-]{1,80}").matches(n) || n.startsWith(".") || !n.endsWith(".jpg")) continue
                        val b = readCapped(z, ENTRY_MAX) ?: return null
                        total += b.size
                        if (total > ENTRY_MAX) return null
                        pics[n] = b
                    }
                }
            }
        } catch (e: Exception) { return null }
        val h = head ?: return null
        if (h.optString("format") != RecipeFiles.FORMAT) return null
        val r = h.optJSONArray("recipes")?.optJSONObject(0) ?: return null
        return SpotRecipe(r, pics)
    }

    // ---- installing a pack opened from a file ------------------------------------------------------
    fun dir(c: Context): File { val d = File(c.filesDir, "worlds"); d.mkdirs(); return d }

    /** True when [input] is a zip whose entries include world.json (so a recipe file isn't mistaken for a world). */
    fun looksLikeWorld(input: InputStream): Boolean {
        try {
            ZipInputStream(input).use { z ->
                var count = 0
                while (true) {
                    val e = z.nextEntry ?: return false
                    if (e.name == "world.json") return true
                    if (e.name == "praline.json" || ++count > ENTRIES_MAX) return false
                }
            }
        } catch (e: Exception) { return false }
    }

    /**
     * Copies a pack into the cache with the size limits, checks every entry name and the world itself, and returns
     * the world (read from the copy) for the chef to confirm; [install] then moves it in place.
     */
    fun read(c: Context, input: InputStream): World {
        val tmp = File(c.cacheDir, "incoming$EXT")
        tmp.delete()
        var total = 0L
        FileOutputStream(tmp).use { out ->
            val buf = ByteArray(16384)
            while (true) {
                val k = input.read(buf); if (k < 0) break
                total += k
                if (total > PACK_MAX) { out.close(); tmp.delete(); throw BadPack("too big") }
                out.write(buf, 0, k)
            }
        }
        try {
            ZipFile(tmp).use { z ->
                var count = 0
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (++count > ENTRIES_MAX) throw BadPack("too many files")
                    if (e.isDirectory) continue
                    if (e.size > ENTRY_MAX) throw BadPack("a file is too big")
                    if (e.name.contains("..") || e.name.startsWith("/") || e.name.contains("\\")) throw BadPack("bad file name")
                    if (!NAME.matches(e.name)) continue   // unknown entries are ignored
                }
            }
        } catch (e: BadPack) { tmp.delete(); throw e } catch (e: Exception) { tmp.delete(); throw BadPack("not a zip") }
        val w = load(ZipSource(tmp))
        for (s in w.spots) if (!s.story && spotRecipe(w.source, s.recipe) == null) { w.source.close(); tmp.delete(); throw BadPack("a recipe can't be read") }
        return w
    }

    /** Moves the checked pack from [read] in place, replacing an older copy of the same world. */
    fun install(c: Context, w: World): File {
        w.source.close()
        val from = (w.source as ZipSource).file
        val to = File(dir(c), w.id + EXT)
        Worlds.reload()
        if (!from.renameTo(to)) { from.copyTo(to, true); from.delete() }
        return to
    }

    /** A file opened in Praliné: a world pack goes to [offer], anything else to [RecipeFiles.offer]. */
    fun route(a: MainActivity, uri: Uri) {
        Photos.run {
            val world = try { a.contentResolver.openInputStream(uri)?.use { looksLikeWorld(it) } ?: false } catch (x: Exception) { false }
            Photos.post { if (world) offer(a, uri) else RecipeFiles.offer(a, uri) }
        }
    }

    /** Opens a world file the chef sent to Praliné and asks before adding it. */
    fun offer(a: MainActivity, uri: Uri) {
        Photos.run {
            var err = ""
            val w = try { a.contentResolver.openInputStream(uri)?.use { read(a, it) } } catch (x: BadPack) { err = x.message ?: ""; null } catch (x: Exception) { null }
            Photos.post {
                if (w == null) { a.toast(if (err == "newer") "This world needs a newer Praliné" else "That world file can't be opened"); return@post }
                val old = Worlds.get(w.id)
                val s = Sheet(a, if (old != null) "Update “${w.name}”?" else "Add the world “${w.name}”?")
                val recipes = w.spots.count { !it.story }
                s.body.addView(a.note("A party mission with $recipes recipe spots" + (if (w.spots.size > recipes) " and ${w.spots.size - recipes} story stops" else "") +
                    ". The other chef's phone needs it too for both of you to see the map." + (if (old != null) " Your party's progress is kept." else ""), 12.5f))
                s.button("Not now") { w.source.close() }.button(if (old != null) "Update" else "Add the world", primary = true) {
                    install(a, w)
                    a.toast("${w.name} is on the Missions page")
                    for (sc in ArrayList(a.stack)) sc.onSynced()
                }
                s.show()
            }
        }
    }
}

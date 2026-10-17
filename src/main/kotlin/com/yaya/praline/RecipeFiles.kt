package com.yaya.praline

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Recipes as files, for chefs outside the household: a ".praline" file (a zip with the recipe and its
 * pictures) goes out through the share sheet (Discord, WhatsApp, mail…), and opening one in Praliné
 * adds the recipes to the grimoire as new copies.
 *
 * An incoming file is untrusted: only flat picture/clip names are read, sizes are capped, and every
 * picture gets a fresh name, so a file can never overwrite anything already on the phone.
 */
object RecipeFiles {
    const val EXT = ".praline"
    const val FORMAT = "praline-recipes"
    /** Clips are left out above this, so the file stays small enough for chat apps. */
    const val CLIPS_MAX = 25L * 1024 * 1024
    private const val ENTRY_MAX = 80L * 1024 * 1024
    private const val TOTAL_MAX = 200L * 1024 * 1024
    private const val ENTRIES_MAX = 600
    private val SAFE = Regex("[A-Za-z0-9._-]{1,80}")

    class Packed(val file: File, val clipsLeftOut: Boolean)
    class Incoming(val from: String, val recipes: List<Recipe>, val pictures: Map<String, File>)

    /** "Chili sin carne" -> "Chili-sin-carne.praline". */
    fun fileName(title: String): String {
        val sb = StringBuilder()
        for (ch in title) sb.append(if (ch.isLetterOrDigit() && ch.code < 128) ch else '-')
        var s = sb.toString().replace(Regex("-+"), "-").trim('-')
        if (s.isEmpty()) s = "recipe"
        if (s.length > 40) s = s.substring(0, 40)
        return s + EXT
    }

    /** Packs [recipes] with their pictures (and clips, while they fit) into a file in the share folder. */
    fun pack(c: Context, recipes: List<Recipe>): Packed {
        val names = ArrayList<String>()
        var clipBytes = 0L
        for (r in recipes) for (p in r.photos()) if (p.endsWith(".mp4")) clipBytes += Store.photoFile(c, p).length()
        val clips = clipBytes <= CLIPS_MAX
        val arr = JSONArray()
        for (r in recipes) {
            val o = r.toJson()
            if (!clips) {
                val vs = o.optJSONArray("variants") ?: JSONArray()
                for (i in 0 until vs.length()) {
                    val st = vs.getJSONObject(i).optJSONArray("steps") ?: continue
                    for (k in 0 until st.length()) st.getJSONObject(k).remove("vd")
                }
            }
            arr.put(o)
            for (p in r.photos()) if ((clips || !p.endsWith(".mp4") && !p.endsWith(".mp4.jpg")) && !names.contains(p) && Store.photoFile(c, p).exists()) names.add(p)
        }
        val head = JSONObject().put("format", FORMAT).put("v", 1).put("from", Store.me.name).put("recipes", arr)
        val out = PhotoProvider.shotFile(c, fileName(if (recipes.size == 1) recipes[0].title else "Praline-recipes"))
        ZipOutputStream(FileOutputStream(out)).use { z ->
            z.putNextEntry(ZipEntry("praline.json")); z.write(head.toString().toByteArray()); z.closeEntry()
            for (n in names) {
                z.putNextEntry(ZipEntry("photos/$n"))
                java.io.FileInputStream(Store.photoFile(c, n)).use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return Packed(out, !clips && clipBytes > 0)
    }

    fun shareIntent(c: Context, p: Packed, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, PhotoProvider.uriFor(p.file.name))
            .putExtra(Intent.EXTRA_SUBJECT, title + " · Praliné recipe")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Send the recipe")
    }

    /** Reads a recipe file. Pictures are unpacked to a temporary folder until the chef adds them; null if it isn't one. */
    fun read(c: Context, input: InputStream): Incoming? {
        val tmp = File(c.cacheDir, "incoming"); tmp.deleteRecursively(); tmp.mkdirs()
        var head: JSONObject? = null
        val pics = HashMap<String, File>()
        var total = 0L; var count = 0
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (++count > ENTRIES_MAX) return null
                if (e.isDirectory) continue
                val name = e.name
                if (name == "praline.json") {
                    val bytes = readCapped(z, 4L * 1024 * 1024) ?: return null
                    total += bytes.size
                    head = try { JSONObject(String(bytes)) } catch (x: Exception) { return null }
                } else if (name.startsWith("photos/")) {
                    val n = name.substring(7)
                    if (!SAFE.matches(n) || n.startsWith(".") || !(n.endsWith(".jpg") || n.endsWith(".mp4"))) continue
                    val f = File(tmp, n)
                    var size = 0L
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val k = z.read(buf); if (k < 0) break
                            size += k; total += k
                            if (size > ENTRY_MAX || total > TOTAL_MAX) { out.close(); tmp.deleteRecursively(); return null }
                            out.write(buf, 0, k)
                        }
                    }
                    pics[n] = f
                }
            }
        }
        val h = head ?: return null
        if (h.optString("format") != FORMAT) return null
        val arr = h.optJSONArray("recipes") ?: return null
        val out = ArrayList<Recipe>()
        for (i in 0 until Math.min(arr.length(), 200)) {
            val r = try { Recipe.fromJson(arr.getJSONObject(i)) } catch (x: Exception) { continue }
            out.add(r)
        }
        if (out.isEmpty()) return null
        return Incoming(h.optString("from").take(40), out, pics)
    }

    private fun readCapped(z: InputStream, max: Long): ByteArray? {
        val bo = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val k = z.read(buf); if (k < 0) break
            if (bo.size() + k > max) return null
            bo.write(buf, 0, k)
        }
        return bo.toByteArray()
    }

    /** Adds incoming recipes as new copies with fresh ids and fresh picture names. Returns the copies. */
    fun add(c: Context, inc: Incoming): List<Recipe> {
        // fresh names for every picture; a clip's thumbnail follows its clip ("x.mp4" -> "y.mp4", "x.mp4.jpg" -> "y.mp4.jpg")
        val rename = HashMap<String, String>()
        for (old in inc.pictures.keys) if (!old.endsWith(".mp4.jpg")) {
            val base = Store.newPhotoName().removeSuffix(".jpg")
            rename[old] = if (old.endsWith(".mp4")) "$base.mp4" else "$base.jpg"
        }
        for (old in inc.pictures.keys) if (old.endsWith(".mp4.jpg")) {
            val clip = rename[old.removeSuffix(".jpg")]
            rename[old] = if (clip != null) "$clip.jpg" else Store.newPhotoName()
        }
        for ((old, f) in inc.pictures) { val n = rename[old] ?: continue; f.copyTo(Store.photoFile(c, n), true) }
        val added = ArrayList<Recipe>()
        val now = System.currentTimeMillis()
        for (r in inc.recipes) {
            val o = r.toJson()
            o.put("id", java.util.UUID.randomUUID().toString())
            o.put("photo", rename[r.photo] ?: ""); o.put("orig", rename[r.photoOrig] ?: "")
            o.put("cooked", 0L); o.put("created", now); o.put("updated", now)
            val vs = o.optJSONArray("variants") ?: JSONArray()
            for (i in 0 until vs.length()) {
                val st = vs.getJSONObject(i).optJSONArray("steps") ?: continue
                for (k in 0 until st.length()) {
                    val s = st.getJSONObject(k)
                    for (key in arrayOf("h", "vd")) { val v = s.optString(key); if (v.isNotEmpty()) s.put(key, rename[v] ?: "") }
                }
            }
            val copy = Recipe.fromJson(o)
            Store.recipes.add(copy)
            added.add(copy)
        }
        Store.save(c)
        try { File(c.cacheDir, "incoming").deleteRecursively() } catch (x: Exception) { }
        return added
    }

    /** Opens a recipe file the chef sent to Praliné (from a chat app or Files) and asks before adding it. */
    fun offer(a: MainActivity, uri: Uri) {
        Photos.run {
            val inc = try { a.contentResolver.openInputStream(uri)?.use { read(a, it) } } catch (x: Exception) { null }
            Photos.post {
                if (inc == null) { a.toast("That isn't a Praliné recipe file"); return@post }
                val n = inc.recipes.size
                val s = Sheet(a, if (n == 1) "Add “${inc.recipes[0].title}”?" else "Add $n recipes?")
                val who = if (inc.from.isNotEmpty()) "From ${inc.from}'s grimoire. " else ""
                s.body.addView(a.note(who + "It joins your grimoire as your own copy; changes you make stay yours.", 12.5f))
                if (n > 1) for (r in inc.recipes) s.body.addView(a.body("· " + r.title, 14f, C.INK, 600), lp(MATCH, WRAP).m(t = a.dp(4)))
                s.button("Not now").button("Add to my grimoire", primary = true) {
                    val added = add(a, inc)
                    Sync.nudge(a)
                    a.toast(if (added.size == 1) "${added[0].title} joined your grimoire" else "${added.size} recipes joined your grimoire")
                    for (sc in ArrayList(a.stack)) sc.onSynced()
                    if (added.size == 1) a.push(RecipeScreen(a, added[0]))
                }
                s.show()
            }
        }
    }
}

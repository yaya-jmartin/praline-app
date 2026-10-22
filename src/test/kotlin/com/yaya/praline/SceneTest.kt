package com.yaya.praline

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Praliné 1.4: the map scene, world packs, The Sunforge Sands as a pack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SceneTest {
    private val c get() = RuntimeEnvironment.getApplication()

    @Before fun clean() { Store.reset(); Store.load(c); WorldPack.dir(c).deleteRecursively(); Worlds.reload() }

    private val dir = repoFile("app/assets/worlds/sunforge")

    /** The bundled Sunforge folder as a zip, with [edit] applied to its world.json and [extra] entries added. */
    private fun zip(edit: (JSONObject) -> Unit = {}, extra: Map<String, ByteArray> = HashMap()): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            val w = JSONObject(File(dir, "world.json").readText()); edit(w)
            z.putNextEntry(ZipEntry("world.json")); z.write(w.toString().toByteArray()); z.closeEntry()
            for (sub in arrayOf("slices", "library", "recipes")) { val fs = File(dir, sub).listFiles()!!; java.util.Arrays.sort(fs); for (f in fs) {
                z.putNextEntry(ZipEntry("$sub/${f.name}")); z.write(java.nio.file.Files.readAllBytes(f.toPath())); z.closeEntry()
            } }
            for ((n, b) in extra) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return bo.toByteArray()
    }

    private fun one(name: String): Map<String, ByteArray> { val m = HashMap<String, ByteArray>(); m[name] = ByteArray(4); return m }

    private fun refused(bytes: ByteArray): String = try { WorldPack.read(c, ByteArrayInputStream(bytes)); "" } catch (e: WorldPack.BadPack) { e.message ?: "?" }

    @Test fun sunforgeIsABundledPack() {
        val w = Worlds.get("sunforge")!!
        assertEquals("The Sunforge Sands", w.name)
        assertEquals(10, w.spots.size)
        assertEquals((1..10).map { it.toString() }, w.spots.map { it.id })
        assertEquals("The Calice", w.spots[9].name)
        for (i in w.spots.indices) assertEquals(Missions.GAPS[i], w.spots[i].xp)
        assertEquals(6, w.slices.size)
        assertEquals(333, w.trailX.size)
        assertTrue(w.spots.all { !it.story && w.library.containsKey(it.icon) })
        // the mission world and the pack agree spot by spot: same thresholds, same names
        var at = 0
        for (i in w.spots.indices) { at += w.spots[i].xp; assertEquals(at, Missions.SUNFORGE.spots[i].xp) }
        for (i in 0 until 9) assertEquals(Missions.SUNFORGE.spots[i].label, w.spots[i].name)
        assertEquals("The Calice is lit", w.endTitle)
    }

    @Test fun packRecipesMatchTheTemplates() {
        val w = Worlds.get("sunforge")!!
        for (i in w.spots.indices) {
            val r = Worlds.recipe(c, w, w.spots[i])!!
            assertEquals("mission-sunforge-${i + 1}", r.id)
            assertEquals(Missions.recipeId("sunforge", i), r.id)
            assertEquals(Templates.build("sunforge", i, Templates.SUNFORGE[i]).toJson().toString(), r.toJson().toString())
        }
    }

    @Test fun worldFileInstallsAndReplacesByIdKeepingProgress() {
        val start = System.currentTimeMillis() - 3_600_000L
        Missions.start(c, Missions.SUNFORGE, start)
        val bytes = zip({ it.put("name", "Sunforge, repainted") })
        assertTrue(WorldPack.looksLikeWorld(ByteArrayInputStream(bytes)))
        val w = WorldPack.read(c, ByteArrayInputStream(bytes))
        assertEquals("Sunforge, repainted", w.name)
        WorldPack.install(c, w)
        assertTrue(Worlds.file(c, "sunforge").exists())
        assertEquals("Sunforge, repainted", Worlds.get("sunforge")!!.name)
        assertEquals(1, Worlds.all().size)
        // read from the installed zip: a slice and a recipe
        val got = Worlds.get("sunforge")!!
        assertNotNull(WorldPack.picture(got.source, got.slices[0].file, 400, true))
        assertEquals("Dune Flatbread", Worlds.recipe(c, got, got.spots[0])!!.title)
        assertEquals(start, Missions.started(Missions.SUNFORGE))
        // a second world sits next to it
        WorldPack.install(c, WorldPack.read(c, ByteArrayInputStream(zip({ it.put("id", "lakelands"); it.put("name", "The Lakelands") }))))
        assertEquals(listOf("sunforge", "lakelands"), Worlds.all().map { it.id })
        // a recipe file is not mistaken for a world
        val rec = RecipeFiles.pack(c, listOf(Worlds.recipe(c, got, got.spots[1])!!)).file
        assertFalse(WorldPack.looksLikeWorld(rec.inputStream()))
    }

    @Test fun badPacksAreRefused() {
        assertEquals("not a world pack", refused(zip({ it.put("format", "nope") })))
        assertEquals("newer", refused(zip({ it.put("v", 2) })))
        assertEquals("bad world id", refused(zip({ it.put("id", "../x") })))
        assertEquals("bad spot id", refused(zip({ it.getJSONArray("spots").getJSONObject(1).put("id", "1") })))
        assertEquals("spots out of order", refused(zip({ it.getJSONArray("spots").getJSONObject(1).put("along", 0.0) })))
        assertEquals("recipe spot without a recipe", refused(zip({ it.getJSONArray("spots").getJSONObject(0).put("recipe", "../../praline.json") })))
        assertEquals("a recipe can't be read", refused(zip({ it.getJSONArray("spots").getJSONObject(0).put("recipe", "recipes/missing.praline") })))
        assertEquals("bad file name", refused(zip(extra = one("library/../../evil.png"))))
        assertEquals("not a zip", refused("hello".toByteArray()))
        assertEquals("", refused(zip(extra = one("notes.txt"))))   // unknown entries are ignored
        // nothing was left installed
        assertEquals(0, WorldPack.dir(c).listFiles()!!.size)
    }

    @Test fun oddValuesAreClampedAndUnknownEffectsSkipped() {
        val o = JSONObject(File(dir, "world.json").readText())
        o.put("width", 1e9); o.getJSONArray("spots").getJSONObject(0).put("x", Double.NaN.toString()).put("story", String(CharArray(2000) { 'x' }))
        o.getJSONObject("library").put("weird", JSONObject().put("kind", "fx").put("fx", "dragons"))
        o.getJSONArray("items").put(JSONObject().put("lib", "weird").put("x", 10).put("y", 10))
        o.getJSONArray("items").put(JSONObject().put("lib", "missing").put("x", 10).put("y", 10))
        o.getJSONObject("library").put("bad", JSONObject().put("kind", "sprite").put("file", "../x.png"))
        val w = WorldPack.parse(o, WorldPack.AssetSource(c, "worlds/sunforge"))
        assertEquals(2000f, w.width)
        assertEquals(600, w.spots[0].text.length)
        assertFalse(w.library.containsKey("bad"))
        assertEquals(17, w.items.size)   // the unknown kind is kept (and skipped when drawn), the missing library id dropped
        val v = SceneView(c, w)
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY), 0)
        v.layout(0, 0, 400, v.measuredHeight); v.viewH = 800
        v.draw(Canvas(Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)))
    }

    @Test fun animationsLoopPingPongOrPlayOnce() {
        val w = Worlds.get("sunforge")!!
        val v = object : SceneView(c, w) { fun at(ms: Long) { now = ms } }
        fun lib(loop: String) = WorldPack.Lib("a", "sheet", "library/a.png", 0, 0, 0f, 0f, 3f, 10, 10, 4, 4, 10f, loop, false, "")
        val seen = ArrayList<Int>()
        var ms = 0L; while (ms < 1000L) { v.at(ms); seen.add(v.frame(lib("loop"))); ms += 100 }
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1), seen)
        seen.clear(); ms = 0L; while (ms < 800L) { v.at(ms); seen.add(v.frame(lib("pingpong"))); ms += 100 }
        assertEquals(listOf(0, 1, 2, 3, 2, 1, 0, 1), seen)
        seen.clear(); ms = 0L; while (ms < 800L) { v.at(ms); seen.add(v.frame(lib("once"))); ms += 100 }
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 3, 3), seen)
    }

    @Test fun leavingTheMapDropsPicturesWithoutRecyclingThem() {
        val w = Worlds.get("sunforge")!!
        val m = MissionMap(c, w, Missions.progress(Missions.SUNFORGE))
        m.measure(android.view.View.MeasureSpec.makeMeasureSpec(412, android.view.View.MeasureSpec.EXACTLY), 0)
        m.layout(0, 0, 412, m.measuredHeight)
        m.viewTop = m.measuredHeight - 900; m.viewH = 900
        val cv = Canvas(Bitmap.createBitmap(412, m.measuredHeight, Bitmap.Config.ARGB_8888))
        for (k in 0 until 3) { m.draw(cv); Thread.sleep(80); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        val held = m.appPicture("missions/chrome/dais_next.png")!!
        m.release()
        assertFalse(held.isRecycled)
        SceneView.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        m.draw(cv)   // drawing again after a release reloads what it needs and doesn't crash
        assertEquals(4, m.spotAt(w.spots[4].x, w.spots[4].y - 34))
        assertEquals(-1, m.spotAt(0f, 0f))
    }
}

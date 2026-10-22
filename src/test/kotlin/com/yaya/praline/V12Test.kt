package com.yaya.praline

import org.json.JSONObject
import org.junit.After
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
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Praliné 1.2: QR preview size, equipment counts, recipe files, home Wi-Fi, resume, party missions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V12Test {
    private val c get() = RuntimeEnvironment.getApplication()
    private val hour = 3_600_000L

    @Before fun clean() { Store.reset(); Store.load(c); HomeWifi.assumeHome = true }
    @After fun done() { HomeWifi.assumeHome = false }

    @Test fun qrPreviewPicksASharpSize() {
        // the old pick: the size closest to a square view, 176x144, far too blurry to read a code
        val sizes = listOf(intArrayOf(176, 144), intArrayOf(320, 240), intArrayOf(640, 480), intArrayOf(1280, 720), intArrayOf(1920, 1080), intArrayOf(4032, 3024))
        val i = pickPreviewSize(sizes, 1f)
        assertEquals(1920, sizes[i][0]); assertEquals(1080, sizes[i][1])
        // only small sizes: still the biggest one
        assertEquals(1, pickPreviewSize(listOf(intArrayOf(176, 144), intArrayOf(320, 240)), 1f))
        assertEquals(-1, pickPreviewSize(listOf(intArrayOf(4032, 3024)), 1f))
    }

    @Test fun equipmentCountsAndEngineerTools() {
        val r = LogicTest.sample("Brioche", true, "bard", "A", 3, 120)
        r.equipment.add("mixbowl"); r.equipment.add("kenwood")
        r.setCount("mixbowl", 2)
        assertEquals("2 × Mixing bowl", Equipment.label(r, "mixbowl"))
        assertEquals(Equipment.name("kenwood"), Equipment.label(r, "kenwood"))
        assertTrue(Equipment.engineer("kenwood")); assertTrue(Equipment.engineer("kcook")); assertFalse(Equipment.engineer("mixbowl"))
        val back = Recipe.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(2, back.count("mixbowl")); assertEquals(1, back.count("kenwood"))
        r.setCount("mixbowl", 1)
        assertFalse(r.toJson().has("equipN"))
        // a robot cooker makes the robot limb the chef's weapon
        r.variants[0].steps.clear(); r.variants[0].steps.add(Step("Knead in the Kenwood."))
        r.equipment.remove("mixbowl")
        Store.upsert(c, r); Store.cook(c, r, "Classic")
        assertEquals("gear", Fame.weapons(Store.me, Store.recipes)[0].first)
    }

    @Test fun recipeFilesRoundTripAsNewCopies() {
        Store.me.name = "Sam"
        val r = LogicTest.sample("Chili", false, "sorcerer", "A", 2, 45)
        r.photo = "pic.jpg"; FileOutputStream(Store.photoFile(c, "pic.jpg")).use { it.write(byteArrayOf(1, 2, 3)) }
        r.variants[0].steps[0].video = "clip.mp4"
        FileOutputStream(Store.photoFile(c, "clip.mp4")).use { it.write(byteArrayOf(4, 5)) }
        FileOutputStream(Store.photoFile(c, "clip.mp4.jpg")).use { it.write(byteArrayOf(6)) }
        Store.upsert(c, r)
        val p = RecipeFiles.pack(c, listOf(r))
        assertTrue(p.file.name.endsWith(".praline")); assertFalse(p.clipsLeftOut)
        val inc = RecipeFiles.read(c, java.io.FileInputStream(p.file))!!
        assertEquals("Sam", inc.from); assertEquals(1, inc.recipes.size); assertEquals(3, inc.pictures.size)
        val added = RecipeFiles.add(c, inc)
        assertEquals(2, Store.recipes.size)
        val copy = added[0]
        assertNotEquals(r.id, copy.id); assertEquals("Chili", copy.title)
        assertNotEquals("pic.jpg", copy.photo)
        assertArrayEquals(byteArrayOf(1, 2, 3), java.nio.file.Files.readAllBytes(Store.photoFile(c, copy.photo).toPath()))
        val v = copy.variants[0].steps[0].video
        assertTrue(v.endsWith(".mp4")); assertNotEquals("clip.mp4", v)
        assertTrue(Store.photoFile(c, "$v.jpg").exists())
        assertEquals(0L, copy.lastCooked)
    }

    @Test fun recipeFilesRefuseStrangeNames() {
        val head = JSONObject().put("format", RecipeFiles.FORMAT).put("v", 1).put("from", "x")
            .put("recipes", org.json.JSONArray().put(LogicTest.sample("A", false, "monk", "B", 1, 5).toJson()))
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry("praline.json")); z.write(head.toString().toByteArray()); z.closeEntry()
            for (n in arrayOf("photos/../../evil.jpg", "photos/.hidden.jpg", "photos/run.sh", "photos/ok.jpg")) { z.putNextEntry(ZipEntry(n)); z.write(byteArrayOf(1)); z.closeEntry() }
        }
        val inc = RecipeFiles.read(c, ByteArrayInputStream(bo.toByteArray()))!!
        assertEquals(1, inc.pictures.size); assertTrue(inc.pictures.containsKey("ok.jpg"))
        assertFalse(File(c.cacheDir, "../evil.jpg").exists())
        // not a recipe file at all
        assertNull(RecipeFiles.read(c, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))))
        val other = ByteArrayOutputStream()
        ZipOutputStream(other).use { z -> z.putNextEntry(ZipEntry("praline.json")); z.write("{\"format\":\"zip\"}".toByteArray()); z.closeEntry() }
        assertNull(RecipeFiles.read(c, ByteArrayInputStream(other.toByteArray())))
        assertEquals("Chili-sin-carne.praline", RecipeFiles.fileName("Chili sin carne"))
        assertEquals("Cr-pes.praline", RecipeFiles.fileName("Crêpes"))
    }

    @Test fun homeWifiNames() {
        assertEquals("Le Donjon", HomeWifi.clean("\"Le Donjon\""))
        assertEquals("", HomeWifi.clean("<unknown ssid>"))
        assertEquals("Box-42", HomeWifi.clean(" Box-42 "))
        HomeWifi.assumeHome = false
        // 1.4: the lock is optional; off, any Wi-Fi will do
        assertTrue(HomeWifi.here(c))
        HomeWifi.setLocked(c, true)
        assertFalse(HomeWifi.here(c))
        assertTrue(HomeWifi.why(c)!!.contains("Save your home Wi-Fi"))
    }

    @Test fun syncRefusedAwayFromHome() {
        HomeWifi.assumeHome = false
        HomeWifi.setLocked(c, true)
        Sync.startServer(c)
        var tries = 0
        while (Sync.port == 0 && tries < 50) { Thread.sleep(20); tries++ }
        Thread.sleep(200)
        Sync.pendingKey = "K7QPX2MD"
        val err = try { Sync.client(c, "127.0.0.1", Sync.port, "K7QPX2MD"); "" } catch (e: Exception) { e.message ?: "" }
        assertEquals(Sync.THEIRS_NOT_HOME, err)
        assertTrue(Store.peers.isEmpty())
        Sync.stopServer(c)
    }

    @Test fun missionProgressWalksBothChefsCooks() {
        val w = Missions.SUNFORGE
        assertEquals("The Sunforge Sands", w.name)
        assertEquals("The Calice", w.spots[w.spots.size - 1].label)
        assertEquals(10, w.spots.size)
        assertEquals(80, w.spots[0].xp); assertEquals(200, w.spots[1].xp)
        assertEquals(0, Missions.progress(w).xp)
        val t0 = System.currentTimeMillis() - 100 * hour
        Missions.start(null, w, t0)
        // a cook before the start doesn't count
        Store.me.history.add(CookEntry("x", "Classic", t0 - hour, 500))
        Store.me.history.add(CookEntry("x", "Classic", t0 + hour, 50))
        var p = Missions.progress(w)
        assertEquals(50, p.xp); assertEquals(0, p.done); assertFalse(p.waiting); assertEquals(30, p.toNext); assertEquals(0, p.reached)
        // the other chef's cook takes the party to the first spot, with XP to spare
        val sam = Chef(); sam.name = "Sam"; TestParty.pair(sam); TestParty.legacy(w)
        sam.history.add(CookEntry("y", "Classic", t0 + 2 * hour, 60))
        p = Missions.progress(w)
        assertTrue(p.waiting); assertEquals(1, p.reached); assertEquals(30, p.banked)
        assertEquals("mission-sunforge-1", Missions.recipeId(w.id, 0))
        // cooking the spot's recipe moves the party on
        Store.me.history.add(CookEntry("mission-sunforge-1", "Classic", t0 + 3 * hour, 40))
        p = Missions.progress(w)
        assertEquals(1, p.done); assertEquals(150, p.xp); assertFalse(p.waiting)
        // the recipe cooked before the party stood there doesn't count
        Store.me.history.add(CookEntry("mission-sunforge-2", "Classic", t0 + 4 * hour, 10))
        assertEquals(1, Missions.progress(w).done)
        // the earliest start wins when the phones merge
        Missions.merge(JSONObject().put("sunforge", t0 - 10 * hour))
        assertEquals(t0 - 10 * hour, Missions.started(w))
        Missions.merge(JSONObject().put("sunforge", t0))
        assertEquals(t0 - 10 * hour, Missions.started(w))
    }

    @Test fun missionRecipesJoinOnceAndStayDeleted() {
        val w = Missions.SUNFORGE
        Missions.start(c, w, System.currentTimeMillis() - hour)
        Store.me.history.add(CookEntry("x", "Classic", System.currentTimeMillis(), 90))
        val added = Missions.unlock(c, w)
        assertEquals(1, added.size)
        val r = Store.recipe("mission-sunforge-1")!!
        assertEquals("Dune Flatbread", r.title); assertEquals("sunforge", r.world)
        assertEquals(5, r.variants[0].steps.size); assertEquals(1200, r.variants[0].steps[1].timerSec)
        assertEquals(0, Missions.unlock(c, w).size)
        Store.delete(c, r)
        assertEquals(0, Missions.unlock(c, w).size)
        assertNull(Store.recipe("mission-sunforge-1"))
        // the start time and the world survive a restart
        Store.reset(); Store.load(c)
        assertTrue(Missions.started(w) > 0L)
        assertTrue(Sync.payload(null, false).has("missions"))
        // every template has a title, ingredients and steps
        for (i in w.spots.indices) { val t = w.spots[i].recipe()!!; assertTrue(t.title.isNotEmpty()); assertTrue(t.variants[0].ingredients.size >= 3); assertTrue(t.variants[0].steps.size >= 4) }
    }
}

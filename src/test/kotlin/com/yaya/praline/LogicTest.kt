package com.yaya.praline

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

/** Quantities, recipes on disk, fame and stats, filters, sync merging and timers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicTest {
    private val c get() = RuntimeEnvironment.getApplication()

    @Before fun clean() { Store.reset(); Store.load(c); HomeWifi.assumeHome = true }
    @org.junit.After fun away() { HomeWifi.assumeHome = false }

    @Test fun quantitiesParse() {
        val a = Ingredient.parse("200 g flour")
        assertEquals(200.0, a.qty!!, 1e-9); assertEquals("g", a.unit); assertEquals("flour", a.name)
        val b = Ingredient.parse("1 1/2 cup milk")
        assertEquals(1.5, b.qty!!, 1e-9); assertEquals("cup", b.unit); assertEquals("milk", b.name)
        val d = Ingredient.parse("½ tsp salt")
        assertEquals(0.5, d.qty!!, 1e-9); assertEquals("tsp", d.unit)
        val e = Ingredient.parse("2,5 kg pommes")
        assertEquals(2.5, e.qty!!, 1e-9); assertEquals("kg", e.unit); assertEquals("pommes", e.name)
        val f = Ingredient.parse("3 eggs")
        assertEquals(3.0, f.qty!!, 1e-9); assertEquals("", f.unit); assertEquals("eggs", f.name)
        val g = Ingredient.parse("a pinch of love")
        assertNull(g.qty); assertEquals("a pinch of love", g.name)
        val h = Ingredient.parse("1/3 cup sugar")
        assertEquals(1.0 / 3, h.qty!!, 1e-9)
    }

    @Test fun quantitiesScale() {
        val flour = Ingredient.parse("200 g flour")
        assertEquals("200 g", flour.amount(1.0))
        assertEquals("300 g", flour.amount(1.5))
        assertEquals("50 g", flour.amount(0.25))
        val milk = Ingredient.parse("1 cup milk")
        assertEquals("1½ cup", milk.amount(1.5))
        assertEquals("⅓ cup", milk.amount(1.0 / 3))
        assertEquals("2 cup", milk.amount(2.0))
        val eggs = Ingredient.parse("3 eggs")
        assertEquals("4½", eggs.amount(1.5))
        assertEquals("1 cup milk", milk.raw())
        // round trip through the editor's text
        val back = Ingredient.parse(Ingredient.parse("1 ½ cup milk").raw())
        assertEquals(1.5, back.qty!!, 1e-9)
    }

    @Test fun recipeSurvivesDisk() {
        val r = sample("Tarte Tatin", true, "bard", "S", 3, 75)
        r.variants.add(r.variant(0).copy("Spicier"))
        r.variants[1].ingredients.add(Ingredient.parse("1 pinch chili"))
        Store.upsert(c, r)
        Store.me.name = "Yaya"
        Store.setPlan(c, "2026-10-19", r.id + "|1")
        Store.save(c)
        Store.reset(); Store.load(c)
        val back = Store.recipe(r.id)!!
        assertEquals("Tarte Tatin", back.title)
        assertTrue(back.dessert)
        assertEquals("S", back.tier)
        assertEquals(2, back.variants.size)
        assertEquals("Spicier", back.variants[1].name)
        assertEquals(4, back.variants[1].ingredients.size)
        assertEquals(600, back.variants[0].steps[1].timerSec)
        assertEquals("Yaya", Store.me.name)
        assertEquals(r.id + "|1", Store.plan["2026-10-19"])
    }

    @Test fun fameAndLevels() {
        assertEquals(0, Fame.threshold(1)); assertEquals(70, Fame.threshold(2)); assertEquals(210, Fame.threshold(3))
        assertEquals(1, Fame.level(0)); assertEquals(2, Fame.level(70)); assertEquals(2, Fame.level(209)); assertEquals(3, Fame.level(210))
        assertEquals(5, Fame.dc(1)); assertEquals(20, Fame.dc(5))
        val r = sample("Tarte Tatin", true, "bard", "S", 3, 75)
        Store.upsert(c, r)
        val first = Store.cook(c, r, "Classic")
        assertEquals(40 + 60 + 30, first)
        val second = Store.cook(c, r, "Classic")
        assertEquals(85, second)   // cooked again within the month: −15%
        assertEquals(215, Fame.total(Store.me))
        assertEquals(3, Fame.level(215))
        assertTrue(r.lastCooked > 0)
        // desserts whisked in the oven: SWEET leads, the whisk wins
        val st = Fame.stats(Store.me, Store.recipes)
        assertEquals("SWEET", st[0].key)
        assertEquals(12, st[0].score)    // 8 + floor(3*log2(3))
        assertEquals("+1", st[0].modText)
        assertEquals("−1", st[1].modText) // SPICE 8
        assertEquals("whisk", Fame.weapons(Store.me, Store.recipes)[0].first)
        assertEquals("Caramel Bard", Fame.classTitle(Store.me, Store.recipes))
        val feats = Fame.feats(Store.me, Store.recipes)
        assertTrue(feats[0].earned); assertTrue(feats[1].earned); assertFalse(feats[2].earned)
    }

    @Test fun filtersPaintThePhoto() {
        val src = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) for (x in 0 until 64) src.setPixel(x, y, 0xFF000000.toInt() or ((x * 4) shl 16) or ((y * 5) shl 8) or 120)
        for (k in 0 until Filters.all.size) {
            val f = Filters.all[k]
            val out = Filters.apply(src, f, 0.8f)
            assertEquals(64, out.width); assertEquals(48, out.height)
            var diff = 0
            var y = 0
            while (y < 48) { var x = 0; while (x < 64) { if (out.getPixel(x, y) != src.getPixel(x, y)) diff++; x += 6 }; y += 6 }
            assertTrue(f.key + " changed the picture", diff > 20)
        }
        val none = Filters.apply(src, Filters.get("torch"), 0f)
        assertEquals(src.getPixel(30, 20), none.getPixel(30, 20))
    }

    @Test fun syncMergeKeepsNewestAndDeletions() {
        // phone B's state
        val shared = sample("Chili", false, "sorcerer", "A", 2, 45)
        shared.updated = 1000
        val gone = sample("Old soup", false, "cleric", "C", 1, 20)
        Store.recipes.add(shared); Store.recipes.add(gone)
        Store.me.name = "Sam"
        Store.plan["2026-10-20"] = shared.id + "|0"; Store.planUpdated = 5000
        val samId = Store.me.id
        val fromB = Sync.payload(null, false)

        // phone A: newer edit of the chili, deleted the soup before, older plan
        Store.reset()
        Store.me.name = "Yaya"
        val mine = Recipe.fromJson(shared.toJson()); mine.title = "Chili sin carne"; mine.updated = 2000; mine.lastCooked = 0
        Store.recipes.add(mine)
        Store.tombstones[gone.id] = System.currentTimeMillis()
        Store.plan["2026-10-21"] = "x|0"; Store.planUpdated = 4000
        val b = JSONObject(fromB.toString())
        b.getJSONArray("recipes").getJSONObject(0).put("cooked", 777L)
        Sync.merge(b)
        assertEquals(1, Store.recipes.size)
        assertEquals("Chili sin carne", Store.recipes[0].title)   // newest edit wins
        assertEquals(777L, Store.recipes[0].lastCooked)           // but the other phone's cook date sticks
        assertEquals(shared.id + "|0", Store.plan["2026-10-20"])  // newer plan wins
        assertNull(Store.plan["2026-10-21"])
        assertEquals("Sam", Store.peer!!.name)
        assertEquals(samId, Store.peer!!.id)
        assertEquals("Yaya", Store.me.name)

        // and the other way, a deletion reaches a phone that still has it
        val tomb = JSONObject().put("tomb", JSONObject().put(mine.id, System.currentTimeMillis() + 10))
        Sync.merge(tomb)
        assertEquals(0, Store.recipes.size)
    }

    @Test fun pairingCode() {
        Sync.pendingKey = ""
        val code = Sync.pairCode()
        assertTrue(code.startsWith("praline:pair?h="))
        val p = Sync.parseCode("praline:pair?h=192.168.1.20&p=47821&k=abc123")!!
        assertEquals("192.168.1.20", p.host); assertEquals(47821, p.port); assertEquals("abc123", p.key)
        assertNull(Sync.parseCode("https://example.com"))
        assertNull(Sync.parseCode("praline:pair?h=&k=x"))
        val q = Sync.qrBitmap("praline:pair?h=1.2.3.4&p=1&k=k", 200)
        assertEquals(200, q.width)
    }

    @Test fun loopbackSyncNeedsTheKey() {
        Sync.startServer(c)
        var tries = 0
        while (Sync.port == 0 && tries < 50) { Thread.sleep(20); tries++ }
        Thread.sleep(200)
        Sync.pendingKey = "secret-from-qr"
        val r = sample("Agrumes", true, "druid", "B", 1, 15)
        Store.upsert(c, r)
        // a wrong key is refused
        var refused = false
        try { Sync.client(c, "127.0.0.1", Sync.port, "nope") } catch (e: Exception) { refused = e.message == "not paired" }
        assertTrue(refused)
        // the scanned key pairs and then keeps working
        Sync.client(c, "127.0.0.1", Sync.port, "secret-from-qr")
        assertEquals("secret-from-qr", Store.pairKey)
        Sync.client(c, "127.0.0.1", Sync.port, "secret-from-qr")
        assertEquals(1, Store.recipes.size)
        Sync.unpair(c)
        assertEquals("", Store.pairKey)
        Sync.stopServer(c)
    }

    @Test fun timersRunPauseAndRing() {
        val id = Timers.idFor("r1", 0, 2)
        assertEquals("r1#0#2", id)
        assertFalse(Timers.pistachioTakesTimers(c))
        Timers.start(c, id, 60_000L, "Simmer · Chili")
        assertEquals(Timers.RUNNING, Timers.state(c, id))
        assertTrue(Timers.remainingMs(c, id) in 58_000L..60_000L)
        Timers.adjust(c, id, 60_000L)
        assertTrue(Timers.remainingMs(c, id) > 100_000L)
        Timers.pause(c, id)
        assertEquals(Timers.PAUSED, Timers.state(c, id))
        Timers.resume(c, id)
        assertEquals(Timers.RUNNING, Timers.state(c, id))
        assertTrue(Timers.active(c).contains(id))
        Timers.start(c, id, 1L, "Simmer · Chili")
        Thread.sleep(5)
        Timers.finishAllDue(c)
        assertEquals(Timers.DONE, Timers.state(c, id))
        Timers.reset(c, id)
        assertEquals(Timers.IDLE, Timers.state(c, id))
        assertFalse(Timers.active(c).contains(id))
    }

    @Test fun weekStartsMonday() {
        val now = Calendar.getInstance(); now.set(2026, Calendar.OCTOBER, 22, 12, 0) // a Thursday
        val m = Days.monday(0, now.timeInMillis)
        assertEquals("2026-10-19", Days.key(m))
        assertEquals("2026-10-26", Days.key(Days.monday(1, now.timeInMillis)))
    }

    companion object {
        fun sample(title: String, dessert: Boolean, cls: String, tier: String, effort: Int, time: Int): Recipe {
            val r = Recipe()
            r.title = title; r.dessert = dessert; r.cls = cls; r.tier = tier; r.effort = effort; r.timeMin = time; r.servings = 4
            val v = Variant("Classic")
            if (dessert) {
                v.ingredients.add(Ingredient.parse("200 g flour")); v.ingredients.add(Ingredient.parse("1 cup milk")); v.ingredients.add(Ingredient.parse("3 eggs"))
                v.steps.add(Step("Whisk the eggs with the sugar until pale."))
                v.steps.add(Step("Bake in the oven at 180°C.", 600, "Bake"))
                v.steps.add(Step("Let it rest, then serve."))
            } else {
                v.ingredients.add(Ingredient.parse("500 g red beans")); v.ingredients.add(Ingredient.parse("2 onions")); v.ingredients.add(Ingredient.parse("1 tbsp chili powder"))
                v.steps.add(Step("Chop the onions and fry them in the pan."))
                v.steps.add(Step("Add the beans and simmer.", 1200, "Simmer"))
            }
            r.variants.add(v)
            return r
        }
    }
}

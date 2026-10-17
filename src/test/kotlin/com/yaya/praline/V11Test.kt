package com.yaya.praline

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.provider.AlarmClock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Praliné 1.1: step variables, fame for repeats, shared-recipe changes, sync v2, timers, friction log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V11Test {
    private val c get() = RuntimeEnvironment.getApplication()
    private val day = 86_400_000L

    @Before fun clean() { Store.reset(); Store.load(c) }

    private fun ings(vararg lines: String): MutableList<Ingredient> { val l = ArrayList<Ingredient>(); for (x in lines) l.add(Ingredient.parse(x)); return l }

    @Test fun stepAmountsScaleWithServings() {
        val l = ings("4 eggs", "200 g sugar", "1 cup milk", "2 egg yolks", "salt")
        // only part of the eggs, a unit with "of", a plural, and a time that must not move
        val t = "Beat 2 eggs with 100 g of sugar, add ½ cup milk and bake 20 min."
        assertEquals("Beat 3 eggs with 150 g of sugar, add ¾ cup milk and bake 20 min.", StepText.plain(t, l, 1.5))
        assertEquals("Beat 1 eggs with 50 g of sugar, add ¼ cup milk and bake 20 min.", StepText.plain(t, l, 0.5))
        assertEquals(t, StepText.plain(t, l, 1.0))
        val hits = StepText.find(t, l)
        assertEquals(3, hits.size)
        assertEquals(0, hits[0].ingredient); assertEquals(1, hits[1].ingredient); assertEquals("g", hits[1].unit); assertEquals(2, hits[2].ingredient)
        // singular in the step, plural in the list; two-word names; numbers that aren't amounts stay
        assertEquals("Add 2 egg and 2 egg yolks, 6 times.", StepText.plain("Add 1 egg and 1 egg yolks, 6 times.", l, 2.0))
        assertEquals("Oven at 180°C for 12 minutes", StepText.plain("Oven at 180°C for 12 minutes", l, 2.0))
        assertEquals("Scales with servings: 2 eggs · 100 g sugar", "Scales with servings: " + StepText.summary("Beat 2 eggs and 100 g sugar", l))
        val styled = StepText.styled(t, l, 2.0).toString()
        assertEquals("Beat 4 eggs with 200 g of sugar, add 1 cup milk and bake 20 min.", styled)
    }

    @Test fun ingredientAutocompleteKeepsTheAmount() {
        val sp = Pantry.split("200 g fl")
        assertEquals("200 g ", sp.first); assertEquals("fl", sp.second)
        val sp2 = Pantry.split("but")
        assertEquals("", sp2.first); assertEquals("but", sp2.second)
        val names = ArrayList<String>(); names.add("flour"); names.add("caster sugar"); names.add("butter"); names.add("sugar")
        val m = Pantry.matches("su", names)
        assertEquals("sugar", m[0]); assertEquals("caster sugar", m[1])
        assertEquals(0, Pantry.matches("flour", names).size)
        val r = LogicTest.sample("Crêpes", true, "bard", "A", 2, 30)
        Store.recipes.add(r)
        assertTrue(Pantry.names().contains("milk"))
        assertTrue(Pantry.names().contains("butter"))
        // the step tokenizer completes only a name typed right after an amount
        val tk = AmountTokenizer()
        assertEquals(6, tk.findTokenStart("Add 2 eg", 8))
        assertEquals(11, tk.findTokenStart("Add 100 g su", 12) + 1)
        assertEquals(10, tk.findTokenStart("Add some eg", 11) - 1)
    }

    @Test fun fameFadesForRepeatsAndComesBack() {
        val now = 1_800_000_000_000L
        val r = LogicTest.sample("Tatin", true, "bard", "S", 3, 75)
        r.fame = 120
        val other = LogicTest.sample("Chili", false, "sorcerer", "A", 2, 45)
        Store.recipes.add(r); Store.recipes.add(other)
        assertEquals(120 + 30, Fame.reward(r, Store.me, now))             // first time: full plus the bonus
        Store.me.history.add(CookEntry(r.id, "Classic", now - 2 * day, 150))
        assertEquals(102, Fame.reward(r, Store.me, now))                  // once in the last 20: −15%
        Store.me.history.add(CookEntry(r.id, "Classic", now - 1 * day, 102))
        assertEquals(84, Fame.reward(r, Store.me, now))                   // twice: −30%
        for (k in 0 until 6) Store.me.history.add(CookEntry(r.id, "Classic", now - day / 2, 10))
        assertEquals(30, Fame.reward(r, Store.me, now))                   // never under a quarter
        // a month later it is worth full fame again
        assertEquals(120, Fame.reward(r, Store.me, now + 31 * day))
        // only the last 20 cooks count
        Store.me.history.clear()
        Store.me.history.add(CookEntry(r.id, "Classic", now - 3 * day, 150))
        for (k in 0 until 20) Store.me.history.add(CookEntry(other.id, "Classic", now - day, 80))
        assertEquals(120, Fame.reward(r, Store.me, now))
        // no custom fame: from the effort
        r.fame = 0
        assertEquals(100, Fame.base(r))
    }

    @Test fun equipmentCountsTowardsTheWeapon() {
        val r = LogicTest.sample("Soup", false, "cleric", "B", 2, 30)
        r.variants[0].steps.clear(); r.variants[0].steps.add(Step("Put it all together."))
        r.equipment.add("pin"); r.equipment.add("piping bag")
        Store.upsert(c, r)
        Store.cook(c, r, "Classic")
        assertEquals("pin", Fame.weapons(Store.me, Store.recipes)[0].first)
        Store.reset(); Store.load(c)
        val back = Store.recipe(r.id)!!
        assertEquals(2, back.equipment.size); assertEquals("piping bag", back.equipment[1])
        assertEquals("Rolling pin", Equipment.name("pin")); assertEquals("piping bag", Equipment.name("piping bag"))
    }

    @Test fun stepsKeepClipsAndRounds() {
        val r = LogicTest.sample("Pancakes", true, "rogue", "B", 1, 20)
        r.variants[0].steps[1].video = "abc.mp4"; r.variants[0].steps[1].rounds = 6
        val back = Recipe.fromJson(r.toJson())
        assertEquals("abc.mp4", back.variants[0].steps[1].video)
        assertEquals(6, back.variants[0].steps[1].rounds)
        assertEquals(1, back.variants[0].steps[0].rounds)
        assertTrue(back.photos().contains("abc.mp4")); assertTrue(back.photos().contains("abc.mp4.jpg"))
    }

    /** Two phones in one test: their stores are swapped in and out. */
    private class Phone(var json: JSONObject)
    private fun save(): Phone = Phone(Store.toJson())
    private fun use(p: Phone) { Store.fromJson(p.json) }
    private fun keep(p: Phone) { p.json = Store.toJson() }

    @Test fun sharedRecipeChangesNeedTheOtherChef() {
        // phone A (Yaya) writes a recipe; phone B (Sam) gets it on the first sync
        Store.me.name = "Yaya"; Store.pairKey = "k"
        val r = LogicTest.sample("Tatin", true, "bard", "S", 3, 75)
        Store.recipes.add(r)
        val yaya = save()
        Store.reset(); Store.me.name = "Sam"; Store.pairKey = "k"
        val sam = save()

        use(sam); Sync.merge(payloadOf(yaya)); keep(sam)
        assertEquals(1, Store.recipes.size)
        assertTrue(Store.peerSeen.contains(r.id))
        use(yaya); Sync.merge(payloadOf(sam)); keep(yaya)
        assertTrue(Share.isShared(Store.recipe(r.id)!!))

        // Yaya changes the main recipe for everyone
        use(yaya)
        val mine = Store.recipe(r.id)!!
        mine.variants[0].steps[0].text = "Whisk 2 eggs hard."
        mine.updated = System.currentTimeMillis() + 5000
        Share.proposeMain(null, mine)
        // and makes a new variant for everyone
        Share.proposeVariant(null, mine, mine.variants[0].copy("Spicy · Yaya"))
        keep(yaya)

        // Sam's copy does not change behind Sam's back; two offers wait instead
        use(sam); Sync.merge(payloadOf(yaya)); keep(sam)
        assertEquals("Whisk the eggs with the sugar until pale.", Store.recipe(r.id)!!.variants[0].steps[0].text)
        assertEquals(2, Store.inbox.size)
        assertTrue(Sync.news.any { it.contains("Yaya sent a change") }); Sync.news.clear()
        assertTrue(Share.describe(Store.inbox[0]).contains("steps of Classic"))
        // a second sync doesn't offer them twice
        Sync.merge(payloadOf(yaya)); assertEquals(2, Store.inbox.size)
        Share.accept(null, Store.inbox[0])
        assertEquals("Whisk 2 eggs hard.", Store.recipe(r.id)!!.variants[0].steps[0].text)
        Share.refuse(null, Store.inbox[0])
        assertEquals(1, Store.recipe(r.id)!!.variants.size)
        assertEquals(0, Store.inbox.size)
        keep(sam)

        // Yaya hears back; the offers leave the outbox
        use(yaya); Sync.merge(payloadOf(sam)); keep(yaya)
        assertEquals(0, Store.outbox.size)
        assertTrue(Sync.news.any { it.contains("Sam accepted your change to Tatin") })
        assertTrue(Sync.news.any { it.contains("Sam kept Tatin as it was") }); Sync.news.clear()
        // Sam drops answers once Yaya's offers are gone
        use(sam); Sync.merge(payloadOf(yaya)); keep(sam)
        assertEquals(0, Store.answers.size)
        // and a refused offer is never offered again
        assertEquals(0, Store.inbox.size)
    }

    @Test fun specialsStayMineUntilShared() {
        Store.me.name = "Yaya"; Store.pairKey = "k"
        val r = LogicTest.sample("Chili", false, "sorcerer", "A", 2, 45)
        Store.recipes.add(r)
        val v = r.variants[0].copy("My Classic"); v.ingredients.add(Ingredient.parse("1 tsp smoked paprika"))
        val k = Share.addSpecial(null, r, v)
        assertEquals(0, k)
        assertEquals(1, Store.me.specials)
        assertTrue(Fame.feats(Store.me, Store.recipes).any { it.title == "First special" && it.earned })
        assertEquals("My Classic 2", Share.uniqueName(namesOf(Share.specials(r.id)), "My Classic"))
        assertEquals("My Classic", Share.variantAt(r, Share.SPECIAL).name)
        assertEquals("Classic", Share.variantAt(r, 0).name)
        // specials are not part of what the other phone gets as the cookbook
        val p = Sync.payload(null, false)
        assertFalse(p.toString().contains("smoked paprika"))
        // survives the disk
        Store.save(c); Store.reset(); Store.load(c)
        assertEquals("My Classic", Store.mine[r.id]!![0].name)
        // shared on, it arrives as the other chef's own special
        val yaya = save()
        Share.shareSpecial(null, Store.recipe(r.id)!!, Store.mine[r.id]!![0]); keep(yaya)
        Store.reset(); Store.me.name = "Sam"; Store.pairKey = "k"; Store.recipes.add(Recipe.fromJson(r.toJson()))
        Sync.merge(payloadOf(yaya))
        val offer = Store.inbox[0]
        assertEquals("special", offer.kind)
        Share.accept(null, offer)
        assertEquals("Yaya's My Classic", Store.mine[r.id]!![0].name)
        assertEquals(1, Store.recipe(r.id)!!.variants.size)
    }

    private fun namesOf(l: List<Variant>): List<String> { val out = ArrayList<String>(); for (v in l) out.add(v.name); return out }

    private fun payloadOf(p: Phone): JSONObject {
        val keepNow = Store.toJson()
        use(p)
        val out = Sync.payload(null, false)
        Store.fromJson(keepNow)
        return out
    }

    @Test fun manualCodeRoundTrip() {
        val key = Sync.newKey()
        assertEquals(8, key.length)
        val code = Sync.manualCode("192.168.1.20", Sync.PORT, key)
        assertEquals("20 · " + key.substring(0, 4) + " · " + key.substring(4), code)
        val back = Sync.parseManual(code.lowercase(), "192.168.1.57")!!
        assertEquals("192.168.1.20", back.host); assertEquals(Sync.PORT, back.port); assertEquals(key, back.key)
        val other = Sync.parseManual("20:47821 " + key, "10.0.0.3")!!
        assertEquals("10.0.0.20", other.host); assertEquals(47821, other.port)
        assertEquals("20:47821 · " + key.substring(0, 4) + " · " + key.substring(4), Sync.manualCode("10.0.0.20", 47821, key))
        assertNull(Sync.parseManual("20 ABC", "10.0.0.3"))
        assertNull(Sync.parseManual("300 " + key, "10.0.0.3"))
        assertNull(Sync.parseManual("hello", "10.0.0.3"))
    }

    @Test fun filesTravelAsRawBytes() {
        val a = Store.photoFile(c, "clip1.mp4"); val big = ByteArray(300_000); for (i in big.indices) big[i] = (i % 251).toByte(); java.io.FileOutputStream(a).use { it.write(big) }
        val b = Store.photoFile(c, "pic.jpg"); java.io.FileOutputStream(b).use { it.write(byteArrayOf(1, 2, 3)) }
        val want = org.json.JSONArray().put("clip1.mp4").put("pic.jpg").put("missing.jpg").put("../evil")
        val bytes = ByteArrayOutputStream()
        Sync.sendFiles(c, DataOutputStream(bytes), want)
        a.delete(); b.delete()
        Sync.recvFiles(c, DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertArrayEquals(big, java.nio.file.Files.readAllBytes(Store.photoFile(c, "clip1.mp4").toPath()))
        assertArrayEquals(byteArrayOf(1, 2, 3), java.nio.file.Files.readAllBytes(Store.photoFile(c, "pic.jpg").toPath()))
        assertFalse(Store.photoFile(c, "missing.jpg").exists())
    }

    @Test fun loopbackSyncV2CarriesOffersAndRefusesOldPhones() {
        Sync.startServer(c)
        var tries = 0
        while (Sync.port == 0 && tries < 50) { Thread.sleep(20); tries++ }
        Thread.sleep(200)
        Sync.pendingKey = "K7QPX2MD"
        val r = LogicTest.sample("Agrumes", true, "druid", "B", 1, 15)
        r.photo = "agr.jpg"; java.io.FileOutputStream(Store.photoFile(c, "agr.jpg")).use { it.write(byteArrayOf(9, 9, 9)) }
        Store.upsert(c, r)
        Sync.client(c, "127.0.0.1", Sync.port, "K7QPX2MD")
        assertEquals("K7QPX2MD", Store.pairKey)
        // a phone still on 1.0 is told to update
        val s = java.net.Socket("127.0.0.1", Sync.port)
        val out = DataOutputStream(s.getOutputStream()); val inp = DataInputStream(s.getInputStream())
        val hello = JSONObject().put("v", 1).put("key", "K7QPX2MD").toString().toByteArray()
        out.writeInt(hello.size); out.write(hello); out.flush()
        val n = inp.readInt(); val buf = ByteArray(n); inp.readFully(buf)
        assertEquals(Sync.TOO_OLD, JSONObject(String(buf)).getString("error"))
        s.close()
        Sync.unpair(c)
        Sync.stopServer(c)
    }

    private fun installPistachio(rounds: Boolean) {
        val pm = shadowOf(c.packageManager)
        val cn = ComponentName(Timers.PISTACHIO, "com.yaya.timer.TimerIntentActivity")
        val ai = pm.addActivityIfNotPresent(cn)
        ai.exported = true
        if (rounds) { ai.metaData = android.os.Bundle(); ai.metaData.putBoolean(Timers.META_ROUNDS, true) }
        val f = IntentFilter(AlarmClock.ACTION_SET_TIMER); f.addCategory(Intent.CATEGORY_DEFAULT)
        pm.addIntentFilterForActivity(cn, f)
    }

    @Test fun timersGoToPistachio() {
        installPistachio(false)
        assertTrue(Timers.pistachioTakesTimers(c))
        assertFalse(Timers.pistachioTakesRounds(c))
        val id = Timers.idFor("r1", 0, 1)
        Timers.start(c, id, 600_000L, "Bake · Tatin")
        assertTrue(Timers.external(c, id))
        val sent = shadowOf(c).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_TIMER, sent.action)
        assertEquals(Timers.PISTACHIO, sent.`package`)
        assertEquals(600, sent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertEquals("Bake · Tatin", sent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(sent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertFalse(sent.hasExtra(Timers.EXTRA_ROUNDS))
        // Pistachio rings; here the step is simply done
        Timers.start(c, id, 1L, "Bake · Tatin"); shadowOf(c).nextStartedActivity
        Thread.sleep(5); Timers.finishAllDue(c)
        assertEquals(Timers.IDLE, Timers.state(c, id)); assertTrue(Timers.completed(c, id))
        // repeating timers stay in Praliné while Pistachio can't repeat them
        val id2 = Timers.idFor("r1", 0, 2)
        Timers.start(c, id2, 60_000L, "Pancakes", 4)
        assertFalse(Timers.external(c, id2)); assertNull(shadowOf(c).nextStartedActivity)
        Timers.reset(c, id2)
        // the setting turns the link off
        c.getSharedPreferences("app", 0).edit().putBoolean("handoff", false).apply()
        Timers.start(c, id2, 60_000L, "Rest")
        assertFalse(Timers.external(c, id2)); assertNull(shadowOf(c).nextStartedActivity)
    }

    @Test fun pistachioWithRoundsGetsThem() {
        installPistachio(true)
        assertTrue(Timers.pistachioTakesRounds(c))
        Timers.start(c, "r#0#0", 60_000L, "Pancakes", 4)
        val sent = shadowOf(c).nextStartedActivity
        assertEquals(4, sent.getIntExtra(Timers.EXTRA_ROUNDS, 0))
    }

    @Test fun repeatRoundsThenDoneThenTheCardStays() {
        c.getSharedPreferences("app", 0).edit().putInt("round_gap", 0).apply()
        val id = Timers.idFor("r9", 0, 0)
        Timers.start(c, id, 1L, "Pancake", 3)
        assertEquals(1, Timers.round(c, id)); assertEquals(3, Timers.rounds(c, id))
        Thread.sleep(5); Timers.finishAllDue(c)
        assertEquals(Timers.RUNNING, Timers.state(c, id)); assertEquals(2, Timers.round(c, id))
        // round 3 shown with a gap when one is set
        c.getSharedPreferences("app", 0).edit().putInt("round_gap", 5).apply()
        Thread.sleep(5); Timers.finishAllDue(c)
        assertEquals(3, Timers.round(c, id))
        assertTrue(Timers.gapLeft(c, id) > 3000)
        assertEquals(1L, Timers.remainingMs(c, id))   // shows the round's full time during the pause
        // last round rings, Stop keeps a card and marks the step done
        val p = c.getSharedPreferences("timers", 0); p.edit().putLong("e@$id", 0L).apply()
        Timers.finishAllDue(c)
        assertEquals(Timers.DONE, Timers.state(c, id))
        Timers.stop(c, id, 1_000_000L)
        assertEquals(Timers.IDLE, Timers.state(c, id))
        assertTrue(Timers.completed(c, id))
        assertEquals(1, Timers.stoppedCards(c).size)
        assertEquals("Pancake", Timers.stoppedCards(c)[0].label)
        val nm = shadowOf(c.getSystemService(android.app.NotificationManager::class.java))
        assertTrue(nm.allNotifications.any { it.extras.getString(android.app.Notification.EXTRA_TITLE) == "✓ Pancake" })
        Timers.dismissStopped(c, id)
        assertEquals(0, Timers.stoppedCards(c).size)
    }

    @Test fun timersBelongToOneQuest() {
        val a = Timers.idFor("rq", 0, 0); val b = Timers.idFor("rq", 0, 1); val other = Timers.idFor("zz", 0, 0)
        Timers.start(c, a, 60_000L, "A"); Timers.start(c, other, 60_000L, "Z")
        c.getSharedPreferences("timers", 0).edit().putBoolean("c@$b", true).apply()
        assertEquals(1, Timers.liveInQuest(c, "rq", 0, 2))
        assertEquals(1, Timers.liveForRecipe(c, "rq").size)
        Timers.resetQuest(c, "rq", 0, 2)
        assertEquals(Timers.IDLE, Timers.state(c, a)); assertFalse(Timers.completed(c, b))
        assertEquals(Timers.RUNNING, Timers.state(c, other))   // another recipe's timer is left alone
    }

    @Test fun frictionLogKeepsLongDescriptions() {
        val e = Friction.add(c, "Quest · step 2", 1_000L)
        Friction.add(c, "Home", 2_000L)
        val long = StringBuilder(); for (i in 0 until 40) long.append("The timer covered the step text. ")
        Friction.setNote(c, e.at, "First line\n" + long.toString())
        val all = Friction.all(c)
        assertEquals(2, all.size)
        assertTrue(all[0].note.length > 1000)
        val text = Friction.text(c)
        assertTrue(text.startsWith("Praliné friction log (2)"))
        assertTrue(text.contains("Quest · step 2"))
        assertTrue(text.contains("\n   First line\n   The timer covered"))
        val share = Friction.shareIntent(c)
        val inner = share.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", inner.type)
        Friction.remove(c, 2_000L)
        assertEquals(1, Friction.all(c).size)
        Friction.clear(c)
        assertEquals(0, Friction.all(c).size)
    }

    @Test fun portraitsUnlockWithLevel() {
        assertEquals(9, Portraits.COUNT)
        assertTrue(Portraits.unlocked(3, 1)); assertFalse(Portraits.unlocked(4, 2)); assertTrue(Portraits.unlocked(4, 3))
        assertFalse(Portraits.unlocked(8, 15)); assertTrue(Portraits.unlocked(8, 16))
        for (i in 0 until Portraits.COUNT) assertTrue("portrait $i", java.io.File("app/res/drawable-nodpi/chef_$i.jpg").length() > 50_000)
    }

    @Test fun cameraPreviewIsNotStretched() {
        // a 720x1280 portrait preview in a square view: scaled evenly, cropped top and bottom
        val m = cropMatrix(1000f, 1000f, 720f, 1280f)
        val pts = floatArrayOf(0f, 0f, 1000f, 1000f)
        m.mapPoints(pts)
        val w = pts[2] - pts[0]; val h = pts[3] - pts[1]
        // the texture is first stretched to the view (1000x1000), so true aspect means h/w = 1280/720
        assertEquals(1280f / 720f, h / w, 0.01f)
        assertEquals(1000f, w, 1f)
    }
}

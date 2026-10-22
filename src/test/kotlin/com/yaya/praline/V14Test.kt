package com.yaya.praline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Praliné 1.4: parties of four, mission invites, beverages, photo framing, the optional home lock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V14Test {
    private val c get() = RuntimeEnvironment.getApplication()
    private val H = 3_600_000L

    @Before fun clean() { Store.reset(); Store.load(c); HomeWifi.assumeHome = true; Sync.clearFailures(); Sync.pendingKey = ""; Sync.pendingShort = "" }
    @After fun done() { HomeWifi.assumeHome = false; Sync.stopServer(c); Sync.clearFailures() }

    private fun chef(name: String): Chef { val ch = Chef(); ch.name = name; ch.updated = 1L; return ch }

    @Test fun aCookbookFrom13MovesOver() {
        val me = chef("Yaya"); val sam = chef("Sam")
        val t0 = System.currentTimeMillis() - 10 * H
        val o = JSONObject()
        o.put("me", me.toJson()); o.put("peer", sam.toJson())
        o.put("pairKey", "K7QPX2MD"); o.put("pairSecret", "c2VjcmV0"); o.put("secretOk", true)
        o.put("peerHost", "192.168.1.20"); o.put("peerPort", 47821)
        o.put("answers", JSONObject().put("p1", "accepted"))
        o.put("missions", JSONObject().put("sunforge", t0))
        o.put("recipes", JSONArray().put(JSONObject().put("id", "r1").put("title", "Crème brûlée").put("dessert", true)))
        Store.fromJson(o)
        assertEquals(1, Store.peers.size)
        val p = Store.peers[0]
        assertEquals(sam.id, p.chefId); assertEquals("K7QPX2MD", p.key); assertEquals("c2VjcmV0", p.secret); assertTrue(p.confirmed)
        assertEquals("192.168.1.20", p.host); assertEquals(47821, p.port)
        assertEquals("Sam", Store.peer!!.name)
        assertEquals("accepted", Store.votes["p1|" + me.id])
        val party = Store.parties["sunforge"]!!
        assertEquals(t0, party.members[me.id]); assertEquals(t0, party.members[sam.id])
        assertTrue(party.legacyUntil > t0)
        assertEquals(Recipe.DESSERT, Store.recipe("r1")!!.kind)
        // saved in the new shape, and read back without migrating twice
        val again = Store.toJson()
        assertFalse(again.has("peer")); assertFalse(again.has("pairKey"))
        Store.fromJson(again)
        assertEquals(1, Store.peers.size); assertEquals(party.legacyUntil, Store.parties["sunforge"]!!.legacyUntil)
    }

    @Test fun beveragesAndOldDessertFiles() {
        assertEquals(Recipe.DESSERT, Recipe.kindOf(JSONObject().put("dessert", true)))
        assertEquals(Recipe.MEAL, Recipe.kindOf(JSONObject().put("dessert", false)))
        assertEquals(Recipe.BEVERAGE, Recipe.kindOf(JSONObject().put("kind", "beverage").put("dessert", false)))
        val r = Recipe(); r.title = "Mint tea"; r.kind = Recipe.BEVERAGE
        val o = r.toJson()
        assertEquals("beverage", o.getString("kind")); assertFalse(o.getBoolean("dessert"))
        assertEquals(Recipe.BEVERAGE, Recipe.fromJson(o).kind)
        r.dessert = true; assertEquals(Recipe.DESSERT, r.kind)
        r.dessert = false; assertEquals(Recipe.MEAL, r.kind)
        assertEquals(3, Recipe.KINDS.size)
        assertTrue(Templates.SUNFORGE.any { it.kind == Recipe.BEVERAGE })
    }

    @Test fun photoFramingIsSavedAndKeptInBounds() {
        val r = Recipe(); r.title = "Tatin"
        assertFalse(r.toJson().has("fx"))
        r.focusX = 0.2f; r.focusY = 0.7f; r.zoom = 2.5f
        val back = Recipe.fromJson(r.toJson())
        assertEquals(0.2f, back.focusX, 0.001f); assertEquals(0.7f, back.focusY, 0.001f); assertEquals(2.5f, back.zoom, 0.001f)
        val wild = Recipe.fromJson(JSONObject().put("id", "w").put("fx", 3.0).put("fy", -1.0).put("zoom", 40.0))
        assertEquals(1f, wild.focusX, 0f); assertEquals(0f, wild.focusY, 0f); assertEquals(Recipe.MAX_ZOOM, wild.zoom, 0f)
        // the crop never leaves the photo: a 200×100 photo in a 100×100 circle, zoomed 2× on its left edge
        val m = Framing.matrix(200, 100, 100f, 100f, 0f, 0.5f, 2f)
        val pts = floatArrayOf(0f, 0f, 200f, 100f); m.mapPoints(pts)
        assertTrue(pts[0] <= 0.01f && pts[1] <= 0.01f && pts[2] >= 99.99f && pts[3] >= 99.99f)
    }

    @Test fun spotsStretchWithThePartyButNotOnceReached() {
        val w = Missions.SUNFORGE
        val sam = chef("Sam"); TestParty.pair(sam)
        val t0 = System.currentTimeMillis() - 20 * H
        // alone: spot 1 sits at its one-chef distance
        Missions.start(c, w, t0, listOf(sam.id))
        assertEquals(w.spots[0].gap, Missions.progress(w).xpAt(0))
        Store.me.history.add(CookEntry("x", "Classic", t0 + H, w.spots[0].gap))
        assertTrue(Missions.progress(w).waiting)
        // Sam joins after the party reached spot 1: it stays, the spots ahead stretch to two chefs
        Store.parties[w.id]!!.members[sam.id] = t0 + 2 * H
        var p = Missions.progress(w)
        assertEquals(2, p.size)
        assertEquals(w.spots[0].gap, p.xpAt(0))
        assertTrue(p.waiting)
        Store.me.history.add(CookEntry(Missions.recipeId(w.id, 0), "Classic", t0 + 3 * H, 0))
        p = Missions.progress(w)
        assertEquals(1, p.done)
        assertEquals(w.spots[0].gap + 2 * w.spots[1].gap, p.xpAt(1))
        // Sam's cooks count from when they joined, not before
        sam.history.add(CookEntry("y", "Classic", t0 + H / 2, 500))
        assertEquals(p.xp, Missions.progress(w).xp)
        sam.history.add(CookEntry("y", "Classic", t0 + 4 * H, 50))
        assertEquals(p.xp + 50, Missions.progress(w).xp)
    }

    @Test fun aPartyThatSetsOffTogetherWalksFurther() {
        val w = Missions.SUNFORGE
        val sam = chef("Sam"); TestParty.pair(sam)
        val t0 = System.currentTimeMillis() - 20 * H
        Missions.start(c, w, t0)
        Store.parties[w.id]!!.members[sam.id] = t0 + 60_000L
        Store.me.history.add(CookEntry("x", "Classic", t0 + H, w.spots[0].gap))
        val p = Missions.progress(w)
        assertEquals(2 * w.spots[0].gap, p.xpAt(0))
        assertFalse(p.waiting)
        assertEquals(w.spots[0].gap, p.toNext)
    }

    @Test fun invitesAreAnsweredOnEachPhone() {
        val w = Missions.SUNFORGE
        val sam = chef("Sam"); TestParty.pair(sam)
        val t0 = System.currentTimeMillis() - H
        // Sam started and invited this chef
        Store.missions[w.id] = t0
        val party = MissionParty(); party.by = sam.id; party.members[sam.id] = t0; party.invited[Store.me.id] = t0
        Store.parties[w.id] = party
        assertTrue(Missions.invitedHere(w)); assertFalse(Missions.joined(w))
        assertEquals(listOf(w), Missions.invites())
        Missions.decline(c, w)
        assertFalse(Missions.invitedHere(w)); assertTrue(Missions.invites().isEmpty())
        // changing their mind later still works from the mission page
        assertTrue(Missions.accept(c, w))
        assertTrue(Missions.joined(w))
        assertEquals(listOf(Store.me.id, sam.id), Missions.members(w).map { it.id })
        // what travels: the earliest join wins on both phones
        val other = MissionParty.fromJson(party.toJson()); other.members[Store.me.id] = t0 + 5 * H
        other.merge(party.toJson())
        assertEquals(party.members[Store.me.id], other.members[Store.me.id])
    }

    @Test fun aPartyStopsAtFour() {
        val w = Missions.SUNFORGE
        val chefs = listOf(chef("Sam"), chef("Alex"), chef("Jo"), chef("Lou"))
        for (ch in chefs.take(3)) TestParty.pair(ch, ch.name)
        Missions.start(c, w, System.currentTimeMillis(), chefs.map { it.id })
        assertEquals(3, Store.parties[w.id]!!.invited.size)
        // a 4th phone can't pair: the code is refused before anything is sent
        var err = ""
        try { Sync.clientCode(c, "127.0.0.1", 1, "K7QPX2MD9FHT") } catch (e: Exception) { err = e.message ?: "" }
        assertEquals(Sync.PARTY_FULL, err)
        // a full mission refuses a 5th member
        val p = Store.parties[w.id]!!
        for (ch in chefs.take(3)) p.members[ch.id] = System.currentTimeMillis()
        p.members.remove(Store.me.id); p.members[chefs[3].id] = 1L
        assertFalse(Missions.accept(c, w))
        assertFalse(Missions.joined(w))
    }

    @Test fun chefsMetThroughAnotherPhoneJoinTheParty() {
        val sam = chef("Sam"); val alex = chef("Alex")
        val peer = TestParty.pair(sam)
        val remote = JSONObject().put("me", sam.toJson()).put("party", JSONArray().put(alex.toJson()).put(Store.me.toJson()))
        Sync.merge(remote, peer)
        assertEquals(listOf(sam.id, alex.id), Store.party().map { it.id })
        assertEquals("Sam and Alex", Share.peerName())
        assertNull(Store.chefs[Store.me.id])
        // a newer profile replaces the old one; an older one doesn't
        val newer = Chef(alex.id); newer.name = "Alexandra"; newer.updated = 5L
        Sync.merge(JSONObject().put("me", sam.toJson()).put("party", JSONArray().put(newer.toJson())), peer)
        assertEquals("Alexandra", Store.chef(alex.id)!!.name)
        Sync.merge(JSONObject().put("me", sam.toJson()).put("party", JSONArray().put(alex.toJson())), peer)
        assertEquals("Alexandra", Store.chef(alex.id)!!.name)
        // unpairing Sam keeps Alex while another phone could still bring news of them
        Sync.unpair(c, peer)
        assertNull(Store.chefs[sam.id])
        assertTrue(Store.peers.isEmpty())
    }

    @Test fun offersTravelUntilEveryoneAnswered() {
        val sam = chef("Sam"); val alex = chef("Alex")
        TestParty.pair(sam); Store.chefs[alex.id] = alex
        val r = LogicTest.sample("Tatin", true, "bard", "S", 3, 40); Store.upsert(c, r)
        val now = System.currentTimeMillis()
        val offer = Proposal("p1", r.id, "variant", r.variant(0).toJson(), alex.id, "Alex", now, "Tatin")
        // Sam's phone passes on Alex's offer
        val remote = JSONObject().put("proposals", JSONArray().put(offer.toJson())).put("votes", JSONObject())
        val news = Share.merge(remote, now)
        assertEquals(listOf("Alex sent a change to Tatin"), news)
        assertEquals(1, Store.inbox.size); assertEquals(1, Store.relay.size)
        Share.refuse(c, Store.inbox[0])
        assertEquals("refused", Store.votes["p1|" + Store.me.id])
        // Sam hasn't answered yet: keep passing it on
        Share.merge(remote, now)
        assertEquals(1, Store.relay.size)
        assertEquals(1, Sync.payload(null, false).getJSONArray("proposals").length())
        // Sam answers: done everywhere, and it's not offered again
        remote.put("votes", JSONObject().put("p1|" + sam.id, "accepted"))
        Share.merge(remote, now)
        assertTrue(Store.relay.isEmpty()); assertTrue(Store.inbox.isEmpty())
        // a month later an unanswered offer is dropped
        val old = Proposal("p2", r.id, "variant", r.variant(0).toJson(), alex.id, "Alex", now - Share.KEEP_MS - H, "Tatin")
        Share.merge(JSONObject().put("proposals", JSONArray().put(old.toJson())), now)
        assertTrue(Store.inbox.none { it.id == "p2" })
    }

    @Test fun answersToMyOfferAreToldOnce() {
        val sam = chef("Sam"); val alex = chef("Alex")
        TestParty.pair(sam); Store.chefs[alex.id] = alex
        val r = LogicTest.sample("Tatin", true, "bard", "S", 3, 40); Store.upsert(c, r)
        val p = Share.proposeVariant(c, r, r.variant(0))
        val v1 = JSONObject().put("votes", JSONObject().put(p.id + "|" + sam.id, "accepted"))
        assertEquals(listOf("Sam accepted your change to Tatin"), Share.merge(v1))
        assertTrue(Share.merge(v1).isEmpty())
        assertEquals(1, Store.outbox.size)
        v1.getJSONObject("votes").put(p.id + "|" + alex.id, "refused")
        assertEquals(listOf("Alex kept Tatin as it was"), Share.merge(v1))
        assertTrue(Store.outbox.isEmpty())
    }

    @Test fun theHomeLockIsOptional() {
        HomeWifi.assumeHome = false
        // a new phone: no lock, any Wi-Fi
        assertFalse(HomeWifi.locked(c)); assertNull(HomeWifi.why(c)); assertTrue(HomeWifi.here(c))
        // a phone that saved a home in 1.2 or 1.3 keeps the lock on
        HomeWifi.save(c, "Le Donjon")
        assertTrue(HomeWifi.locked(c))
        assertNotNull(HomeWifi.why(c)); assertFalse(HomeWifi.here(c))
        HomeWifi.setLocked(c, false)
        assertTrue(HomeWifi.here(c))
        HomeWifi.forget(c); HomeWifi.setLocked(c, true)
        assertTrue(HomeWifi.why(c)!!.startsWith("Save your home Wi-Fi"))
    }

    @Test fun longerCodesAndSlowGuesses() {
        assertEquals(26, Sync.newKey(Sync.QR_LEN).length)
        Sync.fixedIp = "192.168.1.20"
        val qr = Sync.parseCode(Sync.pairCode())!!
        Sync.fixedIp = ""
        assertEquals(Sync.QR_LEN, qr.key.length)
        assertEquals(Sync.CODE_LEN, Sync.pendingShort.length)
        assertTrue(Sync.pendingShort != Sync.pendingKey)
        // an 8-character 1.3 code is no longer read
        assertNull(Sync.parseManual("20 · K7QP · X2MD", "192.168.1.57"))
        // the code is stretched before use, the same on both phones
        val t = System.nanoTime()
        val a = SecureLink.codeSecret("ZZQPX2MD9FHT")
        assertTrue(System.nanoTime() - t > 1_000_000L)
        assertArrayEquals(a, SecureLink.codeSecret("ZZQPX2MD9FHT"))
        assertFalse(a.contentEquals(SecureLink.codeSecret("ZZQPX2MD9FHU")))
    }

    @Test fun pairingSpendsTheCodeAndSyncsBySecret() {
        Sync.startServer(c)
        var tries = 0
        while (Sync.port == 0 && tries < 50) { Thread.sleep(20); tries++ }
        Thread.sleep(200)
        // one process plays both sides: each pairing leaves a peer on each side with the same secret
        Sync.pendingShort = "AAAABBBBCCCC"
        Sync.client(c, "127.0.0.1", Sync.port, "AAAABBBBCCCC")
        assertEquals(2, Store.peers.size)
        assertEquals("", Sync.pendingShort)
        // the code is spent once used
        var err = ""
        try { Sync.clientCode(c, "127.0.0.1", Sync.port, "AAAABBBBCCCC") } catch (e: Exception) { err = e.message ?: "" }
        assertEquals(Sync.NOT_PAIRED, err)
        Sync.clearFailures()
        // a known phone still syncs with its secret
        Sync.client(c, "127.0.0.1", Sync.port, "AAAABBBBCCCC")
        // (both sides share one cookbook here, so the one secret proves itself to its own copy)
        assertTrue(Store.peers.any { it.confirmed })
        assertTrue(Store.peers.all { it.lastSync > 0L })
    }

    @Test fun shoppingListAddsUpTodayToSunday() {
        // Wednesday 21 October 2026, noon
        val cal = java.util.Calendar.getInstance(); cal.set(2026, 9, 21, 12, 0, 0)
        val now = cal.timeInMillis
        val r = LogicTest.sample("Tatin", true, "bard", "S", 3, 40); Store.upsert(c, r)
        r.variants[0].ingredients.add(Ingredient(null, "", "Salt"))
        val flour = r.variants[0].ingredients.first { it.name == "flour" }
        Store.plan["2026-10-19"] = r.id + "|0"   // Monday: already past
        Store.plan["2026-10-21"] = r.id + "|0"   // today
        Store.plan["2026-10-25"] = r.id + "|0"   // Sunday
        Store.plan["2026-10-26"] = r.id + "|0"   // next Monday: next week's list
        assertEquals(listOf("2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25"), Shopping.days(now))
        val ls = Shopping.lines(now)
        val f = ls.first { it.name == "flour" }
        assertEquals(flour.qty!! * 2, f.qty!!, 1e-9)
        assertEquals(1, ls.count { it.name.equals("salt", true) })
        assertEquals("Salt (to taste)", ls.first { it.name == "Salt" }.text())
        assertTrue(Shopping.text(now).startsWith("Shopping list"))
        assertTrue(Shopping.text(now).contains("☐ " + f.text()))
    }

    @Test fun aRecipeNeedsIngredientsStepsAndEquipment() {
        val r = LogicTest.sample("Tatin", true, "bard", "S", 3, 40)
        r.equipment.clear(); r.noEquipment = false
        assertEquals(1, Validate.missing(r)!!.tab)
        r.noEquipment = true
        assertNull(Validate.missing(r))
        assertTrue(r.toJson().getBoolean("noEq"))
        assertTrue(Recipe.fromJson(r.toJson()).noEquipment)
        // a variant without steps, then without ingredients
        val v = r.variant(0).copy("Bare"); v.steps.clear(); r.variants.add(v)
        val m = Validate.missing(r)!!
        assertEquals(2, m.tab); assertEquals(1, m.variant); assertTrue(m.text.contains("Bare"))
        v.steps.add(Step("Bake", 0, "", "", "", 1)); v.ingredients.clear()
        assertEquals(0, Validate.missing(r)!!.tab)
        // a special has no equipment of its own
        v.ingredients.add(Ingredient(1.0, "", "egg")); r.noEquipment = false
        assertNull(Validate.missing(r, checkEquipment = false))
        // picking a tool undoes the tick when saved
        r.noEquipment = true; r.equipment.add("whisk")
        assertFalse(Recipe.fromJson(r.toJson()).noEquipment)
    }

    @Test fun theLevelUpChimeIsMadeOnThePhone() {
        val pcm = LevelUp.samples()
        assertTrue(pcm.size > 22050)
        var peak = 0; for (x in pcm) peak = Math.max(peak, Math.abs(x.toInt()))
        assertTrue(peak in 20000..Short.MAX_VALUE.toInt())
        // it fades out
        var tail = 0; for (i in pcm.size - 500 until pcm.size) tail = Math.max(tail, Math.abs(pcm[i].toInt()))
        assertTrue(tail < peak / 10)
    }

    @Test fun pastedStepsAreSplit() {
        assertEquals(listOf("Whisk the eggs and sugar until pale.", "Fold in the flour.", "Bake 25 min at 180°C."),
            StepImport.split("1. Whisk the eggs and sugar\nuntil pale.\n2) Fold in the flour.\n\nStep 3: Bake 25 min at 180°C."))
        assertEquals(listOf("Melt the butter.", "Add 2 eggs and mix well."), StepImport.split("Melt the butter.\n\nAdd 2 eggs\nand mix well."))
        assertEquals(listOf("Melt the butter.", "Add 2 eggs."), StepImport.split("- Melt the butter.\n• Add 2 eggs.\n\n"))
        assertTrue(StepImport.split("  \n ").isEmpty())
    }
}

package com.yaya.praline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Every screen, opened with a seeded cookbook, poked, and photographed into build/shots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    private fun all(v: View, out: MutableList<View> = ArrayList()): List<View> {
        out.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out); return out
    }
    private fun idle(ms: Long = 400) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
    private fun has(root: View, s: String) = all(root).filterIsInstance<TextView>().any { it.text.toString() == s && it.isShown }
    private fun hasPart(root: View, s: String) = all(root).filterIsInstance<TextView>().any { it.text.toString().contains(s) && it.isShown }
    private fun tv(root: View, s: String) = all(root).filterIsInstance<TextView>().last { it.text.toString() == s && it.isShown }
    /** Clicks the nearest clickable view holding the text. */
    private fun tap(root: View, s: String) {
        var v: View = tv(root, s)
        while (!v.isClickable && v.parent is View) v = v.parent as View
        assertTrue(v.performClick())
        idle()
    }
    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        save(b, name)
    }
    /** The whole scrolling page, not only what fits on screen. */
    private fun longShot(a: MainActivity, name: String) {
        val sv = all(a.top()!!.root).filterIsInstance<ScrollView>()[0]
        val content = sv.getChildAt(0)
        val b = Bitmap.createBitmap(a.top()!!.root.width, Math.max(content.height, sv.height), Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        a.top()!!.root.background?.let { it.setBounds(0, 0, b.width, b.height); it.draw(cv) }
        content.draw(cv)
        save(b, name)
    }
    private fun save(b: Bitmap, name: String) {
        File("build/shots").mkdirs()
        val o = FileOutputStream("build/shots/$name.png"); try { b.compress(Bitmap.CompressFormat.PNG, 100, o) } finally { o.close() }
    }

    private val c get() = RuntimeEnvironment.getApplication()

    private fun photo(name: String): String {
        val n = Store.newPhotoName()
        java.nio.file.Files.copy(repoFile("src/test/resources/photos/$name.jpg").toPath(), Store.photoFile(c, n).toPath())
        return n
    }

    lateinit var tatin: Recipe
    lateinit var chili: Recipe

    @Before fun seed() {
        Store.reset()
        Store.load(c)
        Store.me.name = "Yaya"
        tatin = LogicTest.sample("Tarte Tatin", true, "bard", "S", 3, 75); tatin.photo = photo("tatin"); tatin.filter = "torch"
        tatin.variants[0].steps[0].hint = photo("orange")
        chili = LogicTest.sample("Chili sin carne", false, "sorcerer", "A", 2, 45)
        chili.variants.add(chili.variant(0).copy("Spicier"))
        val tira = LogicTest.sample("Tiramisù", true, "wizard", "A", 2, 30); tira.photo = photo("tiramisu"); tira.filter = "mimic"
        val agr = LogicTest.sample("Agrumes", true, "druid", "B", 1, 15); agr.photo = photo("agrumes"); agr.filter = "grotto"
        val cook = LogicTest.sample("Cookies", true, "rogue", "B", 1, 25); cook.photo = photo("cookies")
        val soup = LogicTest.sample("Soupe à l'oignon", false, "cleric", "C", 2, 60)
        val toast = LogicTest.sample("Toast", false, "monk", "D", 1, 5)
        for (r in arrayOf(tatin, chili, tira, agr, cook, soup, toast)) { r.noEquipment = true; Store.upsert(c, r) }
        for (k in 0 until 6) Store.cook(c, tatin, "Classic", System.currentTimeMillis() - k * 86_400_000L)
        Store.cook(c, chili, "Spicier"); Store.cook(c, tira, "Classic"); Store.cook(c, agr, "Classic")
        val peer = Chef(); peer.name = "Sam"; peer.portrait = 2
        peer.history.add(CookEntry(chili.id, "Spicier", System.currentTimeMillis(), 110))
        TestParty.pair(peer)
        Store.save(c)
        HomeWifi.assumeHome = true
    }

    @org.junit.After fun away() { HomeWifi.assumeHome = false }

    private fun launch(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(600)
        return a
    }

    @Test fun homeShowsTiersAndChef() {
        val a = launch()
        val root = a.window.decorView
        assertTrue(has(root, "Praliné"))
        assertTrue(has(root, "Tarte Tatin"))
        assertTrue(hasPart(root, "Lv "))
        shot(root, "01-home")
        tap(root, "Desserts")
        assertTrue(has(root, "Tiramisù")); assertFalse(has(root, "Chili sin carne"))
        tap(root, "Meals")
        assertTrue(has(root, "Chili sin carne"))
        tap(root, "All")
        tap(root, "BY TIER ▾"); tap(root, "Quickest first")
        longShot(a, "02-home-quickest")
        tap(root, "Roll a recipe")
        idle(1200)
        assertTrue(hasPart(root, "Fate has spoken") || has(root, "Fate has spoken"))
        shot(root, "03-fate")
    }

    @Test fun recipeScalesTicksAndTimes() {
        val a = launch()
        val s = RecipeScreen(a, tatin)
        a.push(s); idle(600)
        val root = a.window.decorView
        assertTrue(has(root, "200 g"))
        shot(root, "04-recipe")
        longShot(a, "05-recipe-long")
        s.servings = 6; s.rebuild(); idle()
        assertTrue(has(root, "300 g")); assertTrue(has(root, "1½ cup")); assertTrue(has(root, "4½"))
        tap(root, "flour")
        // timers belong to the quest (1.4): no timer chips on the recipe page
        assertFalse(hasPart(root, "10:00 · Bake"))
        s.startQuest(1); idle(600)
        assertTrue(a.top() is QuestScreen)
        assertEquals(1, (a.top() as QuestScreen).current)
        assertEquals(Timers.IDLE, Timers.state(a, Timers.idFor(tatin.id, 0, 1)))
        a.onBackPressed(); idle(600)
        // variants
        val ch = RecipeScreen(a, chili); a.push(ch); idle(600)
        tap(root, "Spicier")
        assertEquals(1, ch.variant)
        shot(root, "06-recipe-variant")
    }

    @Test fun questFinishEarnsFame() {
        val a = launch()
        val before = Fame.total(Store.me)
        a.push(RecipeScreen(a, chili)); idle(600)
        val root = a.window.decorView
        tap(root, "Start the quest")
        assertTrue(a.top() is QuestScreen)
        shot(root, "07-quest-step1")
        tap(root, "Next step")
        assertTrue(has(root, "tap ▶ to start"))
        shot(root, "08-quest-timer")
        tap(root, "Finish the quest")
        assertTrue(has(root, "Quest complete!"))
        assertTrue(Fame.total(Store.me) > before)
        assertTrue(hasPart(root, "fame"))
        idle(300)
        shot(root, "09-quest-reward")
        tap(root, "Back to the grimoire")
        idle(600)
        assertTrue(a.top() is HomeScreen)
    }

    @Test fun editorWritesANewRecipe() {
        val a = launch()
        val e = EditorScreen(a, null)
        a.push(e); idle(600)
        val root = a.window.decorView
        shot(root, "10-editor-new")
        val title = all(root).filterIsInstance<EditText>().first { it.hint.toString() == "Name it…" }
        title.setText("Crêpes")
        tap(root, "Dessert")
        tap(root, "Wizard")
        e.r.tier = "A"; e.rebuild(); idle()
        tap(root, "Add ingredient")
        all(root).filterIsInstance<EditText>().last { it.hint.toString() == "200 g flour" }.setText("250 g flour")
        tap(root, "Add ingredient")
        all(root).filterIsInstance<EditText>().last { it.hint.toString() == "200 g flour" }.setText("½ l milk")
        longShot(a, "11-editor-ingredients")
        tap(root, "Steps · 0")
        tap(root, "Add a step")
        all(root).filterIsInstance<EditText>().last { it.hint.toString() == "What happens in this step…" }.setText("Whisk everything and rest the batter.")
        tap(root, "Add a timer")
        tap(root, "30 min")
        val label = all(root).filterIsInstance<EditText>().first { it.hint.toString().startsWith("Label") }
        label.setText("Rest")
        tap(root, "Set")
        val tx = StringBuilder(); for (t in all(root).filterIsInstance<TextView>()) tx.append(t.text).append(" | ")
        assertTrue(tx.toString(), hasPart(root, "30:00 · Rest"))
        tap(root, "New variant")
        all(root).filterIsInstance<EditText>().first { it.hint.toString() == "Spicier, Curry, Vegan…" }.setText("Buckwheat")
        tap(root, "Create")
        assertEquals(2, e.r.variants.size)
        longShot(a, "12-editor-steps")
        tap(root, "Add to the grimoire")
        idle(600)
        // 1.4: no saving without equipment, unless "No equipment needed" is ticked
        assertTrue(has(root, "Not ready yet"))
        assertTrue(Store.recipes.none { it.title == "Crêpes" })
        shot(root, "12b-editor-not-ready")
        tap(root, "Keep editing"); idle()
        assertTrue(has(root, "Equipment · 0"))
        byDesc(root, "no equipment").performClick(); idle()
        assertTrue(has(root, "Equipment · none"))
        tap(root, "Add to the grimoire")
        idle(600)
        val saved = Store.recipes.first { it.title == "Crêpes" }
        assertTrue(saved.noEquipment)
        assertTrue(saved.dessert); assertEquals("wizard", saved.cls); assertEquals("A", saved.tier)
        assertEquals(2, saved.variants[0].ingredients.size)
        assertEquals(0.5, saved.variants[0].ingredients[1].qty!!, 1e-9)
        assertEquals("l", saved.variants[0].ingredients[1].unit)
        assertEquals(1800, saved.variants[0].steps[0].timerSec)
        assertEquals("Buckwheat", saved.variants[1].name)
        assertEquals(1, saved.variants[1].steps.size)
        assertTrue(a.top() is RecipeScreen)
    }

    @Test fun editorCancelsAndDeletes() {
        val a = launch()
        val e = EditorScreen(a, tatin, 0)
        a.push(e); idle(600)
        val root = a.window.decorView
        val title = all(root).filterIsInstance<EditText>().first { it.hint.toString() == "Name it…" }
        title.setText("Tarte Tatin ruined")
        a.onBackPressed(); idle()
        assertTrue(has(root, "Leave without saving?"))
        tap(root, "Discard"); idle(600)
        assertEquals("Tarte Tatin", Store.recipe(tatin.id)!!.title)
        a.push(EditorScreen(a, tatin, 0)); idle(600)
        (tv(root, "EDITING").parent as ViewGroup).getChildAt(2).performClick()
        idle()
        assertTrue(hasPart(root, "Delete “Tarte Tatin”?"))
        tap(root, "Delete"); idle(800)
        assertNull(Store.recipe(tatin.id))
        assertTrue(Store.tombstones.containsKey(tatin.id))
        assertTrue(a.top() is HomeScreen)
    }

    @Test fun photoDarkroomKeepsBoth() {
        val a = launch()
        val shotFile = File(a.cacheDir, "shots/test.jpg"); shotFile.parentFile!!.mkdirs()
        java.nio.file.Files.copy(repoFile("src/test/resources/photos/cookies.jpg").toPath(), shotFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        var got: Array<String>? = null
        var frame: PhotoScreen.Frame? = null
        val p = PhotoScreen(a, shotFile, "torch", 0.8f, { o, f, k, s, fr -> got = arrayOf(o, f, k, s.toString()); frame = fr })
        a.push(p); idle(600)
        for (i in 0 until 40) { Thread.sleep(50); idle(50) }
        val root = a.window.decorView
        assertTrue(has(root, "Torchlight"))
        shot(root, "13-darkroom-torch")
        tap(root, "Moonlit")
        for (i in 0 until 20) { Thread.sleep(50); idle(50) }
        shot(root, "14-darkroom-moon")
        // 1.4: the preview is the recipe page's exact crop; drag and pinch frame it
        val fp = all(root).filterIsInstance<FramePhoto>()[0]
        val ratio = fp.width.toFloat() / fp.height
        assertEquals(a.resources.displayMetrics.widthPixels.toFloat() / (a.dp(300) + a.insetTop), ratio, 0.05f)
        fp.zoom = 2f; fp.settle()
        fp.pan(fp.width / 4f, 0f)
        assertTrue(fp.focusX < 0.5f)
        fp.pan(-10_000f, -10_000f)
        // never past the edge: the picture still fills the frame
        val b = fp.bitmap!!
        val pts = floatArrayOf(0f, 0f, b.width.toFloat(), b.height.toFloat())
        Framing.matrix(b.width, b.height, fp.width.toFloat(), fp.height.toFloat(), fp.focusX, fp.focusY, fp.zoom).mapPoints(pts)
        assertTrue(pts[2] >= fp.width - 1f && pts[3] >= fp.height - 1f)
        assertTrue(all(root).filterIsInstance<RoundPhoto>().any { it !is FramePhoto && it.zoom == 2f && it.focusX == fp.focusX })
        shot(root, "14b-darkroom-framed")
        tap(root, "Keep it")
        for (i in 0 until 40) { Thread.sleep(50); idle(50); if (got != null) break }
        val g = got!!
        assertEquals("moon", g[2])
        assertTrue(Store.photoFile(a, g[0]).exists()); assertTrue(Store.photoFile(a, g[1]).exists())
        assertTrue(g[0] != g[1])
        assertFalse(a.top() is PhotoScreen)
        assertEquals(2f, frame!!.zoom, 0.001f); assertTrue(frame!!.x > 0.5f)
    }

    @Test fun profilesSheetsAndParty() {
        val a = launch()
        a.push(ProfileScreen(a, Store.me, false)); idle(600)
        val root = a.window.decorView
        assertTrue(hasPart(root, "Caramel"))
        assertTrue(has(root, "Whisk"))
        assertTrue(has(root, "SWEET"))
        assertTrue(has(root, "Sam"))
        longShot(a, "15-profile")
        tap(root, "Sam")
        assertTrue(a.top() is ProfileScreen && (a.top() as ProfileScreen).readOnly)
        assertTrue(has(root, "THE OTHER CHEF · READ ONLY"))
        longShot(a, "16-profile-peer")
        a.pop(); idle(600)
        tap(root, "Unpair"); tap(root, "Unpair")
        idle(400)
        assertTrue(Store.peers.isEmpty())
        assertNull(Store.peer)
        assertTrue(has(root, "Invite a chef"))
        tap(root, "Invite a chef")
        assertTrue(a.top() is PairScreen)
        shot(root, "17-pair-choose")
    }

    @Test fun pairShowsCode() {
        val a = launch()
        Store.peers.clear(); Store.chefs.clear()
        val p = PairScreen(a); p.mode = 1
        a.push(p); idle(600)
        val root = a.window.decorView
        shot(root, "18-pair-code")
        // simulate the other phone syncing in
        val sam = Chef(); sam.name = "Sam"; TestParty.pair(sam)
        p.onSynced(); idle()
        assertTrue(has(root, "Sam joined the party"))
        shot(root, "19-paired")
    }

    @Test fun boardSuggestsPlansAndMoves() {
        val a = launch()
        val b = BoardScreen(a)
        a.push(b); idle(600)
        val root = a.window.decorView
        tap(root, "20 min")
        assertTrue(b.suggestions().all { it.timeMin <= 20 && it.effort <= 2 })
        val texts = StringBuilder(); for (t in all(root).filterIsInstance<TextView>()) texts.append(t.text).append(" | ")
        assertTrue(texts.toString(), has(root, "Agrumes")); assertFalse(texts.toString(), has(root, "Tiramisù"))
        tap(root, "45 min")
        val mon = Days.monday(0)
        tap(root, "Roll the empty days")
        // days before today stay as they were (1.4)
        val today = Days.key(Calendar.getInstance())
        for (i in 0..6) { val d = mon.clone() as Calendar; d.add(Calendar.DAY_OF_MONTH, i); val k = Days.key(d); if (k >= today) assertNotNull(b.entry(k)) else assertNull(b.entry(k)) }
        val tc = Calendar.getInstance(); tc.add(Calendar.DAY_OF_MONTH, 1)
        val d0 = today; val d1 = Days.key(tc)
        Store.setPlan(a, d1, tatin.id + "|0")
        val x = Store.plan[d0]; val y = Store.plan[d1]
        b.move(d0, d1); idle()
        assertEquals(x, Store.plan[d1]); assertEquals(y, Store.plan[d0])
        val past = Calendar.getInstance(); past.add(Calendar.DAY_OF_MONTH, -1); val pk = Days.key(past)
        b.move(d0, pk); idle()
        assertNull(Store.plan[pk]); assertEquals(y, Store.plan[d0])
        Store.setPlan(a, d0, null); b.rebuild(); idle()
        assertTrue(has(root, "+ pick a recipe or roll"))
        longShot(a, "20-board")
    }

    // ---- 1.1 ---------------------------------------------------------------------------
    private fun touch(a: MainActivity, action: Int, vararg xy: Float) {
        val n = xy.size / 2
        val props = arrayOfNulls<android.view.MotionEvent.PointerProperties>(n)
        val coords = arrayOfNulls<android.view.MotionEvent.PointerCoords>(n)
        for (i in 0 until n) {
            val p = android.view.MotionEvent.PointerProperties(); p.id = i; p.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER; props[i] = p
            val q = android.view.MotionEvent.PointerCoords(); q.x = xy[i * 2]; q.y = xy[i * 2 + 1]; q.pressure = 1f; q.size = 1f; coords[i] = q
        }
        val t = android.os.SystemClock.uptimeMillis()
        val e = android.view.MotionEvent.obtain(t, t, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        a.dispatchTouchEvent(e); e.recycle()
    }

    private fun byDesc(root: View, d: String): View = all(root).last { it.contentDescription?.toString() == d && it.isShown }

    @Test fun editorTabsInPlaceWithEquipmentAndVariables() {
        val a = launch()
        val e = EditorScreen(a, tatin, 0)
        a.push(e); idle(600)
        val root = a.window.decorView
        val page = all(root).filterIsInstance<ScrollView>().last { it.isShown }
        tap(root, "Equipment · none")
        // the tab switches in place: same page, nothing rebuilt
        assertTrue(page === all(root).filterIsInstance<ScrollView>().last { it.isShown })
        byDesc(root, "equipment whisk").performClick(); idle()
        byDesc(root, "equipment oven").performClick(); idle()
        assertEquals(2, e.r.equipment.size)
        assertTrue(has(root, "Equipment · 2"))
        longShot(a, "21-editor-equipment")
        tap(root, "Steps · 3")
        val st = all(root).filterIsInstance<EditText>().first { it.contentDescription?.toString() == "step text" }
        st.setText("Beat 2 eggs with 100 g flour until pale.")
        idle()
        assertTrue(hasPart(root, "Scales with servings: 2 eggs · 100 g flour"))
        longShot(a, "22-editor-step-variables")
        tap(root, "Ingredients · 3")
        tap(root, "Add ingredient")
        val fields = all(root).filterIsInstance<EditText>().filter { it.contentDescription?.toString() == "ingredient" }
        assertEquals(4, fields.size)
        assertTrue(fields[3].hasFocus())
        fields[3].setText("salt")
        tap(root, "Save")
        idle(600)
        val saved = Store.recipe(tatin.id)!!
        assertEquals(4, saved.variants[0].ingredients.size)
        assertNull(saved.variants[0].ingredients[3].qty)
        assertTrue(saved.equipment.contains("whisk"))
        assertEquals("Beat 2 eggs with 100 g flour until pale.", saved.variants[0].steps[0].text)
    }

    @Test fun recipeScalesStepAmountsAndShowsToTaste() {
        tatin.variants[0].ingredients.add(Ingredient.parse("salt"))
        tatin.variants[0].steps[0].text = "Whisk 2 eggs with 100 g flour."
        tatin.equipment.add("whisk"); tatin.equipment.add("tin")
        Store.upsert(c, tatin)
        val a = launch()
        val s = RecipeScreen(a, tatin); a.push(s); idle(600)
        val root = a.window.decorView
        assertTrue(has(root, "to taste"))
        assertTrue(has(root, "Cake tin"))
        s.servings = 8; s.rebuild(); idle()
        assertTrue(has(root, "Whisk 4 eggs with 200 g flour."))
        // Start the quest sits on the paper dock at the very bottom
        val dock = s.dockView!!
        val loc = IntArray(2); dock.getLocationOnScreen(loc)
        assertEquals(root.height.toFloat(), (loc[1] + dock.height).toFloat(), a.dp(60).toFloat())
        assertTrue(hasPart(root, "fame"))
        shot(root, "23-recipe-dock")
        longShot(a, "24-recipe-variables")
    }

    @Test fun questTimerRingsInPlaceThenShrinks() {
        val a = launch()
        a.push(RecipeScreen(a, chili)); idle(600)
        val root = a.window.decorView
        tap(root, "Start the quest")
        tap(root, "Next step")
        val q = a.top() as QuestScreen
        val id = Timers.idFor(chili.id, 0, 1)
        byDesc(root, "timer ring").performClick(); idle(600)
        assertEquals(Timers.RUNNING, Timers.state(a, id))
        shot(root, "25-quest-running")
        a.getSharedPreferences("timers", 0).edit().putLong("e@$id", System.currentTimeMillis() - 10).apply()
        idle(1200)
        assertEquals(Timers.DONE, Timers.state(a, id))
        assertTrue(has(root, "TIME'S UP"))
        assertFalse(hasPart(root, "DONE AT"))
        shot(root, "26-quest-ringing")
        byDesc(root, "stop timer").performClick(); idle(1200)
        assertTrue(Timers.completed(a, id))
        assertTrue(hasPart(root, "Done · 20:00 · Simmer"))
        shot(root, "27-quest-done-pill")
        // the little card saying when it finished waits outside the quest
        a.onBackPressed(); idle(1200)
        assertFalse(a.top() === q)
        assertTrue(hasPart(root, "DONE AT"))
        shot(root, "28-done-card")
        byDesc(root, "close done card").performClick(); idle(800)
        assertFalse(hasPart(root, "DONE AT"))
        // leaving with nothing running forgot the quest's timers
        assertFalse(Timers.completed(a, id))
    }

    @Test fun questAsksBeforeStoppingTimers() {
        val a = launch()
        val id = Timers.idFor(chili.id, 0, 1)
        Timers.start(a, id, 600_000L, "Simmer")
        val r = RecipeScreen(a, chili); a.push(r); idle(600)
        val root = a.window.decorView
        tap(root, "Start the quest")
        assertTrue(has(root, "Quest under way"))
        shot(root, "29-quest-under-way")
        tap(root, "Carry on")
        assertEquals(Timers.RUNNING, Timers.state(a, id))
        tap(root, "Next step")
        tap(root, "Finish the quest")
        assertTrue(has(root, "Finish the quest?"))
        tap(root, "Finish and stop")
        assertEquals(Timers.IDLE, Timers.state(a, id))
        assertTrue(has(root, "Quest complete!"))
    }

    @Test fun sharedRecipeAsksWhereTheChangeGoes() {
        Store.peerSeen.add(tatin.id); Store.save(c)
        val a = launch()
        a.push(RecipeScreen(a, tatin)); idle(600)
        val root = a.window.decorView
        a.push(EditorScreen(a, tatin, 0)); idle(600)
        tap(root, "Steps · 3")
        all(root).filterIsInstance<EditText>().first { it.contentDescription?.toString() == "step text" }.setText("Whisk 3 eggs, my way.")
        tap(root, "Save")
        assertTrue(has(root, "Save it for…"))
        shot(root, "30-save-choices")
        byDesc(root, "Just for me").performClick(); idle(800)
        assertEquals("Whisk the eggs with the sugar until pale.", Store.recipe(tatin.id)!!.variants[0].steps[0].text)
        assertEquals("Whisk 3 eggs, my way.", Store.mine[tatin.id]!![0].steps[0].text)
        assertTrue(has(root, "My Classic"))
        longShot(a, "31-recipe-my-variant")
        // the special can go to the other chef
        tap(root, "My Classic")
        tap(root, "Share with Sam")
        assertEquals("special", Store.outbox[0].kind)
        // and a change for everyone becomes an offer
        a.push(EditorScreen(a, tatin, 0)); idle(600)
        tap(root, "Steps · 3")
        all(root).filterIsInstance<EditText>().first { it.contentDescription?.toString() == "step text" }.setText("Whisk the eggs for longer.")
        tap(root, "Save")
        byDesc(root, "Update the recipe for everyone").performClick(); idle(800)
        assertEquals("Whisk the eggs for longer.", Store.recipe(tatin.id)!!.variants[0].steps[0].text)
        assertEquals("main", Store.outbox[1].kind)
    }

    @Test fun homeShowsOffersFromTheOtherChef() {
        val v = chili.variants[0].copy("Smoky")
        Store.inbox.add(Proposal("p1", chili.id, "variant", v.toJson(), Store.peer!!.id, "Sam", System.currentTimeMillis(), chili.title))
        Store.save(c)
        val a = launch()
        val root = a.window.decorView
        assertTrue(has(root, "SAM SENT A NEW VARIANT FOR"))
        shot(root, "32-home-offer")
        byDesc(root, "accept Chili sin carne").performClick(); idle(600)
        assertEquals(3, Store.recipe(chili.id)!!.variants.size)
        assertEquals("accepted", Store.votes["p1|" + Store.me.id])
        assertFalse(has(root, "SAM SENT A NEW VARIANT FOR"))
    }

    @Test fun settingsSlideInAndFrictionNotes() {
        val a = launch()
        val root = a.window.decorView
        // swipe right on home: Settings follows the finger in from the left
        touch(a, android.view.MotionEvent.ACTION_DOWN, 100f, 900f)
        for (k in 1..8) { touch(a, android.view.MotionEvent.ACTION_MOVE, 100f + k * 70f, 905f); idle(16) }
        assertTrue(a.sliding != null)
        touch(a, android.view.MotionEvent.ACTION_UP, 660f, 905f)
        idle(800)
        assertTrue(a.top() is SettingsScreen)
        assertTrue(has(root, "Run timers in Pistachio"))
        shot(root, "33-settings")
        // two fingers resting: saved, and the note box opens at once
        touch(a, android.view.MotionEvent.ACTION_DOWN, 300f, 1200f)
        touch(a, android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f, 1200f, 600f, 1250f)
        idle(800)
        touch(a, android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f, 1200f, 600f, 1250f)
        touch(a, android.view.MotionEvent.ACTION_UP, 300f, 1200f)
        assertEquals(1, Friction.all(a).size)
        assertEquals("Settings", Friction.all(a)[0].screen)
        assertTrue(has(root, "Friction saved"))
        val box = all(root).filterIsInstance<EditText>().first { it.contentDescription?.toString() == "friction description" }
        assertTrue(box.hasFocus())
        assertTrue(box.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        box.setText("The sync took a while.\nI tapped twice because nothing showed it was working, then it ran two syncs at once and I wasn't sure which recipe version won.")
        shot(root, "34-friction-note")
        tap(root, "Save note")
        assertTrue(Friction.all(a)[0].note.startsWith("The sync took a while.\nI tapped twice"))
        assertTrue(hasPart(root, "I tapped twice"))
        longShot(a, "35-settings-friction")
        // swiping left closes Settings
        touch(a, android.view.MotionEvent.ACTION_DOWN, 800f, 600f)
        for (k in 1..8) { touch(a, android.view.MotionEvent.ACTION_MOVE, 800f - k * 80f, 600f); idle(16) }
        touch(a, android.view.MotionEvent.ACTION_UP, 160f, 600f)
        idle(800)
        assertTrue(a.top() is HomeScreen)
    }

    @Test fun pairingHasATypedCode() {
        val a = launch()
        Store.peers.clear(); Store.chefs.clear()
        Sync.fixedIp = "192.168.1.20"
        val p = PairScreen(a); p.mode = 1
        a.push(p); idle(600)
        val root = a.window.decorView
        assertTrue(has(root, "OR TYPE THIS CODE"))
        shot(root, "36-pair-code-typed")
        p.mode = 0; p.rebuild(); idle()
        tap(root, "Type their code")
        assertTrue(byDesc(root, "code field") is EditText)
        shot(root, "37-pair-type")
        Sync.fixedIp = ""
    }

    @Test fun portraitsLockedUntilTheirLevel() {
        val a = launch()
        a.push(ProfileScreen(a, Store.me, false)); idle(600)
        val root = a.window.decorView
        (tv(root, "CHARACTER SHEET").parent as ViewGroup).getChildAt(2).performClick(); idle()
        assertTrue(has(root, "Lv 16"))
        assertTrue(has(root, "Tavern cook") || has(root, "Lv 3"))
        byDesc(root, "portrait 8").performClick(); idle()
        assertTrue(hasPart(root, "Reach level 16"))
        shot(root, "38-portraits")
    }

    @Test fun missionMapWalksTheParty() {
        val a = launch()
        val w = Missions.SUNFORGE
        val t0 = System.currentTimeMillis() - 50 * 3_600_000L
        Missions.start(c, w, t0)
        TestParty.legacy(w)
        // the party cooked its way past the first two spots and stands at the third
        var at = t0
        fun cook(id: String, xp: Int) { at += 3_600_000L; Store.me.history.add(CookEntry(id, "Classic", at, xp)) }
        cook("x", 90); cook(Missions.recipeId(w.id, 0), 60); cook("x", 60); cook(Missions.recipeId(w.id, 1), 40); cook("x", 160)
        Store.peer!!.history.add(CookEntry("y", "Classic", at + 1000, 60))
        Store.save(c)
        a.push(MissionScreen(a)); idle(800)
        val root = a.window.decorView
        assertTrue(has(root, "The Sunforge Sands"))
        assertTrue(has(root, "Recipe 3 / 10"))
        assertTrue(hasPart(root, "must cook"))
        assertNotNull(Store.recipe(Missions.recipeId(w.id, 2)))
        assertNull(Store.recipe(Missions.recipeId(w.id, 3)))
        val map = all(root).filterIsInstance<MissionMap>()[0]
        // the scenery loads in the background as the map draws
        for (k in 0 until 3) { map.draw(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))); for (j in 0 until 6) { Thread.sleep(60); idle(100) } }
        shot(root, "40-mission-map")
        // the whole map, every slice painted
        map.viewTop = 0; map.viewH = map.height
        for (k in 0 until 3) { map.draw(Canvas(Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888))); for (j in 0 until 8) { Thread.sleep(60); idle(100) } }
        val b = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888)
        map.draw(Canvas(b)); save(b, "41-mission-map-whole")
        map.onSpot!!.invoke(2); idle()
        assertTrue(has(root, "Open the recipe"))
        shot(root, "42-mission-spot-here")
        a.onBackPressed(); idle()
        map.onSpot!!.invoke(6); idle()
        assertTrue(hasPart(root, "more XP to reach it"))
        shot(root, "43-mission-spot-locked")
        a.onBackPressed(); idle()
        // home has the missions entry
        a.pop(); idle(600)
        assertTrue(has(root, "Missions"))
        shot(root, "44-home-missions")
    }

    @Test fun missionCompleteLightsTheCalice() {
        val a = launch()
        val w = Missions.SUNFORGE
        val t0 = System.currentTimeMillis() - 200 * 3_600_000L
        Missions.start(c, w, t0)
        var at = t0
        for (i in w.spots.indices) {
            at += 3_600_000L; Store.me.history.add(CookEntry("x", "Classic", at, 300))
            at += 3_600_000L; Store.me.history.add(CookEntry(Missions.recipeId(w.id, i), "Classic", at, 10))
        }
        a.push(MissionScreen(a)); idle(800)
        val root = a.window.decorView
        assertTrue(has(root, "The Calice is lit"))
        val map = all(root).filterIsInstance<MissionMap>()[0]
        for (k in 0 until 3) { map.draw(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))); for (j in 0 until 6) { Thread.sleep(60); idle(100) } }
        shot(root, "45-mission-complete")
        assertEquals(10, Store.recipes.count { it.world == "sunforge" })
    }

    @Test fun editorCountsEquipmentAndRemovesStepExtras() {
        val a = launch()
        tatin.variants[0].steps[0].video = "none.mp4"
        val e = EditorScreen(a, tatin, 0)
        a.push(e); idle(600)
        val root = a.window.decorView
        tap(root, "Equipment · none")
        byDesc(root, "equipment mixbowl").performClick(); idle()
        byDesc(root, "equipment kenwood").performClick(); idle()
        // a selected chip asks how many
        byDesc(root, "equipment mixbowl").performClick(); idle()
        byDesc(root, "more").performClick(); idle()
        assertTrue(has(root, "2 ×"))
        shot(root, "46-equipment-count")
        tap(root, "Set")
        assertEquals(2, e.r.count("mixbowl"))
        assertTrue(hasPart(root, "2 × Mixing bowl"))
        longShot(a, "47-editor-equipment-engineer")
        tap(root, "Steps · 3")
        longShot(a, "48-editor-step-extras")
        byDesc(root, "remove picture").performClick(); idle()
        byDesc(root, "remove clip").performClick(); idle()
        assertEquals("", e.r.variants[0].steps[0].hint)
        assertEquals("", e.r.variants[0].steps[0].video)
        byDesc(root, "remove timer").performClick(); idle()
        assertEquals(0, e.r.variants[0].steps[1].timerSec)
        tap(root, "Save"); idle(600)
        val saved = Store.recipe(tatin.id)!!
        assertEquals(2, saved.count("mixbowl"))
        a.push(RecipeScreen(a, saved)); idle(600)
        assertTrue(hasPart(root, "2 × Mixing bowl"))
        longShot(a, "49-recipe-equipment")
    }

    @Test fun pairingWaitsForHomeWifi() {
        val a = launch()
        HomeWifi.assumeHome = false
        HomeWifi.setLocked(a, true)
        Store.peers.clear(); Store.chefs.clear()
        val p = PairScreen(a); p.mode = 0
        a.push(p); idle(600)
        val root = a.window.decorView
        assertTrue(has(root, "Home dungeon access"))
        assertTrue(byDesc(root, "set home wifi").isShown)
        shot(root, "50-pair-home-first")
        a.pop(); idle(600)
        a.push(SettingsScreen(a)); idle(600)
        assertTrue(has(root, "Open a recipe file") || hasPart(root, "recipe file"))
        longShot(a, "51-settings-home-files")
    }

    // ---- 1.4 ---------------------------------------------------------------------------
    @Test fun portraitPickShowsWhichOne() {
        val a = launch()
        a.push(ProfileScreen(a, Store.me, false)); idle(600)
        val root = a.window.decorView
        byDesc(root, "edit chef").performClick(); idle()
        val meds = all(root).filterIsInstance<Medallion>().filter { (it.parent as? View)?.contentDescription?.toString()?.startsWith("portrait") == true }
        assertEquals(1, meds.count { it.picked })
        byDesc(root, "portrait 2").performClick(); idle()
        assertTrue(meds[2].picked); assertEquals(1, meds.count { it.picked })
        shot(root, "60-portrait-picked")
    }

    @Test fun questCanBeAbandoned() {
        val a = launch()
        val before = Store.me.history.size
        a.push(RecipeScreen(a, tatin)); idle(600)
        a.push(QuestScreen(a, tatin, 0, 1.0, 1)); idle(600)
        val root = a.window.decorView
        val id = Timers.idFor(tatin.id, 0, 1)
        Timers.start(a, id, 600_000L, "Bake")
        byDesc(root, "Abandon the quest").performClick(); idle()
        assertTrue(has(root, "Abandon the quest?"))
        assertTrue(hasPart(root, "running timer stops"))
        shot(root, "61-quest-abandon")
        tap(root, "Abandon"); idle(600)
        assertFalse(a.top() is QuestScreen)
        assertEquals(Timers.IDLE, Timers.state(a, id))
        assertEquals(before, Store.me.history.size)
    }

    @Test fun recipePageShowsToTasteUnderTheName() {
        tatin.variants[0].ingredients.add(Ingredient(null, "", "salt")); Store.upsert(c, tatin)
        val a = launch()
        a.push(RecipeScreen(a, tatin)); idle(600)
        val root = a.window.decorView
        val salt = tv(root, "salt")
        val taste = byDesc(root, "to taste")
        assertTrue((salt.parent as View) === (taste.parent as View))
        longShot(a, "62-recipe-to-taste")
    }

    @Test fun pastDaysAreLockedAndShoppingListAddsUp() {
        val a = launch()
        val today = Calendar.getInstance()
        Store.setPlan(a, Days.key(today), tatin.id + "|0")
        val b = BoardScreen(a)
        a.push(b); idle(600)
        val root = a.window.decorView
        if (Days.key(Days.monday(0)) < Days.key(today)) {
            val y = Calendar.getInstance(); y.add(Calendar.DAY_OF_MONTH, -1)
            assertNotNull(byDesc(root, "past day " + Days.key(y)))
        }
        tap(root, "Shopping list"); idle()
        assertTrue(has(root, "Shopping list"))
        assertTrue(hasPart(root, "Tarte Tatin"))
        longShot(a, "63-shopping-list")
    }

    @Test fun newVariantStartsFromMain() {
        chili.variants[1].ingredients.add(Ingredient(1.0, "", "extra chili")); Store.upsert(c, chili)
        val a = launch()
        val s = RecipeScreen(a, chili); a.push(s); idle(600)
        val root = a.window.decorView
        tap(root, "Spicier")
        val plus = all(root).first { it.contentDescription?.toString() == "New variant" }
        plus.performClick(); idle()
        all(root).filterIsInstance<EditText>().first { it.hint.toString() == "Spicier, Curry, Vegan…" }.setText("Mild")
        tap(root, "Create"); idle(600)
        val r = Store.recipe(chili.id)!!
        val mild = r.variants.first { it.name == "Mild" }
        assertEquals(r.variants[0].ingredients.size, mild.ingredients.size)
        assertTrue(mild.ingredients.none { it.name == "extra chili" })
    }

    @Test fun ingredientsDragToReorderAndStepsPaste() {
        val a = launch()
        val e = EditorScreen(a, tatin, 0)
        a.push(e); idle(600)
        val root = a.window.decorView
        val first = e.linesFor(0)[0]
        val grip = all(root).filter { it.contentDescription?.toString() == "move ingredient" }[0]
        val row = grip.parent as View
        val list = row.parent as LinearLayout
        // drag the first row down past the second
        row.translationY = row.height * 1.2f
        Reorder.step(row, list) { from, to -> e.linesFor(0).add(to, e.linesFor(0).removeAt(from)) }
        idle()
        assertEquals(first, e.linesFor(0)[1])
        assertEquals(1, list.indexOfChild(row))
        tap(root, "Add to the grimoire".takeIf { has(root, it) } ?: "Save"); idle(600)
        assertEquals(first, Store.recipe(tatin.id)!!.variants[0].ingredients[1].raw())
        // paste a numbered method into a new recipe's steps
        val n = EditorScreen(a, null); a.push(n); idle(600)
        tap(root, "Steps · 0")
        tap(root, "Paste steps")
        all(root).filterIsInstance<EditText>().last { it.isShown }.setText("1. Melt the butter.\n2. Add the sugar.\n3. Stir.")
        tap(root, "Add them"); idle()
        assertEquals(3, n.r.variant(0).steps.size)
        assertTrue(has(root, "Steps · 3"))
        longShot(a, "64-editor-pasted-steps")
    }

    @Test fun readACookbookPageIntoTheEditor() {
        val a = launch()
        val before = Ocr.reader
        Ocr.reader = object : TextReader {
            override fun read(c: android.content.Context, b: Bitmap, done: (String?) -> Unit) { done("Pea soup\nIngredients\n500 g peas\n1 onion\nMethod\n1. Soften the onion.\n2. Add the peas and simmer.") }
        }
        try {
            val root = a.window.decorView
            byDesc(root, "Add a recipe").performClick(); idle()
            assertTrue(has(root, "Read a cookbook page"))
            shot(root, "65-add-recipe-choice")
            val shotFile = File(a.cacheDir, "shots/page.jpg"); shotFile.parentFile!!.mkdirs()
            java.nio.file.Files.copy(repoFile("src/test/resources/photos/cookies.jpg").toPath(), shotFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            tap(root, "Read a cookbook page"); idle()
            assertTrue(has(root, "Take a photo"))
            tap(root, "Cancel"); idle()
            Ocr.readFile(a, shotFile)
            for (i in 0 until 40) { Thread.sleep(50); idle(50); if (a.top() is EditorScreen) break }
            val e = a.top() as EditorScreen
            assertEquals("Pea soup", e.r.title)
            assertEquals(2, e.r.variant(0).ingredients.size)
            assertEquals(2, e.r.variant(0).steps.size)
            longShot(a, "66-editor-from-page")
        } finally { Ocr.reader = before }
    }

    @Test fun settingsHeldSwipeStaysStill() {
        val a = launch()
        a.push(SettingsScreen(a)); idle(800)
        val s = a.top()!!
        // drag Settings 240 px to the left, then hold the finger still: the page must stay where the finger is
        touch(a, android.view.MotionEvent.ACTION_DOWN, 800f, 600f)
        for (k in 1..4) { touch(a, android.view.MotionEvent.ACTION_MOVE, 800f - k * 60f, 600f); idle(16) }
        val held = s.root.translationX
        assertEquals(-240f, held, 30f)
        for (k in 1..6) {
            touch(a, android.view.MotionEvent.ACTION_MOVE, 560f, 600f); idle(16)
            assertEquals("frame $k", held, s.root.translationX, 1f)
        }
        touch(a, android.view.MotionEvent.ACTION_MOVE, 500f, 600f); idle(16)
        assertEquals(held - 60f, s.root.translationX, 1f)
        touch(a, android.view.MotionEvent.ACTION_UP, 500f, 600f); idle(800)
        assertTrue(a.top() is SettingsScreen)
        assertEquals(0f, s.root.translationX, 1f)
    }

    @Test fun homeTitleAndTabsFitTheScreen() {
        val a = launch()
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels
        val logo = all(root).filterIsInstance<android.widget.TextView>().first { it.contentDescription?.toString() == "Praliné" }
        val ink = android.graphics.Rect()
        logo.paint.getTextBounds("Praliné", 0, 7, ink)
        val loc = IntArray(2); logo.getLocationOnScreen(loc)
        // TextView clips to the area inside its padding: the ink sits inside that area on both sides, and the view inside the screen
        val start = logo.layout.getLineLeft(0)
        assertEquals(0, logo.paddingLeft + logo.paddingRight)
        assertTrue(start + ink.left >= 0)
        assertTrue(start + ink.right <= logo.width)
        assertTrue(loc[0] >= 0 && loc[0] + logo.width <= w)
        // all four tabs are fully on screen, the sort sits on its own line
        for (t in arrayOf("Meals", "Desserts", "Beverages", "All")) {
            val v = all(root).filterIsInstance<android.widget.TextView>().first { it.text.toString() == t }
            val l = IntArray(2); v.getLocationOnScreen(l)
            assertTrue("$t fits", l[0] >= 0 && l[0] + v.width <= w)
        }
        // the invite page explains chefs from another home
        a.push(PairScreen(a)); idle(600)
        assertTrue(hasPart(root, "different Wi-Fi networks can't sync yet"))
        assertTrue(hasPart(root, "turn the home lock off"))
        longShot(a, "67-invite-other-home")
    }
}

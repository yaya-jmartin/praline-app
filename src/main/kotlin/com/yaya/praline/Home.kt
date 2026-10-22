package com.yaya.praline

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/** A recipe as a little loot card: photo, lettered title, class, time, effort, last cooked. */
fun MainActivity.recipeCard(r: Recipe, small: Boolean = false): LinearLayout {
    val cls = Classes.get(r.cls)
    val card = card(cls.color, 7, 7)
    val photo = RoundPhoto(this, 13f)
    photo.placeholderIcon = Kinds.icon(r.kind)
    photo.placeholderColor = cls.color
    photo.showRecipe(r, 480)
    card.addView(photo, lp(MATCH, dp(if (small) 70 else 104)))
    card.addView(script(r.title.ifEmpty { "Untitled" }, if (small) 21f else 26f).single(), lp(MATCH, WRAP).m(t = dp(2)))
    if (!small) {
        val clsRow = hbox()
        clsRow.addView(icon(cls.icon, 17, cls.color, C.INK, 7f), lp(dp(17), dp(17)).m(r = dp(5)))
        clsRow.addView(body(cls.name, 11.5f, C.INK, 600))
        card.addView(clsRow, lp(MATCH, WRAP).m(l = dp(2)))
    }
    val meta = hbox()
    meta.addView(icon("hour", 13, 0, C.INK, 8f), lp(dp(13), dp(13)).m(r = dp(3)))
    meta.addView(body(fmtDuration(r.timeMin) + if (small) " · DC ${Fame.dc(r.effort)}" else "", 11f, C.INK, 500), lp(0, WRAP, 1f))
    if (!small) meta.addView(DiceView(this, r.effort, 5, C.RASP), lp(WRAP, dp(11)))
    card.addView(meta, lp(MATCH, WRAP).m(l = dp(2), t = dp(5)))
    if (!small) card.addView(note(Days.ago(r.lastCooked), 10.5f), lp(MATCH, WRAP).m(l = dp(2), t = dp(3)))
    card.rotation = ((r.id.hashCode() % 7) - 3) * 0.35f
    card.pressable()
    card.onClick { push(RecipeScreen(this, r)) }
    return card
}

class HomeScreen(a: MainActivity) : Screen(a) {
    override val name: String get() = "Home"
    private var tab: Int
        get() = a.getSharedPreferences("app", 0).getInt("home_tab", 2)
        set(v) { a.getSharedPreferences("app", 0).edit().putInt("home_tab", v).apply() }
    private var sort: String
        get() = a.getSharedPreferences("app", 0).getString("home_sort", "tier") ?: "tier"
        set(v) { a.getSharedPreferences("app", 0).edit().putString("home_sort", v).apply() }

    private var bottomBar: View? = null

    override fun build(): View {
        drawnAt = Store.version
        // a swipe to the right brings Settings in from the left, as in Pistachio
        val frame = SwipeCatcher(a, 1) { d, p, v -> a.slideIn({ SettingsScreen(a) }, d, p, v) }
        val sv = ScrollView(a)
        sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        col.setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(130))
        sv.addView(col)
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))

        // header: lettering on the left, the party's medallions on the right, side by side so neither covers the other
        val head = a.hbox(); head.gravity = Gravity.TOP
        val titles = a.vbox()
        val logo = a.script("Praliné", 66f)
        logo.maxLines = 1
        // the script's last letter leans out past its box: room on the right so the accent is never cut
        logo.setPadding(0, 0, a.dp(14), 0)
        logo.contentDescription = "Praliné"
        titles.addView(logo, lp(WRAP, WRAP).m(l = -a.dp(6)))
        titles.addView(a.note("the grimoire of ${Store.me.name}'s kitchen", 13f), lp(WRAP, WRAP).m(l = a.dp(16), t = -a.dp(8)))
        head.addView(titles, lp(0, WRAP, 1f))
        val party = a.vbox(); party.gravity = Gravity.CENTER_HORIZONTAL
        val fame = Fame.total(Store.me); val lvl = Fame.level(fame)
        val med = Medallion(a, Store.me.portrait)
        val medBox = FrameLayout(a)
        // the other chefs as small medallions tucked behind this chef's, up to three
        val others = Store.party().take(Store.PARTY_MAX - 1)
        for ((k, ch) in others.withIndex()) {
            val pm = Medallion(a, ch.portrait, C.MINT)
            pm.contentDescription = "chef ${ch.name}"
            medBox.addView(pm, flp(a.dp(40), a.dp(40), Gravity.START or Gravity.BOTTOM).m(l = a.dp(26 * (others.size - 1 - k))))
            pm.onClick { a.push(ProfileScreen(a, ch, true)) }
        }
        medBox.addView(med, flp(a.dp(68), a.dp(68), Gravity.END or Gravity.TOP))
        med.onClick { a.push(ProfileScreen(a, Store.me, false)) }
        party.addView(medBox, lp(a.dp(if (others.isNotEmpty()) 68 + 28 + 26 * (others.size - 1) else 68), a.dp(68)))
        val lv = a.chip("Lv $lvl", true, C.BUTTER, null, 11f)
        party.addView(lv, lp(WRAP, WRAP).m(t = -a.dp(10)))
        lv.onClick { a.push(ProfileScreen(a, Store.me, false)) }
        head.addView(party, lp(WRAP, WRAP).m(t = a.dp(8), l = a.dp(6)))
        // a bigger party leaves less room: the lettering shrinks to fit instead of running under the medallions
        val partyW = if (others.isNotEmpty()) 68 + 28 + 26 * (others.size - 1) else 68
        val room = a.resources.displayMetrics.widthPixels - a.dp(44 + partyW + 6 + 14 - 6)
        val need = logo.paint.measureText("Praliné")
        if (need > room && need > 0f) logo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, logo.textSize * room / need)
        col.addView(head)

        // mission invites waiting for this chef
        for (w in Missions.invites()) col.addView(MissionInvite.card(a, w) { rebuild() }, lp(MATCH, WRAP).m(t = a.dp(14)))

        // fame
        val fr = a.hbox()
        fr.addView(a.label("Fame", C.INK), lp(WRAP, WRAP).m(r = a.dp(8)))
        val next = Fame.threshold(lvl + 1)
        val prev = Fame.threshold(lvl)
        fr.addView(FameBar(a, (fame - prev).toFloat() / Math.max(1, next - prev)), lp(0, a.dp(14), 1f))
        fr.addView(a.body("$fame / $next", 12f, C.INK, 600), lp(WRAP, WRAP).m(l = a.dp(8)))
        col.addView(fr, lp(MATCH, WRAP).m(t = a.dp(12)))

        // changes the other chef sent, waiting for an answer
        for (p in ArrayList(Store.inbox)) col.addView(Inbox.card(a, p) { rebuild() }, lp(MATCH, WRAP).m(t = a.dp(14)))

        // tabs + sort
        val tabs = a.hbox()
        // tab numbers stay as saved since 1.0 (0 meals, 1 desserts, 2 all); beverages (1.4) are 3, shown before All
        val names = arrayOf("Meals", "Desserts", "All", "Beverages")
        val colors = intArrayOf(C.TANG, C.RASP, C.MINT, C.BLUE)
        val hsTabs = HorizontalScrollView(a); hsTabs.isHorizontalScrollBarEnabled = false
        val tabRow = a.hbox()
        for (i in intArrayOf(0, 1, 3, 2)) {
            val ch = a.chip(names[i], tab == i, colors[i], null, if (tab == i) 14f else 13f)
            if (tab == i) (ch.tag as android.widget.TextView).setTextColor(C.WHITE)
            ch.onClick { tab = i; rebuild() }
            tabRow.addView(ch, lp(WRAP, WRAP).m(r = a.dp(8)))
        }
        hsTabs.addView(tabRow)
        tabs.addView(hsTabs, lp(0, WRAP, 1f))
        val sortLbl = a.label(when (sort) { "tier" -> "by tier ▾"; "recent" -> "recently cooked ▾"; "time" -> "quickest ▾"; "effort" -> "easiest ▾"; else -> "a–z ▾" })
        sortLbl.setPadding(a.dp(6), a.dp(10), 0, a.dp(10))
        sortLbl.onClick { chooseSort() }
        tabs.addView(sortLbl)
        col.addView(tabs, lp(MATCH, WRAP).m(t = a.dp(18)))

        val list = Store.recipes.filter { inTab(it) }
        if (Store.recipes.isEmpty()) col.addView(emptyState(), lp(MATCH, WRAP).m(t = a.dp(30)))
        else if (list.isEmpty()) col.addView(a.note("No " + Kinds.plural(tabKind()).lowercase() + " yet. Tap + to add one.", 14f), lp(MATCH, WRAP).m(t = a.dp(30)))
        else if (sort == "tier") tierList(col, list)
        else grid(col, sorted(list))

        // bottom: roll, quest board, add
        val bar = FrameLayout(a)
        val left = a.hbox()
        val roll = a.vbox(); roll.gravity = Gravity.CENTER_HORIZONTAL
        val d20 = a.icon("d20", 34, C.BUTTER)
        roll.addView(d20, lp(a.dp(34), a.dp(34)))
        roll.addView(a.body("Roll a recipe", 10.5f, C.INK, 600).single(), lp(WRAP, WRAP))
        roll.onClick { rollDice(d20) }
        left.addView(roll, lp(WRAP, WRAP).m(r = a.dp(14)))
        val board = a.vbox(); board.gravity = Gravity.CENTER_HORIZONTAL
        board.addView(a.icon("scroll", 34, C.LILAC), lp(a.dp(34), a.dp(34)))
        board.addView(a.body("Quest board", 10.5f, C.INK, 600).single(), lp(WRAP, WRAP))
        board.onClick { a.push(BoardScreen(a)) }
        left.addView(board, lp(WRAP, WRAP).m(r = a.dp(14)))
        val miss = a.vbox(); miss.gravity = Gravity.CENTER_HORIZONTAL
        miss.addView(a.icon("map", 34, C.TANG), lp(a.dp(34), a.dp(34)))
        miss.addView(a.body("Missions", 10.5f, C.INK, 600).single(), lp(WRAP, WRAP))
        miss.contentDescription = "Party missions"
        miss.onClick { a.push(MissionScreen(a)) }
        left.addView(miss)
        bar.addView(left, flp(WRAP, WRAP, Gravity.START or Gravity.BOTTOM).m(l = a.dp(16), b = a.dp(10)))
        val fab = FrameLayout(a)
        val fbg = InkBox(a, C.RASP, C.INK, 999f, 2.4f, 4f, 2)
        fab.background = fbg
        fab.addView(a.icon("plus", 30, 0, C.WHITE, 11f), flp(a.dp(30), a.dp(30), Gravity.CENTER).m(r = fbg.offsetPx, b = fbg.offsetPx))
        fab.pressable()
        fab.onClick { addRecipe() }
        fab.contentDescription = "Add a recipe"
        bar.addView(fab, flp(a.dp(70), a.dp(70), Gravity.END or Gravity.BOTTOM).m(r = a.dp(20), b = a.dp(4)))
        bar.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(C.PAPER and 0x00FFFFFF, C.PAPER and 0xF0FFFFFF.toInt(), C.PAPER, C.PAPER))
        bar.setPadding(0, a.dp(26), 0, 0)
        frame.addView(bar, flp(MATCH, WRAP, Gravity.BOTTOM))
        bottomBar = bar
        return frame
    }

    override fun onInsets(top: Int, bottom: Int) {}

    private fun sorted(list: List<Recipe>): List<Recipe> = when (sort) {
        "recent" -> list.sortedByDescending { it.lastCooked }
        "time" -> list.sortedBy { it.timeMin }
        "effort" -> list.sortedWith(compareBy<Recipe> { it.effort }.thenBy { it.timeMin })
        else -> list.sortedBy { it.title.lowercase() }
    }

    /** Write a recipe, or (where the reader is in the build) read one from a cookbook page. */
    private fun addRecipe() {
        if (!Ocr.available) { a.push(EditorScreen(a, null)); return }
        val s = Sheet(a, "A new recipe")
        s.body.addView(a.note("Read from a photo stays on this phone: the text is read by the phone itself, then you check it in the editor.", 12f), lp(MATCH, WRAP).m(b = a.dp(6)))
        s.button("Read a cookbook page") { Ocr.start(a) }
        s.button("Write it", primary = true) { a.push(EditorScreen(a, null)) }
        s.show()
    }

    private fun emptyState(): View {
        val c = a.card(C.LILAC, 18, 18)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(a.icon("scroll", 70, C.BUTTER), lp(a.dp(70), a.dp(70)))
        c.addView(a.script("A blank grimoire", 34f), lp(WRAP, WRAP).m(t = a.dp(4)))
        c.addView(a.note("Every recipe you add becomes a card here, ranked from Tier S down. Cooking them earns you fame.", 13.5f).also { it.gravity = Gravity.CENTER })
        val b = a.bigButton("Write the first one", C.RASP, C.WHITE, C.INK, "plus")
        b.onClick { addRecipe() }
        c.addView(b, lp(WRAP, WRAP).m(t = a.dp(14)))
        return c
    }

    private fun tierList(col: LinearLayout, list: List<Recipe>) {
        for (t in Tiers.all) {
            val inTier = list.filter { it.tier == t }.sortedByDescending { it.lastCooked }
            if (inTier.isEmpty()) continue
            val row = a.hbox(); row.gravity = Gravity.TOP
            val sh = ShieldView(a, t, Tiers.color(t))
            row.addView(sh, lp(a.dp(if (t == "S" || t == "A") 46 else 40), a.dp(if (t == "S" || t == "A") 46 else 40)).m(r = a.dp(10), t = a.dp(if (t == "S" || t == "A") 22 else 8)))
            if (t == "S" || t == "A") {
                val g = a.vbox()
                var i = 0
                while (i < inTier.size) {
                    val pair = a.hbox(); pair.gravity = Gravity.TOP
                    pair.addView(a.recipeCard(inTier[i]), lp(0, WRAP, 1f).m(r = a.dp(6)))
                    if (i + 1 < inTier.size) pair.addView(a.recipeCard(inTier[i + 1]), lp(0, WRAP, 1f).m(l = a.dp(6)))
                    else pair.addView(a.space(), lp(0, 1, 1f).m(l = a.dp(6)))
                    g.addView(pair, lp(MATCH, WRAP).m(b = a.dp(12)))
                    i += 2
                }
                row.addView(g, lp(0, WRAP, 1f))
            } else {
                val hs = HorizontalScrollView(a); hs.isHorizontalScrollBarEnabled = false
                val strip = a.hbox(); strip.gravity = Gravity.TOP
                for (r in inTier) strip.addView(mini(r), lp(a.dp(72), WRAP).m(r = a.dp(6)))
                hs.addView(strip)
                row.addView(hs, lp(0, WRAP, 1f))
            }
            col.addView(row, lp(MATCH, WRAP).m(t = a.dp(10)))
        }
    }

    private fun mini(r: Recipe): View {
        val v = a.vbox(); v.gravity = Gravity.CENTER_HORIZONTAL
        val ph = FrameLayout(a)
        val bg = InkBox(a, C.CARD, Classes.get(r.cls).color, 999f, 2f, 3f, 4)
        ph.background = bg
        val p = RoundPhoto(a, 40f)
        p.placeholderIcon = Kinds.icon(r.kind); p.placeholderColor = Classes.get(r.cls).color
        p.showRecipe(r, 200)
        ph.addView(p, flp(a.dp(54), a.dp(54)).m(a.dp(2), a.dp(2)))
        v.addView(ph, lp(a.dp(61), a.dp(61)))
        val t = a.script(r.title, 19f); t.maxLines = 2; t.gravity = Gravity.CENTER; t.setLineSpacing(0f, 0.8f)
        v.addView(t, lp(WRAP, WRAP))
        v.pressable(); v.onClick { a.push(RecipeScreen(a, r)) }
        return v
    }

    private fun grid(col: LinearLayout, list: List<Recipe>) {
        var i = 0
        while (i < list.size) {
            val pair = a.hbox(); pair.gravity = Gravity.TOP
            pair.addView(a.recipeCard(list[i]), lp(0, WRAP, 1f).m(r = a.dp(6)))
            if (i + 1 < list.size) pair.addView(a.recipeCard(list[i + 1]), lp(0, WRAP, 1f).m(l = a.dp(6)))
            else pair.addView(a.space(), lp(0, 1, 1f).m(l = a.dp(6)))
            col.addView(pair, lp(MATCH, WRAP).m(t = a.dp(14)))
            i += 2
        }
    }

    private fun chooseSort() {
        val s = Sheet(a, "Sort the grimoire")
        val opts = arrayOf("tier" to "By tier", "recent" to "Recently cooked", "time" to "Quickest first", "effort" to "Easiest first", "name" to "A to Z")
        val f = FlowLayout(a, a.dp(8), a.dp(10))
        for ((k, n) in opts) {
            val ch = a.chip(n, sort == k, C.BUTTER, null, 14f)
            ch.onClick { sort = k; s.dismiss(); rebuild() }
            f.addView(ch)
        }
        s.body.addView(f)
        s.show()
    }

    private fun rollDice(d20: View) {
        val pool = Store.recipes.filter { inTab(it) }
        if (pool.isEmpty()) { a.toast("Add a recipe first, then fate can choose"); return }
        d20.animate().rotationBy(720f).scaleX(1.3f).scaleY(1.3f).setDuration(650).withEndAction(object : Runnable {
            override fun run() {
                d20.scaleX = 1f; d20.scaleY = 1f
                val pick = pool[java.util.Random().nextInt(pool.size)]
                Fate.show(a, pick, pool)
            }
        }).start()
    }

    private var shown = false
    /** [Store.version] when the page was last drawn: coming back to an unchanged home redraws nothing (no flicker after Settings). */
    private var drawnAt = -1L
    override fun onShow() { missionRecipes(); if (shown && drawnAt != Store.version) rebuild(); shown = true }
    override fun onSynced() { missionRecipes(); rebuild() }

    private fun tabKind(): String = when (tab) { 1 -> Recipe.DESSERT; 3 -> Recipe.BEVERAGE; else -> Recipe.MEAL }
    private fun inTab(r: Recipe): Boolean = tab == 2 || r.kind == tabKind()

    /** Mission recipes the party reached since last time (a cook, or the other chef's cooks after a sync). */
    private fun missionRecipes() {
        val added = ArrayList<Recipe>()
        for (w in Missions.all) if (Missions.started(w) > 0L && Missions.joined(w)) added.addAll(Missions.unlock(a, w))
        if (added.isNotEmpty()) { Sync.nudge(a); a.toast(if (added.size == 1) "Party mission: ${added[0].title} joined your grimoire" else "Party mission: ${added.size} recipes joined your grimoire") }
    }
}

/** "Fate chose…": the result of a d20 roll, with cook / reroll. */
object Fate {
    fun show(a: MainActivity, r: Recipe, pool: List<Recipe>) {
        val s = Sheet(a, "Fate has spoken")
        val roll = 1 + java.util.Random().nextInt(20)
        val row = a.hbox()
        val d = a.vbox(); d.gravity = Gravity.CENTER_HORIZONTAL
        d.addView(a.icon("d20", 56, if (roll == 20) C.BUTTER else if (roll == 1) C.RASP else C.LILAC), lp(a.dp(56), a.dp(56)))
        val n = a.body(roll.toString(), 22f, C.INK, 800); n.gravity = Gravity.CENTER
        d.addView(n, lp(MATCH, WRAP))
        row.addView(d, lp(a.dp(64), WRAP).m(r = a.dp(14)))
        val col = a.vbox()
        col.addView(a.note(if (roll == 20) "Natural 20! Tonight is" else if (roll == 1) "Critical fail… but still:" else "Tonight's quest:", 13f))
        col.addView(a.script(r.title, 34f).single())
        col.addView(a.body(fmtDuration(r.timeMin) + " · DC " + Fame.dc(r.effort), 12f, C.INK2, 600))
        row.addView(col, lp(0, WRAP, 1f))
        s.body.addView(row)
        s.button("Reroll") { val p = pool[java.util.Random().nextInt(pool.size)]; show(a, p, pool) }
        s.button("Open it", primary = true) { a.push(RecipeScreen(a, r)) }
        s.show()
    }
}

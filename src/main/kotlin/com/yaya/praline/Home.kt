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
    photo.placeholderIcon = if (r.dessert) "potion" else "bowl"
    photo.placeholderColor = cls.color
    photo.show(r.photo, 480)
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
        // a swipe to the right brings Settings in from the left, as in Pistachio
        val frame = SwipeCatcher(a, 1) { d, p, v -> a.slideIn({ SettingsScreen(a) }, d, p, v) }
        val sv = ScrollView(a)
        sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        col.setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(130))
        sv.addView(col)
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))

        // header: lettering + chef medallion
        val head = FrameLayout(a)
        val titles = a.vbox()
        titles.addView(a.script("Praliné", 66f), lp(WRAP, WRAP).m(l = -a.dp(6)))
        titles.addView(a.note("the grimoire of ${Store.me.name}'s kitchen", 13f), lp(WRAP, WRAP).m(l = a.dp(16), t = -a.dp(8)))
        head.addView(titles, flp(WRAP, WRAP))
        val party = a.vbox(); party.gravity = Gravity.CENTER_HORIZONTAL
        val fame = Fame.total(Store.me); val lvl = Fame.level(fame)
        val med = Medallion(a, Store.me.portrait)
        val medBox = FrameLayout(a)
        val peer = Store.peer
        if (peer != null) {
            val pm = Medallion(a, peer.portrait, C.MINT)
            medBox.addView(pm, flp(a.dp(40), a.dp(40), Gravity.START or Gravity.BOTTOM))
            pm.onClick { a.push(ProfileScreen(a, peer, true)) }
        }
        medBox.addView(med, flp(a.dp(68), a.dp(68), Gravity.END or Gravity.TOP))
        med.onClick { a.push(ProfileScreen(a, Store.me, false)) }
        party.addView(medBox, lp(a.dp(if (peer != null) 96 else 68), a.dp(68)))
        val lv = a.chip("Lv $lvl", true, C.BUTTER, null, 11f)
        party.addView(lv, lp(WRAP, WRAP).m(t = -a.dp(10)))
        lv.onClick { a.push(ProfileScreen(a, Store.me, false)) }
        head.addView(party, flp(WRAP, WRAP, Gravity.END or Gravity.TOP).m(t = a.dp(8)))
        col.addView(head)

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
        val names = arrayOf("Meals", "Desserts", "All")
        val colors = intArrayOf(C.TANG, C.RASP, C.MINT)
        for (i in 0..2) {
            val ch = a.chip(names[i], tab == i, colors[i], null, if (tab == i) 14f else 13f)
            if (tab == i) (ch.tag as android.widget.TextView).setTextColor(C.WHITE)
            ch.onClick { tab = i; rebuild() }
            tabs.addView(ch, lp(WRAP, WRAP).m(r = a.dp(8)))
        }
        tabs.addView(a.space(), lp(0, 1, 1f))
        val sortLbl = a.label(when (sort) { "tier" -> "by tier ▾"; "recent" -> "recently cooked ▾"; "time" -> "quickest ▾"; "effort" -> "easiest ▾"; else -> "a–z ▾" })
        sortLbl.setPadding(a.dp(6), a.dp(10), 0, a.dp(10))
        sortLbl.onClick { chooseSort() }
        tabs.addView(sortLbl)
        col.addView(tabs, lp(MATCH, WRAP).m(t = a.dp(18)))

        val list = Store.recipes.filter { tab == 2 || (tab == 1) == it.dessert }
        if (Store.recipes.isEmpty()) col.addView(emptyState(), lp(MATCH, WRAP).m(t = a.dp(30)))
        else if (list.isEmpty()) col.addView(a.note(if (tab == 0) "No meals yet. Tap + to add one." else "No desserts yet. Tap + to add one.", 14f), lp(MATCH, WRAP).m(t = a.dp(30)))
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
        left.addView(roll, lp(WRAP, WRAP).m(r = a.dp(18)))
        val board = a.vbox(); board.gravity = Gravity.CENTER_HORIZONTAL
        board.addView(a.icon("scroll", 34, C.LILAC), lp(a.dp(34), a.dp(34)))
        board.addView(a.body("Quest board", 10.5f, C.INK, 600).single(), lp(WRAP, WRAP))
        board.onClick { a.push(BoardScreen(a)) }
        left.addView(board, lp(WRAP, WRAP).m(r = a.dp(18)))
        val set = a.vbox(); set.gravity = Gravity.CENTER_HORIZONTAL
        set.addView(a.icon("dots", 34, C.MINT), lp(a.dp(34), a.dp(34)))
        set.addView(a.body("Settings", 10.5f, C.INK, 600).single(), lp(WRAP, WRAP))
        set.contentDescription = "Settings"
        set.onClick { a.push(SettingsScreen(a)) }
        left.addView(set)
        bar.addView(left, flp(WRAP, WRAP, Gravity.START or Gravity.BOTTOM).m(l = a.dp(22), b = a.dp(10)))
        val fab = FrameLayout(a)
        val fbg = InkBox(a, C.RASP, C.INK, 999f, 2.4f, 4f, 2)
        fab.background = fbg
        fab.addView(a.icon("plus", 30, 0, C.WHITE, 11f), flp(a.dp(30), a.dp(30), Gravity.CENTER).m(r = fbg.offsetPx, b = fbg.offsetPx))
        fab.pressable()
        fab.onClick { a.push(EditorScreen(a, null)) }
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

    private fun emptyState(): View {
        val c = a.card(C.LILAC, 18, 18)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(a.icon("scroll", 70, C.BUTTER), lp(a.dp(70), a.dp(70)))
        c.addView(a.script("A blank grimoire", 34f), lp(WRAP, WRAP).m(t = a.dp(4)))
        c.addView(a.note("Every recipe you add becomes a card here, ranked from Tier S down. Cooking them earns you fame.", 13.5f).also { it.gravity = Gravity.CENTER })
        val b = a.bigButton("Write the first one", C.RASP, C.WHITE, C.INK, "plus")
        b.onClick { a.push(EditorScreen(a, null)) }
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
        p.placeholderIcon = if (r.dessert) "potion" else "bowl"; p.placeholderColor = Classes.get(r.cls).color
        p.show(r.photo, 200)
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
        val pool = Store.recipes.filter { tab == 2 || (tab == 1) == it.dessert }
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
    override fun onShow() { if (shown) rebuild(); shown = true }
    override fun onSynced() { rebuild() }
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

package com.yaya.praline

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/**
 * A party mission: the world map with the party on its trail, the party's XP and the next spot to cook.
 * Both chefs' cooks move the party; a spot's recipe joins the grimoire when the party reaches it.
 */
class MissionScreen(a: MainActivity, val world: Missions.World = Missions.SUNFORGE) : Screen(a) {
    private var map: MissionMap? = null
    private var sv: ScrollView? = null
    private var placed = false

    override fun build(): View {
        Missions.start(a, world)
        val col = a.vbox()

        // the map first: it knows where the spots sit along the trail
        val m = MissionMap(a, world.asset, Missions.progress(world))
        map = m
        val p = Missions.progress(world)
        m.progress = p
        unlock(p)

        val head = a.vbox()
        head.setPadding(a.dp(18), a.dp(6), a.dp(18), a.dp(10))
        val top = a.hbox()
        val back = a.roundIcon("back", 38, C.CARD, 16); back.onClick { a.pop() }
        back.contentDescription = "Back"
        top.addView(back, lp(a.dp(38), a.dp(38)).m(r = a.dp(12)))
        top.addView(a.label("World I · Party mission"), lp(0, WRAP, 1f))
        top.addView(a.body("Recipe ${Math.min(p.done + 1, world.spots.size)} / ${world.spots.size}", 12f, C.INK, 600))
        head.addView(top)
        head.addView(a.script(world.name, 44f), lp(MATCH, WRAP).m(l = -a.dp(4), t = -a.dp(2)))

        val xr = a.hbox()
        xr.addView(a.label("Party XP", C.INK), lp(WRAP, WRAP).m(r = a.dp(8)))
        xr.addView(FameBar(a, p.into.toFloat() / p.span), lp(0, a.dp(14), 1f))
        val goal = p.next?.xp ?: p.xp
        xr.addView(a.body(if (p.complete) "${p.xp} XP" else "${Math.min(p.xp, goal)} / $goal XP", 12f, C.INK, 600), lp(WRAP, WRAP).m(l = a.dp(8)))
        head.addView(xr, lp(MATCH, WRAP).m(t = a.dp(2)))

        val nr = a.hbox()
        nr.addView(a.note(nextLine(p), 12.5f), lp(0, WRAP, 1f))
        nr.addView(dots(p))
        head.addView(nr, lp(MATCH, WRAP).m(t = a.dp(6)))
        if (p.complete) head.addView(banner(), lp(MATCH, WRAP).m(t = a.dp(10)))
        col.addView(head)

        val scroll = object : ScrollView(a) {
            override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
                super.onScrollChanged(l, t, ol, ot); m.viewTop = t; m.viewH = height; m.invalidate()
            }
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                super.onSizeChanged(w, h, ow, oh); m.viewH = h
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(m, FrameLayout.LayoutParams(MATCH, WRAP))
        sv = scroll
        col.addView(scroll, lp(MATCH, 0, 1f))
        m.onSpot = { i -> spotSheet(i) }
        // opens on the party, a little below the middle so the road ahead shows
        scroll.post {
            if (!placed) { placed = true; scroll.scrollTo(0, Math.max(0, m.partyY() - scroll.height * 3 / 5)) }
            m.viewTop = scroll.scrollY; m.viewH = scroll.height
        }
        return col
    }

    private fun nextLine(p: Missions.Progress): String {
        val n = p.next ?: return "The Calice is lit. Chapter complete."
        val title = n.recipe().title
        return if (p.waiting) "Next spot: $title · must cook" else "Next spot: $title · ${p.toNext} XP to go"
    }

    private fun dots(p: Missions.Progress): View {
        val row = a.hbox()
        for (i in world.spots.indices) {
            val d = View(a)
            val g = android.graphics.drawable.GradientDrawable(); g.shape = android.graphics.drawable.GradientDrawable.OVAL
            g.setColor(when { i < p.done -> C.BUTTER; i == p.done && p.waiting -> C.RASP; else -> C.CARD })
            g.setStroke(a.dp(1), C.INK)
            d.background = g
            row.addView(d, lp(a.dp(7), a.dp(7)).m(l = a.dp(3)))
        }
        return row
    }

    private fun banner(): View {
        val b = a.hbox(); b.gravity = Gravity.CENTER_VERTICAL
        b.background = InkBox(a, C.BUTTER, C.INK, 14f, 2f, 3f, 2)
        b.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(12))
        b.addView(a.icon("map", 26, C.CARD), lp(a.dp(26), a.dp(26)).m(r = a.dp(10)))
        val t = a.vbox()
        t.addView(a.body("The Calice is lit", 15f, C.INK, 700))
        t.addView(a.note("Your party crossed the Sunforge Sands. All ten recipes are in the grimoire.", 12f, C.INK))
        b.addView(t, lp(0, WRAP, 1f))
        return b
    }

    private fun spotSheet(i: Int) {
        val p = map?.progress ?: Missions.progress(world)
        val spot = world.spots[i]
        val id = Missions.recipeId(world.id, i)
        val title = spot.recipe().title
        val s = Sheet(a, title)
        val where = if (i == world.spots.size - 1) "The Calice · the last spot" else "Spot ${i + 1} of ${world.spots.size}"
        when {
            i < p.done -> {
                s.body.addView(a.note("$where. The party cooked it here. ✓", 13f))
                openButton(s, id)
            }
            i < p.reached -> {
                s.body.addView(a.note("$where. The party is standing here: cook this recipe to move on. Other cooks still earn XP, kept for the road ahead.", 13f))
                openButton(s, id)
            }
            else -> {
                val need = Math.max(0, spot.xp - p.xp)
                s.body.addView(a.note("$where. Locked: the party needs $need more XP to reach it. Any recipe either of you cooks counts.", 13f))
                if (i > p.reached) s.body.addView(a.note("Cook the spots before it first.", 12f), lp(MATCH, WRAP).m(t = a.dp(4)))
                s.button("Close", primary = true)
            }
        }
        s.show()
    }

    private fun openButton(s: Sheet, id: String) {
        val r = Store.recipe(id)
        if (r != null) s.button("Close").button("Open the recipe", primary = true) { a.push(RecipeScreen(a, r)) }
        else {
            s.body.addView(a.note("You took it out of your grimoire.", 12f), lp(MATCH, WRAP).m(t = a.dp(4)))
            s.button("Close").button("Bring it back", primary = true) {
                Store.tombstones.remove(id)
                val back = Missions.unlock(a, world)
                if (back.isNotEmpty()) a.push(RecipeScreen(a, back[0]))
            }
        }
    }

    private fun unlock(p: Missions.Progress) {
        val added = Missions.unlock(a, world, p)
        if (added.isNotEmpty()) {
            Sync.nudge(a)
            a.toast(if (added.size == 1) "${added[0].title} joined your grimoire" else "${added.size} mission recipes joined your grimoire")
        }
    }

    private var shown = false
    override fun onShow() { if (shown) rebuild(); shown = true }
    override fun onSynced() { rebuild() }
    override fun onHide() { map?.release() }
}

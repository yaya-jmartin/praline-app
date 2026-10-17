package com.yaya.praline

import android.content.ClipData
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Calendar
import java.util.Random

/** Suggestions for tonight by time and energy, a d20 roll, and the week planner. */
class BoardScreen(a: MainActivity) : Screen(a) {
    private val prefs get() = a.getSharedPreferences("app", 0)
    private var time: Int
        get() = prefs.getInt("board_time", 1)
        set(v) { prefs.edit().putInt("board_time", v).apply() }
    private var energy: Int
        get() = prefs.getInt("board_energy", 0)
        set(v) { prefs.edit().putInt("board_energy", v).apply() }
    var week = 0
    private var shown = false
    private val rnd = Random()

    /** Recipes that fit the time and energy chips, least recently cooked first. */
    fun suggestions(): List<Recipe> {
        val maxTime = when (time) { 0 -> 20; 1 -> 45; else -> Int.MAX_VALUE }
        val maxEffort = when (energy) { 0 -> 2; 1 -> 3; else -> 5 }
        val out = Store.recipes.filter { it.timeMin <= maxTime && it.effort <= maxEffort }
        return out.sortedWith(object : Comparator<Recipe> { override fun compare(x: Recipe, y: Recipe): Int { val c = x.lastCooked.compareTo(y.lastCooked); return if (c != 0) c else Tiers.rank(x.tier) - Tiers.rank(y.tier) } })
    }

    override fun build(): View {
        val sv = ScrollView(a); sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        col.setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(40))
        sv.addView(col)

        val top = a.hbox()
        val back = a.roundIcon("back", 38, C.CARD, 16); back.onClick { a.pop() }
        top.addView(back, lp(a.dp(38), a.dp(38)).m(r = a.dp(12)))
        top.addView(a.label("What's cooking"))
        col.addView(top)
        col.addView(a.script("Quest board", 52f), lp(MATCH, WRAP).m(l = -a.dp(4)))

        val tr = FlowLayout(a, a.dp(8), a.dp(8))
        tr.addView(a.note("Tonight I have", 13f).also { it.setPadding(0, a.dp(6), 0, 0) })
        val times = arrayOf("20 min", "45 min", "1 h+")
        for (i in times.indices) { val c = a.chip(times[i], time == i, C.BUTTER, null, 12.5f); c.onClick { time = i; rebuild() }; tr.addView(c) }
        col.addView(tr, lp(MATCH, WRAP).m(t = a.dp(4)))
        val er = FlowLayout(a, a.dp(8), a.dp(8))
        er.addView(a.note("and energy for", 13f).also { it.setPadding(0, a.dp(6), 0, 0) })
        val energies = arrayOf("easy · DC 5–10", "a bit more", "a challenge")
        for (i in energies.indices) { val c = a.chip(energies[i], energy == i, C.PIST, null, 12.5f); c.onClick { energy = i; rebuild() }; er.addView(c) }
        col.addView(er, lp(MATCH, WRAP).m(t = a.dp(8)))

        val sug = suggestions()
        if (Store.recipes.isEmpty()) col.addView(a.note("Write a few recipes and the board fills up.", 13.5f), lp(MATCH, WRAP).m(t = a.dp(16)))
        else if (sug.isEmpty()) col.addView(a.note("Nothing fits tonight. Try more time or more energy.", 13.5f), lp(MATCH, WRAP).m(t = a.dp(16)))
        else {
            val hs = HorizontalScrollView(a); hs.isHorizontalScrollBarEnabled = false; hs.clipToPadding = false
            hs.setPadding(0, a.dp(4), a.dp(8), a.dp(8))
            val row = a.hbox(); row.gravity = Gravity.TOP
            val w = (a.resources.displayMetrics.widthPixels - a.dp(44) - a.dp(24)) / 3
            for (k in 0 until Math.min(10, sug.size)) row.addView(a.recipeCard(sug[k], true), lp(w, WRAP).m(r = a.dp(12)))
            hs.addView(row)
            col.addView(hs, lp(MATCH, WRAP).m(t = a.dp(14)))
        }
        if (Store.recipes.isNotEmpty()) {
            val roll = a.hbox()
            val d = a.icon("d20", 26, C.BUTTER)
            roll.addView(d, lp(a.dp(26), a.dp(26)).m(r = a.dp(8)))
            roll.addView(a.body("Can't choose? Roll for it", 13f, C.INK, 600))
            roll.setPadding(0, a.dp(8), 0, a.dp(8))
            roll.onClick {
                d.animate().rotationBy(360f).setDuration(450).start()
                val pool = if (sug.isNotEmpty()) sug else Store.recipes
                Fate.show(a, pool[rnd.nextInt(pool.size)], pool)
            }
            col.addView(roll, lp(MATCH, WRAP).m(t = a.dp(4)))
        }

        // week planner
        val mon = Days.monday(week)
        val sun = mon.clone() as Calendar; sun.add(Calendar.DAY_OF_MONTH, 6)
        val wh = a.hbox()
        val prev = a.roundIcon("chev", 30, C.CARD, 12); prev.rotation = 180f; prev.onClick { week--; rebuild() }
        val next = a.roundIcon("chev", 30, C.CARD, 12); next.onClick { week++; rebuild() }
        wh.addView(a.script(when (week) { 0 -> "This week"; 1 -> "Next week"; -1 -> "Last week"; else -> "Week" }, 34f), lp(0, WRAP, 1f))
        wh.addView(prev, lp(a.dp(30), a.dp(30)).m(r = a.dp(6)))
        wh.addView(a.label(range(mon, sun)), lp(WRAP, WRAP).m(r = a.dp(6)))
        wh.addView(next, lp(a.dp(30), a.dp(30)))
        col.addView(wh, lp(MATCH, WRAP).m(t = a.dp(16)))

        val card = a.card(C.LILAC, 12, 4)
        val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val today = Days.key(Calendar.getInstance())
        var empty = 0
        for (i in 0..6) {
            val day = mon.clone() as Calendar; day.add(Calendar.DAY_OF_MONTH, i)
            val key = Days.key(day)
            val planned = entry(key)
            if (planned == null) empty++
            card.addView(dayRow(names[i], key, key == today, planned, i < 6))
        }
        col.addView(card, lp(MATCH, WRAP).m(t = a.dp(4)))
        if (empty > 0 && Store.recipes.isNotEmpty()) {
            val b = a.chip("Roll the empty days", false, C.BUTTER, "d20", 13f)
            b.onClick { rollEmpty(mon) }
            col.addView(b, lp(WRAP, WRAP).m(t = a.dp(12)))
        }
        col.addView(a.note("Long-press a planned day and drag it onto another to move or swap it.", 11f), lp(MATCH, WRAP).m(t = a.dp(10)))
        return sv
    }

    private fun range(a1: Calendar, b: Calendar): String =
        if (a1.get(Calendar.MONTH) == b.get(Calendar.MONTH)) "${a1.get(Calendar.DAY_OF_MONTH)} – ${b.get(Calendar.DAY_OF_MONTH)} ${Days.month(b)}"
        else "${a1.get(Calendar.DAY_OF_MONTH)} ${Days.month(a1)} – ${b.get(Calendar.DAY_OF_MONTH)} ${Days.month(b)}"

    class Planned(val recipe: Recipe, val variant: Int)

    fun entry(key: String): Planned? {
        val v = Store.plan[key] ?: return null
        val parts = v.split("|")
        val r = Store.recipe(parts[0]) ?: return null
        val vi = if (parts.size > 1) (parts[1].toIntOrNull() ?: 0) else 0
        return Planned(r, Math.max(0, Math.min(vi, r.variants.size - 1)))
    }

    private fun dayRow(name: String, key: String, today: Boolean, p: Planned?, line: Boolean): View {
        val wrap = a.vbox()
        val row = a.hbox()
        row.setPadding(0, a.dp(7), 0, a.dp(7))
        row.addView(a.body(name, 12.5f, if (today) C.RASP else C.INK, 700), lp(a.dp(38), WRAP))
        if (p == null) {
            row.addView(a.note("+ pick a recipe or roll", 12.5f, C.FAINT), lp(0, WRAP, 1f))
            val d = a.icon("d20", 20, 0, C.INK, 7f); d.alpha = 0.5f
            d.onClick { rollDay(key) }
            row.addView(d, lp(a.dp(28), a.dp(28)))
            row.onClick { pickFor(key) }
        } else {
            val ph = RoundPhoto(a, 999f); ph.outline = true
            ph.placeholderIcon = Classes.get(p.recipe.cls).icon; ph.placeholderColor = Classes.get(p.recipe.cls).color
            ph.show(p.recipe.photo, 160)
            row.addView(ph, lp(a.dp(30), a.dp(30)).m(r = a.dp(10)))
            val t = p.recipe.title + if (p.recipe.variants.size > 1 && p.variant > 0) " · " + p.recipe.variants[p.variant].name else ""
            row.addView(a.script(t, 22f).single(), lp(0, WRAP, 1f))
            row.addView(a.icon("drag", 18, 0, C.INK2, 7f), lp(a.dp(18), a.dp(18)))
            row.onClick { dayMenu(key, p) }
            row.setOnLongClickListener(object : View.OnLongClickListener {
                override fun onLongClick(v: View): Boolean {
                    v.startDragAndDrop(ClipData.newPlainText("day", key), View.DragShadowBuilder(v), key, 0)
                    v.alpha = 0.4f
                    return true
                }
            })
        }
        row.setOnDragListener(object : View.OnDragListener {
            override fun onDrag(v: View, e: DragEvent): Boolean {
                when (e.action) {
                    DragEvent.ACTION_DRAG_STARTED -> return e.localState is String
                    DragEvent.ACTION_DRAG_ENTERED -> v.setBackgroundColor(0x33F0547A)
                    DragEvent.ACTION_DRAG_EXITED -> v.setBackgroundColor(0)
                    DragEvent.ACTION_DROP -> { v.setBackgroundColor(0); move(e.localState as String, key); return true }
                    DragEvent.ACTION_DRAG_ENDED -> { v.setBackgroundColor(0); v.alpha = 1f }
                }
                return true
            }
        })
        wrap.addView(row)
        if (line) wrap.addView(View(a).also { it.background = DashLine(a) }, lp(MATCH, a.dp(2)))
        return wrap
    }

    /** Moves the plan of day [from] onto day [to], swapping if [to] was planned too. */
    fun move(from: String, to: String) {
        if (from == to) return
        val a1 = Store.plan[from]; val b = Store.plan[to]
        if (b == null) Store.plan.remove(from) else Store.plan[from] = b
        if (a1 == null) Store.plan.remove(to) else Store.plan[to] = a1
        Store.planUpdated = System.currentTimeMillis()
        Store.save(a)
        Photos.post { rebuild() }
    }

    private fun pool(): List<Recipe> = Store.recipes.filter { !it.dessert }.ifEmpty { Store.recipes }

    private fun rollDay(key: String) {
        val p = pool(); if (p.isEmpty()) return
        val r = p[rnd.nextInt(p.size)]
        Store.setPlan(a, key, r.id + "|0")
        a.toast("Fate picks ${r.title}")
        rebuild()
    }

    fun rollEmpty(mon: Calendar) {
        val p = ArrayList(pool()); if (p.isEmpty()) return
        val used = HashSet<String>()
        for (i in 0..6) { val d = mon.clone() as Calendar; d.add(Calendar.DAY_OF_MONTH, i); val e = entry(Days.key(d)); if (e != null) used.add(e.recipe.id) }
        for (i in 0..6) {
            val d = mon.clone() as Calendar; d.add(Calendar.DAY_OF_MONTH, i)
            val k = Days.key(d)
            if (entry(k) != null) continue
            var fresh = p.filter { it.id !in used }
            if (fresh.isEmpty()) { used.clear(); fresh = p }
            val r = fresh[rnd.nextInt(fresh.size)]
            used.add(r.id)
            Store.plan[k] = r.id + "|0"
        }
        Store.planUpdated = System.currentTimeMillis()
        Store.save(a)
        rebuild()
    }

    private fun pickFor(key: String) {
        val s = Sheet(a, "Plan a quest")
        s.body.addView(a.note("Pick a recipe for this day.", 12.5f), lp(MATCH, WRAP).m(b = a.dp(6)))
        val list = Store.recipes.sortedWith(object : Comparator<Recipe> { override fun compare(x: Recipe, y: Recipe): Int { val c = Tiers.rank(x.tier) - Tiers.rank(y.tier); return if (c != 0) c else x.title.lowercase().compareTo(y.title.lowercase()) } })
        for (r in list) {
            val row = a.hbox(); row.setPadding(0, a.dp(6), 0, a.dp(6))
            val ph = RoundPhoto(a, 999f); ph.outline = true
            ph.placeholderIcon = Classes.get(r.cls).icon; ph.placeholderColor = Classes.get(r.cls).color
            ph.show(r.photo, 160)
            row.addView(ph, lp(a.dp(32), a.dp(32)).m(r = a.dp(10)))
            row.addView(a.script(r.title, 22f).single(), lp(0, WRAP, 1f))
            row.addView(a.note(fmtDuration(r.timeMin) + " · DC " + Fame.dc(r.effort), 11f))
            row.onClick {
                if (r.variants.size > 1) Photos.post { pickVariant(key, r) }
                else { Store.setPlan(a, key, r.id + "|0"); rebuild() }
                s.dismiss()
            }
            s.body.addView(row)
        }
        if (list.isEmpty()) s.body.addView(a.note("No recipes yet.", 13f))
        s.button("Roll for it") { rollDay(key) }.button("Close")
        s.show()
    }

    private fun pickVariant(key: String, r: Recipe) {
        val s = Sheet(a, r.title)
        s.body.addView(a.note("Which variant?", 12.5f))
        val fl = FlowLayout(a, a.dp(8), a.dp(8))
        for (i in r.variants.indices) {
            val c = a.chip(r.variants[i].name, false, C.PIST)
            c.onClick { Store.setPlan(a, key, r.id + "|" + i); s.dismiss(); rebuild() }
            fl.addView(c)
        }
        s.body.addView(fl, lp(MATCH, WRAP).m(t = a.dp(8)))
        s.show()
    }

    private fun dayMenu(key: String, p: Planned) {
        val s = Sheet(a, p.recipe.title)
        if (p.recipe.variants.size > 1) {
            val fl = FlowLayout(a, a.dp(8), a.dp(8))
            for (i in p.recipe.variants.indices) {
                val c = a.chip(p.recipe.variants[i].name, i == p.variant, C.PIST)
                c.onClick { Store.setPlan(a, key, p.recipe.id + "|" + i); s.dismiss(); rebuild() }
                fl.addView(c)
            }
            s.body.addView(fl)
        } else s.body.addView(a.note(fmtDuration(p.recipe.timeMin) + " · DC " + Fame.dc(p.recipe.effort), 12.5f))
        s.button("Clear") { Store.setPlan(a, key, null); rebuild() }
        s.button("Change") { Photos.post { pickFor(key) } }
        s.button("Open", primary = true) { val rs = RecipeScreen(a, p.recipe); rs.variant = p.variant; a.push(rs) }
        s.show()
    }

    override fun onShow() { if (shown) rebuild(); shown = true }
    override fun onSynced() { rebuild() }
}

/** A hand-dashed separator line. */
class DashLine(c: android.content.Context) : android.graphics.drawable.Drawable() {
    private val p = Ink.inkPaint(c, c.dpf(1.5f), C.DASH)
    init { p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(c.dpf(5f), c.dpf(4f)), 0f) }
    override fun draw(canvas: android.graphics.Canvas) { val y = bounds.exactCenterY(); canvas.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, p) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

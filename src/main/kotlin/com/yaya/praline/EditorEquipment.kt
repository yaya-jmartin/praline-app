package com.yaya.praline

import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView

/** The editor's equipment tab: tools, engineer's limbs, counts, and anything else typed in. */
class EquipmentTab(val ed: EditorScreen) {
    private val a get() = ed.a
    private val r get() = ed.r
    private val variant get() = ed.variant
    private val scroll get() = ed.scroll
    private fun refreshTabCounts() = ed.refreshTabCounts()
    private fun showTab(t: Int, keepFocus: Boolean = true) = ed.showTab(t, keepFocus)
    private fun reveal(v: View) = ed.reveal(v)
    private fun styleField(e: android.widget.EditText, hint: String, size: Float) = ed.styleField(e, hint, size)
    private fun watch(f: (String) -> Unit) = ed.watch(f)
    private fun keepScroll(sv: android.widget.ScrollView, y: Int) = ed.keepScroll(sv, y)

    // ---- equipment ----------------------------------------------------------------
    fun build(col: LinearLayout) {
        col.addView(a.note("What the recipe calls for. Tap to pick; your favourite weapon counts them too.", 11.5f), lp(MATCH, WRAP).m(b = a.dp(8)))
        val fl = FlowLayout(a, a.dp(7), a.dp(8))
        // a tap picks a tool; tapping a picked one sets how many (2 × Mixing bowl) or puts it back
        for (t in Equipment.all) if (!t.engineer) {
            val on = r.equipment.contains(t.key)
            val ch = a.chip(if (on) Equipment.label(r, t.key) else t.name, on, t.color, t.icon, 13f)
            ch.contentDescription = "equipment " + t.key
            ch.onClick { if (on) countSheet(t.key) else { r.equipment.add(t.key); r.noEquipment = false; refreshEquipment() } }
            fl.addView(ch)
        }
        for (k in r.equipment) if (Equipment.get(k) == null) {
            val ch = a.chip(Equipment.label(r, k), true, C.BEIGE, "spoon", 13f)
            ch.contentDescription = "equipment $k"
            ch.onClick { countSheet(k) }
            fl.addView(ch)
        }
        col.addView(fl)
        col.addView(a.label("Engineer's limbs"), lp(MATCH, WRAP).m(t = a.dp(14), b = a.dp(6)))
        val gl = FlowLayout(a, a.dp(7), a.dp(8))
        for (t in Equipment.all) if (t.engineer) {
            val on = r.equipment.contains(t.key)
            val ch = a.gearChip(if (on) Equipment.label(r, t.key) else t.name, on, t.icon, 13f)
            ch.contentDescription = "equipment " + t.key
            ch.onClick { if (on) countSheet(t.key) else { r.equipment.add(t.key); r.noEquipment = false; refreshEquipment() } }
            gl.addView(ch)
        }
        col.addView(gl)
        val row = a.hbox()
        val e = Fields.line(a, "Something else (mandoline, piping bag…)", 14f)
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        row.addView(e, lp(0, WRAP, 1f))
        val add = a.chip("Add", false, C.PIST, "plus", 13f)
        val addIt = {
            val w = e.text.toString().trim()
            if (w.isNotEmpty() && !r.equipment.contains(w)) { r.equipment.add(w); r.noEquipment = false; e.setText(""); refreshEquipment() }
        }
        add.onClick { addIt() }
        e.setOnEditorActionListener(object : TextView.OnEditorActionListener {
            override fun onEditorAction(v: TextView?, actionId: Int, ev: KeyEvent?): Boolean { addIt(); return true }
        })
        row.addView(add, lp(WRAP, WRAP).m(l = a.dp(8)))
        col.addView(row, lp(MATCH, WRAP).m(t = a.dp(12)))
        // an empty list is only allowed on purpose
        val none = r.noEquipment && r.equipment.isEmpty()
        val tick = a.chip("No equipment needed", none, C.BEIGE, if (none) "check" else "box", 13f)
        tick.contentDescription = "no equipment"
        tick.onClick {
            if (none) r.noEquipment = false
            else { r.equipment.clear(); r.equipCount.clear(); r.noEquipment = true }
            refreshEquipment()
        }
        col.addView(tick, lp(WRAP, WRAP).m(t = a.dp(14)))
    }

    /** How many of a picked tool, or putting it back. */
    private fun countSheet(key: String) {
        val s = Sheet(a, Equipment.name(key))
        var n = r.count(key)
        val row = a.hbox(); row.gravity = Gravity.CENTER_VERTICAL
        val minus = a.roundIcon("minus", 36, C.CARD, 16)
        val t = a.body("", 22f, C.INK, 800); t.gravity = Gravity.CENTER
        t.contentDescription = "equipment count"
        val plus = a.roundIcon("plus", 36, C.CARD, 16)
        fun paint() { t.text = "$n ×" }
        minus.onClick { n = Math.max(1, n - 1); paint() }
        plus.onClick { n = Math.min(99, n + 1); paint() }
        minus.contentDescription = "fewer"; plus.contentDescription = "more"
        row.addView(minus, lp(a.dp(36), a.dp(36)))
        row.addView(t, lp(a.dp(110), WRAP))
        row.addView(plus, lp(a.dp(36), a.dp(36)))
        s.body.addView(row, lp(MATCH, WRAP))
        paint()
        s.button("Remove") { r.equipment.remove(key); r.equipCount.remove(key); refreshEquipment() }
        s.button("Set", primary = true) { r.setCount(key, n); refreshEquipment() }
        s.show()
    }

    private fun refreshEquipment() {
        val sv = scroll; val y = sv?.scrollY ?: 0
        showTab(1, false)
        refreshTabCounts()
        if (sv != null) keepScroll(sv, y)
    }
}

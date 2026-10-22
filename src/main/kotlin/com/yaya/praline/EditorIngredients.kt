package com.yaya.praline

import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView

/** The editor's ingredient tab: one line per ingredient, autocomplete from the pantry, paste a whole list. */
class IngredientTab(val ed: EditorScreen) {
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

    // ---- ingredients ------------------------------------------------------------
    private var ingList: LinearLayout? = null

    fun build(col: LinearLayout) {
        val l = ed.linesFor(variant)
        col.addView(a.note("One per line, quantity first: “200 g flour”, “1 ½ cup milk”, “2 eggs”. Leave the amount out for things like salt: it shows as “to taste”.", 11.5f), lp(MATCH, WRAP).m(b = a.dp(8)))
        val list = a.vbox()
        ingList = list
        col.addView(list, lp(MATCH, WRAP))
        val names = Pantry.names()
        for (i in l.indices) list.addView(ingredientRow(l, l[i], names))
        val btns = a.hbox()
        val add = a.chip("Add ingredient", false, C.PIST, "plus", 13f)
        add.onClick { addIngredient(l, names) }
        btns.addView(add, lp(WRAP, WRAP).m(r = a.dp(8)))
        val paste = a.chip("Paste a list", false, C.BUTTER, "scroll", 13f)
        paste.onClick { pasteList(l) }
        btns.addView(paste)
        col.addView(btns, lp(MATCH, WRAP).m(t = a.dp(4)))
    }

    private fun ingredientRow(l: MutableList<String>, text: String, names: List<String>): View {
        val row = a.hbox()
        row.addView(a.icon("sprig", 18, C.PIST, C.INK, 7f), lp(a.dp(18), a.dp(18)).m(r = a.dp(8)))
        val e = android.widget.AutoCompleteTextView(a)
        styleField(e, "200 g flour", 15f)
        e.threshold = 1
        e.setAdapter(IngredientAdapter(a, names))
        e.setDropDownBackgroundDrawable(InkBox(a, C.CARD, C.BUTTER, 14f, 1.8f, 3f, 1))
        e.setText(text)
        e.dismissDropDown()
        e.imeOptions = EditorInfo.IME_ACTION_NEXT
        e.addTextChangedListener(watch { val i = indexIn(row); if (i >= 0 && i < l.size) l[i] = it })
        e.setOnEditorActionListener(object : TextView.OnEditorActionListener {
            override fun onEditorAction(v: TextView?, actionId: Int, ev: KeyEvent?): Boolean {
                if (actionId != EditorInfo.IME_ACTION_NEXT) return false
                val i = indexIn(row)
                if (i == l.size - 1 && e.text.isNotBlank()) { addIngredient(l, names); return true }
                return false
            }
        })
        e.contentDescription = "ingredient"
        row.addView(e, lp(0, WRAP, 1f))
        val del = a.roundIcon("x", 30, C.CARD, 12)
        del.onClick {
            val i = indexIn(row)
            if (i >= 0) { l.removeAt(i); ingList?.removeView(row); refreshTabCounts() }
        }
        row.addView(del, lp(a.dp(30), a.dp(30)).m(l = a.dp(6)))
        row.layoutParams = lp(MATCH, WRAP).m(b = a.dp(6))
        return row
    }

    private fun indexIn(row: View): Int = ingList?.indexOfChild(row) ?: -1

    /** Adds an empty line under the others and puts the cursor in it; the page scrolls just enough to show it. */
    private fun addIngredient(l: MutableList<String>, names: List<String>) {
        val list = ingList ?: return
        l.add("")
        val row = ingredientRow(l, "", names)
        list.addView(row)
        refreshTabCounts()
        val e = (row as LinearLayout).getChildAt(1)
        e.requestFocus()
        a.showKeyboard(e)
        row.post(object : Runnable { override fun run() { reveal(row) } })
    }


    private fun pasteList(l: MutableList<String>) {
        val s = Sheet(a, "Paste ingredients")
        val e = Fields.line(a, "200 g flour\n3 eggs\n1 pinch of salt", 15f, true)
        e.minLines = 6; e.gravity = Gravity.TOP
        s.body.addView(e, lp(MATCH, WRAP))
        s.button("Cancel").button("Add them", primary = true) {
            for (line in e.text.toString().split("\n")) {
                val t = line.trim().trimStart('-', '•', '*', '·').trim()
                if (t.isNotEmpty()) l.add(t)
            }
            a.hideKeyboard(); showTab(0)
        }
        s.show(); a.showKeyboard(e)
    }
}

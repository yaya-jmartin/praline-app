package com.yaya.praline

import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView

/** The editor's step tab: step text with scaling amounts, timers with rounds, hint pictures and clips. */
class StepTab(val ed: EditorScreen) {
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

    // ---- steps ----------------------------------------------------------------
    private var stepList: LinearLayout? = null

    fun build(col: LinearLayout, v: Variant) {
        col.addView(a.note("Amounts written before an ingredient (“add 2 eggs”) follow the servings wheel, even when it's only part of it.", 11.5f), lp(MATCH, WRAP).m(b = a.dp(8)))
        val list = a.vbox()
        stepList = list
        col.addView(list, lp(MATCH, WRAP))
        for (i in v.steps.indices) list.addView(stepCard(v, v.steps[i]))
        val add = a.chip("Add a step", false, C.PIST, "plus", 13f)
        add.onClick {
            val st = Step("")
            v.steps.add(st)
            val card = stepCard(v, st)
            list.addView(card)
            renumber(v)
            refreshTabCounts()
            val e = card.findViewWithTag<View>("text")
            e?.requestFocus(); if (e != null) a.showKeyboard(e)
            card.post(object : Runnable { override fun run() { reveal(card) } })
        }
        val btns = a.hbox()
        btns.addView(add, lp(WRAP, WRAP).m(r = a.dp(8)))
        val paste = a.chip("Paste steps", false, C.BUTTER, "scroll", 13f)
        paste.onClick { pasteSteps(v) }
        btns.addView(paste)
        col.addView(btns, lp(MATCH, WRAP).m(t = a.dp(2)))
    }

    /** A whole method pasted at once, like the ingredient list: numbered lines, paragraphs, or one step per line (1.4). */
    private fun pasteSteps(v: Variant) {
        val s = Sheet(a, "Paste steps")
        val e = Fields.line(a, "1. Whisk the eggs and sugar.\n2. Fold in the flour.\n3. Bake 25 min.", 15f, true)
        e.minLines = 6; e.gravity = Gravity.TOP
        s.body.addView(e, lp(MATCH, WRAP))
        s.body.addView(a.note("Numbered lines, paragraphs or one step per line. Blank steps are left out.", 11.5f), lp(MATCH, WRAP).m(t = a.dp(6)))
        s.button("Cancel").button("Add them", primary = true) {
            // an empty first step from "Add a step" is replaced, not kept
            v.steps.removeAll { it.text.isBlank() && it.timerSec == 0 && it.hint.isEmpty() && it.video.isEmpty() }
            for (t in StepImport.split(e.text.toString())) v.steps.add(Step(t))
            a.hideKeyboard(); redrawSteps(v)
        }
        s.show(); a.showKeyboard(e)
    }

    private fun redrawSteps(v: Variant) {
        val list = stepList ?: return
        val sv = scroll; val y = sv?.scrollY ?: 0
        list.removeAllViews()
        for (st in v.steps) list.addView(stepCard(v, st))
        refreshTabCounts()
        if (sv != null) keepScroll(sv, y)
    }

    private fun renumber(v: Variant) {
        val list = stepList ?: return
        for (i in 0 until list.childCount) {
            val n = list.getChildAt(i).findViewWithTag<TextView>("num") ?: continue
            n.text = (i + 1).toString()
        }
        if (list.childCount != v.steps.size) redrawSteps(v)
    }

    private fun stepCard(v: Variant, st: Step): View {
        val i = v.steps.indexOf(st)
        val card = a.card(if (i % 2 == 0) C.PIST else C.LILAC, 12, 10)
        val head = a.hbox()
        val num = a.script((i + 1).toString(), 32f, C.RASP); num.tag = "num"
        head.addView(num, lp(a.dp(30), WRAP))
        head.addView(a.space(), lp(0, 1, 1f))
        val up = a.roundIcon("chev", 30, C.CARD, 12); up.rotation = -90f
        up.onClick { val k = v.steps.indexOf(st); if (k > 0) { v.steps.add(k - 1, v.steps.removeAt(k)); redrawSteps(v) } }
        head.addView(up, lp(a.dp(30), a.dp(30)).m(r = a.dp(6)))
        val dn = a.roundIcon("chev", 30, C.CARD, 12); dn.rotation = 90f
        dn.onClick { val k = v.steps.indexOf(st); if (k >= 0 && k < v.steps.size - 1) { v.steps.add(k + 1, v.steps.removeAt(k)); redrawSteps(v) } }
        head.addView(dn, lp(a.dp(30), a.dp(30)).m(r = a.dp(6)))
        val del = a.roundIcon("trash", 30, C.CARD, 13, 9f); del.onClick { v.steps.remove(st); redrawSteps(v) }
        head.addView(del, lp(a.dp(30), a.dp(30)))
        card.addView(head)
        val e = MultiAutoCompleteTextView(a)
        styleField(e, "What happens in this step…", 15f)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        e.threshold = 1
        e.setTokenizer(AmountTokenizer())
        val ingNames = ArrayList<String>()
        commitVariantLines()
        for (ing in v.ingredients) ingNames.add(ing.name.lowercase())
        e.setAdapter(android.widget.ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line, ingNames))
        e.setDropDownBackgroundDrawable(InkBox(a, C.CARD, C.BUTTER, 14f, 1.8f, 3f, 1))
        e.setText(st.text); e.minLines = 2; e.gravity = Gravity.TOP
        e.tag = "text"
        e.contentDescription = "step text"
        val vars = a.note(varsText(st.text, v), 11f, C.RASP)
        vars.visibility = if (vars.text.isEmpty()) View.GONE else View.VISIBLE
        e.addTextChangedListener(watch { st.text = it; val t = varsText(it, v); vars.text = t; vars.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE })
        card.addView(e, lp(MATCH, WRAP).m(t = a.dp(4)))
        card.addView(vars, lp(MATCH, WRAP).m(t = a.dp(4)))
        val extras = FlowLayout(a, a.dp(8), a.dp(8))
        val roundsText = if (st.rounds > 1) " ×${st.rounds}" else ""
        val tc = if (st.timerSec > 0) a.timerChip(fmtClock(st.timerSec.toLong()) + roundsText + (if (st.timerLabel.isNotEmpty()) " · " + st.timerLabel else ""))
                 else a.chip("Add a timer", false, C.TIMER, "hour", 12f)
        tc.onClick { timerSheet(v, st) }
        extras.addView(if (st.timerSec > 0) withX(tc, "remove timer") { st.timerSec = 0; st.timerLabel = ""; st.rounds = 1; redrawSteps(v) } else tc)
        if (st.video.isNotEmpty()) {
            val vc = a.chip("Clip", true, C.LILAC, "play", 12f)
            vc.onClick { videoMenu(v, st) }
            extras.addView(withX(vc, "remove clip") { st.video = ""; redrawSteps(v) })
        } else {
            val vc = a.chip("Video clip", false, C.LILAC, "cam", 12f)
            vc.onClick { addVideo(v, st) }
            extras.addView(vc)
        }
        if (st.hint.isNotEmpty()) {
            val p = RoundPhoto(a, 10f); p.outline = true; p.show(st.hint, 240); p.rotation = 2.5f
            p.onClick { hintMenu(v, st) }
            p.layoutParams = android.widget.FrameLayout.LayoutParams(a.dp(54), a.dp(54))
            extras.addView(withX(p, "remove picture") { st.hint = ""; redrawSteps(v) })
        } else {
            val hc = a.chip("Hint picture", false, C.MINT, "cam", 12f)
            hc.onClick { addHint(v, st) }
            extras.addView(hc)
        }
        card.addView(extras, lp(MATCH, WRAP).m(t = a.dp(8)))
        card.layoutParams = lp(MATCH, WRAP).m(b = a.dp(12))
        return card
    }

    /** A step extra (timer, clip, picture) with a small × on its corner that takes it off the step. */
    private fun withX(v: View, desc: String, remove: () -> Unit): View {
        val f = android.widget.FrameLayout(a)
        f.clipChildren = false; f.clipToPadding = false
        val lpv = v.layoutParams
        val w = if (lpv != null && lpv.width > 0) lpv.width else WRAP
        val h = if (lpv != null && lpv.height > 0) lpv.height else WRAP
        f.addView(v, android.widget.FrameLayout.LayoutParams(w, h).also { it.topMargin = a.dp(8); it.rightMargin = a.dp(9) })
        val x = a.roundIcon("x", 22, C.CARD, 10, 9f)
        x.contentDescription = desc
        x.onClick { remove() }
        f.addView(x, android.widget.FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.TOP or Gravity.END))
        return f
    }

    /** Parses the variant's typed ingredient lines now, so steps can match them. */
    private fun commitVariantLines() {
        val l = ed.lines[variant] ?: return
        val v = r.variant(variant)
        v.ingredients.clear()
        for (s in l) if (s.trim().isNotEmpty()) v.ingredients.add(Ingredient.parse(s.trim()))
    }

    private fun varsText(text: String, v: Variant): String {
        val s = StepText.summary(text, v.ingredients)
        return if (s.isEmpty()) "" else "Scales with servings: $s"
    }

    private fun timerSheet(v: Variant, st: Step) {
        val s = Sheet(a, "Step timer")
        val row = a.hbox()
        val min = Fields.line(a, "min", 22f); min.inputType = InputType.TYPE_CLASS_NUMBER; min.gravity = Gravity.CENTER
        val sec = Fields.line(a, "sec", 22f); sec.inputType = InputType.TYPE_CLASS_NUMBER; sec.gravity = Gravity.CENTER
        if (st.timerSec > 0) { min.setText((st.timerSec / 60).toString()); sec.setText((st.timerSec % 60).toString()) }
        row.addView(min, lp(a.dp(90), WRAP)); row.addView(a.body(" min ", 15f, C.INK2, 600))
        row.addView(sec, lp(a.dp(90), WRAP)); row.addView(a.body(" sec", 15f, C.INK2, 600))
        s.body.addView(row)
        val quick = FlowLayout(a, a.dp(7), a.dp(7))
        for (m in intArrayOf(1, 3, 5, 10, 15, 20, 30, 45, 60)) {
            val q = a.chip(if (m == 60) "1 h" else "$m min", false, C.TIMER, null, 12f)
            q.onClick { min.setText(m.toString()); sec.setText("0") }
            quick.addView(q)
        }
        s.body.addView(quick, lp(MATCH, WRAP).m(t = a.dp(10)))
        val label = Fields.line(a, "Label (Simmer, Bake, Rest…)", 15f)
        label.setText(st.timerLabel)
        s.body.addView(label, lp(MATCH, WRAP).m(t = a.dp(10)))
        // repeat
        var rounds = st.rounds
        val rr = a.hbox()
        rr.addView(a.label("Repeat"), lp(0, WRAP, 1f))
        val minus = a.roundIcon("minus", 32, C.CARD, 14)
        val rt = a.body("", 16f, C.INK, 800); rt.gravity = Gravity.CENTER
        val plus = a.roundIcon("plus", 32, C.CARD, 14)
        val rn = a.note("", 11f)
        fun paint() {
            rt.text = if (rounds <= 1) "once" else "$rounds times"
            rn.text = if (rounds <= 1) "For steps done in batches (each pancake, each tray), set how many rounds; the timer starts again by itself after a short pause."
                else if (Timers.pistachioTakesTimers(a) && !Timers.pistachioTakesRounds(a)) "Rings after each round, then starts the next. Repeating timers run in Praliné until Pistachio can repeat them too."
                else "Rings after each round, then starts the next by itself."
        }
        minus.onClick { rounds = Math.max(1, rounds - 1); paint() }
        plus.onClick { rounds = Math.min(20, rounds + 1); paint() }
        rr.addView(minus, lp(a.dp(32), a.dp(32)))
        rr.addView(rt, lp(a.dp(90), WRAP))
        rr.addView(plus, lp(a.dp(32), a.dp(32)))
        s.body.addView(rr, lp(MATCH, WRAP).m(t = a.dp(12)))
        s.body.addView(rn, lp(MATCH, WRAP).m(t = a.dp(4)))
        paint()
        if (st.timerSec > 0) s.button("No timer") { st.timerSec = 0; st.timerLabel = ""; st.rounds = 1; a.hideKeyboard(); redrawSteps(v) }
        s.button("Set", primary = true) {
            val total = (min.text.toString().toIntOrNull() ?: 0) * 60 + (sec.text.toString().toIntOrNull() ?: 0)
            st.timerSec = Math.max(0, Math.min(24 * 3600, total))
            st.timerLabel = if (st.timerSec > 0) label.text.toString().trim() else ""
            st.rounds = if (st.timerSec > 0) rounds else 1
            a.hideKeyboard(); redrawSteps(v)
        }
        s.show()
    }

    private fun addHint(v: Variant, st: Step) {
        a.pickPhoto("Hint picture") { f ->
            if (f != null) Photos.run {
                val name = Store.newPhotoName()
                val ok = try { val b = Filters.load(f, 1280); if (b != null) { Filters.save(b, Store.photoFile(a, name)); true } else false } catch (e: Throwable) { false }
                try { if (f.path.contains("/shots/")) f.delete() } catch (e: Exception) { }
                Photos.post { if (ok) { st.hint = name; redrawSteps(v) } else a.toast("Couldn't open that picture") }
            }
        }
    }

    private fun hintMenu(v: Variant, st: Step) {
        Sheet(a, "Hint picture").button("Remove") { st.hint = ""; redrawSteps(v) }.button("Replace") { addHint(v, st) }.button("View", primary = true) { Viewer.show(a, st.hint) }.show()
    }

    private fun addVideo(v: Variant, st: Step) {
        a.pickVideo { name -> if (name != null) { st.video = name; redrawSteps(v) } }
    }

    private fun videoMenu(v: Variant, st: Step) {
        Sheet(a, "Step clip").button("Remove") { st.video = ""; redrawSteps(v) }.button("Replace") { addVideo(v, st) }
            .button("Play", primary = true) { Clips.play(a, st.video) }.show()
    }
}

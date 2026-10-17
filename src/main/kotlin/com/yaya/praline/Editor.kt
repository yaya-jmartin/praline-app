package com.yaya.praline

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.ScrollView
import android.widget.TextView

/**
 * Writing a recipe: photo, lettered title, meal or dessert, class, tier, effort, time, servings, fame,
 * named variants, and three tabs: ingredients, equipment, and steps with timers, hint pictures and clips.
 * Works on a copy, so leaving without saving changes nothing.
 *
 * [special] >= 0 edits one of this phone's My Variant specials instead of the shared recipe.
 * [newVariant] non-null starts a new variant with that name (a copy of [startVariant]).
 */
class EditorScreen(a: MainActivity, original: Recipe?, startVariant: Int = 0, val special: Int = -1, newVariant: String? = null,
                   draft: Recipe? = null, draftCreated: Int = -1) : Screen(a) {
    val isNew = original == null
    val isSpecial = special >= 0 && original != null
    override val name: String get() = if (isNew) "New recipe" else if (isSpecial) "Edit special" else "Edit recipe"
    val r: Recipe = if (original == null) Recipe().also { it.variants.add(Variant("Classic")) } else Recipe.fromJson(original.toJson())
    /** The recipe as it was, to tell what changed when saving a shared one. */
    private val before: Recipe? = if (original == null) null else Recipe.fromJson(original.toJson())
    var variant = Math.max(0, Math.min(startVariant, r.variants.size - 1))
    /** Index of the variant this editor created (new variant mode), or -1. */
    private var createdVariant = -1
    var tab = 0
    /** Ingredient lines being typed, per variant index. Parsed back on save. */
    private val lines = HashMap<Int, MutableList<String>>()
    private val snapshot: String
    private var tabsRow: LinearLayout? = null
    private var tabBody: LinearLayout? = null
    private var scroll: ScrollView? = null

    init {
        if (isSpecial && original != null) {
            // the special stands in as the only variant while editing
            val sp = Share.specials(original.id)
            r.variants.clear()
            r.variants.add(sp[Math.min(special, sp.size - 1)].copy(sp[Math.min(special, sp.size - 1)].name))
            variant = 0
        }
        if (newVariant != null && !isNew) {
            r.variants.add(r.variant(variant).copy(Share.uniqueName(names(), newVariant)))
            variant = r.variants.size - 1
            createdVariant = variant
        }
        snapshot = if (newVariant != null) "" else r.toJson().toString()
        if (draft != null) {
            // brought back after Android closed the app: the unsaved draft replaces the copy (and counts as changed)
            r.variants.clear()
            val d = draft.toJson(); d.put("id", r.id)
            val back = Recipe.fromJson(d)
            r.title = back.title; r.dessert = back.dessert; r.cls = back.cls; r.tier = back.tier; r.effort = back.effort; r.timeMin = back.timeMin
            r.servings = back.servings; r.photo = back.photo; r.photoOrig = back.photoOrig; r.filter = back.filter; r.strength = back.strength
            r.fame = back.fame; r.equipment.clear(); r.equipment.addAll(back.equipment); r.equipCount.clear(); r.equipCount.putAll(back.equipCount)
            r.variants.addAll(back.variants)
            variant = Math.max(0, Math.min(startVariant, r.variants.size - 1))
            createdVariant = draftCreated
        }
    }

    override fun saveState(): org.json.JSONObject? {
        commit()
        val o = org.json.JSONObject()
        o.put("kind", "editor"); o.put("draft", r.toJson()); o.put("new", isNew); o.put("variant", variant); o.put("special", special)
        o.put("created", createdVariant); o.put("tab", tab)
        return o
    }

    private fun names(): List<String> { val l = ArrayList<String>(); for (v in r.variants) l.add(v.name); return l }

    private fun linesFor(i: Int): MutableList<String> {
        val hit = lines[i]
        if (hit != null) return hit
        val l = ArrayList<String>()
        for (ing in r.variant(i).ingredients) l.add(ing.raw())
        lines[i] = l
        return l
    }

    /** Writes typed ingredient lines back into the variants. */
    private fun commit() {
        for ((i, l) in lines) {
            if (i >= r.variants.size) continue
            val v = r.variants[i]
            val old = ArrayList<String>(); for (ing in v.ingredients) old.add(ing.raw())
            val clean = ArrayList<String>(); for (s in l) if (s.trim().isNotEmpty()) clean.add(s.trim())
            if (clean == old) continue
            v.ingredients.clear()
            for (s in clean) v.ingredients.add(Ingredient.parse(s))
        }
        lines.clear()
    }

    override fun build(): View {
        val frame = FrameLayout(a)
        val sv = ScrollView(a); sv.isVerticalScrollBarEnabled = false
        scroll = sv
        val col = a.vbox()
        col.setPadding(a.dp(20), a.dp(6), a.dp(20), a.dp(130))
        sv.addView(col)
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = a.hbox()
        val x = a.roundIcon("x", 38, C.CARD, 15); x.onClick { leave() }
        top.addView(x, lp(a.dp(38), a.dp(38)))
        val head = if (isNew) "A new recipe" else if (isSpecial) "My variant · only on this phone" else if (createdVariant >= 0) "A new variant" else "Editing"
        top.addView(a.label(head).also { it.gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        if (!isNew && !isSpecial && createdVariant < 0) {
            val del = a.roundIcon("trash", 38, C.CARD, 16, 9f); del.onClick { confirmDelete() }
            top.addView(del, lp(a.dp(38), a.dp(38)))
        } else top.addView(a.space(a.dp(38), 1))
        col.addView(top)

        if (isSpecial) specialHeader(col) else recipeHeader(col)

        // ingredients / equipment / steps tabs
        val tabs = a.hbox()
        tabsRow = tabs
        col.addView(tabs, lp(MATCH, WRAP).m(t = a.dp(22), b = a.dp(10)))
        val body = a.vbox()
        tabBody = body
        col.addView(body, lp(MATCH, WRAP))
        showTab(tab, false)

        val save = a.bigButton(if (isNew) "Add to the grimoire" else "Save", C.RASP, C.WHITE, C.INK, "check")
        save.onClick { save() }
        dock(frame, save)
        return frame
    }

    // ---- the top of the page ------------------------------------------------------
    private fun recipeHeader(col: LinearLayout) {
        // photo
        val cls = Classes.get(r.cls)
        val ph = FrameLayout(a)
        val photo = RoundPhoto(a, 20f); photo.outline = true
        photo.placeholderIcon = "cam"; photo.placeholderColor = cls.color
        photo.show(r.photo, 900)
        ph.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))
        photo.onClick { newPhoto() }
        val chipsRow = a.hbox()
        val take = a.chip(if (r.photo.isEmpty()) "Take a photo" else "New photo", false, C.MINT, "cam", 12f)
        take.onClick { newPhoto() }
        chipsRow.addView(take, lp(WRAP, WRAP).m(r = a.dp(8)))
        if (r.photoOrig.isNotEmpty()) {
            val light = a.chip("Change the light", false, C.LILAC, "spark", 12f)
            light.onClick { recast() }
            chipsRow.addView(light)
        }
        ph.addView(chipsRow, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).m(l = a.dp(10), b = a.dp(10)))
        col.addView(ph, lp(MATCH, a.dp(190)).m(t = a.dp(10)))

        // title
        val title = EditText(a)
        title.hint = "Name it…"
        title.setText(r.title)
        title.typeface = F.script(a); title.textSize = 44f; title.setTextColor(C.INK); title.setHintTextColor(C.FAINT)
        title.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        title.background = null
        title.setPadding(a.dp(4), a.dp(10), a.dp(18), a.dp(6))
        title.addTextChangedListener(watch { r.title = it })
        col.addView(title, lp(MATCH, WRAP).m(t = a.dp(4)))
        col.addView(View(a).also { it.setBackgroundColor(C.DASH) }, lp(MATCH, a.dp(2)).m(b = a.dp(4)))

        // meal / dessert
        val kind = a.hbox()
        val meal = a.chip("Meal", !r.dessert, C.TANG, "bowl")
        meal.onClick { r.dessert = false; rebuild() }
        val des = a.chip("Dessert", r.dessert, C.RASP, "potion")
        des.onClick { r.dessert = true; rebuild() }
        kind.addView(meal, lp(WRAP, WRAP).m(r = a.dp(8))); kind.addView(des)
        col.addView(section("Kind"))
        col.addView(kind)

        // class
        col.addView(section("Class"))
        val fl = FlowLayout(a, a.dp(7), a.dp(8))
        for (c in Classes.all) {
            val ch = a.chip(c.name, c.key == r.cls, c.color, c.icon, 12.5f)
            ch.onClick { r.cls = c.key; rebuild() }
            fl.addView(ch)
        }
        col.addView(fl)
        col.addView(a.note(cls.name + ": " + cls.blurb, 12f), lp(MATCH, WRAP).m(t = a.dp(6)))

        // tier
        col.addView(section("Tier"))
        val tiers = a.hbox()
        for (t in Tiers.all) {
            val s = ShieldView(a, t, Tiers.color(t))
            s.alpha = if (t == r.tier) 1f else 0.35f
            if (t == r.tier) s.scaleX = 1.12f
            if (t == r.tier) s.scaleY = 1.12f
            s.onClick { r.tier = t; rebuild() }
            tiers.addView(s, lp(a.dp(50), a.dp(50)).m(r = a.dp(10)))
        }
        col.addView(tiers)

        // effort
        col.addView(section("Effort"))
        val er = a.hbox()
        val dice = DiceView(a, r.effort, 5, C.RASP)
        val dc = a.body("DC ${Fame.dc(r.effort)} · ${Fame.effortWord(r.effort)}", 14f, C.INK, 600)
        er.addView(dice, lp(WRAP, a.dp(30)).m(r = a.dp(14)))
        er.addView(dc)
        col.addView(er)

        // time + servings
        val ts = a.hbox(); ts.gravity = Gravity.TOP
        ts.addView(stepper("Time", { fmtDuration(r.timeMin) }, { d -> r.timeMin = Math.max(5, Math.min(24 * 60, r.timeMin + d * (if (r.timeMin >= 60) 15 else 5))) }, { typeNumber("Cooking time, in minutes", r.timeMin) { n -> r.timeMin = Math.max(1, n) } }), lp(0, WRAP, 1f).m(r = a.dp(12)))
        ts.addView(stepper("Servings", { r.servings.toString() }, { d -> r.servings = Math.max(1, Math.min(99, r.servings + d)) }, { typeNumber("Servings", r.servings) { n -> r.servings = Math.max(1, Math.min(999, n)) } }), lp(0, WRAP, 1f))
        col.addView(ts, lp(MATCH, WRAP).m(t = a.dp(4)))

        // fame
        col.addView(section("Fame reward"))
        val fr = a.hbox(); fr.gravity = Gravity.TOP
        var fameNote: TextView? = null
        val fs = stepper("Fame", { if (r.fame > 0) "${r.fame}" else "auto · ${Fame.base(r)}" }, { d ->
            val now = if (r.fame > 0) r.fame else Fame.base(r)
            r.fame = Math.max(10, Math.min(999, now + d * 10))
        }, { typeNumber("Fame for this recipe (0 = from the effort)", r.fame) { n -> r.fame = Math.max(0, Math.min(999, n)) } })
        fr.addView(fs, lp(0, WRAP, 1f).m(r = a.dp(12)))
        val fn = a.note("Repeats earn less: −15% for each time it's among your last 20 quests, never under 25%. A month without it and it's worth full fame again.", 11f)
        fameNote = fn
        fr.addView(fn, lp(0, WRAP, 1.2f))
        col.addView(fr, lp(MATCH, WRAP))
        dice.onPick = { v -> r.effort = v; dc.text = "DC ${Fame.dc(v)} · ${Fame.effortWord(v)}"; if (r.fame == 0) refreshSteppers(fs) }
        if (r.fame > 0) fameNote?.let { it.alpha = 1f }

        // variants
        col.addView(section("Variants"))
        val hs = HorizontalScrollView(a); hs.isHorizontalScrollBarEnabled = false
        val vr = a.hbox()
        val shadows = intArrayOf(C.PIST, C.TANG, C.LILAC, C.MINT, C.BUTTER, C.BLUE)
        for (i in r.variants.indices) {
            val ch = a.darkChip(r.variants[i].name, i == variant, shadows[i % shadows.size])
            ch.onClick { if (i == variant) variantMenu(i) else { variant = i; rebuild() } }
            ch.onLongClick { variantMenu(i) }
            vr.addView(ch, lp(WRAP, WRAP).m(r = a.dp(8)))
        }
        if (createdVariant < 0) {
            val plus = a.chip("New variant", false, C.BUTTER, "plus", 12f)
            plus.onClick { addVariant() }
            vr.addView(plus)
        }
        hs.addView(vr)
        col.addView(hs)
        col.addView(a.note("Tap the selected variant to rename or delete it.", 11f), lp(MATCH, WRAP).m(t = a.dp(4)))
    }

    private fun refreshSteppers(v: View) {
        // the stepper's value is the TextView in its middle row
        val row = (v as? LinearLayout)?.getChildAt(1) as? LinearLayout ?: return
        val t = row.getChildAt(1) as? TextView ?: return
        t.text = if (r.fame > 0) "${r.fame}" else "auto · ${Fame.base(r)}"
    }

    private fun specialHeader(col: LinearLayout) {
        col.addView(a.script(r.title, 44f), lp(MATCH, WRAP).m(t = a.dp(4), l = -a.dp(4)))
        val name = Fields.line(a, "Name of your variant")
        name.setText(r.variant(0).name)
        name.addTextChangedListener(watch { r.variant(0).name = it.trim().ifEmpty { "My variant" } })
        col.addView(a.label("Your variant's name"), lp(MATCH, WRAP).m(t = a.dp(6)))
        col.addView(name, lp(MATCH, WRAP).m(t = a.dp(4)))
        col.addView(a.note("Only you see it. You can share it with " + Share.peerName() + " from its tab on the recipe.", 11.5f), lp(MATCH, WRAP).m(t = a.dp(6)))
    }

    private fun section(s: String): View = a.label(s).also { it.layoutParams = lp(MATCH, WRAP).m(t = a.dp(16), b = a.dp(6)) }

    private fun watch(f: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: Editable?) { f(e?.toString() ?: "") }
    }

    private fun stepper(label: String, value: () -> String, step: (Int) -> Unit, type: () -> Unit): View {
        val c = a.card(C.BUTTER, 10, 8)
        c.addView(a.label(label))
        val row = a.hbox()
        val minus = a.roundIcon("minus", 32, C.CARD, 14)
        val t = a.body(value(), 17f, C.INK, 800); t.gravity = Gravity.CENTER
        val plus = a.roundIcon("plus", 32, C.CARD, 14)
        minus.onClick { step(-1); t.text = value() }
        plus.onClick { step(1); t.text = value() }
        t.onClick { type() }
        row.addView(minus, lp(a.dp(32), a.dp(32)))
        row.addView(t, lp(0, WRAP, 1f))
        row.addView(plus, lp(a.dp(32), a.dp(32)))
        c.addView(row, lp(MATCH, WRAP).m(t = a.dp(4)))
        return c
    }

    private fun typeNumber(title: String, now: Int, done: (Int) -> Unit) {
        val s = Sheet(a, title)
        val e = Fields.line(a, now.toString(), 22f)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(now.toString()); e.setSelection(e.text.length)
        s.body.addView(e, lp(a.dp(160), WRAP))
        s.button("Cancel").button("Set", primary = true) { val n = e.text.toString().toIntOrNull(); if (n != null && n >= 0) { done(n); a.hideKeyboard(); rebuild() } }
        s.show(); a.showKeyboard(e)
    }

    // ---- tabs: switched in place, the rest of the page stays put -------------------------
    private fun showTab(t: Int, keepFocus: Boolean = true) {
        if (!keepFocus) { } else a.hideKeyboard()
        tab = t
        val tabs = tabsRow ?: return
        tabs.removeAllViews()
        val v = r.variant(variant)
        val names = arrayOf("Ingredients · ${linesFor(variant).count { it.isNotBlank() }}", "Equipment · ${r.equipment.size}", "Steps · ${v.steps.size}")
        val hs = HorizontalScrollView(a); hs.isHorizontalScrollBarEnabled = false
        val row = a.hbox()
        for (i in 0..2) {
            if (isSpecial && i == 1) continue
            val ch = a.chip(names[i], tab == i, C.INK, null, 14f)
            ch.onClick { if (tab != i) showTab(i) }
            row.addView(ch, lp(WRAP, WRAP).m(r = a.dp(8)))
        }
        hs.addView(row)
        tabs.addView(hs, lp(MATCH, WRAP))
        val body = tabBody ?: return
        body.removeAllViews()
        when (tab) {
            0 -> ingredients(body)
            1 -> equipment(body)
            else -> steps(body, v)
        }
    }

    private fun refreshTabCounts() {
        val row = ((tabsRow?.getChildAt(0) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout) ?: return
        val v = r.variant(variant)
        val counts = ArrayList<String>()
        counts.add("Ingredients · ${linesFor(variant).count { it.isNotBlank() }}")
        if (!isSpecial) counts.add("Equipment · ${r.equipment.size}")
        counts.add("Steps · ${v.steps.size}")
        for (i in 0 until Math.min(row.childCount, counts.size)) ((row.getChildAt(i) as LinearLayout).tag as TextView).text = counts[i]
    }

    // ---- ingredients ------------------------------------------------------------
    private var ingList: LinearLayout? = null

    private fun ingredients(col: LinearLayout) {
        val l = linesFor(variant)
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

    /** Scrolls the least needed so [v] sits above the keyboard and the dock. */
    private fun reveal(v: View) {
        val sv = scroll ?: return
        val r = android.graphics.Rect()
        v.getDrawingRect(r)
        sv.offsetDescendantRectToMyCoords(v, r)
        val visibleBottom = sv.scrollY + sv.height - a.dp(24)
        if (r.bottom > visibleBottom) sv.smoothScrollBy(0, r.bottom - visibleBottom)
        else if (r.top < sv.scrollY + a.dp(12)) sv.smoothScrollBy(0, r.top - sv.scrollY - a.dp(12))
    }

    private fun styleField(e: EditText, hint: String, size: Float) {
        e.hint = hint
        e.setHintTextColor(C.FAINT)
        e.setTextColor(C.INK)
        e.textSize = size
        e.typeface = F.regular(a)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        e.background = InkBox(a, C.CARD, 0, 14f, 1.8f, 0f, hint.length)
        e.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
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

    // ---- equipment ----------------------------------------------------------------
    private fun equipment(col: LinearLayout) {
        col.addView(a.note("What the recipe calls for. Tap to pick; your favourite weapon counts them too.", 11.5f), lp(MATCH, WRAP).m(b = a.dp(8)))
        val fl = FlowLayout(a, a.dp(7), a.dp(8))
        // a tap picks a tool; tapping a picked one sets how many (2 × Mixing bowl) or puts it back
        for (t in Equipment.all) if (!t.engineer) {
            val on = r.equipment.contains(t.key)
            val ch = a.chip(if (on) Equipment.label(r, t.key) else t.name, on, t.color, t.icon, 13f)
            ch.contentDescription = "equipment " + t.key
            ch.onClick { if (on) countSheet(t.key) else { r.equipment.add(t.key); refreshEquipment() } }
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
            ch.onClick { if (on) countSheet(t.key) else { r.equipment.add(t.key); refreshEquipment() } }
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
            if (w.isNotEmpty() && !r.equipment.contains(w)) { r.equipment.add(w); e.setText(""); refreshEquipment() }
        }
        add.onClick { addIt() }
        e.setOnEditorActionListener(object : TextView.OnEditorActionListener {
            override fun onEditorAction(v: TextView?, actionId: Int, ev: KeyEvent?): Boolean { addIt(); return true }
        })
        row.addView(add, lp(WRAP, WRAP).m(l = a.dp(8)))
        col.addView(row, lp(MATCH, WRAP).m(t = a.dp(12)))
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

    // ---- steps ----------------------------------------------------------------
    private var stepList: LinearLayout? = null

    private fun steps(col: LinearLayout, v: Variant) {
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
        col.addView(add, lp(WRAP, WRAP).m(t = a.dp(2)))
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
        val l = lines[variant] ?: return
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

    // ---- variants ---------------------------------------------------------------
    private fun addVariant() {
        val s = Sheet(a, "New variant")
        s.body.addView(a.note("It starts as a copy of “${r.variant(variant).name}”, with its own ingredients and steps.", 12.5f))
        val e = Fields.line(a, "Spicier, Curry, Vegan…")
        s.body.addView(e, lp(MATCH, WRAP).m(t = a.dp(10)))
        s.button("Cancel").button("Create", primary = true) {
            val name = e.text.toString().trim()
            if (name.isNotEmpty()) { commit(); r.variants.add(r.variant(variant).copy(Share.uniqueName(names(), name))); variant = r.variants.size - 1; a.hideKeyboard(); rebuild() }
        }
        s.show(); a.showKeyboard(e)
    }

    private fun variantMenu(i: Int) {
        val s = Sheet(a, "Variant")
        val e = Fields.line(a, "Name"); e.setText(r.variants[i].name)
        s.body.addView(e, lp(MATCH, WRAP))
        if (r.variants.size > 1 && i != createdVariant) s.button("Delete") {
            commit(); r.variants.removeAt(i); if (variant > i) variant--; if (createdVariant > i) createdVariant--
            variant = Math.max(0, Math.min(variant, r.variants.size - 1)); rebuild()
        }
        s.button("Rename", primary = true) { val n = e.text.toString().trim(); if (n.isNotEmpty()) { r.variants[i].name = n; a.hideKeyboard(); rebuild() } }
        s.show()
    }

    // ---- photo ------------------------------------------------------------------
    private fun newPhoto() {
        a.pickPhoto("Recipe photo") { f ->
            if (f != null) a.push(PhotoScreen(a, f, r.filter.ifEmpty { "torch" }, r.strength) { orig, filtered, key, str ->
                r.photoOrig = orig; r.photo = filtered; r.filter = key; r.strength = str
                rebuild()
            })
        }
    }

    private fun recast() {
        val f = Store.photoFile(a, r.photoOrig)
        a.push(PhotoScreen(a, f, r.filter, r.strength) { orig, filtered, key, str ->
            r.photoOrig = orig; r.photo = filtered; r.filter = key; r.strength = str
            rebuild()
        })
    }

    // ---- save / leave -----------------------------------------------------------
    private fun cleanSteps() { for (v in r.variants) v.steps.removeAll { it.text.trim().isEmpty() && it.timerSec == 0 && it.hint.isEmpty() && it.video.isEmpty() } }

    private fun save() {
        commit()
        r.title = r.title.trim()
        if (r.title.isEmpty()) { a.toast("Give it a name first"); return }
        cleanSteps()
        a.hideKeyboard()
        if (isSpecial) { saveSpecial(); return }
        val b = before
        if (b != null && Share.isShared(b)) { chooseHowToSave(b); return }
        Store.upsert(a, r)
        if (isNew) a.replaceTop(RecipeScreen(a, r)) else a.pop()
        a.toast(if (isNew) "Added to the grimoire" else "Saved")
    }

    private fun saveSpecial() {
        val live = Store.recipe(r.id) ?: run { a.pop(); return }
        val l = Share.specials(live.id)
        if (special < l.size) l[special] = r.variant(0)
        Store.save(a)
        a.pop()
        a.toast("Saved, just for you")
    }

    /** The edited variant compared with how it was. */
    private fun variantChanged(b: Recipe): Boolean {
        val v = r.variant(variant)
        if (createdVariant >= 0) return true
        var old: Variant? = null
        for (x in b.variants) if (x.name == v.name) old = x
        if (old == null) return true
        return old.toJson().toString() != v.toJson().toString()
    }

    /**
     * Someone else has this recipe too, so the chef picks where the change goes:
     * just for me (a My Variant), a new variant for everyone, or the main recipe for everyone.
     */
    private fun chooseHowToSave(b: Recipe) {
        val peer = Share.peerName()
        val vName = r.variant(variant).name
        val s = Sheet(a, "Save it for…")
        s.body.addView(a.note("${b.title} is shared with $peer. Choose where this change goes; $peer can accept or refuse anything sent to them.", 12.5f))
        val changedVariant = variantChanged(b)
        fun option(icon: String, color: Int, title: String, sub: String, f: () -> Unit) {
            val c = a.card(color, 12, 10)
            val row = a.hbox()
            row.addView(a.icon(icon, 34, color), lp(a.dp(34), a.dp(34)).m(r = a.dp(12)))
            val col = a.vbox()
            col.addView(a.body(title, 15.5f, C.INK, 800))
            col.addView(a.note(sub, 11.5f))
            row.addView(col, lp(0, WRAP, 1f))
            c.addView(row)
            c.contentDescription = title
            c.onClick { s.dismiss(); f() }
            s.body.addView(c, lp(MATCH, WRAP).m(t = a.dp(10)))
        }
        if (changedVariant) {
            option("heart", C.RASP, "Just for me", "Saved as your own variant tab, only on this phone. ${b.title} stays as it is.") { saveForMe(b) }
            option("plus", C.PIST, "A new variant for everyone", "Sent to $peer as a new variant next to “${if (createdVariant >= 0) r.variant(0).name else vName}”.") { saveAsNewVariant(b) }
            if (createdVariant < 0) option("scroll", C.BUTTER, "Update the recipe for everyone", "The main recipe changes here, and $peer is asked to take the change.") { saveMainForEveryone() }
        } else {
            option("heart", C.RASP, "Just on this phone", "Your copy changes; $peer's stays as it is.") { Store.upsert(a, r); a.pop(); a.toast("Saved on this phone") }
            option("scroll", C.BUTTER, "Update it for everyone", "The recipe changes here, and $peer is asked to take the change.") { saveMainForEveryone() }
        }
        s.button("Keep editing")
        s.show()
    }

    /** The edited variant becomes a My Variant special; the shared recipe keeps its old variant. Other changes stay on this phone. */
    private fun saveForMe(b: Recipe) {
        val v = r.variant(variant)
        val mineV = v.copy(if (createdVariant >= 0) v.name else "My " + v.name)
        val kept = Recipe.fromJson(r.toJson())
        if (createdVariant >= 0) kept.variants.removeAt(createdVariant)
        else { var old: Variant? = null; for (x in b.variants) if (x.name == v.name) old = x; if (old != null) kept.variants[variant] = old.copy(old.name) }
        Store.upsert(a, kept)
        val k = Share.addSpecial(a, kept, mineV)
        a.pop()
        (a.top() as? RecipeScreen)?.showSpecial(k)
        a.toast("Saved as your variant, just for you")
    }

    private fun saveAsNewVariant(b: Recipe) {
        val v = r.variant(variant)
        val kept = Recipe.fromJson(r.toJson())
        val nv: Variant
        if (createdVariant >= 0) nv = v
        else {
            nv = v.copy(Share.uniqueName(names(), v.name + " · " + Store.me.name))
            var old: Variant? = null; for (x in b.variants) if (x.name == v.name) old = x
            if (old != null) kept.variants[variant] = old.copy(old.name)
            kept.variants.add(nv.copy(nv.name))
        }
        Store.upsert(a, kept)
        Share.proposeVariant(a, kept, nv)
        a.pop()
        (a.top() as? RecipeScreen)?.showVariant(nv.name)
        a.toast("Added, and sent to ${Share.peerName()}")
        Sync.nudge(a)
    }

    private fun saveMainForEveryone() {
        Store.upsert(a, r)
        Share.proposeMain(a, r)
        a.pop()
        a.toast("Saved, and sent to ${Share.peerName()} to accept")
        Sync.nudge(a)
    }

    private fun leave() {
        commit()
        a.hideKeyboard()
        if (r.toJson().toString() == snapshot) { a.pop(); return }
        Sheet(a, "Leave without saving?").button("Keep editing").button("Discard", primary = true) { a.pop() }.show()
    }

    private fun confirmDelete() {
        val live0 = Store.recipe(r.id)
        val running = Timers.liveForRecipe(a, r.id).size
        val s = Sheet(a, "Delete “${r.title}”?")
        s.body.addView(a.note("The recipe, its variants and photos are gone for good, on both phones.", 13f))
        if (running > 0) s.body.addView(a.note(if (running == 1) "Its timer is still running and will stop." else "Its $running timers are still running and will stop.", 13f, C.RASP), lp(MATCH, WRAP).m(t = a.dp(6)))
        s.button("Keep it").button(if (running > 0) "Delete and stop" else "Delete", primary = true) {
            for (id in Timers.liveForRecipe(a, r.id)) Timers.reset(a, id)
            if (live0 != null) { Store.mine.remove(live0.id); Store.delete(a, live0) }
            a.hideKeyboard()
            a.pop()
        }
        s.show()
    }

    override fun onBack(): Boolean { leave(); return true }
}

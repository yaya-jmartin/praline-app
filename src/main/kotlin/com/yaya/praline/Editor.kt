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
 * Writing a recipe: photo, lettered title, meal, dessert or beverage, class, tier, effort, time, servings, fame,
 * named variants, and three tabs: ingredients, equipment, and steps with timers, hint pictures and clips.
 * Works on a copy, so leaving without saving changes nothing.
 *
 * [special] >= 0 edits one of this phone's My Variant specials instead of the shared recipe.
 * [newVariant] non-null starts a new variant with that name (a copy of the main variant).
 */
class EditorScreen(a: MainActivity, original: Recipe?, startVariant: Int = 0, val special: Int = -1, newVariant: String? = null,
                   draft: Recipe? = null, draftCreated: Int = -1) : Screen(a) {
    val isNew = original == null
    val isSpecial = special >= 0 && original != null
    override val name: String get() = if (isNew) "New recipe" else if (isSpecial) "Edit special" else "Edit recipe"
    val r: Recipe = if (original == null) Recipe().also { it.variants.add(Variant("Classic")) } else Recipe.fromJson(original.toJson())
    /** The recipe as it was, to tell what changed when saving a shared one. */
    internal val before: Recipe? = if (original == null) null else Recipe.fromJson(original.toJson())
    var variant = Math.max(0, Math.min(startVariant, r.variants.size - 1))
    /** Index of the variant this editor created (new variant mode), or -1. */
    internal var createdVariant = -1
    var tab = 0
    internal val ingTab = IngredientTab(this)
    internal val equipTab = EquipmentTab(this)
    internal val stepTab = StepTab(this)
    private val saving = SaveChoices(this)
    /** Ingredient lines being typed, per variant index. Parsed back on save. */
    internal val lines = HashMap<Int, MutableList<String>>()
    private val snapshot: String
    private var tabsRow: LinearLayout? = null
    private var tabBody: LinearLayout? = null
    internal var scroll: ScrollView? = null

    init {
        if (isSpecial && original != null) {
            // the special stands in as the only variant while editing
            val sp = Share.specials(original.id)
            r.variants.clear()
            r.variants.add(sp[Math.min(special, sp.size - 1)].copy(sp[Math.min(special, sp.size - 1)].name))
            variant = 0
        }
        if (newVariant != null && !isNew) {
            r.variants.add(r.variant(0).copy(Share.uniqueName(names(), newVariant)))
            variant = r.variants.size - 1
            createdVariant = variant
        }
        snapshot = if (newVariant != null) "" else r.toJson().toString()
        if (draft != null) {
            // brought back after Android closed the app: the unsaved draft replaces the copy (and counts as changed)
            r.variants.clear()
            val d = draft.toJson(); d.put("id", r.id)
            val back = Recipe.fromJson(d)
            r.title = back.title; r.kind = back.kind; r.cls = back.cls; r.tier = back.tier; r.effort = back.effort; r.timeMin = back.timeMin
            r.servings = back.servings; r.photo = back.photo; r.photoOrig = back.photoOrig; r.filter = back.filter; r.strength = back.strength
            r.focusX = back.focusX; r.focusY = back.focusY; r.zoom = back.zoom
            r.fame = back.fame; r.equipment.clear(); r.equipment.addAll(back.equipment); r.equipCount.clear(); r.equipCount.putAll(back.equipCount)
            r.noEquipment = back.noEquipment
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

    internal fun names(): List<String> { val l = ArrayList<String>(); for (v in r.variants) l.add(v.name); return l }

    internal fun linesFor(i: Int): MutableList<String> {
        val hit = lines[i]
        if (hit != null) return hit
        val l = ArrayList<String>()
        for (ing in r.variant(i).ingredients) l.add(ing.raw())
        lines[i] = l
        return l
    }

    /** Writes typed ingredient lines back into the variants. */
    internal fun commit() {
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
        photo.showRecipe(r, 900)
        ph.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))
        photo.onClick { newPhoto() }
        val chipsRow = a.hbox()
        val take = a.chip(if (r.photo.isEmpty()) "Take a photo" else "New photo", false, C.MINT, "cam", 12f)
        take.onClick { newPhoto() }
        chipsRow.addView(take, lp(WRAP, WRAP).m(r = a.dp(8)))
        if (r.photoOrig.isNotEmpty()) {
            val light = a.chip("Light and framing", false, C.LILAC, "spark", 12f)
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

        // meal / dessert / beverage
        val kind = a.hbox()
        for (k in Recipe.KINDS) {
            val ch = a.chip(Kinds.name(k), r.kind == k, Kinds.color(k), Kinds.icon(k))
            ch.onClick { r.kind = k; rebuild() }
            kind.addView(ch, lp(WRAP, WRAP).m(r = a.dp(8)))
        }
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

    internal fun section(s: String): View = a.label(s).also { it.layoutParams = lp(MATCH, WRAP).m(t = a.dp(16), b = a.dp(6)) }

    internal fun watch(f: (String) -> Unit): TextWatcher = object : TextWatcher {
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
    internal fun showTab(t: Int, keepFocus: Boolean = true) {
        if (!keepFocus) { } else a.hideKeyboard()
        tab = t
        val tabs = tabsRow ?: return
        tabs.removeAllViews()
        val v = r.variant(variant)
        val names = arrayOf("Ingredients · ${linesFor(variant).count { it.isNotBlank() }}", equipCount(), "Steps · ${v.steps.size}")
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
            0 -> ingTab.build(body)
            1 -> equipTab.build(body)
            else -> stepTab.build(body, v)
        }
    }

    private fun equipCount() = if (r.noEquipment && r.equipment.isEmpty()) "Equipment · none" else "Equipment · ${r.equipment.size}"

    internal fun refreshTabCounts() {
        val row = ((tabsRow?.getChildAt(0) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout) ?: return
        val v = r.variant(variant)
        val counts = ArrayList<String>()
        counts.add("Ingredients · ${linesFor(variant).count { it.isNotBlank() }}")
        if (!isSpecial) counts.add(equipCount())
        counts.add("Steps · ${v.steps.size}")
        for (i in 0 until Math.min(row.childCount, counts.size)) ((row.getChildAt(i) as LinearLayout).tag as TextView).text = counts[i]
    }

    /** Scrolls the least needed so [v] sits above the keyboard and the dock. */
    internal fun reveal(v: View) {
        val sv = scroll ?: return
        val r = android.graphics.Rect()
        v.getDrawingRect(r)
        sv.offsetDescendantRectToMyCoords(v, r)
        val visibleBottom = sv.scrollY + sv.height - a.dp(24)
        if (r.bottom > visibleBottom) sv.smoothScrollBy(0, r.bottom - visibleBottom)
        else if (r.top < sv.scrollY + a.dp(12)) sv.smoothScrollBy(0, r.top - sv.scrollY - a.dp(12))
    }

    internal fun styleField(e: EditText, hint: String, size: Float) {
        e.hint = hint
        e.setHintTextColor(C.FAINT)
        e.setTextColor(C.INK)
        e.textSize = size
        e.typeface = F.regular(a)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        e.background = InkBox(a, C.CARD, 0, 14f, 1.8f, 0f, hint.length)
        e.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
    }

    // ---- variants ---------------------------------------------------------------
    private fun addVariant() {
        val s = Sheet(a, "New variant")
        s.body.addView(a.note("It starts as a copy of “${r.variant(0).name}”, with its own ingredients and steps.", 12.5f))
        val e = Fields.line(a, "Spicier, Curry, Vegan…")
        s.body.addView(e, lp(MATCH, WRAP).m(t = a.dp(10)))
        s.button("Cancel").button("Create", primary = true) {
            val name = e.text.toString().trim()
            if (name.isNotEmpty()) { commit(); r.variants.add(r.variant(0).copy(Share.uniqueName(names(), name))); variant = r.variants.size - 1; a.hideKeyboard(); rebuild() }
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
            if (f != null) a.push(PhotoScreen(a, f, r.filter.ifEmpty { "torch" }, r.strength, { orig, filtered, key, str, fr ->
                r.photoOrig = orig; r.photo = filtered; r.filter = key; r.strength = str; fr.applyTo(r)
                rebuild()
            }))
        }
    }

    private fun recast() {
        val f = Store.photoFile(a, r.photoOrig)
        a.push(PhotoScreen(a, f, r.filter, r.strength, { orig, filtered, key, str, fr ->
            r.photoOrig = orig; r.photo = filtered; r.filter = key; r.strength = str; fr.applyTo(r)
            rebuild()
        }, PhotoScreen.Frame.of(r)))
    }

    // ---- save / leave -----------------------------------------------------------
    private fun cleanSteps() { for (v in r.variants) v.steps.removeAll { it.text.trim().isEmpty() && it.timerSec == 0 && it.hint.isEmpty() && it.video.isEmpty() } }

    private fun save() {
        commit()
        r.title = r.title.trim()
        if (r.title.isEmpty()) { a.toast("Give it a name first"); return }
        cleanSteps()
        a.hideKeyboard()
        if (!complete()) return
        if (isSpecial) { saving.special(); return }
        val b = before
        if (b != null && Share.isShared(b)) { saving.choose(b); return }
        Store.upsert(a, r)
        if (isNew) a.replaceTop(RecipeScreen(a, r)) else a.pop()
        a.toast(if (isNew) "Added to the grimoire" else "Saved")
    }


    /**
     * A recipe needs at least one ingredient and one step in every variant, and its equipment (or "No equipment
     * needed" ticked). Otherwise says what's missing and opens that tab (1.4).
     */
    internal fun complete(): Boolean {
        val miss = Validate.missing(r, !isSpecial)
        if (miss == null) return true
        val s = Sheet(a, "Not ready yet")
        s.body.addView(a.note(miss.text, 13f))
        s.button("Keep editing", primary = true) {
            if (miss.variant >= 0 && miss.variant != variant) { variant = miss.variant; rebuild() }
            showTab(miss.tab)
        }
        s.show()
        return false
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

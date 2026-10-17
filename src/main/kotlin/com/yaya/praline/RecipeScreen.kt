package com.yaya.praline

import android.graphics.Bitmap
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One recipe: hero photo, stat block, variants, servings wheel, ingredients and steps. */
class RecipeScreen(a: MainActivity, var r: Recipe) : Screen(a) {
    override val fullBleed = true
    override val name: String get() = "Recipe"
    var variant = 0
    var servings = r.servings
    private val checked = HashSet<Int>()
    private var topBar: View? = null
    private var heroView: View? = null
    private var shown = false
    private val chips = ArrayList<Pair<String, LinearLayout>>()

    val scale: Double get() = servings.toDouble() / Math.max(1, r.servings)

    override fun build(): View {
        val frame = FrameLayout(a)
        val sv = ScrollView(a); sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        sv.addView(col)
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        chips.clear()
        val cls = Classes.get(r.cls)

        // hero
        val hero = FrameLayout(a)
        val photo = RoundPhoto(a, 0f)
        photo.placeholderIcon = cls.icon; photo.placeholderColor = cls.color
        photo.show(r.photo, 1280)
        hero.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))
        if (r.photo.isEmpty()) {
            val add = a.chip("Add a photo", false, C.MINT, "cam")
            add.onClick { changePhoto() }
            hero.addView(add, flp(WRAP, WRAP, Gravity.CENTER).m(t = a.dp(80)))
        } else photo.onClick { Viewer.show(a, r.photo) }
        hero.addView(TornEdge(a), flp(MATCH, a.dp(26), Gravity.BOTTOM).m(b = -a.dp(2)))
        val f = Filters.get(r.filter)
        if (f != null && r.photo.isNotEmpty()) {
            val pill = a.hbox()
            val bg = InkBox(a, 0xFF1A1530.toInt(), 0, 999f, 1.6f, 0f, 3); pill.background = bg
            pill.setPadding(a.dp(10), a.dp(4), a.dp(12), a.dp(5))
            pill.addView(a.icon("spark", 12, 0, 0xFFFFD79A.toInt(), 8f), lp(a.dp(12), a.dp(12)).m(r = a.dp(5)))
            pill.addView(a.body(f.name, 11f, 0xFFFFD79A.toInt(), 600))
            pill.onClick { changePhoto() }
            hero.addView(pill, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).m(l = a.dp(18), b = a.dp(36)))
        }
        val bar = a.hbox()
        val back = a.roundIcon("back"); back.onClick { a.pop() }
        bar.addView(back)
        bar.addView(a.space(), lp(0, 1, 1f))
        val send = a.roundIcon("scroll", 42, C.CARD, 18, 10f)
        send.contentDescription = "send recipe"
        send.onClick { sendRecipe() }
        bar.addView(send, lp(a.dp(42), a.dp(42)).m(r = a.dp(10)))
        val edit = a.roundIcon("pencil", 42, C.CARD, 18, 10f)
        edit.onClick { if (variant >= Share.SPECIAL) a.push(EditorScreen(a, r, 0, variant - Share.SPECIAL)) else a.push(EditorScreen(a, r, variant)) }
        bar.addView(edit, lp(a.dp(42), a.dp(42)).m(r = a.dp(10)))
        val sh = ShieldView(a, r.tier, Tiers.color(r.tier))
        bar.addView(sh, lp(a.dp(56), a.dp(56)))
        hero.addView(bar, flp(MATCH, WRAP, Gravity.TOP).m(a.dp(16), a.insetTop + a.dp(8), a.dp(16), 0))
        topBar = bar
        col.addView(hero, lp(MATCH, a.dp(300) + a.insetTop))
        heroView = hero

        val body = a.vbox()
        body.setPadding(a.dp(22), 0, a.dp(22), a.dp(120))
        col.addView(body)
        body.addView(a.script(r.title, 54f), lp(MATCH, WRAP).m(t = a.dp(2), l = -a.dp(4)))

        // stat block
        val stat = a.vbox()
        stat.addView(rule())
        stat.addView(a.body((if (r.dessert) "Dessert" else "Meal") + " · " + cls.name + " · " + cls.blurb, 13f, C.STAT_TEXT, -1), lp(MATCH, WRAP).m(t = a.dp(7)))
        val grid = a.hbox(); grid.gravity = Gravity.TOP
        grid.addView(statCell("hour", C.BUTTER, "Time", fmtDuration(r.timeMin)), lp(0, WRAP, 1f))
        grid.addView(statCell("d20", C.RASP, "Effort", "DC ${Fame.dc(r.effort)}", " · ${r.effort}/5"), lp(0, WRAP, 1f))
        grid.addView(statCell("potion", C.MINT, "Last cooked", if (r.lastCooked > 0) Days.short(r.lastCooked) else "never"), lp(0, WRAP, 1f))
        stat.addView(grid, lp(MATCH, WRAP).m(t = a.dp(8), b = a.dp(8)))
        stat.addView(rule())
        body.addView(stat, lp(MATCH, WRAP).m(t = a.dp(4)))

        // variants
        val hs = HorizontalScrollView(a); hs.isHorizontalScrollBarEnabled = false; hs.clipToPadding = false
        val vrow = a.hbox()
        val shadows = intArrayOf(C.PIST, C.TANG, C.LILAC, C.MINT, C.BUTTER, C.BLUE)
        for (i in r.variants.indices) {
            val ch = a.darkChip(r.variants[i].name, i == variant, shadows[i % shadows.size])
            ch.onClick { variant = i; checked.clear(); rebuild() }
            vrow.addView(ch, lp(WRAP, WRAP).m(r = a.dp(8)))
        }
        val sp = Store.mine[r.id]
        if (sp != null) for (k in sp.indices) {
            val idx = Share.SPECIAL + k
            val ch = a.chip(sp[k].name, idx == variant, C.RASP, "heart")
            if (idx == variant) (ch.tag as TextView).setTextColor(C.WHITE)
            ch.contentDescription = "special " + sp[k].name
            ch.onClick { if (idx == variant) specialMenu(k) else { variant = idx; checked.clear(); rebuild() } }
            ch.onLongClick { specialMenu(k) }
            vrow.addView(ch, lp(WRAP, WRAP).m(r = a.dp(8)))
        }
        val plus = a.chip("", false, C.BUTTER, "plus", 0f)
        plus.onClick { addVariant() }
        vrow.addView(plus)
        hs.addView(vrow)
        body.addView(hs, lp(MATCH, WRAP).m(t = a.dp(14)))
        if (variant >= Share.SPECIAL) body.addView(a.note("Your own variant, only on this phone. Tap its tab again to share it with ${Share.peerName()}, rename or delete it.", 11f, C.RASP), lp(MATCH, WRAP).m(t = a.dp(6)))

        // a change the other chef sent for this recipe
        for (p in Store.inbox) if (p.recipeId == r.id) body.addView(Inbox.card(a, p) { rebuild() }, lp(MATCH, WRAP).m(t = a.dp(12)))

        // equipment
        if (r.equipment.isNotEmpty()) {
            body.addView(a.label("Equipment"), lp(MATCH, WRAP).m(t = a.dp(14), b = a.dp(6)))
            val fl = FlowLayout(a, a.dp(7), a.dp(7))
            for (k in r.equipment) {
                if (Equipment.engineer(k)) { fl.addView(a.gearChip(Equipment.label(r, k), true, Equipment.icon(k), 12.5f).also { it.isClickable = false }); continue }
                val c = a.hbox()
                c.background = InkBox(a, C.CARD, Equipment.color(k), 999f, 1.8f, 2.5f, k.length)
                c.setPadding(a.dp(8), a.dp(4), a.dp(12), a.dp(6))
                c.addView(a.icon(Equipment.icon(k), 16, Equipment.color(k), C.INK, 7f), lp(a.dp(16), a.dp(16)).m(r = a.dp(5)))
                c.addView(a.body(Equipment.label(r, k), 12.5f, C.INK, 600))
                fl.addView(c)
            }
            body.addView(fl)
        }

        // ingredients + servings wheel
        val v = Share.variantAt(r, variant)
        val ih = a.hbox()
        ih.addView(a.script("Ingredients", 36f), lp(0, WRAP, 1f))
        val lbl = a.vbox(); lbl.gravity = Gravity.END
        lbl.addView(a.label("servings"))
        val was = a.note(if (servings == r.servings) "drag or tap" else "was ${r.servings} · ×" + Qty.format(scale, ""), 10.5f, C.RASP)
        lbl.addView(was)
        ih.addView(lbl, lp(WRAP, WRAP).m(r = a.dp(8)))
        val wheel = ServingsWheel(a, servings, { n -> servings = n; refreshAmounts(); refreshSteps(); was.text = if (n == r.servings) "drag or tap" else "was ${r.servings} · ×" + Qty.format(scale, "") }, { typeServings() })
        ih.addView(wheel, lp(a.dp(50), a.dp(66)))
        body.addView(ih, lp(MATCH, WRAP).m(t = a.dp(12)))
        amounts.clear()
        if (v.ingredients.isEmpty()) body.addView(a.note("No ingredients written yet.", 13f))
        for (i in v.ingredients.indices) body.addView(ingredientRow(v.ingredients[i], i))

        // steps
        stepTexts.clear()
        if (v.steps.isNotEmpty()) {
            body.addView(a.script("Steps", 36f), lp(MATCH, WRAP).m(t = a.dp(14)))
            for (i in v.steps.indices) body.addView(stepRow(v, v.steps[i], i))
        }

        // start the quest, pinned to the bottom on paper
        val fame = Fame.reward(r, Store.me, System.currentTimeMillis())
        val btn = a.bigButton("Start the quest", C.RASP, C.WHITE, C.INK, "play", "· +$fame fame")
        btn.contentDescription = "Start the quest"
        btn.onClick { startQuest(0) }
        dock(frame, btn)
        return frame
    }

    /** Opens the quest at [step]. If this quest's timers are still going from before, asks whether to carry on or start over. */
    fun startQuest(step: Int) {
        val v = Share.variantAt(r, variant)
        val live = Timers.liveInQuest(a, r.id, variant, v.steps.size)
        if (live == 0) {
            Timers.resetQuest(a, r.id, variant, v.steps.size)
            a.push(QuestScreen(a, r, variant, scale, step)); return
        }
        val s = Sheet(a, "Quest under way")
        s.body.addView(a.note(if (live == 1) "A timer from this quest is still going." else "$live timers from this quest are still going.", 13f))
        s.button("Start over") {
            Timers.resetQuest(a, r.id, variant, v.steps.size)
            a.push(QuestScreen(a, r, variant, scale, step))
        }
        s.button("Carry on", primary = true) { a.push(QuestScreen(a, r, variant, scale, step)) }
        s.body.addView(a.note("Start over stops " + (if (live == 1) "it" else "them") + ".", 11.5f, C.RASP), lp(MATCH, WRAP).m(t = a.dp(4)))
        s.show()
    }

    fun showSpecial(k: Int) { variant = Share.SPECIAL + k; checked.clear() }
    fun showVariant(name: String) { for (i in r.variants.indices) if (r.variants[i].name == name) variant = i; checked.clear() }

    private fun specialMenu(k: Int) {
        val l = Share.specials(r.id)
        if (k >= l.size) return
        val sv = l[k]
        val s = Sheet(a, sv.name)
        val e = Fields.line(a, "Name"); e.setText(sv.name)
        s.body.addView(e, lp(MATCH, WRAP))
        if (Store.peer != null && Store.pairKey.isNotEmpty()) {
            val sh = a.chip("Share with ${Share.peerName()}", false, C.MINT, "heart", 13f)
            sh.onClick { s.dismiss(); Share.shareSpecial(a, r, sv); a.toast("Sent to ${Share.peerName()}"); Sync.nudge(a) }
            s.body.addView(sh, lp(WRAP, WRAP).m(t = a.dp(10)))
        }
        s.button("Delete") {
            Sheet(a, "Delete “${sv.name}”?").button("Keep it").button("Delete", primary = true) {
                l.removeAt(k); variant = 0; Store.save(a); Store.cleanPhotos(a); rebuild()
            }.show()
        }
        s.button("Edit") { a.push(EditorScreen(a, r, 0, k)) }
        s.button("Rename", primary = true) { val n = e.text.toString().trim(); if (n.isNotEmpty()) { sv.name = n; Store.save(a); a.hideKeyboard(); rebuild() } }
        s.show()
    }

    private fun rule(): View { val v = View(a); v.setBackgroundColor(C.STAT_RED); v.layoutParams = lp(MATCH, a.dp(3)); return v }

    private fun statCell(icon: String, color: Int, label: String, value: String, small: String = ""): View {
        val c = a.vbox(); c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(a.icon(icon, 26, color), lp(a.dp(26), a.dp(26)))
        c.addView(a.label(label), lp(WRAP, WRAP).m(t = a.dp(2)))
        val t = a.body(value, 16f, C.INK, 800)
        if (small.isNotEmpty()) t.append(android.text.SpannableString(small).also { it.setSpan(android.text.style.RelativeSizeSpan(0.75f), 0, small.length, 0); it.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), 0, small.length, 0) })
        c.addView(t)
        return c
    }

    private val amounts = ArrayList<Pair<Ingredient, TextView>>()

    private fun ingredientRow(ing: Ingredient, i: Int): View {
        val row = a.hbox()
        row.setPadding(0, a.dp(5), 0, a.dp(5))
        val box = FrameLayout(a)
        box.addView(a.icon("box", 20, 0, C.INK, 7f), flp(a.dp(20), a.dp(20), Gravity.CENTER))
        val tick = a.icon("check", 22, 0, C.RASP, 11f)
        box.addView(tick, flp(a.dp(22), a.dp(22)).m(l = a.dp(3), t = -a.dp(5)))
        row.addView(box, lp(a.dp(24), a.dp(24)).m(r = a.dp(10)))
        val q = a.body(ing.amount(scale), 15f, C.INK, 800)
        q.minWidth = a.dp(56)
        if (ing.qty == null) {
            // no amount: a little sprig "to taste" instead of a blank
            val t = a.hbox(); t.minimumWidth = a.dp(56)
            t.addView(a.icon("sprig", 16, C.PIST, C.INK, 7f), lp(a.dp(16), a.dp(16)).m(r = a.dp(3)))
            t.addView(a.note("to taste", 10.5f))
            t.contentDescription = "to taste"
            row.addView(t, lp(WRAP, WRAP).m(r = a.dp(8)))
        } else row.addView(q, lp(WRAP, WRAP).m(r = a.dp(8)))
        val n = a.body(ing.name, 15f)
        row.addView(n, lp(0, WRAP, 1f))
        amounts.add(Pair(ing, q))
        fun paint() {
            val on = checked.contains(i)
            tick.visibility = if (on) View.VISIBLE else View.INVISIBLE
            val flags = if (on) android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else 0
            q.paintFlags = (q.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()) or flags
            n.paintFlags = (n.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()) or flags
            q.setTextColor(if (on) C.INK2 else C.INK); n.setTextColor(if (on) C.INK2 else C.INK)
        }
        paint()
        row.onClick { if (checked.contains(i)) checked.remove(i) else checked.add(i); paint() }
        return row
    }

    private fun refreshAmounts() { for ((ing, t) in amounts) t.text = ing.amount(scale) }

    private val stepTexts = ArrayList<Pair<Step, TextView>>()
    private var stepIngs: List<Ingredient> = ArrayList()

    private fun refreshSteps() { for ((st, t) in stepTexts) t.text = StepText.styled(st.text, stepIngs, scale) }

    private fun stepRow(v: Variant, s: Step, i: Int): View {
        val row = a.hbox(); row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, a.dp(6), 0, a.dp(6))
        // the number stays centred on one-line steps
        row.addView(a.script((i + 1).toString(), 30f).also { it.gravity = Gravity.CENTER }, lp(a.dp(30), WRAP).m(r = a.dp(8)))
        val col = a.vbox()
        stepIngs = v.ingredients
        val t = a.body(StepText.styled(s.text, v.ingredients, scale), 14.5f).also { it.setLineSpacing(0f, 1.15f) }
        stepTexts.add(Pair(s, t))
        col.addView(t)
        if (s.timerSec > 0) {
            val id = Timers.idFor(r.id, variant, i)
            val chip = a.timerChip(fmtClock(s.timerSec.toLong()) + (if (s.rounds > 1) " ×${s.rounds}" else "") + (if (s.timerLabel.isNotEmpty()) " · " + s.timerLabel else ""))
            // timers belong to the quest: the chip opens it at this step
            chip.onClick { startQuest(i) }
            chips.add(Pair(id, chip))
            col.addView(chip, lp(WRAP, WRAP).m(t = a.dp(7)))
        }
        row.addView(col, lp(0, WRAP, 1f))
        if (s.video.isNotEmpty()) {
            val p = Clips.thumb(a, s.video, 62)
            p.onClick { Clips.play(a, s.video) }
            row.addView(p, lp(a.dp(62), a.dp(62)).m(l = a.dp(10)))
        }
        if (s.hint.isNotEmpty()) {
            val p = RoundPhoto(a, 12f); p.outline = true; p.show(s.hint, 240)
            p.rotation = 2.5f
            p.onClick { Viewer.show(a, s.hint) }
            row.addView(p, lp(a.dp(62), a.dp(62)).m(l = a.dp(10)))
        }
        return row
    }

    fun timerLabel(s: Step): String = if (s.timerLabel.isNotEmpty()) s.timerLabel + " · " + r.title else r.title

    override fun tick() {
        for ((id, chip) in chips) {
            val st = Timers.state(a, id)
            val t = chip.tag as TextView
            if (st == Timers.IDLE) continue
            val rem = Timers.remainingMs(a, id) / 1000
            t.text = when (st) { Timers.RUNNING -> fmtClock(rem) + " · in the quest"; Timers.PAUSED -> fmtClock(rem) + " · paused"; else -> "Time's up!" }
        }
    }

    private fun typeServings() {
        val s = Sheet(a, "How many servings?")
        val e = EditText(a)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(servings.toString()); e.setSelection(e.text.length)
        e.typeface = F.black(a); e.textSize = 28f; e.setTextColor(C.INK); e.gravity = Gravity.CENTER
        e.background = InkBox(a, C.CARD, C.BUTTER, 16f, 2f, 3f, 2)
        e.setPadding(a.dp(12), a.dp(8), a.dp(15), a.dp(11))
        s.body.addView(e, lp(a.dp(140), WRAP).m(t = a.dp(6)))
        s.body.addView(a.note("The recipe is written for ${r.servings}. Every quantity scales with it.", 12.5f), lp(MATCH, WRAP).m(t = a.dp(8)))
        s.button("Cancel").button("Set", primary = true) {
            val n = e.text.toString().toIntOrNull()
            if (n != null && n in 1..999) { servings = n; rebuild() }
        }
        s.show()
        a.showKeyboard(e)
    }

    private fun addVariant() {
        val s = Sheet(a, "New variant")
        val from = if (variant >= Share.SPECIAL) 0 else variant
        s.body.addView(a.note("It starts as a copy of “${r.variant(from).name}”, with its own ingredients and steps.", 12.5f))
        val e = Fields.line(a, "Spicier, Curry, Vegan…")
        s.body.addView(e, lp(MATCH, WRAP).m(t = a.dp(10)))
        s.button("Cancel").button("Create", primary = true) {
            val name = e.text.toString().trim()
            if (name.isNotEmpty()) {
                a.hideKeyboard()
                if (Share.isShared(r)) a.push(EditorScreen(a, r, from, -1, name))
                else {
                    r.variants.add(r.variant(from).copy(Share.uniqueName(namesOf(), name)))
                    Store.upsert(a, r)
                    variant = r.variants.size - 1
                    a.push(EditorScreen(a, r, variant))
                }
            }
        }
        s.show()
        a.showKeyboard(e)
    }

    private fun namesOf(): List<String> { val l = ArrayList<String>(); for (v in r.variants) l.add(v.name); return l }

    private fun changePhoto() {
        a.pickPhoto("Recipe photo") { f ->
            if (f != null) a.push(PhotoScreen(a, f, r.filter.ifEmpty { "torch" }, r.strength) { orig, filtered, key, str ->
                r.photoOrig = orig; r.photo = filtered; r.filter = key; r.strength = str
                Store.upsert(a, r)
                if (Share.isShared(r)) { Share.proposeMain(a, r); a.toast("New photo sent to ${Share.peerName()} to accept"); Sync.nudge(a) }
            })
        }
    }

    override fun onShow() {
        val fresh = Store.recipe(r.id)
        if (fresh == null) { a.pop(); return }
        r = fresh
        if (variant >= Share.SPECIAL) { if (variant - Share.SPECIAL >= (Store.mine[r.id]?.size ?: 0)) variant = 0 }
        else if (variant >= r.variants.size) variant = 0
        if (shown) rebuild()
        shown = true
    }

    override fun onInsets(top: Int, bottom: Int) {
        val l = topBar?.layoutParams as? FrameLayout.LayoutParams ?: return
        if (top > 0 && l.topMargin != top + a.dp(8)) {
            l.topMargin = top + a.dp(8); topBar?.layoutParams = l
            val h = heroView?.layoutParams; if (h != null) { h.height = a.dp(300) + top; heroView?.layoutParams = h }
        }
    }

    override fun onSynced() { onShow() }

    /** Sends this recipe as a file to a chef outside the household (Discord, WhatsApp…). */
    private fun sendRecipe() {
        Photos.run {
            val p = try { RecipeFiles.pack(a, listOf(r)) } catch (e: Exception) { null }
            Photos.post {
                if (p == null) { a.toast("Couldn't pack the recipe"); return@post }
                if (p.clipsLeftOut) a.toast("Clips left out: too big to send")
                try { a.startActivity(RecipeFiles.shareIntent(a, p, r.title)) } catch (e: Exception) { a.toast("No app to send it with") }
            }
        }
    }
}

/** Full-screen look at a photo. */
object Viewer {
    fun show(a: MainActivity, name: String) {
        val s = Sheet(a, null)
        val p = RoundPhoto(a, 18f); p.outline = true
        Photos.load(a, name, 1600) { b: Bitmap? -> p.bitmap = b }
        s.body.addView(p, lp(MATCH, (a.resources.displayMetrics.heightPixels * 0.62f).toInt()))
        s.button("Close")
        s.show()
    }
}

/** Text fields in the Praliné style. */
object Fields {
    fun line(a: MainActivity, hint: String, size: Float = 16f, multi: Boolean = false): EditText {
        val e = EditText(a)
        e.hint = hint
        e.setHintTextColor(C.FAINT)
        e.setTextColor(C.INK)
        e.textSize = size
        e.typeface = F.regular(a)
        e.inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val bg = InkBox(a, C.CARD, 0, 14f, 1.8f, 0f, hint.length)
        e.background = bg
        e.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        return e
    }
}


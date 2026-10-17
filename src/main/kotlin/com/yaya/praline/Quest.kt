package com.yaya.praline

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Cooking mode: one step at a time, with its hint picture, its clip and its Pistachio timer. Timers live only inside the quest. */
class QuestScreen(a: MainActivity, val r: Recipe, val variant: Int, val scale: Double, startStep: Int = 0) : Screen(a) {
    var current = startStep
    override val name: String get() = "Quest · step ${current + 1}"
    private val v get() = Share.variantAt(r, variant)
    private var panelBox: FrameLayout? = null
    private var panelKey = ""
    private var panelViews: TimerViews? = null
    private val chips = ArrayList<Pair<String, LinearLayout>>()
    private var currentCard: View? = null

    class TimerViews(val id: String, val sec: Int, val time: TextView, val ring: TimerRing?, val state: TextView)

    override fun ownsTimer(id: String): Boolean = id.startsWith(r.id + "#" + variant + "#")

    override fun saveState(): org.json.JSONObject? =
        org.json.JSONObject().put("kind", "quest").put("r", r.id).put("variant", variant).put("scale", scale).put("step", current)

    private fun labelOf(s: Step, i: Int) = (if (s.timerLabel.isNotEmpty()) s.timerLabel else "Step ${i + 1}") + " · " + r.title

    override fun build(): View {
        val frame = FrameLayout(a)
        val sv = ScrollView(a); sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        col.setPadding(a.dp(22), a.dp(4), a.dp(22), a.dp(130))
        sv.addView(col)
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        chips.clear(); panelBox = null; panelKey = ""; panelViews = null
        current = Math.max(0, Math.min(current, v.steps.size - 1))

        val top = a.hbox()
        val back = a.roundIcon("back", 36, C.CARD, 15); back.onClick { leave() }
        top.addView(back, lp(a.dp(36), a.dp(36)).m(r = a.dp(10)))
        top.addView(a.label(r.title + " · " + v.name).single(), lp(0, WRAP, 1f))
        val ing = a.chip("Ingredients", false, C.BUTTER, null, 12f)
        ing.onClick { showIngredients() }
        top.addView(ing)
        col.addView(top)
        col.addView(a.script("The quest", 48f), lp(MATCH, WRAP).m(t = a.dp(4), l = -a.dp(4)))
        val bars = a.hbox()
        for (i in v.steps.indices) {
            val b = View(a)
            b.background = InkBox(a, if (i < current) C.PIST else if (i == current) C.BUTTER else C.CARD, 0, 6f, 1.8f, 0f, i)
            b.onClick { go(i) }
            bars.addView(b, lp(0, a.dp(11), 1f).m(r = if (i < v.steps.size - 1) a.dp(5) else 0))
        }
        col.addView(bars, lp(MATCH, WRAP).m(t = a.dp(10), b = a.dp(12)))

        if (v.steps.isEmpty()) col.addView(a.note("This variant has no steps yet. Add them in the editor.", 14f))
        currentCard = null
        for (i in v.steps.indices) {
            val s = v.steps[i]
            if (i == current) { val c = currentCard(s, i); currentCard = c; col.addView(c, lp(MATCH, WRAP).m(t = a.dp(4), b = a.dp(14))) }
            else col.addView(otherStep(s, i), lp(MATCH, WRAP).m(b = a.dp(10)))
        }

        val nav = a.hbox()
        val prev = a.boxButton("Previous"); prev.onClick { if (current > 0) go(current - 1) }
        prev.alpha = if (current > 0) 1f else 0.4f
        val last = current >= v.steps.size - 1
        val next = a.boxButton(if (last) "Finish the quest" else "Next step", true, if (last) C.RASP else C.PIST)
        next.contentDescription = if (last) "Finish the quest" else "Next step"
        next.onClick { if (last) finish() else go(current + 1) }
        nav.addView(prev, lp(0, WRAP, 1f).m(r = a.dp(10)))
        nav.addView(next, lp(0, WRAP, 2f))
        dock(frame, nav)
        tick()
        return frame
    }

    /** Moves to step [i] and brings its card into view. */
    private fun go(i: Int) {
        current = i
        rebuild()
        val c = currentCard ?: return
        val sv = c.parent?.parent as? ScrollView ?: return
        sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (sv.viewTreeObserver.isAlive) sv.viewTreeObserver.removeOnPreDrawListener(this)
                val y = Math.max(0, c.top - a.dp(70))
                if (Math.abs(sv.scrollY - y) > a.dp(4)) sv.scrollTo(0, y)
                return true
            }
        })
    }

    private fun currentCard(s: Step, i: Int): View {
        val card = a.card(C.PIST, 14, 14)
        card.rotation = -0.5f
        // number and text centred against each other, the text centred in its space
        val row = a.hbox(); row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = a.dp(64)
        row.addView(a.script((i + 1).toString(), 40f, C.RASP).also { it.gravity = Gravity.CENTER }, lp(a.dp(34), WRAP))
        val text = a.body(StepText.styled(s.text, v.ingredients, scale), 16f, C.INK, 500)
        text.setLineSpacing(0f, 1.18f)
        text.gravity = Gravity.CENTER_VERTICAL
        row.addView(text, lp(0, WRAP, 1f))
        if (s.hint.isNotEmpty()) {
            val hv = a.vbox(); hv.gravity = Gravity.CENTER_HORIZONTAL
            val p = RoundPhoto(a, 12f); p.outline = true; p.show(s.hint, 400); p.rotation = 3f
            hv.addView(p, lp(a.dp(70), a.dp(70)))
            hv.addView(a.note("hint · tap", 9.5f), lp(WRAP, WRAP).m(t = a.dp(4)))
            hv.onClick { Viewer.show(a, s.hint) }
            row.addView(hv, lp(WRAP, WRAP).m(l = a.dp(10)))
        }
        card.addView(row)
        if (s.video.isNotEmpty()) card.addView(Clips.loop(a, s.video), lp(MATCH, a.dp(190)).m(t = a.dp(12)))
        if (s.timerSec > 0) {
            // the timer sits under the text, never over it
            val box = FrameLayout(a)
            panelBox = box
            card.addView(box, lp(MATCH, WRAP).m(t = a.dp(14)))
        }
        return card
    }

    /** What the panel should look like now; it is redrawn only when this changes. */
    private fun panelKeyFor(id: String): String {
        val st = Timers.state(a, id)
        return "$st|${Timers.external(a, id)}|${Timers.completed(a, id)}"
    }

    private fun fillPanel(s: Step, i: Int) {
        val box = panelBox ?: return
        val id = Timers.idFor(r.id, variant, i)
        val key = panelKeyFor(id)
        if (key == panelKey) return
        panelKey = key
        box.removeAllViews()
        panelViews = null
        val st = Timers.state(a, id)
        when {
            st == Timers.IDLE && Timers.completed(a, id) -> box.addView(donePill(s, i, id), flp(WRAP, WRAP))
            st == Timers.DONE -> box.addView(ringPanel(s, i, id), flp(MATCH, WRAP))
            else -> box.addView(timerPanel(s, i, id), flp(MATCH, WRAP))
        }
    }

    /** The Pistachio timer, drawn in Praliné's ink: big digits, ring, −1 / +1 / +5 / reset. */
    private fun timerPanel(s: Step, i: Int, id: String): View {
        val label = if (s.timerLabel.isNotEmpty()) s.timerLabel else "Step ${i + 1}"
        val ext = Timers.external(a, id) && Timers.state(a, id) != Timers.IDLE
        val p = a.vbox()
        p.background = InkBox(a, C.TIMER_BG, 0, 22f, 2f, 0f, 8)
        p.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
        val head = a.hbox()
        head.addView(a.label((if (ext) "In Pistachio · " else "Pistachio timer · ") + label + (if (s.rounds > 1) " · ×${s.rounds}" else ""), C.TIMER_SOFT).single(), lp(0, WRAP, 1f))
        val stv = a.body("", 11f, C.TIMER_SOFT, 600)
        head.addView(stv)
        p.addView(head)
        val mid = a.hbox()
        val time = a.body(fmtClock(s.timerSec.toLong()), 52f, C.TIMER, 300)
        time.setShadowLayer(a.dpf(14f), 0f, 0f, 0x8CB6F36A.toInt())
        mid.addView(time, lp(0, WRAP, 1f))
        var ring: TimerRing? = null
        if (!ext) {
            val rg = TimerRing(a)
            rg.contentDescription = "timer ring"
            rg.onClick {
                when (Timers.state(a, id)) {
                    Timers.IDLE -> a.startTimer(id, s.timerSec, labelOf(s, i), s.rounds)
                    Timers.RUNNING -> Timers.pause(a, id)
                    Timers.PAUSED -> Timers.resume(a, id)
                    else -> Timers.stop(a, id)
                }
                tick()
            }
            mid.addView(rg, lp(a.dp(62), a.dp(62)))
            ring = rg
        }
        p.addView(mid, lp(MATCH, WRAP).m(t = a.dp(2)))
        if (ext) {
            val open = a.body("Open Pistachio", 13f, C.TIMER, 600)
            open.gravity = Gravity.CENTER
            open.background = InkBox(a, C.TIMER_BTN, 0, 12f, 0f, 0f, 1)
            open.setPadding(0, a.dp(9), 0, a.dp(9))
            open.onClick { openPistachio() }
            p.addView(open, lp(MATCH, WRAP).m(t = a.dp(8)))
            p.addView(a.note("Pistachio counts it down and rings, even if Praliné is closed. Change or stop it there.", 10.5f, C.TIMER_SOFT), lp(MATCH, WRAP).m(t = a.dp(6)))
        } else {
            val btns = a.hbox()
            val acts = arrayOf("−1", "+1", "+5", "↺")
            for (k in acts.indices) {
                val b = a.body(acts[k], 13f, C.TIMER, 600)
                b.gravity = Gravity.CENTER
                b.background = InkBox(a, C.TIMER_BTN, 0, 12f, 0f, 0f, k)
                b.setPadding(0, a.dp(7), 0, a.dp(7))
                b.onClick {
                    when (k) {
                        0 -> Timers.adjust(a, id, -60_000L)
                        1 -> if (Timers.state(a, id) == Timers.IDLE) a.startTimer(id, s.timerSec + 60, labelOf(s, i), s.rounds) else Timers.adjust(a, id, 60_000L)
                        2 -> if (Timers.state(a, id) == Timers.IDLE) a.startTimer(id, s.timerSec + 300, labelOf(s, i), s.rounds) else Timers.adjust(a, id, 300_000L)
                        else -> Timers.reset(a, id)
                    }
                    tick()
                }
                btns.addView(b, lp(0, WRAP, 1f).m(r = if (k < 3) a.dp(8) else 0))
            }
            p.addView(btns, lp(MATCH, WRAP).m(t = a.dp(8)))
            if (Timers.state(a, id) == Timers.IDLE && Timers.wouldHandOff(a, s.rounds)) p.addView(a.note("Starts in Pistachio", 10.5f, C.TIMER_SOFT), lp(WRAP, WRAP).m(t = a.dp(6)))
        }
        panelViews = TimerViews(id, s.timerSec, time, ring, stv)
        return p
    }

    /** Time's up, right in the step: Stop or one more minute. */
    private fun ringPanel(s: Step, i: Int, id: String): View {
        val p = a.vbox()
        p.background = InkBox(a, C.RASP, C.INK, 22f, 2.2f, 4f, 3)
        p.setPadding(a.dp(14), a.dp(12), a.dp(18), a.dp(16))
        val row = a.hbox()
        row.addView(a.icon("hour", 34, C.BUTTER, C.INK, 7f), lp(a.dp(34), a.dp(34)).m(r = a.dp(10)))
        val col = a.vbox()
        col.addView(a.label("Time's up", C.WHITE))
        col.addView(a.script(if (s.timerLabel.isNotEmpty()) s.timerLabel else "Step ${i + 1}", 30f, C.WHITE).single())
        row.addView(col, lp(0, WRAP, 1f))
        p.addView(row)
        val btns = a.hbox()
        val plus = a.chip("+1 min", false, C.BUTTER, null, 14f); plus.onClick { Timers.adjust(a, id, 60_000L); tick() }
        val stop = a.chip("Stop", true, C.BUTTER, "check", 14f); stop.onClick { Timers.stop(a, id); tick() }
        stop.contentDescription = "stop timer"
        btns.addView(plus, lp(WRAP, WRAP).m(r = a.dp(8))); btns.addView(stop)
        p.addView(btns, lp(WRAP, WRAP).m(t = a.dp(10)))
        return p
    }

    /** After the timer ran its whole course it shrinks to a small pill; tap to run it again. */
    private fun donePill(s: Step, i: Int, id: String): View {
        val pill = a.hbox()
        val bg = InkBox(a, C.TIMER_BG, C.PIST, 999f, 1.8f, 2.5f, 4)
        pill.background = bg
        pill.setPadding(a.dp(10), a.dp(6), a.dp(14) + bg.offsetPx, a.dp(7) + bg.offsetPx)
        pill.addView(a.icon("check", 14, 0, C.TIMER, 11f), lp(a.dp(14), a.dp(14)).m(r = a.dp(7)))
        pill.addView(a.body("Done · " + fmtClock(s.timerSec.toLong()) + (if (s.rounds > 1) " ×${s.rounds}" else "") + (if (s.timerLabel.isNotEmpty()) " · " + s.timerLabel else ""), 12.5f, C.TIMER, 600))
        pill.addView(a.body("  ↺", 13f, C.TIMER_SOFT, 600))
        pill.contentDescription = "timer done"
        pill.pressable()
        pill.onClick { Timers.reset(a, id); tick() }
        return pill
    }

    private fun openPistachio() {
        val i = a.packageManager.getLaunchIntentForPackage(Timers.PISTACHIO)
        if (i != null) try { a.startActivity(i) } catch (e: Exception) { }
    }

    private fun otherStep(s: Step, i: Int): View {
        val row = a.hbox(); row.gravity = Gravity.CENTER_VERTICAL
        row.alpha = if (i < current) 0.45f else 0.9f
        row.addView(a.script((i + 1).toString(), 30f).also { it.gravity = Gravity.CENTER }, lp(a.dp(30), WRAP).m(r = a.dp(6)))
        val col = a.vbox()
        col.addView(a.body(StepText.styled(s.text, v.ingredients, scale), 14f).also { it.setLineSpacing(0f, 1.15f) })
        if (s.timerSec > 0) {
            val id = Timers.idFor(r.id, variant, i)
            val label = if (s.timerLabel.isNotEmpty()) s.timerLabel else "Step ${i + 1}"
            val chip = a.timerChip(fmtClock(s.timerSec.toLong()) + (if (s.rounds > 1) " ×${s.rounds}" else "") + " · " + label)
            chip.onClick {
                when (Timers.state(a, id)) {
                    Timers.IDLE -> if (Timers.completed(a, id)) Timers.reset(a, id) else a.startTimer(id, s.timerSec, labelOf(s, i), s.rounds)
                    Timers.RUNNING -> if (!Timers.external(a, id)) Timers.pause(a, id) else openPistachio()
                    Timers.PAUSED -> Timers.resume(a, id)
                    else -> Timers.stop(a, id)
                }
                tick()
            }
            chips.add(Pair(id, chip))
            col.addView(chip, lp(WRAP, WRAP).m(t = a.dp(7)))
        }
        row.addView(col, lp(0, WRAP, 1f))
        if (s.video.isNotEmpty()) {
            val p = Clips.thumb(a, s.video, 46)
            p.onClick { Clips.play(a, s.video) }
            row.addView(p, lp(a.dp(46), a.dp(46)).m(l = a.dp(8)))
        }
        if (s.hint.isNotEmpty()) {
            val p = RoundPhoto(a, 10f); p.outline = true; p.show(s.hint, 200)
            p.onClick { Viewer.show(a, s.hint) }
            row.addView(p, lp(a.dp(46), a.dp(46)).m(l = a.dp(8)))
        }
        row.onClick { go(i) }
        return row
    }

    override fun tick() {
        val steps = v.steps
        if (current < steps.size && steps[current].timerSec > 0) fillPanel(steps[current], current)
        val tv = panelViews
        if (tv != null) {
            val st = Timers.state(a, tv.id)
            val rem = Timers.remainingMs(a, tv.id)
            tv.time.text = if (st == Timers.IDLE) fmtClock(tv.sec.toLong()) else fmtClock((rem + 999) / 1000)
            val set = Math.max(1L, if (st == Timers.IDLE) tv.sec * 1000L else Timers.setMs(a, tv.id))
            tv.ring?.progress = if (st == Timers.IDLE) 1f else (rem.toFloat() / set)
            tv.ring?.running = st == Timers.RUNNING
            tv.ring?.invalidate()
            val n = Timers.rounds(a, tv.id)
            val gap = Timers.gapLeft(a, tv.id)
            val round = if (n > 1 && st != Timers.IDLE) "round ${Timers.round(a, tv.id)}/$n · " else ""
            tv.state.text = when {
                gap > 0 -> round + "next in ${(gap + 999) / 1000} s"
                st == Timers.RUNNING -> round + "running"
                st == Timers.PAUSED -> round + "paused"
                else -> "tap ▶ to start"
            }
        }
        for ((id, chip) in chips) {
            val st = Timers.state(a, id)
            val t = chip.tag as TextView
            when {
                st == Timers.RUNNING -> t.text = fmtClock((Timers.remainingMs(a, id) + 999) / 1000) + (if (Timers.rounds(a, id) > 1) " · round ${Timers.round(a, id)}" else "") + " · running"
                st == Timers.PAUSED -> t.text = "paused"
                st == Timers.DONE -> t.text = "time's up! tap to stop"
                Timers.completed(a, id) -> t.text = "✓ done"
            }
        }
    }

    private fun showIngredients() {
        val s = Sheet(a, "Ingredients")
        for (ing in v.ingredients) {
            val row = a.hbox(); row.setPadding(0, a.dp(4), 0, a.dp(4))
            row.addView(a.body(if (ing.qty == null) "to taste" else ing.amount(scale), if (ing.qty == null) 12f else 15f, if (ing.qty == null) C.INK2 else C.INK, 800).also { it.minWidth = a.dp(70) })
            row.addView(a.body(ing.name, 15f), lp(0, WRAP, 1f))
            s.body.addView(row)
        }
        if (r.equipment.isNotEmpty()) {
            val eq = StringBuilder(); for (k in r.equipment) { if (eq.isNotEmpty()) eq.append(", "); eq.append(Equipment.label(r, k)) }
            s.body.addView(a.note("Equipment: $eq", 12f), lp(MATCH, WRAP).m(t = a.dp(8)))
        }
        s.button("Close").show()
    }

    private fun live(): Int = Timers.liveInQuest(a, r.id, variant, v.steps.size)

    /** Leaving with nothing running forgets this quest's timers; running ones keep going until it is finished. */
    private fun leave() {
        if (live() == 0) Timers.resetQuest(a, r.id, variant, v.steps.size)
        a.pop()
    }

    override fun onBack(): Boolean { leave(); return true }

    private fun finish() {
        val n = live()
        if (n == 0) { reallyFinish(); return }
        val s = Sheet(a, "Finish the quest?")
        s.body.addView(a.note(if (n == 1) "A timer is still running. Finishing stops it." else "$n timers are still running. Finishing stops them.", 13f, C.RASP))
        s.button("Keep cooking").button("Finish and stop", primary = true) { reallyFinish() }
        s.show()
    }

    private fun reallyFinish() {
        val beforeFame = Fame.total(Store.me)
        val beforeLvl = Fame.level(beforeFame)
        val beforeWeapon = Fame.weapons(Store.me, Store.recipes)[0]
        val earned = Store.cook(a, r, v.name)
        val lvl = Fame.level(Fame.total(Store.me))
        val weapon = Fame.weapons(Store.me, Store.recipes)[0]
        Timers.resetQuest(a, r.id, variant, v.steps.size)
        val s = Sheet(a, "Quest complete!")
        val row = a.hbox()
        val crown = a.icon("crown", 64, C.BUTTER)
        row.addView(crown, lp(a.dp(64), a.dp(64)).m(r = a.dp(14)))
        val col = a.vbox()
        col.addView(a.label("Quest reward"))
        col.addView(a.script("+$earned fame", 40f))
        val extra = StringBuilder()
        if (lvl > beforeLvl) {
            extra.append("Level up! You are now level $lvl. ")
            for (k in 0 until Portraits.COUNT) if (Portraits.UNLOCK[k] in (beforeLvl + 1)..lvl && Portraits.UNLOCK[k] > 1) extra.append("New portrait unlocked: ${Portraits.NAMES[k]}. ")
        }
        if (weapon.first != beforeWeapon.first && weapon.second > 0) extra.append("New favourite weapon: ${Fame.weaponName(weapon.first)}. ")
        if (earned < Fame.base(r)) extra.append("Cooked often lately, so it earns a little less fame for now. ")
        extra.append("${r.title} was last cooked today.")
        col.addView(a.note(extra.toString(), 12.5f))
        row.addView(col, lp(0, WRAP, 1f))
        s.body.addView(row)
        s.button("Back to the grimoire", primary = true) { a.popTo(a.stack[0]) }
        s.onDismiss { if (a.top() === this) a.pop() }
        s.show()
        crown.scaleX = 0.3f; crown.scaleY = 0.3f
        crown.animate().scaleX(1f).scaleY(1f).rotation(360f).setDuration(600).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        Confetti.burst(a)
    }
}

/** A little burst of paper confetti over the whole screen. */
object Confetti {
    fun burst(a: MainActivity) {
        val v = object : View(a) {
            val parts = ArrayList<FloatArray>()
            val colors = intArrayOf(C.RASP, C.BUTTER, C.PIST, C.MINT, C.LILAC, C.TANG, C.BLUE)
            val p = Ink.fillPaint(0)
            var start = 0L
            init {
                val rnd = java.util.Random()
                for (i in 0 until 90) parts.add(floatArrayOf(rnd.nextFloat(), -0.1f - rnd.nextFloat() * 0.3f, (rnd.nextFloat() - 0.5f) * 0.3f, 0.35f + rnd.nextFloat() * 0.5f, rnd.nextFloat() * 360f, rnd.nextInt(colors.size).toFloat()))
            }
            override fun onDraw(c: android.graphics.Canvas) {
                if (start == 0L) start = System.currentTimeMillis()
                val t = (System.currentTimeMillis() - start) / 1000f
                if (t > 2.6f) { (parent as? android.view.ViewGroup)?.removeView(this); return }
                for (q in parts) {
                    val x = (q[0] + q[2] * t) * width; val y = (q[1] + q[3] * t * t * 0.6f + q[3] * t * 0.3f) * height
                    p.color = colors[q[5].toInt()]
                    c.save(); c.translate(x, y); c.rotate(q[4] + t * 200f)
                    c.drawRect(-a.dpf(4f), -a.dpf(2.5f), a.dpf(4f), a.dpf(2.5f), p)
                    c.restore()
                }
                postInvalidateOnAnimation()
            }
        }
        a.host.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

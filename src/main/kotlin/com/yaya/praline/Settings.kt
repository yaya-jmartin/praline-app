package com.yaya.praline

import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Catches a sideways drag anywhere on a page (rightwards when [dir] is 1, leftwards when -1) and reports it,
 * unless the finger starts on a strip that can still scroll that way.
 */
class SwipeCatcher(c: android.content.Context, private val dir: Int, private val onDrag: (dist: Float, phase: Int, vel: Float) -> Unit) : FrameLayout(c) {
    private val slop = ViewConfiguration.get(c).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var blocked = false
    private var vt: VelocityTracker? = null

    private fun scrollerAt(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (x < loc[0] || x > loc[0] + v.width || y < loc[1] || y > loc[1] + v.height) return false
        if (v is HorizontalScrollView && v.canScrollHorizontally(-dir)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (scrollerAt(v.getChildAt(i), x, y)) return true
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                blocked = scrollerAt(this, e.rawX, e.rawY)
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (!blocked && !dragging && dx * dir > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; blocked = false; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && dx * dir > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.4f) dragging = true
                if (dragging) onDrag(Math.max(0f, dx * dir), 0, 0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = (vt?.xVelocity ?: 0f) * dir
                    onDrag(Math.max(0f, (e.x - downX) * dir), if (e.actionMasked == MotionEvent.ACTION_UP) 1 else 2, v)
                }
                dragging = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }
}

/** Settings, to the left of home (swipe right on home, or tap Settings): Pistachio, repeat timers, the friction log. */
class SettingsScreen(a: MainActivity) : Screen(a) {
    override val fromLeft = true
    override val name: String get() = "Settings"
    private var frictionBox: LinearLayout? = null

    override fun build(): View {
        val frame = SwipeCatcher(a, -1) { d, p, v -> a.slideOut(d, p, -v) }
        val sv = ScrollView(a); sv.isVerticalScrollBarEnabled = false
        val col = a.vbox()
        col.setPadding(a.dp(22), a.dp(6), a.dp(22), a.dp(40))
        sv.addView(col)
        frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = a.hbox()
        top.addView(a.script("Settings", 52f), lp(0, WRAP, 1f).m(l = -a.dp(4)))
        val close = a.roundIcon("chev", 38, C.CARD, 15); close.rotation = 180f
        close.contentDescription = "close settings"
        close.onClick { a.pop() }
        top.addView(close, lp(a.dp(38), a.dp(38)))
        col.addView(top)
        col.addView(a.note("Praliné " + Friction.version(a) + " · swipe left to go back", 12f), lp(MATCH, WRAP).m(t = -a.dp(6)))

        // Pistachio
        col.addView(section("Timers"))
        val pc = a.card(C.TIMER, 14, 12)
        val takes = Timers.pistachioTakesTimers(a)
        val installed = try { a.packageManager.getPackageInfo(Timers.PISTACHIO, 0); true } catch (e: Exception) { false }
        val row = a.hbox()
        row.addView(a.icon("hour", 30, C.TIMER), lp(a.dp(30), a.dp(30)).m(r = a.dp(10)))
        val tc = a.vbox()
        tc.addView(a.body("Run timers in Pistachio", 15f, C.INK, 800))
        tc.addView(a.note(when {
            !installed -> "Pistachio isn't on this phone, so Praliné runs its own timers."
            !takes -> "This Pistachio is too old to take timers. Update it to 2.8.7 or later."
            Timers.handoffOn(a) -> "On: quest timers start in Pistachio and ring there, even with Praliné closed. Repeating timers stay in Praliné for now."
            else -> "Off: Praliné runs its own timers."
        }, 11.5f))
        row.addView(tc, lp(0, WRAP, 1f))
        if (takes) {
            val on = Timers.handoffOn(a)
            val t = a.chip(if (on) "On" else "Off", on, C.PIST, null, 13f)
            t.contentDescription = "pistachio handoff"
            t.onClick { setHandoff(!on) }
            row.addView(t, lp(WRAP, WRAP).m(l = a.dp(8)))
        }
        pc.addView(row)
        col.addView(pc, lp(MATCH, WRAP))

        // pause between rounds
        val gap = a.card(C.BUTTER, 14, 12)
        gap.addView(a.body("Pause between repeat rounds", 15f, C.INK, 800))
        gap.addView(a.note("When a step's timer repeats, it chimes, waits this long, then runs the next round by itself.", 11.5f))
        val fl = FlowLayout(a, a.dp(7), a.dp(7))
        val now = Timers.roundGapSec(a)
        for (sec in intArrayOf(0, 5, 10, 20, 30)) {
            val ch = a.chip(if (sec == 0) "None" else "$sec s", sec == now, C.BUTTER, null, 13f)
            ch.onClick { a.getSharedPreferences("app", 0).edit().putInt("round_gap", sec).apply(); rebuild() }
            fl.addView(ch)
        }
        gap.addView(fl, lp(MATCH, WRAP).m(t = a.dp(8)))
        col.addView(gap, lp(MATCH, WRAP).m(t = a.dp(12)))

        // friction log
        col.addView(section("Friction log"))
        col.addView(a.note("Rest two fingers anywhere for a moment to save what's on screen, then describe what felt slow or awkward.", 11.5f), lp(MATCH, WRAP).m(b = a.dp(8)))
        val fb = a.vbox()
        frictionBox = fb
        col.addView(fb, lp(MATCH, WRAP))
        fillFriction()
        return frame
    }

    private fun section(s: String): View = a.label(s).also { it.layoutParams = lp(MATCH, WRAP).m(t = a.dp(22), b = a.dp(8)) }

    /** Turning the link off with timers already in Pistachio says what happens to them first. */
    private fun setHandoff(on: Boolean) {
        val apply = { a.getSharedPreferences("app", 0).edit().putBoolean("handoff", on).apply(); rebuild() }
        var inPistachio = 0
        for (id in Timers.active(a)) if (Timers.external(a, id) && Timers.state(a, id) == Timers.RUNNING) inPistachio++
        if (on || inPistachio == 0) { apply(); return }
        val s = Sheet(a, "Turn off the Pistachio link?")
        s.body.addView(a.note((if (inPistachio == 1) "A timer is running in Pistachio right now. It keeps running and ringing there; " else "$inPistachio timers are running in Pistachio right now. They keep running and ringing there; ") +
            "stop " + (if (inPistachio == 1) "it" else "them") + " in Pistachio if you need to. New timers will run in Praliné.", 13f, C.RASP))
        s.button("Keep it on").button("Turn off", primary = true) { apply() }
        s.show()
    }

    private fun fillFriction() {
        val box = frictionBox ?: return
        box.removeAllViews()
        val l = Friction.all(a)
        if (l.isEmpty()) box.addView(a.note("Nothing saved yet.", 13f))
        for (k in l.indices.reversed()) {
            val e = l[k]
            val c = a.card(C.LILAC, 12, 10)
            val head = a.hbox()
            head.addView(a.body(e.screen.ifEmpty { "Praliné" }, 14f, C.INK, 800).single(), lp(0, WRAP, 1f))
            head.addView(a.note(Friction.time(e.at) + " · v" + e.version, 11f))
            c.addView(head)
            c.addView(if (e.note.isEmpty()) a.note("Tap to describe it", 12f, C.FAINT) else a.body(e.note, 13f, C.INK2), lp(MATCH, WRAP).m(t = a.dp(3)))
            c.contentDescription = "friction entry"
            c.onClick { a.frictionNote(e, true) }
            box.addView(c, lp(MATCH, WRAP).m(b = a.dp(8)))
        }
        val btns = a.hbox()
        val send = a.boxButton("Send friction log", true, C.LILAC)
        send.alpha = if (l.isEmpty()) 0.4f else 1f
        send.onClick { if (l.isNotEmpty()) try { a.startActivity(Friction.shareIntent(a)) } catch (x: Exception) { a.toast("Nothing can share text on this phone") } }
        btns.addView(send, lp(0, WRAP, 2f).m(r = a.dp(10)))
        val clear = a.boxButton("Clear")
        clear.alpha = if (l.isEmpty()) 0.4f else 1f
        clear.onClick {
            if (l.isNotEmpty()) Sheet(a, "Clear the friction log?").also { it.body.addView(a.note("All ${l.size} moments and their descriptions are deleted.", 13f)) }
                .button("Keep them").button("Clear", primary = true) { Friction.clear(a); fillFriction() }.show()
        }
        btns.addView(clear, lp(0, WRAP, 1f))
        box.addView(btns, lp(MATCH, WRAP).m(t = a.dp(6)))
    }

    override fun onFriction() { fillFriction() }
}

package com.yaya.praline

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** Friction log gesture: two fingers resting anywhere for [Friction.HOLD_MS] save this moment, with a glow and a note box. */
class FrictionTouch(val a: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop by lazy { android.view.ViewConfiguration.get(a).scaledTouchSlop * 2f }
    private val twoDown = FloatArray(4)
    private var watching = false
    /** Once a second finger is down, the rest of that touch goes nowhere. */
    private var swallow = false
    private val hold = object : Runnable { override fun run() { watching = false; log() } }

    /** What's on screen, for the friction log: the page, and the sheet over it. */
    fun screenName(): String {
        val page = a.sliding?.name ?: a.top()?.name ?: ""
        val sh = Sheet.open
        return if (sh != null && sh.a === a && sh.title.isNotEmpty()) "$page › ${sh.title}" else page
    }

    fun log() {
        val e = Friction.add(a, screenName())
        a.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        glow()
        a.top()?.onFriction()
        note(e)
    }

    /** Right after the moment is saved: a box to describe it, keyboard up. Skip, back or swiping down keeps it without a note. */
    fun note(e: FrictionEntry, editing: Boolean = false) {
        val s = Sheet(a, if (editing) "Friction note" else "Friction saved")
        s.body.addView(a.note(e.screen + " · " + Friction.time(e.at) + " · v" + e.version, 11.5f))
        val box = Fields.line(a, "What felt slow or awkward? Describe it as long as you like…", 15f, true)
        box.minLines = 4; box.maxLines = 10; box.gravity = Gravity.TOP
        box.setText(e.note); box.setSelection(box.text.length)
        box.contentDescription = "friction description"
        s.body.addView(box, lp(MATCH, WRAP).m(t = a.dp(8)))
        if (editing) s.button("Delete") { Friction.remove(a, e.at); a.top()?.onFriction() }
        s.button(if (editing) "Cancel" else "Skip")
        s.button("Save note", primary = true) { Friction.setNote(a, e.at, box.text.toString()); a.top()?.onFriction() }
        s.show()
        a.showKeyboard(box)
    }

    /** A soft rim of light around the screen, there and gone: the moment is saved. */
    private fun glow() {
        val v = View(a)
        val d = android.graphics.drawable.GradientDrawable()
        d.cornerRadius = a.dp(28).toFloat()
        d.setStroke(a.dp(3), (C.RASP and 0x00FFFFFF) or (150 shl 24))
        v.background = d
        v.alpha = 0f
        v.contentDescription = "friction saved"
        a.host.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        // up in 180 ms, then gone over 520 ms
        v.animate().alpha(1f).setDuration(180).withEndAction(object : Runnable {
            override fun run() { v.animate().alpha(0f).setDuration(520).withEndAction(object : Runnable { override fun run() { a.host.removeView(v) } }).start() }
        }).start()
    }

    private fun watch(e: MotionEvent, pass: (MotionEvent) -> Boolean) {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoDown[0] = e.getX(0); twoDown[1] = e.getY(0); twoDown[2] = e.getX(1); twoDown[3] = e.getY(1)
                watching = true
                handler.removeCallbacks(hold); handler.postDelayed(hold, Friction.HOLD_MS)
                // two fingers are never a tap, scroll or swipe here: the page lets go of them at once
                if (!swallow) {
                    swallow = true
                    val cancel = MotionEvent.obtain(e); cancel.action = MotionEvent.ACTION_CANCEL
                    pass(cancel); cancel.recycle()
                }
            } else { watching = false; handler.removeCallbacks(hold) }
            MotionEvent.ACTION_MOVE -> if (watching && e.pointerCount >= 2) {
                val moved = Math.abs(e.getX(0) - twoDown[0]) > slop || Math.abs(e.getY(0) - twoDown[1]) > slop ||
                    Math.abs(e.getX(1) - twoDown[2]) > slop || Math.abs(e.getY(1) - twoDown[3]) > slop
                if (moved) { watching = false; handler.removeCallbacks(hold) }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                watching = false; handler.removeCallbacks(hold)
            }
        }
    }

    /** Called for every touch; [pass] hands it on to the page. */
    fun dispatch(e: MotionEvent, pass: (MotionEvent) -> Boolean): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) swallow = false
        watch(e, pass)
        if (swallow) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false; return true }
        return pass(e)
    }
}

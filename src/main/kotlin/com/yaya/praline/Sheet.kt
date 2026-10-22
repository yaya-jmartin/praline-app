package com.yaya.praline

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/** Bottom sheet drawn as a paper card sliding up over a dim veil. */
class Sheet(val a: MainActivity, titleIn: String?) {
    val title: String = titleIn ?: ""
    val overlay = FrameLayout(a)
    val panel = a.vbox()
    val body = a.vbox()
    private val buttons = a.hbox()
    private var onDismiss: (() -> Unit)? = null
    private var dismissed = false
    private var dragY = 0f
    private var dragging = false
    private val pull = object : View.OnTouchListener {
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { dragY = e.rawY; dragging = false; return true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - dragY
                    if (dy > a.dp(6)) dragging = true
                    if (dragging) panel.translationY = Math.max(0f, dy)
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging && panel.translationY > a.dp(90)) {
                        panel.animate().translationY(panel.height.toFloat()).setDuration(160).start(); dismiss()
                    } else panel.animate().translationY(0f).setDuration(160).start()
                    dragging = false
                    return true
                }
            }
            return false
        }
    }


    init {
        overlay.setBackgroundColor(0x88201410.toInt())
        overlay.isClickable = true
        overlay.onClick { dismiss() }
        val bg = InkBox(a, C.PAPER, C.RASP, 26f, 2.2f, 0f, 9)
        panel.background = bg
        panel.isClickable = true
        panel.setPadding(a.dp(22), a.dp(16), a.dp(22), a.dp(18))
        // grab handle: swipe the sheet down to close it
        val grab = FrameLayout(a)
        val bar = View(a)
        bar.background = InkBox(a, C.DASH, 0, 999f, 0f, 0f, 1)
        grab.addView(bar, flp(a.dp(44), a.dp(5), Gravity.CENTER))
        panel.addView(grab, lp(MATCH, a.dp(20)).m(t = -a.dp(8)))
        if (titleIn != null) panel.addView(a.script(titleIn, 36f), lp(MATCH, WRAP).m(b = a.dp(4)))
        val sv = ScrollView(a)
        sv.isFillViewport = false
        sv.addView(body)
        panel.addView(sv, lp(MATCH, WRAP, 1f))
        buttons.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        panel.addView(buttons, lp(MATCH, WRAP).m(t = a.dp(14)))
        val lpp = flp(MATCH, WRAP, Gravity.BOTTOM).m(a.dp(8), a.insetTop + a.dp(40), a.dp(8), a.insetBottom + a.dp(8))
        overlay.addView(panel, lpp)
        grab.setOnTouchListener(pull)
        if (titleIn != null) panel.getChildAt(1).setOnTouchListener(pull)
    }

    fun button(text: String, primary: Boolean = false, keepOpen: Boolean = false, f: (() -> Unit)? = null): Sheet {
        val b = a.chip(text, primary, if (primary) C.RASP else C.BUTTER, null, 14f)
        if (primary) (b.tag as TextView).setTextColor(C.WHITE)
        b.onClick { if (!keepOpen) dismiss(); f?.invoke() }
        buttons.addView(b, lp(WRAP, WRAP).m(l = a.dp(8)))
        return this
    }

    fun onDismiss(f: () -> Unit): Sheet { onDismiss = f; return this }

    /** Keeps the sheet above the keyboard and clear of the status bar when the insets change. */
    fun relayout() {
        val l = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        l.topMargin = a.insetTop + a.dp(40); l.bottomMargin = a.insetBottom + a.dp(8)
        panel.layoutParams = l
    }

    fun show(): Sheet {
        open?.dismiss()
        open = this
        a.host.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        if (buttons.childCount == 0) buttons.visibility = View.GONE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(160).start()
        panel.translationY = a.dp(300).toFloat()
        panel.animate().translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        return this
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        if (open === this) open = null
        a.hideKeyboard()
        overlay.animate().alpha(0f).setDuration(150).withEndAction(object : Runnable { override fun run() { a.host.removeView(overlay) } }).start()
        onDismiss?.invoke()
    }

    companion object { var open: Sheet? = null }
}

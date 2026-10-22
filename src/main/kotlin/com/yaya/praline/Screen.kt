package com.yaya.praline

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/** One page of the app. Screens are stacked; the top one is shown. */
abstract class Screen(val a: MainActivity) {
    lateinit var root: FrameLayout
    open val dark = false
    /** Full-bleed screens draw under the status bar and handle the inset themselves. */
    open val fullBleed = false
    /** What this page is called in the friction log. */
    open val name: String get() = javaClass.simpleName.removeSuffix("Screen")
    /** Slides in from the left and leaves to the left (Settings). */
    open val fromLeft = false
    /** True while this page shows the quest for timer [id]: the floating time's-up card stays away. */
    open fun ownsTimer(id: String): Boolean = false
    abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onBack(): Boolean = false
    open fun tick() {}
    open fun onInsets(top: Int, bottom: Int) {}
    open fun onSynced() { }
    /** A friction moment was saved or edited (Settings refreshes its list). */
    open fun onFriction() { }
    /** What to bring back if Android closes Praliné in the background (an unsaved draft, the quest step), or null. */
    open fun saveState(): org.json.JSONObject? = null

    fun make(): FrameLayout {
        root = FrameLayout(a)
        root.background = if (dark) null else PaperDrawable(a)
        if (dark) root.setBackgroundColor(C.NIGHT)
        root.isClickable = true
        root.addView(build(), FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    fun applyInsets(top: Int, bottom: Int) {
        if (!fullBleed) root.setPadding(0, top, 0, bottom)
        placeDock()
        onInsets(top, bottom)
    }

    /** Rebuilds the page in place (after data changed), keeping the scroll position with no flash. */
    fun rebuild() {
        val sv = findScroll(root)
        val y = sv?.scrollY ?: 0
        root.removeAllViews()
        dockView = null
        root.addView(build(), FrameLayout.LayoutParams(MATCH, MATCH))
        onInsets(a.insetTop, a.insetBottom)
        val sv2 = findScroll(root)
        if (sv2 != null && y > 0) keepScroll(sv2, y)
    }

    /** Puts [sv] at [y] before its first frame is drawn, so the page never shows at the top first. */
    fun keepScroll(sv: ScrollView, y: Int) {
        val vto = sv.viewTreeObserver
        vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (sv.viewTreeObserver.isAlive) sv.viewTreeObserver.removeOnPreDrawListener(this)
                sv.scrollTo(0, y)
                return true
            }
        })
    }

    // ---- sticky bottom dock: the main button sits on paper so the page never shows through ----
    var dockView: FrameLayout? = null
    private var dockHidesForKeyboard = true

    /** Pins [content] to the bottom of [frame] over a paper fade. Hidden while the keyboard is up when [hideForKeyboard]. */
    fun dock(frame: FrameLayout, content: View, hideForKeyboard: Boolean = true): FrameLayout {
        val d = FrameLayout(a)
        d.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(C.PAPER and 0x00FFFFFF, C.PAPER and 0xF0FFFFFF.toInt(), C.PAPER, C.PAPER))
        d.addView(content, flp(MATCH, WRAP, Gravity.BOTTOM))
        frame.addView(d, flp(MATCH, WRAP, Gravity.BOTTOM))
        dockView = d
        dockHidesForKeyboard = hideForKeyboard
        placeDock()
        return d
    }

    fun placeDock() {
        val d = dockView ?: return
        // full-bleed pages draw under the navigation bar, so the dock lifts itself above it
        val nav = if (fullBleed) a.navBottom else 0
        d.setPadding(a.dp(20), a.dp(30), a.dp(20), a.dp(14) + nav)
        d.visibility = if (dockHidesForKeyboard && a.imeVisible) View.GONE else View.VISIBLE
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) { val s = findScroll(v.getChildAt(i)); if (s != null) return s }
        return null
    }

    val c get() = a
}

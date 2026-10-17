package com.yaya.praline

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ScrollView
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

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

class MainActivity : Activity() {
    lateinit var host: FrameLayout
    val stack = ArrayList<Screen>()
    var insetTop = 0
    var insetBottom = 0
    /** The navigation bar alone, without the keyboard. */
    var navBottom = 0
    var imeVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private var banner: View? = null
    private var photoCallback: ((File?) -> Unit)? = null
    private var pendingShot: String = ""
    private var permCallback: ((Boolean) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            Timers.finishAllDue(this@MainActivity)
            top()?.tick()
            updateBanner()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        Store.load(this)
        Timers.channels(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        host = FrameLayout(this)
        host.setOnApplyWindowInsetsListener(object : View.OnApplyWindowInsetsListener {
            override fun onApplyWindowInsets(v: View, ins: WindowInsets): WindowInsets {
                @Suppress("DEPRECATION")
                val top = ins.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottom = ins.systemWindowInsetBottom
                var ime = false
                var nav = bottom
                if (Build.VERSION.SDK_INT >= 30) {
                    ime = ins.isVisible(WindowInsets.Type.ime())
                    nav = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else if (bottom > dp(120)) { ime = true; nav = 0 }
                if (top != insetTop || bottom != insetBottom || ime != imeVisible || nav != navBottom) {
                    insetTop = top; insetBottom = bottom; imeVisible = ime; navBottom = nav
                    for (s in stack) s.applyInsets(top, bottom)
                    sliding?.applyInsets(top, bottom)
                    Sheet.open?.relayout()
                }
                return ins
            }
        })
        setContentView(host)
        Photos.init(this)
        push(HomeScreen(this), animate = false)
        // Android closed the app in the background: bring back the open draft or quest
        Resume.restore(this, savedInstanceState != null)
        Sync.onSynced = object : () -> Unit {
            override fun invoke() {
                for (s in stack) s.onSynced()
                // tell the chef what the other chef did with their changes, one line at a time
                var delay = 0L
                for (n in ArrayList(Sync.news)) { handler.postDelayed(object : Runnable { override fun run() { toast(n) } }, delay); delay += 2900 }
                Sync.news.clear()
            }
        }
        Sync.startServer(this)
        showLastCrash()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        Timers.restore(this)
        if (Store.pairKey.isNotEmpty()) Sync.autoSync(this) { err -> if (err == Sync.TOO_OLD) toast(err) }
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker); Resume.save(this) }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); Photos.trim(level) }

    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); Photos.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }

    override fun onDestroy() { super.onDestroy(); Sync.stopServer(this) }

    fun top(): Screen? = if (stack.isEmpty()) null else stack[stack.size - 1]

    fun push(s: Screen, animate: Boolean = true) {
        hideKeyboard()
        val prev = top()
        val v = s.make()
        stack.add(s)
        host.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        s.applyInsets(insetTop, insetBottom)
        lightBars(!s.dark)
        if (animate) {
            val side = if (s.fromLeft) -1f else 1f
            v.translationX = side * host.width.toFloat().coerceAtLeast(600f)
            v.animate().translationX(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            prev?.root?.animate()?.translationX(-side * host.width * 0.25f)?.setDuration(260)?.withEndAction(object : Runnable {
                override fun run() { if (top() !== prev) prev.root.visibility = View.INVISIBLE }
            })?.start()
        } else prev?.root?.visibility = View.INVISIBLE
        prev?.onHide()
        s.onShow()
        bringBannerToFront()
    }

    fun pop() {
        if (stack.size <= 1) { finish(); return }
        hideKeyboard()
        val s = stack.removeAt(stack.size - 1)
        s.onHide()
        val v = s.root
        val side = if (s.fromLeft) -1f else 1f
        v.animate().translationX(side * host.width.toFloat()).setDuration(220).withEndAction(object : Runnable { override fun run() { host.removeView(v) } }).start()
        val t = top()!!
        t.root.visibility = View.VISIBLE
        if (!s.fromLeft) t.root.translationX = -host.width * 0.25f
        t.root.animate().translationX(0f).setDuration(220).start()
        lightBars(!t.dark)
        t.onShow()
    }

    /** Pops back to the first screen of type [T], or to home. */
    fun popTo(keep: Screen) {
        while (stack.size > 1 && top() !== keep) {
            val s = stack.removeAt(stack.size - 1); s.onHide(); host.removeView(s.root)
        }
        top()?.root?.translationX = 0f
        top()?.root?.visibility = View.VISIBLE
        top()?.onShow()
        lightBars(top()?.dark != true)
    }

    fun replaceTop(s: Screen) {
        val old = stack.removeAt(stack.size - 1)
        old.onHide(); host.removeView(old.root)
        push(s, animate = false)
    }

    // ---- a page dragged in by the finger (Settings, from the left of home) -----------------
    var sliding: Screen? = null
    private var slidingWidth = 1f

    /** [phase] 0 = finger moving ([dist] from where it started), 1 = let go with [vel], 2 = cancelled. */
    fun slideIn(make: () -> Screen, dist: Float, phase: Int, vel: Float) {
        var s = sliding
        if (s == null) {
            if (phase != 0 || dist <= 0f) return
            hideKeyboard()
            s = make()
            val v = s.make()
            host.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
            s.applyInsets(insetTop, insetBottom)
            sliding = s
            slidingWidth = Math.max(1, host.width).toFloat()
            bringBannerToFront()
        }
        val w = slidingWidth
        val prev = top()
        val d = Math.max(0f, Math.min(w, dist))
        if (phase == 0) {
            s.root.translationX = -w + d
            prev?.root?.translationX = d * 0.25f
            return
        }
        sliding = null
        val go = phase == 1 && (d > w * 0.3f || vel > dp(700))
        if (go) {
            stack.add(s)
            val dur = Math.max(120L, Math.min(260L, ((w - d) / Math.max(1f, Math.abs(vel)) * 1000f).toLong()))
            s.root.animate().translationX(0f).setDuration(dur).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            prev?.root?.animate()?.translationX(w * 0.25f)?.setDuration(dur)?.withEndAction(object : Runnable {
                override fun run() { if (top() !== prev) prev.root.visibility = View.INVISIBLE }
            })?.start()
            lightBars(!s.dark)
            prev?.onHide()
            s.onShow()
        } else {
            val v = s.root
            v.animate().translationX(-w).setDuration(200).withEndAction(object : Runnable { override fun run() { host.removeView(v) } }).start()
            prev?.root?.animate()?.translationX(0f)?.setDuration(200)?.start()
        }
    }

    /** Dragging the top page (Settings) back out to the left. */
    fun slideOut(dist: Float, phase: Int, vel: Float) {
        val s = top() ?: return
        if (stack.size < 2) return
        val prev = stack[stack.size - 2]
        val w = Math.max(1, host.width).toFloat()
        val d = Math.max(0f, Math.min(w, dist))
        if (phase == 0) {
            prev.root.visibility = View.VISIBLE
            s.root.translationX = -d
            prev.root.translationX = w * 0.25f * (1f - d / w)
            return
        }
        if (phase == 1 && (d > w * 0.3f || vel < -dp(700))) pop()
        else {
            s.root.animate().translationX(0f).setDuration(200).start()
            prev.root.animate().translationX(w * 0.25f).setDuration(200).withEndAction(object : Runnable { override fun run() { if (top() !== prev) prev.root.visibility = View.INVISIBLE } }).start()
        }
    }

    // ---- friction log: two fingers resting anywhere save this moment -------------------
    private val frictionSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop * 2f }
    private val twoDown = FloatArray(4)
    private var twoWatching = false
    /** Once a second finger is down, the rest of that touch goes nowhere. */
    private var swallow = false
    private val twoHold = object : Runnable { override fun run() { twoWatching = false; logFriction() } }

    /** What's on screen, for the friction log: the page, and the sheet over it. */
    fun screenName(): String {
        val page = sliding?.name ?: top()?.name ?: ""
        val sh = Sheet.open
        return if (sh != null && sh.a === this && sh.title.isNotEmpty()) "$page › ${sh.title}" else page
    }

    fun logFriction() {
        val e = Friction.add(this, screenName())
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        frictionGlow()
        top()?.onFriction()
        frictionNote(e)
    }

    /** Right after the moment is saved: a box to describe it, keyboard up. Skip, back or swiping down keeps it without a note. */
    fun frictionNote(e: FrictionEntry, editing: Boolean = false) {
        val s = Sheet(this, if (editing) "Friction note" else "Friction saved")
        s.body.addView(note(e.screen + " · " + Friction.time(e.at) + " · v" + e.version, 11.5f))
        val box = Fields.line(this, "What felt slow or awkward? Describe it as long as you like…", 15f, true)
        box.minLines = 4; box.maxLines = 10; box.gravity = Gravity.TOP
        box.setText(e.note); box.setSelection(box.text.length)
        box.contentDescription = "friction description"
        s.body.addView(box, lp(MATCH, WRAP).m(t = dp(8)))
        if (editing) s.button("Delete") { Friction.remove(this, e.at); top()?.onFriction() }
        s.button(if (editing) "Cancel" else "Skip")
        s.button("Save note", primary = true) { Friction.setNote(this, e.at, box.text.toString()); top()?.onFriction() }
        s.show()
        showKeyboard(box)
    }

    /** A soft rim of light around the screen, there and gone: the moment is saved. */
    private fun frictionGlow() {
        val v = View(this)
        val d = android.graphics.drawable.GradientDrawable()
        d.cornerRadius = dp(28).toFloat()
        d.setStroke(dp(3), (C.RASP and 0x00FFFFFF) or (150 shl 24))
        v.background = d
        v.alpha = 0f
        v.contentDescription = "friction saved"
        host.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        // up in 180 ms, then gone over 520 ms
        v.animate().alpha(1f).setDuration(180).withEndAction(object : Runnable {
            override fun run() { v.animate().alpha(0f).setDuration(520).withEndAction(object : Runnable { override fun run() { host.removeView(v) } }).start() }
        }).start()
    }

    private fun watchTwoFingers(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoDown[0] = e.getX(0); twoDown[1] = e.getY(0); twoDown[2] = e.getX(1); twoDown[3] = e.getY(1)
                twoWatching = true
                handler.removeCallbacks(twoHold); handler.postDelayed(twoHold, Friction.HOLD_MS)
                // two fingers are never a tap, scroll or swipe here: the page lets go of them at once
                if (!swallow) {
                    swallow = true
                    val cancel = android.view.MotionEvent.obtain(e); cancel.action = android.view.MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
            } else { twoWatching = false; handler.removeCallbacks(twoHold) }
            android.view.MotionEvent.ACTION_MOVE -> if (twoWatching && e.pointerCount >= 2) {
                val moved = Math.abs(e.getX(0) - twoDown[0]) > frictionSlop || Math.abs(e.getY(0) - twoDown[1]) > frictionSlop ||
                    Math.abs(e.getX(1) - twoDown[2]) > frictionSlop || Math.abs(e.getY(1) - twoDown[3]) > frictionSlop
                if (moved) { twoWatching = false; handler.removeCallbacks(twoHold) }
            }
            android.view.MotionEvent.ACTION_POINTER_UP, android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                twoWatching = false; handler.removeCallbacks(twoHold)
            }
        }
    }

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) swallow = false
        watchTwoFingers(e)
        if (swallow) { if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swallow = false; return true }
        return super.dispatchTouchEvent(e)
    }

    @Suppress("DEPRECATION")
    private fun lightBars(light: Boolean) {
        var f = window.decorView.systemUiVisibility
        f = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = f
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val sheet = Sheet.open
        if (sheet != null && sheet.a === this) { sheet.dismiss(); return }
        val t = top()
        if (t != null && t.onBack()) return
        pop()
    }

    fun hideKeyboard() {
        val v = currentFocus ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    fun showKeyboard(v: View) {
        v.requestFocus()
        v.postDelayed(object : Runnable { override fun run() { getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0) } }, 120)
    }

    // ---- time's-up banner, on every screen ---------------------------------------
    private fun updateBanner() {
        val t = top()
        var ringing: String? = null
        for (id in Timers.active(this)) if (Timers.state(this, id) == Timers.DONE && t?.ownsTimer(id) != true) { ringing = id; break }
        var done: Timers.Stopped? = null
        if (ringing == null) for (x in Timers.stoppedCards(this)) if (t?.ownsTimer(x.id) != true) done = x
        val key = if (ringing != null) "ring:$ringing" else if (done != null) "done:${done.id}:${done.at}" else null
        if (key == null) { banner?.let { host.removeView(it) }; banner = null; return }
        if (banner != null && banner!!.tag == key) return
        banner?.let { host.removeView(it) }
        val card = if (ringing != null) ringCard(ringing) else doneCard(done!!)
        card.tag = key
        val lpb = flp(MATCH, WRAP, Gravity.TOP).m(dp(12), insetTop + dp(8), dp(12), 0)
        host.addView(card, lpb)
        card.translationY = -dp(120).toFloat()
        card.animate().translationY(0f).setDuration(260).start()
        banner = card
    }

    private fun ringCard(id: String): View {
        val card = card(C.INK, 14, 10, C.RASP)
        val row = hbox()
        row.addView(icon("hour", 28, C.BUTTER, C.INK, 7f), lp(dp(28), dp(28)).m(r = dp(10)))
        val col = vbox()
        col.addView(label("Time's up", C.WHITE))
        col.addView(script(Timers.label(this, id).ifEmpty { "Timer" }, 26f, C.WHITE).single())
        row.addView(col, lp(0, WRAP, 1f))
        val plus = chip("+1 min"); plus.onClick { Timers.adjust(this, id, 60_000L); updateBanner() }
        val stop = chip("Stop", true, C.BUTTER); stop.onClick { Timers.stop(this, id); updateBanner() }
        row.addView(plus, lp(WRAP, WRAP).m(r = dp(6))); row.addView(stop)
        card.addView(row)
        return card
    }

    /** After Stop the card stays, quieter, saying when it finished, until it is closed. */
    private fun doneCard(d: Timers.Stopped): View {
        val card = card(C.PIST, 14, 10, C.TIMER_BG)
        val row = hbox()
        row.addView(icon("check", 26, 0, C.TIMER, 10f), lp(dp(26), dp(26)).m(r = dp(10)))
        val col = vbox()
        col.addView(label("Done at " + Timers.clock(d.at), C.TIMER_SOFT))
        col.addView(script(d.label.ifEmpty { "Timer" }, 24f, C.WHITE).single())
        row.addView(col, lp(0, WRAP, 1f))
        val x = roundIcon("x", 34, C.CARD, 13); x.contentDescription = "close done card"
        x.onClick { Timers.dismissStopped(this, d.id); updateBanner() }
        row.addView(x, lp(dp(34), dp(34)))
        card.addView(row)
        return card
    }

    fun refreshBanner() = updateBanner()

    private fun bringBannerToFront() { banner?.bringToFront(); Sheet.open?.overlay?.bringToFront() }

    // ---- photos: camera or gallery -------------------------------------------------
    fun pickPhoto(title: String = "Add a picture", done: (File?) -> Unit) {
        val s = Sheet(this, title)
        val row = hbox()
        val cam = card(C.MINT, 10, 12); cam.gravity = Gravity.CENTER_HORIZONTAL
        cam.addView(icon("cam", 44, C.MINT), lp(dp(44), dp(44)))
        cam.addView(body("Take a photo", 13f, C.INK, 600), lp(WRAP, WRAP).m(t = dp(6)))
        val gal = card(C.LILAC, 10, 12); gal.gravity = Gravity.CENTER_HORIZONTAL
        gal.addView(icon("gallery", 44, C.LILAC), lp(dp(44), dp(44)))
        gal.addView(body("From gallery", 13f, C.INK, 600), lp(WRAP, WRAP).m(t = dp(6)))
        row.addView(cam, lp(0, WRAP, 1f).m(r = dp(8))); row.addView(gal, lp(0, WRAP, 1f).m(l = dp(8)))
        s.body.addView(row)
        cam.onClick { s.dismiss(); takePhoto(done) }
        gal.onClick { s.dismiss(); fromGallery(done) }
        s.button("Cancel") { done(null) }
        s.show()
    }

    fun takePhoto(done: (File?) -> Unit) {
        withPermission(Manifest.permission.CAMERA) { ok ->
            if (!ok) { toast("Praliné needs the camera to take the picture"); done(null) }
            else {
                pendingShot = "shot_" + System.currentTimeMillis() + ".jpg"
                val f = PhotoProvider.shotFile(this, pendingShot); f.delete()
                val uri = PhotoProvider.uriFor(pendingShot)
                val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = ClipData.newRawUri("shot", uri)
                photoCallback = done
                try { startActivityForResult(i, REQ_CAMERA) } catch (e: Exception) { photoCallback = null; toast("No camera app found"); done(null) }
            }
        }
    }

    fun fromGallery(done: (File?) -> Unit) {
        photoCallback = done
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_GALLERY) } catch (e: Exception) { photoCallback = null; done(null) }
    }

    // ---- step clips: record or pick a short video ---------------------------------------
    private var videoCallback: ((String?) -> Unit)? = null

    fun pickVideo(done: (String?) -> Unit) {
        val s = Sheet(this, "Step clip")
        s.body.addView(note("A few seconds is enough: it loops silently in the quest.", 12f))
        val row = hbox()
        val rec = card(C.LILAC, 10, 12); rec.gravity = Gravity.CENTER_HORIZONTAL
        rec.addView(icon("cam", 44, C.LILAC), lp(dp(44), dp(44)))
        rec.addView(body("Record a clip", 13f, C.INK, 600), lp(WRAP, WRAP).m(t = dp(6)))
        val gal = card(C.MINT, 10, 12); gal.gravity = Gravity.CENTER_HORIZONTAL
        gal.addView(icon("gallery", 44, C.MINT), lp(dp(44), dp(44)))
        gal.addView(body("From gallery", 13f, C.INK, 600), lp(WRAP, WRAP).m(t = dp(6)))
        row.addView(rec, lp(0, WRAP, 1f).m(r = dp(8))); row.addView(gal, lp(0, WRAP, 1f).m(l = dp(8)))
        s.body.addView(row, lp(MATCH, WRAP).m(t = dp(10)))
        rec.onClick { s.dismiss(); recordVideo(done) }
        gal.onClick {
            s.dismiss()
            videoCallback = done
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
            try { startActivityForResult(i, REQ_VIDEO_GALLERY) } catch (e: Exception) { videoCallback = null; done(null) }
        }
        s.button("Cancel") { done(null) }
        s.show()
    }

    private fun recordVideo(done: (String?) -> Unit) {
        withPermission(Manifest.permission.CAMERA) { ok ->
            if (!ok) { toast("Praliné needs the camera to record the clip"); done(null) }
            else {
                pendingShot = "clip_" + System.currentTimeMillis() + ".mp4"
                val f = PhotoProvider.shotFile(this, pendingShot); f.delete()
                val uri = PhotoProvider.uriFor(pendingShot)
                val i = Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 15).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                    .putExtra(MediaStore.EXTRA_SIZE_LIMIT, Clips.MAX_BYTES)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = ClipData.newRawUri("clip", uri)
                videoCallback = done
                try { startActivityForResult(i, REQ_VIDEO) } catch (e: Exception) { videoCallback = null; toast("No camera app found"); done(null) }
            }
        }
    }

    private fun keepClip(src: File?, done: (String?) -> Unit) {
        if (src == null) { done(null); return }
        toast("Saving the clip…")
        Photos.run {
            val name = Clips.keep(this, src)
            try { src.delete() } catch (e: Exception) { }
            Photos.post { if (name == null) toast("That clip is too big or couldn't be read. Try a shorter one."); done(name) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VIDEO || requestCode == REQ_VIDEO_GALLERY) {
            val vcb = videoCallback ?: return
            videoCallback = null
            if (resultCode != RESULT_OK) { vcb(null); return }
            if (requestCode == REQ_VIDEO) {
                val f = PhotoProvider.shotFile(this, pendingShot)
                if (f.exists() && f.length() > 0) { keepClip(f, vcb); return }
                // some camera apps ignore EXTRA_OUTPUT and hand back their own uri
                val u = data?.data
                if (u == null) { vcb(null); return }
                keepClip(copyIn(u, "clip_in.mp4"), vcb)
            } else {
                val u = data?.data
                if (u == null) { vcb(null); return }
                keepClip(copyIn(u, "clip_in.mp4"), vcb)
            }
            return
        }
        val cb = photoCallback ?: return
        photoCallback = null
        if (resultCode != RESULT_OK) { cb(null); return }
        when (requestCode) {
            REQ_CAMERA -> { val f = PhotoProvider.shotFile(this, pendingShot); cb(if (f.exists() && f.length() > 0) f else null) }
            REQ_GALLERY -> {
                val uri = data?.data
                if (uri == null) { cb(null); return }
                try {
                    val f = PhotoProvider.shotFile(this, "pick_" + System.currentTimeMillis() + ".jpg")
                    contentResolver.openInputStream(uri)!!.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
                    cb(f)
                } catch (e: Exception) { cb(null) }
            }
        }
    }

    private fun copyIn(uri: android.net.Uri, name: String): File? = try {
        val f = PhotoProvider.shotFile(this, System.currentTimeMillis().toString() + "_" + name)
        contentResolver.openInputStream(uri)!!.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
        f
    } catch (e: Exception) { null }

    fun withPermission(perm: String, done: (Boolean) -> Unit) {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) { done(true); return }
        permCallback = done
        requestPermissions(arrayOf(perm), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val cb = permCallback ?: return
        permCallback = null
        cb(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
    }

    /** Asks for notifications once, the first time a timer starts. */
    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val p = getSharedPreferences("app", MODE_PRIVATE)
            if (!p.getBoolean("asked_notif", false)) { p.edit().putBoolean("asked_notif", true).apply(); requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM + 1) }
        }
    }

    fun startTimer(id: String, sec: Int, label: String, rounds: Int = 1) {
        ensureNotifications()
        Timers.start(this, id, sec * 1000L, label, rounds)
    }

    fun toast(s: String) {
        val t = body(s, 13.5f, C.PAPER, 600)
        val bg = InkBox(this, C.INK, C.RASP, 16f, 0f, 3f, 2)
        t.background = bg
        t.setPadding(dp(16), dp(12), dp(16) + bg.offsetPx, dp(12) + bg.offsetPx)
        host.addView(t, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).m(dp(24), 0, dp(24), insetBottom + dp(110)))
        t.alpha = 0f
        t.animate().alpha(1f).setDuration(180).start()
        handler.postDelayed(object : Runnable { override fun run() { t.animate().alpha(0f).setDuration(220).withEndAction(object : Runnable { override fun run() { host.removeView(t) } }).start() } }, 2600)
    }

    // ---- crashes -------------------------------------------------------------------
    private fun stack(e: Throwable): String { val w = StringWriter(); e.printStackTrace(PrintWriter(w)); return w.toString() }

    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                try { getSharedPreferences("app", MODE_PRIVATE).edit().putString("last_crash", stack(e)).commit() } catch (x: Throwable) { }
                previous?.uncaughtException(t, e)
            }
        })
    }

    private fun showLastCrash() {
        val p = getSharedPreferences("app", MODE_PRIVATE)
        val trace = p.getString("last_crash", null) ?: return
        p.edit().remove("last_crash").apply()
        val sheet = Sheet(this, "Something went wrong")
        sheet.body.addView(note("Copy this and send it to Claude so it can be fixed.", 13f))
        val t = body(trace.take(900), 10.5f, C.INK2)
        t.setTextIsSelectable(true)
        val sv = ScrollView(this); sv.addView(t)
        sheet.body.addView(sv, lp(MATCH, dp(180)).m(t = dp(8)))
        sheet.button("Close").button("Copy", primary = true) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Praliné error", trace))
        }.show()
    }

    companion object {
        const val REQ_CAMERA = 11
        const val REQ_GALLERY = 12
        const val REQ_PERM = 20
        const val REQ_VIDEO = 13
        const val REQ_VIDEO_GALLERY = 14
    }
}

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


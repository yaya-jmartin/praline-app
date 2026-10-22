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


class MainActivity : Activity() {
    lateinit var host: FrameLayout
    val stack = ArrayList<Screen>()
    var insetTop = 0
    var insetBottom = 0
    /** The navigation bar alone, without the keyboard. */
    var navBottom = 0
    var imeVisible = false
    private val handler = Handler(Looper.getMainLooper())
    val friction = FrictionTouch(this)
    val banner = TimeBanner(this)
    private var photoCallback: ((File?) -> Unit)? = null
    private var pendingShot: String = ""
    private var permCallback: ((Boolean) -> Unit)? = null

    private var ticks = 0
    private var syncedStamp = 0
    private val ticker = object : Runnable {
        override fun run() {
            Timers.finishAllDue(this@MainActivity)
            top()?.tick()
            banner.update()
            // live sync with the other open phones, every few seconds
            if (++ticks % 10 == 0) Sync.live(this@MainActivity)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crashes.install(this)
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
                // live sync runs every 15 s: pages only redraw when something the chefs can see changed
                val stamp = try { Store.contentStamp() } catch (e: Exception) { syncedStamp + 1 }
                if (stamp != syncedStamp || Sync.news.isNotEmpty()) { syncedStamp = stamp; for (s in stack) s.onSynced() }
                // tell the chef what the other chef did with their changes, one line at a time
                var delay = 0L
                for (n in ArrayList(Sync.news)) { handler.postDelayed(object : Runnable { override fun run() { toast(n) } }, delay); delay += 2900 }
                Sync.news.clear()
            }
        }
        Sync.startServer(this)
        Crashes.showLast(this)
        openRecipeFile(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        Timers.restore(this)
        // every paired phone, found on the Wi-Fi; only reasons the chef can act on are said
        Sync.autoSync(this) { _, err -> if (err == Sync.TOO_OLD || err == Sync.TOO_MANY || err == Sync.NOT_YOURS) toast(err) }
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker); Resume.save(this) }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); openRecipeFile(i) }

    /** A recipe file ([RecipeFiles]) or a world file ([WorldPack]) opened from a chat app or Files. */
    fun openRecipeFile(i: Intent?) {
        if (i == null) return
        @Suppress("DEPRECATION")
        val uri: android.net.Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
            else -> null
        }
        if (uri == null) return
        i.action = null
        WorldPack.route(this, uri)
    }

    /** Picks a recipe file from the phone (Settings). */
    fun pickRecipeFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, REQ_RECIPE_FILE) } catch (e: Exception) { toast("No file picker found") }
    }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); Photos.trim(level); SceneView.trim(level) }

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
        banner.toFront()
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
            banner.toFront()
        }
        val w = slidingWidth
        val prev = top()
        val d = Math.max(0f, Math.min(w, dist))
        if (phase == 0) {
            // the finger takes over from any slide still running, which would otherwise fight it frame by frame
            s.root.animate().cancel(); prev?.root?.animate()?.cancel()
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
            s.root.animate().cancel(); prev.root.animate().cancel()
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

    fun screenName(): String = friction.screenName()
    fun logFriction() = friction.log()
    fun frictionNote(e: FrictionEntry, editing: Boolean = false) = friction.note(e, editing)
    fun refreshBanner() = banner.update()

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean = friction.dispatch(e) { super.dispatchTouchEvent(it) }

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
        if (requestCode == REQ_RECIPE_FILE) { val u = data?.data; if (resultCode == Activity.RESULT_OK && u != null) WorldPack.route(this, u); return }
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


    companion object {
        const val REQ_CAMERA = 11
        const val REQ_GALLERY = 12
        const val REQ_PERM = 20
        const val REQ_VIDEO = 13
        const val REQ_VIDEO_GALLERY = 14
        const val REQ_RECIPE_FILE = 15
    }
}


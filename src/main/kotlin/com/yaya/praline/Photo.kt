package com.yaya.praline

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * The dungeon darkroom: a fresh shot, five light spells and a strength slider.
 * The preview is cut exactly as the recipe page shows it, and can be dragged and pinched to frame it (1.4).
 * Keeping saves the untouched original and the filtered picture into the photos folder,
 * then hands both names and the framing back through [onKeep].
 */
class PhotoScreen(a: MainActivity, val file: File, startKey: String, startStrength: Float,
                  val onKeep: (orig: String, filtered: String, key: String, strength: Float, frame: Frame) -> Unit,
                  start: Frame = Frame()) : Screen(a) {
    /** How a photo is framed (1.4): the point in the middle (0..1 across and down) and the zoom (1 = fill). */
    class Frame(val x: Float = 0.5f, val y: Float = 0.5f, val zoom: Float = 1f) {
        fun applyTo(r: Recipe) { r.focusX = x; r.focusY = y; r.zoom = zoom }
        companion object { fun of(r: Recipe) = Frame(r.focusX, r.focusY, r.zoom) }
    }
    var focusX = start.x
    var focusY = start.y
    var zoom = start.zoom
    private val others = ArrayList<RoundPhoto>()
    override val dark = true
    var key = startKey
    var strength = startStrength
    private var source: Bitmap? = null     // ~1600 px, upright
    private var small: Bitmap? = null      // ~720 px, for the live preview
    private var thumb: Bitmap? = null
    private var preview: FramePhoto? = null
    private var spellName: TextView? = null
    private var slider: Slider? = null
    private val thumbs = ArrayList<Pair<String, RoundPhoto>>()
    private val labels = ArrayList<Pair<String, TextView>>()
    private var saving = false
    @Volatile private var generation = 0

    companion object { private val worker = Executors.newSingleThreadExecutor() }

    override fun build(): View {
        val col = a.vbox()
        col.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(16))
        thumbs.clear(); labels.clear(); others.clear()

        val top = a.hbox()
        val back = a.roundIcon("x", 38, C.CARD, 15)
        back.onClick { a.pop() }
        top.addView(back, lp(a.dp(38), a.dp(38)))
        val title = a.script("Cast a light", 38f, C.NIGHT_TEXT)
        title.gravity = Gravity.CENTER
        top.addView(title, lp(0, WRAP, 1f))
        top.addView(a.space(a.dp(38), 1))
        col.addView(top)

        // the recipe page's own shape: full width, its photo band tall
        val dm = a.resources.displayMetrics
        val box = AspectBox(a, dm.widthPixels.toFloat() / (a.dp(300) + a.insetTop))
        val pv = FramePhoto(a, 20f)
        pv.placeholderIcon = "cam"; pv.placeholderColor = C.LILAC
        pv.focusX = focusX; pv.focusY = focusY; pv.zoom = zoom
        pv.onFrame = {
            focusX = pv.focusX; focusY = pv.focusY; zoom = pv.zoom
            for (o in others) { o.focusX = focusX; o.focusY = focusY; o.zoom = zoom; o.invalidate() }
        }
        preview = pv
        box.addView(pv)
        col.addView(box, lp(MATCH, 0, 1f).m(t = a.dp(8), b = a.dp(6)))
        val hint = a.hbox(); hint.gravity = Gravity.CENTER_VERTICAL
        hint.addView(a.body("Drag to move, pinch to zoom", 12f, C.NIGHT_MUTED, 400), lp(0, WRAP, 1f))
        // the same framing in the grimoire's round card
        for (label in arrayOf("Grimoire")) {
            val o = RoundPhoto(a, 999f); o.outline = true
            o.focusX = focusX; o.focusY = focusY; o.zoom = zoom
            others.add(o)
            hint.addView(a.body(label, 11f, C.NIGHT_MUTED, 400), lp(WRAP, WRAP).m(r = a.dp(6)))
            hint.addView(o, lp(a.dp(40), a.dp(40)))
        }
        val reset = a.chip("Reset", false, C.CARD, "reset", 11f)
        reset.contentDescription = "reset framing"
        reset.onClick { pv.focusX = 0.5f; pv.focusY = 0.5f; pv.zoom = 1f; pv.settle() }
        hint.addView(reset, lp(WRAP, WRAP).m(l = a.dp(8)))
        col.addView(hint, lp(MATCH, WRAP).m(b = a.dp(8)))

        val row = a.hbox()
        val nm = a.body(nameOf(key), 15f, C.NIGHT_TEXT, 600)
        spellName = nm
        row.addView(nm, lp(0, WRAP, 1f))
        row.addView(a.label("Spell strength", C.NIGHT_MUTED))
        col.addView(row)
        val sl = Slider(a, strength, colorOf(key)) { v, done -> strength = v; render(if (done) 2 else 1) }
        slider = sl
        sl.visibility = if (key.isEmpty()) View.INVISIBLE else View.VISIBLE
        col.addView(sl, lp(MATCH, a.dp(34)).m(t = a.dp(2)))

        val hs = HorizontalScrollView(a); hs.isHorizontalScrollBarEnabled = false
        val strip = a.hbox(); strip.gravity = Gravity.TOP
        strip.addView(filterThumb("", "Original", C.BEIGE))
        for (f in Filters.all) strip.addView(filterThumb(f.key, f.name, f.ui))
        hs.addView(strip)
        col.addView(hs, lp(MATCH, WRAP).m(t = a.dp(8)))

        val btns = a.hbox()
        val retake = a.boxButton("Retake")
        retake.onClick { a.pickPhoto("New picture") { f -> if (f != null) { a.pop(); a.push(PhotoScreen(a, f, key, strength, onKeep)) } } }
        // (a new picture starts centred: the old framing was for the old one)
        btns.addView(retake, lp(0, WRAP, 1f).m(r = a.dp(10)))
        val keep = a.boxButton("Keep it", true, C.RASP)
        keep.onClick { keep() }
        btns.addView(keep, lp(0, WRAP, 2f))
        col.addView(btns, lp(MATCH, WRAP).m(t = a.dp(14)))

        if (source == null) loadSource() else { render(2); renderThumbs() }
        return col
    }

    private fun nameOf(k: String): String = Filters.get(k)?.name ?: "Original"
    private fun colorOf(k: String): Int = Filters.get(k)?.ui ?: C.BEIGE

    private fun filterThumb(k: String, name: String, color: Int): View {
        val c = a.vbox(); c.gravity = Gravity.CENTER_HORIZONTAL
        val p = RoundPhoto(a, 14f)
        p.placeholderIcon = "spark"; p.placeholderColor = color
        p.outline = k == key
        c.addView(p, lp(a.dp(66), a.dp(66)))
        val t = a.body(name, 11f, if (k == key) C.NIGHT_TEXT else C.NIGHT_MUTED, if (k == key) 600 else 400)
        c.addView(t, lp(WRAP, WRAP).m(t = a.dp(4)))
        thumbs.add(Pair(k, p)); labels.add(Pair(k, t))
        c.onClick { pick(k) }
        c.layoutParams = lp(WRAP, WRAP).m(r = a.dp(10))
        return c
    }

    private fun pick(k: String) {
        if (k == key) return
        key = k
        spellName?.text = nameOf(k)
        slider?.visibility = if (k.isEmpty()) View.INVISIBLE else View.VISIBLE
        for ((tk, p) in thumbs) { p.outline = tk == k; p.invalidate() }
        for ((tk, t) in labels) { t.setTextColor(if (tk == k) C.NIGHT_TEXT else C.NIGHT_MUTED); t.typeface = if (tk == k) F.semibold(a) else F.regular(a) }
        render(2)
    }

    private fun loadSource() {
        val f = file
        worker.execute(object : Runnable {
            override fun run() {
                val src = try { Filters.load(f, 1600) } catch (e: Throwable) { null }
                val sm = if (src == null) null else scaled(src, 720)
                val th = if (src == null) null else scaled(src, 160)
                Photos.post {
                    source = src; small = sm; thumb = th
                    if (src == null) { a.toast("Couldn't open that picture"); return@post }
                    render(2); renderThumbs()
                }
            }
        })
    }

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val s = Math.min(1f, side.toFloat() / Math.max(b.width, b.height))
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, Math.max(1, (b.width * s).toInt()), Math.max(1, (b.height * s).toInt()), true)
    }

    /** Re-draws the preview. While the slider moves only the newest request is drawn. */
    private fun render(quality: Int) {
        val sm = small ?: return
        val g = ++generation
        val k = key; val st = strength
        worker.execute(object : Runnable {
            override fun run() {
                if (g != generation) return
                val out = if (k.isEmpty()) sm else Filters.apply(sm, Filters.get(k), st)
                Photos.post {
                    if (g == generation || quality == 2) {
                        preview?.bitmap = out; preview?.settle()
                        for (o in others) o.bitmap = out
                    }
                }
            }
        })
    }

    private fun renderThumbs() {
        val th = thumb ?: return
        for ((k, p) in thumbs) {
            worker.execute(object : Runnable {
                override fun run() {
                    val out = if (k.isEmpty()) th else Filters.apply(th, Filters.get(k), 0.85f)
                    Photos.post { p.bitmap = out; p.invalidate() }
                }
            })
        }
    }

    private fun keep() {
        val src = source
        if (src == null || saving) return
        saving = true
        a.toast("Casting…")
        val k = key; val st = strength
        worker.execute(object : Runnable {
            override fun run() {
                var err: String? = null
                val origName = Store.newPhotoName()
                var outName = origName
                try {
                    Filters.save(src, Store.photoFile(a, origName))
                    if (k.isNotEmpty()) {
                        outName = Store.newPhotoName()
                        Filters.save(Filters.apply(src, Filters.get(k), st), Store.photoFile(a, outName))
                    }
                } catch (e: Throwable) { err = e.message ?: "couldn't save" }
                Photos.post {
                    saving = false
                    if (err != null) { a.toast("Couldn't save the picture"); return@post }
                    try { if (file.path.contains("/shots/")) file.delete() } catch (e: Exception) { }
                    onKeep(origName, outName, k, st, Frame(focusX, focusY, zoom))
                    if (a.top() === this@PhotoScreen) a.pop()
                }
            }
        })
    }
}

/**
 * The photo exactly as the recipe page will crop it (1.4): drag to move it, pinch to zoom. The framing is kept as
 * the point shown in the middle and a zoom, so every other shape (grimoire circle, planner) centres the same point.
 */
class FramePhoto(c: android.content.Context, radiusDp: Float) : RoundPhoto(c, radiusDp) {
    var onFrame: (() -> Unit)? = null
    private var lastX = 0f; private var lastY = 0f
    private var pointer = -1
    private val scaler = android.view.ScaleGestureDetector(c, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            zoom = Math.max(1f, Math.min(Recipe.MAX_ZOOM, zoom * d.scaleFactor))
            settle(); return true
        }
    })

    init { contentDescription = "frame the photo" }

    /** Keeps the focus inside what can actually be centred, so a drag back always moves the picture at once. */
    fun settle() {
        val b = bitmap
        if (b != null && width > 0 && height > 0) {
            val s = Math.max(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
            val hx = width / 2f / (b.width * s); val hy = height / 2f / (b.height * s)
            focusX = Math.max(hx, Math.min(1f - hx, focusX)); focusY = Math.max(hy, Math.min(1f - hy, focusY))
        }
        invalidate(); onFrame?.invoke()
    }

    /** Moves the picture by [dx], [dy] pixels on screen. */
    fun pan(dx: Float, dy: Float) {
        val b = bitmap ?: return
        val s = Math.max(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
        focusX -= dx / (b.width * s); focusY -= dy / (b.height * s)
        settle()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { pointer = e.getPointerId(0); lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            android.view.MotionEvent.ACTION_POINTER_DOWN, android.view.MotionEvent.ACTION_POINTER_UP -> {
                // follow whichever finger stays down
                val keep = if (e.actionMasked == android.view.MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.actionIndex) == pointer) (if (e.actionIndex == 0) 1 else 0) else e.findPointerIndex(pointer).coerceAtLeast(0)
                pointer = e.getPointerId(keep); lastX = e.getX(keep); lastY = e.getY(keep)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i >= 0 && !scaler.isInProgress) pan(e.getX(i) - lastX, e.getY(i) - lastY)
                if (i >= 0) { lastX = e.getX(i); lastY = e.getY(i) }
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); post { settle() } }
}

/** Holds its child at a fixed width:height, as large as fits. */
class AspectBox(c: android.content.Context, var ratio: Float) : FrameLayout(c) {
    override fun onMeasure(ws: Int, hs: Int) {
        val w = View.MeasureSpec.getSize(ws); val h = View.MeasureSpec.getSize(hs)
        var cw = w; var ch = (w / ratio).toInt()
        if (View.MeasureSpec.getMode(hs) != View.MeasureSpec.UNSPECIFIED && ch > h) { ch = h; cw = (h * ratio).toInt() }
        for (i in 0 until childCount) getChildAt(i).measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY))
        setMeasuredDimension(w, if (View.MeasureSpec.getMode(hs) == View.MeasureSpec.UNSPECIFIED) ch else h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i); val x = (r - l - v.measuredWidth) / 2; val y = (b - t - v.measuredHeight) / 2
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
    }
}

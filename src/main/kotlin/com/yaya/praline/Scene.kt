package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference

/**
 * The map scene: plays a world pack ([WorldPack.World]) as a tall view inside a scroll view.
 *
 * It owns what every painted scene needs, once: the camera (map units to view px, the visible window), the painted
 * slices (decoded only near the screen, RGB_565, shrunk to the screen's width), the library pictures and animations,
 * the live effects ([SceneFx]), one clock, and taps turned into map units. A scene kind (the mission map today,
 * housing later) subclasses it and draws its own layer between the "under" and "over" items in [drawStage].
 *
 * Map units: the map is [WorldPack.World.width] units wide (412, a phone's width in dp); [s] = view px per unit.
 */
open class SceneView(c: Context, val world: WorldPack.World) : View(c) {
    var s = 1f
        private set
    /** The part of the map on screen (set by the scrolling parent), in view px. */
    var viewTop = 0
    var viewH = 0
    /** A finished world: effects that care (the beam) burn brighter. */
    var lit = false

    private class SliceBmp(val def: WorldPack.Slice) { var bmp: Bitmap? = null; var loading = false }
    private val slices = ArrayList<SliceBmp>()
    private val pictures = HashMap<String, Bitmap?>()
    private val loadingPics = HashSet<String>()

    protected val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pAdd = Paint(Paint.FILTER_BITMAP_FLAG).also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    protected val rect = RectF()
    private val src = Rect()
    private val started = SystemClock.uptimeMillis()
    /** Milliseconds since the scene opened: every animation and effect reads this one clock. */
    var now = 0L
        protected set
    val fx = SceneFx(this)

    init {
        for (d in world.slices) slices.add(SliceBmp(d))
        isClickable = true
        synchronized(live) { live.add(WeakReference(this)) }
    }

    // ---- camera ---------------------------------------------------------------------------------
    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm)
        s = w / world.width
        setMeasuredDimension(w, (world.height * s).toInt())
    }

    /** Visible window in view px, with a margin, for culling. */
    fun visTop(margin: Float = 40f) = viewTop - margin * s
    fun visBottom(margin: Float = 40f) = viewTop + viewH + margin * s
    /** True when a box (map units) touches the visible window. */
    fun onScreen(top: Float, bottom: Float, margin: Float = 40f) = bottom * s >= visTop(margin) && top * s <= visBottom(margin)

    // ---- memory ---------------------------------------------------------------------------------
    /** Keeps the slices on or near the screen decoded (a screen above and below); the rest are let go. */
    private fun keepSlices() {
        val top = (viewTop - viewH) / s; val bottom = (viewTop + 2 * viewH) / s
        for (sl in slices) {
            val near = sl.def.top + sl.def.h >= top && sl.def.top <= bottom
            if (near && sl.bmp == null && !sl.loading) {
                sl.loading = true
                val want = width
                Photos.run {
                    val b = WorldPack.picture(world.source, sl.def.file, want, true)
                    Photos.post { if (released) b?.recycle() else { sl.bmp = b; invalidate() }; sl.loading = false }
                }
            } else if (!near && sl.bmp != null) sl.bmp = null
        }
    }

    /** A library picture, decoded in the background on first use at the size it is drawn; null until ready. */
    fun picture(lib: WorldPack.Lib, drawnW: Float): Bitmap? = cached("lib:" + lib.file, lib.file, drawnW.toInt(), world.source)

    /** A picture of the app's own (assets/<path>), for the scene kind's chrome. */
    fun appPicture(path: String): Bitmap? {
        val key = "app:$path"
        if (pictures.containsKey(key)) return pictures[key]
        val b = try { context.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        pictures[key] = b
        return b
    }

    private fun cached(key: String, file: String, maxW: Int, from: WorldPack.Source): Bitmap? {
        if (pictures.containsKey(key)) return pictures[key]
        if (loadingPics.add(key)) Photos.run {
            val b = WorldPack.picture(from, file, maxW, false)
            Photos.post { loadingPics.remove(key); if (released) b?.recycle() else { pictures[key] = b; invalidate() } }
        }
        return null
    }

    private var released = false

    /**
     * Lets every picture go (leaving the scene, or Android asking for memory); they come back as needed.
     * Pictures are dropped, never recycled: the last frame drawn on the render thread may still hold them, and
     * recycling one under it crashes some phones and emulators (BlueStacks). Android frees them once unused.
     */
    fun release() {
        for (sl in slices) sl.bmp = null
        pictures.clear()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); released = false }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); released = true; release(); world.source.close() }

    // ---- drawing --------------------------------------------------------------------------------
    final override fun onDraw(cv: Canvas) {
        if (viewH == 0) viewH = resources.displayMetrics.heightPixels
        keepSlices()
        now = SystemClock.uptimeMillis() - started
        val vt = visTop(); val vb = visBottom()
        for (sl in slices) {
            val b = sl.bmp ?: continue
            rect.set(0f, sl.def.top * s, width.toFloat(), (sl.def.top + sl.def.h) * s + 1)
            if (rect.bottom < vt || rect.top > vb) continue
            cv.drawBitmap(b, null, rect, pBmp)
        }
        drawItems(cv, false)
        drawTitle(cv)
        drawStage(cv)
        drawItems(cv, true)
        drawTop(cv)
        // the clock runs only while the scene is on screen: hidden or detached, nothing is scheduled
        if (isAttachedToWindow && !released && windowVisibility == VISIBLE && android.animation.ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v == VISIBLE) invalidate() }

    /** The scene kind's own layer, above the "under" items and below the "over" ones. */
    protected open fun drawStage(cv: Canvas) {}
    /** Above everything. */
    protected open fun drawTop(cv: Canvas) {}

    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).also { it.textAlign = Paint.Align.CENTER }
    private fun drawTitle(cv: Canvas) {
        val t = world.title ?: return
        if (!onScreen(t.y - t.size, t.y + t.size)) return
        pTitle.typeface = if (t.script) F.script(context) else F.semibold(context)
        pTitle.textSize = t.size * s; pTitle.color = t.color
        cv.save(); cv.rotate(t.rotate, t.x * s, t.y * s)
        cv.drawText(t.text, t.x * s, t.y * s, pTitle)
        cv.restore()
    }

    private fun drawItems(cv: Canvas, over: Boolean) {
        for (it in world.items) {
            if (it.over != over) continue
            val lib = world.library[it.lib] ?: continue
            val bob = if (it.bobAmp > 0f && it.bobPeriod > 0f) it.bobAmp * Math.sin(now / (it.bobPeriod * 1000.0) * 2 * Math.PI).toFloat() else 0f
            when (lib.kind) {
                "fx" -> fx.draw(cv, lib, it, bob)
                "sprite", "sheet" -> drawPicture(cv, lib, it, bob)
            }
        }
    }

    private fun drawPicture(cv: Canvas, lib: WorldPack.Lib, it: WorldPack.Item, bob: Float) {
        val sheet = lib.kind == "sheet"
        val fw = if (sheet) lib.frameW else lib.w
        val fh = if (sheet) lib.frameH else lib.h
        val k = it.scale / lib.ppu          // map units per painted px
        val w = fw * k; val h = fh * k
        val top = it.y + bob - lib.ay * k
        if (!onScreen(top - w, top + h + w)) return
        val b = picture(lib, if (sheet) 0f else w * s * 1.5f) ?: return
        // the decoded picture may be smaller than painted: work in its own px
        val shrink = if (sheet) b.width.toFloat() / Math.max(1, lib.frameW * lib.cols) else b.width.toFloat() / Math.max(1, lib.w)
        if (sheet) {
            val f = frame(lib)
            val col = f % lib.cols; val row = f / lib.cols
            src.set((col * lib.frameW * shrink).toInt(), (row * lib.frameH * shrink).toInt(),
                ((col + 1) * lib.frameW * shrink).toInt(), ((row + 1) * lib.frameH * shrink).toInt())
        } else src.set(0, 0, b.width, b.height)
        val x = it.x * s; val y = (it.y + bob) * s
        rect.set(x - lib.ax * k * s, y - lib.ay * k * s, x + (fw - lib.ax) * k * s, y + (fh - lib.ay) * k * s)
        val p = if (lib.add) pAdd else pBmp
        p.alpha = (255 * it.alpha).toInt()
        cv.save()
        if (it.rot != 0f) cv.rotate(it.rot, x, y)
        if (it.flip) cv.scale(-1f, 1f, x, y)
        cv.drawBitmap(b, src, rect, p)
        cv.restore()
        p.alpha = 255
    }

    /** The frame of an animation now: looping, back and forth, or once then holding the last. */
    fun frame(lib: WorldPack.Lib): Int {
        val n = Math.max(1, lib.frames)
        val t = (now * lib.fps / 1000f).toLong()
        return when (lib.loop) {
            "once" -> Math.min(n - 1L, t).toInt()
            "pingpong" -> { if (n < 2) 0 else { val p = (t % (2 * n - 2)).toInt(); if (p < n) p else 2 * n - 2 - p } }
            else -> (t % n).toInt()
        }
    }

    // ---- taps -----------------------------------------------------------------------------------
    /** A tap at map units ([mx], [my]); true if the scene kind used it. */
    protected open fun onTap(mx: Float, my: Float): Boolean = false

    private var downX = 0f; private var downY = 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < 20 * s) { if (onTap(e.x / s, e.y / s)) return true }
        }
        return true
    }

    companion object {
        private val live = ArrayList<WeakReference<SceneView>>()
        /** Android asked for memory back: every open scene drops its pictures (they reload near the screen). */
        fun trim(level: Int) {
            if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
            val all = synchronized(live) { live.removeAll { it.get() == null }; live.mapNotNull { it.get() } }
            for (v in all) { v.release(); v.invalidate() }
        }
    }
}

/**
 * The live effects a pack's "fx" items name (WORLD-FORMAT.md, "FX kinds"). Each is drawn from the scene's clock
 * alone, with no particles kept between frames; an unknown kind is skipped.
 */
class SceneFx(private val v: SceneView) {
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val rect = RectF()

    fun draw(cv: Canvas, lib: WorldPack.Lib, it: WorldPack.Item, bob: Float) {
        val p = it.params
        val s = v.s
        val x = it.x * s; val y = (it.y + bob) * s
        val k = it.scale
        val t = v.now
        when (lib.fx) {
            "rays" -> rays(cv, x, y, p, k, it.alpha, t)
            "halo" -> halo(cv, x, y, p, k, it.alpha, t)
            "glow" -> glow(cv, x, y, p, k, it.alpha, t, it.y)
            "glints" -> glints(cv, x, y, p, k, it.alpha, t, it.y)
            "ripples" -> ripples(cv, x, y, p, k, it.alpha, t, it.y)
            "flame" -> flame(cv, x, y, p, k, it.alpha, t, it.y)
            "beam" -> beam(cv, x, y, p, k, it.alpha, t, it.y)
            "sparks" -> rising(cv, x, y, p, k, it.alpha, t, it.y, WorldPack.color(p.optString("color"), 0xFFFFC266.toInt()), 14, 900.0, 52f, true)
            "smoke" -> rising(cv, x, y, p, k, it.alpha, t, it.y, WorldPack.color(p.optString("color"), 0xFFB8A898.toInt()), 6, 4200.0, 70f, false)
            "sand" -> sand(cv, x, y, p, k, it.alpha, t, it.y)
            "heat" -> heat(cv, x, y, p, k, it.alpha, t, it.y)
            "motes" -> motes(cv, p, it.alpha, t)
        }
        pFill.shader = null; pFill.alpha = 255; pStroke.alpha = 255
    }

    private fun d(o: org.json.JSONObject, key: String, def: Double): Float { val x = o.optDouble(key, def); return if (x.isNaN() || x.isInfinite()) def.toFloat() else x.toFloat() }
    private fun col(o: org.json.JSONObject, def: Int): Int = WorldPack.color(o.optString("color"), def)
    private fun alpha(c: Int, a: Float): Int = (Math.max(0f, Math.min(1f, a)) * (c ushr 24) / 255f * 255).toInt().shl(24) or (c and 0xFFFFFF)

    /** Warm shafts of light fanning out from a point, breathing. */
    private fun rays(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long) {
        val s = v.s
        val len = d(p, "length", 1500.0) * s * k
        if (y - len > v.visBottom() || y + len < v.visTop()) return
        val a0 = d(p, "from", 92.0); val a1 = d(p, "to", 172.0)
        val n = Math.max(1, Math.min(24, p.optInt("count", 7)))
        val c = col(p, 0xFFFFE7A6.toInt())
        val al = d(p, "alpha", 0.35) * a
        pFill.shader = RadialGradient(x, y, len, intArrayOf(alpha(c, al * 1.15f), alpha(c, al * 0.4f), alpha(c, 0f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        for (i in 0 until n) {
            val mid = a0 + (a1 - a0) * (i + 0.5f) / n
            val wdg = 2.2f + (i % 3) * 1.1f
            pFill.alpha = (255 * (0.55f + 0.45f * Math.sin(t / 2600.0 + i * 1.7).toFloat())).toInt()
            path.reset(); path.moveTo(x, y)
            val ra = Math.toRadians((mid - wdg).toDouble()); val rb = Math.toRadians((mid + wdg).toDouble())
            path.lineTo(x + (Math.cos(ra) * len).toFloat(), y + (Math.sin(ra) * len).toFloat())
            path.lineTo(x + (Math.cos(rb) * len).toFloat(), y + (Math.sin(rb) * len).toFloat())
            path.close()
            cv.drawPath(path, pFill)
        }
    }

    /** A soft round glow, breathing slowly (a sun). */
    private fun halo(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long) {
        val hr = d(p, "halo", 110.0) * v.s * k * (0.92f + 0.08f * Math.sin(t / 1800.0).toFloat())
        if (y - hr > v.visBottom() || y + hr < v.visTop()) return
        val c = col(p, 0xFFFFF1C2.toInt())
        pFill.shader = RadialGradient(x, y, hr, intArrayOf(alpha(c, 0.53f * a), alpha(0xFFFFD27A.toInt(), 0.2f * a), 0x00FFD27A), floatArrayOf(0.2f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, hr, pFill)
    }

    /** A pulsing light (lantern, kiln). */
    private fun glow(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float) {
        val r0 = d(p, "r", 22.0)
        val halo = d(p, "halo", r0 * 2.0 * 0.5)
        if (!v.onScreen(my - halo * 2, my + halo * 2)) return
        val pulse = Math.max(0.2f, d(p, "pulse", 2.2))
        val r = Math.max(halo, r0) * v.s * k * (0.85f + 0.15f * Math.sin(t / (pulse * 1000.0) * 2 * Math.PI).toFloat())
        val c = col(p, 0xFFFFB347.toInt())
        pFill.shader = RadialGradient(x, y, r, intArrayOf(alpha(c, 0.75f * a), alpha(c, 0f)), null, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r, pFill)
    }

    /** Twinkling sparkles scattered in an ellipse (sun on water). */
    private fun glints(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float) {
        val rx = d(p, "rx", 60.0) * k; val ry = d(p, "ry", 24.0) * k
        if (!v.onScreen(my - ry - 10, my + ry + 10)) return
        val n = Math.max(1, Math.min(40, p.optInt("count", 6)))
        val s = v.s
        val c = col(p, -1)
        pStroke.strokeWidth = 1.2f * s
        for (i in 0 until n) {
            val ang = i * 2.39996; val rr = Math.sqrt((i + 0.5) / n)
            val gx = x + (Math.cos(ang) * rr * rx).toFloat() * s; val gy = y + (Math.sin(ang) * rr * ry).toFloat() * s
            val tw = Math.max(0.0, Math.sin(t / 700.0 + i * 1.9)).toFloat()
            if (tw < 0.05f) continue
            val r = 5 * s * tw
            pStroke.color = alpha(c, 0.9f * tw * a)
            cv.drawLine(gx - r, gy, gx + r, gy, pStroke); cv.drawLine(gx, gy - r, gx, gy + r, pStroke)
        }
    }

    /** Slow rings spreading on still water. */
    private fun ripples(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float) {
        val rx = d(p, "rx", 30.0) * k * v.s; val ry = d(p, "ry", 10.0) * k * v.s
        if (!v.onScreen(my - ry / v.s, my + ry / v.s)) return
        val period = Math.max(0.5f, d(p, "period", 3.2)) * 1000.0
        val ph = (((t / period) + (x % 7) / 14.0) % 1.0).toFloat()
        pStroke.color = col(p, -1); pStroke.alpha = (110 * (1 - ph) * a).toInt(); pStroke.strokeWidth = 1.2f * v.s
        rect.set(x - rx * 0.5f * ph, y - ry * 0.5f * ph, x + rx * 0.5f * ph, y + ry * 0.5f * ph)
        cv.drawOval(rect, pStroke)
    }

    /** A flickering flame standing on the point, with its warm glow. */
    private fun flame(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float) {
        val s = v.s
        val w = d(p, "w", 12.0) * k * s; val h = d(p, "h", 28.0) * k * s
        if (!v.onScreen(my - 80, my + 80)) return
        val fl = 0.85f + 0.15f * Math.sin(t / 90.0).toFloat() + 0.06f * Math.sin(t / 37.0).toFloat()
        val c = col(p, 0xFFFF8A2A.toInt())
        val core = WorldPack.color(p.optString("core"), 0xFFFFD25A.toInt())
        pFill.shader = RadialGradient(x, y - 8 * s, 40 * s * k, intArrayOf(alpha(c, 0.4f * a), alpha(c, 0f)), null, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y - 8 * s, 40 * s * k, pFill); pFill.shader = null
        tongue(cv, x, y, w, h * fl, alpha(c, a))
        tongue(cv, x, y, w * 0.58f, h * 0.64f * fl, alpha(core, a))
    }

    private fun tongue(cv: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        path.reset()
        path.moveTo(x, y - h)
        path.cubicTo(x + w * 0.9f, y - h * 0.45f, x + w, y - w * 0.2f, x, y)
        path.cubicTo(x - w, y - w * 0.2f, x - w * 0.9f, y - h * 0.45f, x, y - h)
        pFill.color = color
        cv.drawPath(path, pFill)
    }

    /** A beam of light from the point to (tx, ty), with a flare and sparks where it lands. */
    private fun beam(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float) {
        val s = v.s
        val ex = d(p, "tx", (x / s).toDouble()) * s; val ey = d(p, "ty", (y / s).toDouble()) * s
        if (Math.min(y, ey) - 100 * s > v.visBottom(0f) || Math.max(y, ey) + 100 * s < v.visTop(0f)) return
        val pulse = 0.75f + 0.25f * Math.sin(t / 420.0).toFloat()
        val boost = if (v.lit) 1.4f else 1f
        val wd = d(p, "width", 14.0) * k * s
        val c = col(p, 0xFFFFE7A6.toInt())
        pStroke.color = alpha(c, 0.2f * a); pStroke.strokeWidth = wd * 2.75f * pulse * boost; cv.drawLine(x, y, ex, ey, pStroke)
        pStroke.color = alpha(c, 0.53f * a); pStroke.strokeWidth = wd * pulse * boost; cv.drawLine(x, y, ex, ey, pStroke)
        pStroke.color = alpha(0xFFFFFFF0.toInt(), 0.94f * a); pStroke.strokeWidth = wd * 0.33f * boost; cv.drawLine(x, y, ex, ey, pStroke)
        val fr = d(p, "flare", 30.0) * k * s
        if (fr > 0f) {
            pFill.shader = RadialGradient(x, y, fr * 0.87f * pulse, intArrayOf(alpha(-1, 0.93f * a), 0x00FFF0C0), null, Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, fr * 0.87f * pulse, pFill)
            pFill.shader = RadialGradient(ex, ey, fr * pulse * boost, intArrayOf(alpha(0xFFFFF6D0.toInt(), 0.93f * a), alpha(0xFFFF8A30.toInt(), 0.4f * a), 0x00FF6A20), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(ex, ey, fr * pulse * boost, pFill)
            pFill.shader = null
        }
        if (p.optBoolean("sparks", true)) for (i in 0 until 16) {
            val ph = ((t / 900.0 + i / 16.0) % 1.0).toFloat()
            val dir = ((i * 37) % 21 - 10) / 10f
            pFill.color = if (i % 3 == 0) 0xFFFFF2B0.toInt() else 0xFFFFA040.toInt()
            pFill.alpha = (255 * (1 - ph) * a).toInt()
            cv.drawCircle(ex + dir * 44 * s * ph, ey - 52 * s * ph + 38 * s * ph * ph, (1.6f - ph) * s, pFill)
        }
    }

    /** Sparks (falling back) or smoke (drifting and growing) rising from the point. */
    private fun rising(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float,
                       c: Int, n0: Int, life: Double, height: Float, sparks: Boolean) {
        if (!v.onScreen(my - height * 2, my + 20)) return
        val s = v.s
        val n = Math.max(1, Math.min(48, (n0 * d(p, "rate", 1.0)).toInt()))
        for (i in 0 until n) {
            val ph = ((t / life + i.toDouble() / n) % 1.0).toFloat()
            val dir = ((i * 37) % 21 - 10) / 10f
            val px = x + dir * (if (sparks) 30f else 10f) * s * k * ph + (if (sparks) 0f else 8 * s * k * Math.sin(t / 1300.0 + i).toFloat() * ph)
            val py = y - height * s * k * ph + (if (sparks) 26 * s * k * ph * ph else 0f)
            pFill.color = c
            pFill.alpha = (255 * (1 - ph) * a * (if (sparks) 1f else 0.35f)).toInt()
            cv.drawCircle(px, py, (if (sparks) (1.6f - ph) else (3f + 9f * ph)) * s * k, pFill)
        }
    }

    /** A plume of sand blown sideways off a dune crest. */
    private fun sand(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float) {
        if (!v.onScreen(my - 30, my + 30)) return
        val s = v.s
        val c = col(p, 0xFFE8C890.toInt())
        val n = Math.max(1, Math.min(40, (12 * d(p, "rate", 1.0)).toInt()))
        for (i in 0 until n) {
            val ph = ((t / 2600.0 + i.toDouble() / n) % 1.0).toFloat()
            pFill.color = c; pFill.alpha = (150 * Math.sin(ph * Math.PI).toFloat() * a).toInt()
            cv.drawCircle(x + 46 * s * k * ph, y - 10 * s * k * ph + 3 * s * Math.sin(t / 400.0 + i).toFloat(), (0.8f + 1.4f * ph) * s * k, pFill)
        }
    }

    /** Heat shimmer: faint wavering bands over hot air. */
    private fun heat(cv: Canvas, x: Float, y: Float, p: org.json.JSONObject, k: Float, a: Float, t: Long, my: Float) {
        val s = v.s
        val w = d(p, "w", 40.0) * k * s; val h = d(p, "h", 60.0) * k * s
        if (!v.onScreen(my - h / s, my)) return
        pStroke.strokeWidth = 1.4f * s; pStroke.color = 0xFFFFFFFF.toInt()
        for (i in 0 until 4) {
            val ph = ((t / 1800.0 + i / 4.0) % 1.0).toFloat()
            val by = y - h * ph
            pStroke.alpha = (40 * Math.sin(ph * Math.PI).toFloat() * a).toInt()
            path.reset(); path.moveTo(x - w / 2, by)
            path.quadTo(x - w / 4, by - 3 * s * Math.sin(t / 300.0 + i).toFloat(), x, by)
            path.quadTo(x + w / 4, by + 3 * s * Math.sin(t / 300.0 + i).toFloat(), x + w / 2, by)
            cv.drawPath(path, pStroke)
        }
    }

    /** Dust motes drifting over the whole visible map. */
    private fun motes(cv: Canvas, p: org.json.JSONObject, a: Float, t: Long) {
        val s = v.s
        val n = Math.max(1, Math.min(200, p.optInt("count", 70)))
        val c = col(p, 0xFFFFE9B8.toInt())
        val top = v.visTop(); val bottom = v.visBottom()
        val w = v.world.width.toInt(); val h = Math.max(1, v.world.height.toInt())
        val sec = t / 1000f
        for (i in 0 until n) {
            val bx = ((i * 97) % w).toFloat(); val by = ((i * 431) % h).toFloat()
            val mx = (bx + 18 * Math.sin(sec / 3.0 + i).toFloat() + (sec * 6 + i * 11) % 40 - 20) * s
            val my = (by - (sec * 4 + i * 7) % 60) * s
            if (my < top || my > bottom) continue
            val al = 0.35f + 0.35f * Math.sin(sec * 1.3 + i * 2.1).toFloat()
            pFill.color = if (i % 5 == 0) 0xFFBFF5FF.toInt() else c
            pFill.alpha = (255 * Math.max(0f, al) * a).toInt()
            cv.drawCircle(mx, my, (if (i % 5 == 0) 1.6f else 1.1f) * s, pFill)
        }
    }
}

package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

// ---------------------------------------------------------------------------------
// Buttons, chips, cards

/** A paper chip with ink outline. Selected chips are filled and get an ink shadow. */
fun Context.chip(text: String, selected: Boolean = false, color: Int = C.BUTTER, iconName: String? = null, size: Float = 13f): LinearLayout {
    val l = hbox()
    val bg = InkBox(this, if (selected) color else C.CARD, if (selected) C.INK else color, 14f, 2f, 3f, text.hashCode() and 7)
    l.background = bg
    l.setPadding(dp(if (iconName != null) 8 else 13), dp(6), dp(13) + bg.offsetPx, dp(7) + bg.offsetPx)
    if (iconName != null) {
        val ic = icon(iconName, 18, if (selected) 0 else color, C.INK, 7f)
        l.addView(ic, lp(dp(18), dp(18)).m(r = dp(5)))
    }
    val t = body(text, size, if (selected && (color == C.INK)) C.PAPER else C.INK, 600)
    l.addView(t)
    l.tag = t
    l.pressable()
    return l
}

/**
 * Engineer gear (Kenwood, KCook): a squarer brass plate on a steel shadow, with a small gear that turns,
 * like a robotic limb bolted onto the kitchen.
 */
fun Context.gearChip(text: String, selected: Boolean, iconName: String, size: Float = 13f, turning: Boolean = true): LinearLayout {
    val l = hbox()
    val bg = InkBox(this, if (selected) C.BRASS else C.CARD, C.STEEL, 6f, 2.2f, 3.5f, text.hashCode() and 7)
    l.background = bg
    l.setPadding(dp(8), dp(6), dp(10) + bg.offsetPx, dp(7) + bg.offsetPx)
    l.addView(icon(iconName, 18, if (selected) C.CARD else C.BRASS, C.INK, 7f), lp(dp(18), dp(18)).m(r = dp(5)))
    val t = body(text, size, C.INK, 700)
    l.addView(t)
    l.tag = t
    val g = icon("gear", 14, if (selected) C.STEEL else C.BRASS, C.INK, 6f)
    l.addView(g, lp(dp(14), dp(14)).m(l = dp(6)))
    if (turning && android.animation.ValueAnimator.areAnimatorsEnabled()) {
        val spin = android.animation.ObjectAnimator.ofFloat(g, "rotation", 0f, 360f)
        spin.duration = 7000; spin.repeatCount = android.animation.ValueAnimator.INFINITE; spin.interpolator = android.view.animation.LinearInterpolator()
        g.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { spin.start() }
            override fun onViewDetachedFromWindow(v: View) { spin.cancel() }
        })
    }
    l.contentDescription = text
    l.pressable()
    return l
}

/** Dark selected chip (variants, Original). */
fun Context.darkChip(text: String, selected: Boolean, shadow: Int): LinearLayout {
    val c = chip(text, selected, if (selected) C.INK else shadow)
    if (selected) { (c.background as InkBox).shadow = 0; (c.tag as TextView).setTextColor(C.PAPER) }
    return c
}

/** Big hand-drawn button with lettering. */
fun Context.bigButton(text: String, fill: Int = C.RASP, textColor: Int = C.WHITE, shadow: Int = C.INK, iconName: String? = null, sub: String? = null): LinearLayout {
    val l = hbox()
    l.gravity = Gravity.CENTER
    val bg = InkBox(this, fill, shadow, 20f, 2.2f, 5f, 3)
    l.background = bg
    l.setPadding(dp(16), dp(6), dp(16) + bg.offsetPx, dp(6) + bg.offsetPx)
    l.minimumHeight = dp(62)
    if (iconName != null) l.addView(icon(iconName, 20, 0, textColor, 10f), lp(dp(20), dp(20)).m(r = dp(10)))
    l.addView(script(text, 32f, textColor))
    if (sub != null) l.addView(body(sub, 12f, textColor, 600).also { it.alpha = 0.9f }, lp(WRAP, WRAP).m(l = dp(6)))
    l.pressable()
    return l
}

/** Plain text button in a paper box (Previous / Next). */
fun Context.boxButton(text: String, dark: Boolean = false, shadow: Int = C.PIST): TextView {
    val t = body(text, 14f, if (dark) C.PAPER else C.INK, 600)
    val bg = InkBox(this, if (dark) C.INK else C.CARD, if (dark) shadow else 0, 16f, 2f, if (dark) 4f else 0f, 5)
    t.background = bg
    t.gravity = Gravity.CENTER
    t.setPadding(dp(10), dp(14), dp(10) + bg.offsetPx, dp(14) + bg.offsetPx)
    t.pressable()
    return t
}

fun Context.card(shadow: Int = C.BUTTER, padH: Int = 12, padV: Int = 10, fill: Int = C.CARD): LinearLayout {
    val l = vbox()
    val bg = InkBox(this, fill, shadow, 20f, 2.2f, 5f, shadow and 15)
    l.background = bg
    l.setPadding(dp(padH), dp(padV), dp(padH) + bg.offsetPx, dp(padV) + bg.offsetPx)
    return l
}

/** Round paper button holding an icon (back, edit…). */
fun Context.roundIcon(name: String, sizeDp: Int = 42, fill: Int = C.CARD, iconSize: Int = 18, stroke: Float = 12f): FrameLayout {
    val f = FrameLayout(this)
    val bg = InkBox(this, fill, C.INK, 999f, 2f, 2.5f, name.hashCode() and 7)
    f.background = bg
    val ic = icon(name, iconSize, 0, C.INK, stroke)
    f.addView(ic, flp(dp(iconSize), dp(iconSize), Gravity.CENTER).m(r = bg.offsetPx, b = bg.offsetPx))
    f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    f.pressable()
    return f
}

fun Context.timerChip(text: String, running: Boolean = false): LinearLayout {
    val l = hbox()
    val bg = InkBox(this, if (running) C.TIMER_BG else C.TIMER, C.INK, 999f, 1.8f, 2.5f, 2)
    l.background = bg
    l.setPadding(dp(9), dp(4), dp(11) + bg.offsetPx, dp(5) + bg.offsetPx)
    l.addView(icon(if (running) "hour" else "play", 12, 0, if (running) C.TIMER else C.INK, 11f), lp(dp(12), dp(12)).m(r = dp(6)))
    val t = body(text, 12f, if (running) C.TIMER else C.INK, 600)
    l.addView(t)
    l.tag = t
    l.pressable()
    return l
}

// ---------------------------------------------------------------------------------
// Flow layout for chips that wrap

class FlowLayout(c: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(c) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += ch.measuredWidth + hGap
            rowH = Math.max(rowH, ch.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            ch.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
            x += ch.measuredWidth + hGap
            rowH = Math.max(rowH, ch.measuredHeight)
        }
    }
}

// ---------------------------------------------------------------------------------
// Photos

object Photos {
    /** An eighth of what Android lets this app use (set once from the phone in [init]); 24 MB until then. */
    private var cache = newCache(24 * 1024 * 1024)
    private fun newCache(bytes: Int) = object : LruCache<String, Bitmap>(bytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Sizes the photo memory to this phone (low-RAM phones get less). */
    fun init(c: Context) {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        val mb = Math.max(8, Math.min(64, am.memoryClass / 8))
        if (cache.maxSize() != mb * 1024 * 1024) cache = newCache(mb * 1024 * 1024)
    }
    fun cacheBytes(): Int = cache.maxSize()
    /** Android asked for memory back: drop every cached photo once the app is out of sight, half while in use. */
    fun trim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) cache.evictAll()
        else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.trimToSize(cache.maxSize() / 2)
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun get(c: Context, name: String, maxSide: Int): Bitmap? = cache.get("$name@$maxSide")

    fun load(c: Context, name: String, maxSide: Int, done: (Bitmap?) -> Unit) {
        val key = "$name@$maxSide"
        val hit = cache.get(key)
        if (hit != null) { done(hit); return }
        val f = Store.photoFile(c, name)
        pool.execute(object : Runnable {
            override fun run() {
                val b = try { Filters.load(f, maxSide) } catch (e: Throwable) { null }
                if (b != null) cache.put(key, b)
                main.post(object : Runnable { override fun run() { done(b) } })
            }
        })
    }

    fun loadSync(c: Context, name: String, maxSide: Int): Bitmap? {
        val key = "$name@$maxSide"
        val hit = cache.get(key)
        if (hit != null) return hit
        val b = Filters.load(Store.photoFile(c, name), maxSide)
        if (b != null) cache.put(key, b)
        return b
    }

    fun forget(name: String) { cache.evictAll() }

    fun run(work: () -> Unit) { pool.execute(object : Runnable { override fun run() { work() } }) }
    fun post(work: () -> Unit) { main.post(object : Runnable { override fun run() { work() } }) }
}

/** Center-cropped picture with soft hand-cut corners. Shows a drawn placeholder while empty. */
class RoundPhoto(c: Context, private val radiusDp: Float = 14f) : View(c) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var placeholderIcon: String = "bowl"
    var placeholderColor: Int = C.TANG
    var outline = false
    /** The point of the picture kept in the middle (0..1) and the zoom over a plain fill (1 = fill). */
    var focusX = 0.5f
    var focusY = 0.5f
    var zoom = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pBg = Ink.fillPaint(0xFFF4E3C8.toInt())
    private val pInk = Ink.inkPaint(c, c.dpf(2f))
    private var holder: IconDrawable? = null
    private var name = ""

    fun show(photoName: String, maxSide: Int = 720) {
        name = photoName
        if (photoName.isEmpty()) { bitmap = null; return }
        val hit = Photos.get(context, photoName, maxSide)
        if (hit != null) { bitmap = hit; return }
        bitmap = null
        Photos.load(context, photoName, maxSide) { b -> if (name == photoName) bitmap = b }
    }

    /** A recipe's photo, framed the way its chef framed it. */
    fun showRecipe(r: Recipe, maxSide: Int = 720) {
        focusX = r.focusX; focusY = r.focusY; zoom = r.zoom
        show(r.photo, maxSide)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = context.dpf(radiusDp)
        val clip = Path()
        clip.addRoundRect(RectF(0f, 0f, w, h), floatArrayOf(r, r, r * 1.1f, r * 1.1f, r * 0.9f, r * 0.9f, r, r), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val b = bitmap
        if (b != null) {
            canvas.drawBitmap(b, Framing.matrix(b.width, b.height, w, h, focusX, focusY, zoom), paint)
        } else {
            canvas.drawRect(0f, 0f, w, h, pBg)
            var ic = holder
            if (ic == null || ic.name != placeholderIcon) { ic = IconDrawable(context, placeholderIcon, placeholderColor, C.INK, 6f); holder = ic }
            ic.fill = placeholderColor
            val sz = (Math.min(w, h) * 0.5f).toInt()
            ic.setBounds(((w - sz) / 2).toInt(), ((h - sz) / 2).toInt(), ((w + sz) / 2).toInt(), ((h + sz) / 2).toInt())
            ic.draw(canvas)
        }
        canvas.restore()
        if (outline) canvas.drawPath(clip, pInk)
    }
}

/** Fitting a framed photo into any box: fill it, zoom, and keep the focus point as near the middle as the edges allow. */
object Framing {
    fun matrix(bw: Int, bh: Int, w: Float, h: Float, fx: Float, fy: Float, zoom: Float): Matrix {
        val s = Math.max(w / bw, h / bh) * Math.max(1f, zoom)
        val dw = bw * s; val dh = bh * s
        val tx = Math.max(w - dw, Math.min(0f, w / 2f - fx * dw))
        val ty = Math.max(h - dh, Math.min(0f, h / 2f - fy * dh))
        val m = Matrix(); m.postScale(s, s); m.postTranslate(tx, ty)
        return m
    }
}

// ---------------------------------------------------------------------------------
// Chef portrait pieces

object Portraits {
    const val COUNT = 9
    /** Level each portrait unlocks at: the four starters, then new outfits and scenes. */
    val UNLOCK = intArrayOf(1, 1, 1, 1, 3, 5, 8, 12, 16)
    val NAMES = arrayOf("Torchlight", "Ember", "Moss", "Dusk", "Tavern cook", "Hedge wizard", "Forest druid", "Galley pirate", "Royal pâtissier")
    fun unlocked(i: Int, level: Int): Boolean = i in 0 until COUNT && level >= UNLOCK[i]
    private val cache = HashMap<Int, Bitmap>()
    fun get(c: Context, i: Int): Bitmap? {
        val k = Math.max(0, Math.min(COUNT - 1, i))
        val hit = cache[k]; if (hit != null) return hit
        val id = c.resources.getIdentifier("chef_$k", "drawable", c.packageName)
        if (id == 0) return null
        val o = BitmapFactory.Options(); o.inScaled = false
        val b = BitmapFactory.decodeResource(c.resources, id, o) ?: return null
        cache[k] = b
        return b
    }
}

/** The portrait in a dungeon arch with an ink outline and a butter offset block. */
class ArchPortrait(c: Context, var portrait: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pInk = Ink.inkPaint(c, c.dpf(2.4f))
    private val pShadow = Ink.fillPaint(C.BUTTER)
    override fun onDraw(canvas: Canvas) {
        val off = context.dpf(6f)
        val w = width - off - context.dpf(2f); val h = height - off - context.dpf(2f)
        val path = Path()
        val r = w / 2.4f
        path.moveTo(1f, h)
        path.lineTo(1f, r)
        path.cubicTo(1f, r * 0.2f, w * 0.18f, 1f, w / 2, 1f)
        path.cubicTo(w * 0.82f, 1f, w, r * 0.2f, w, r)
        path.lineTo(w, h - context.dpf(22f))
        path.quadTo(w, h, w - context.dpf(22f), h)
        path.lineTo(context.dpf(22f), h)
        path.quadTo(1f, h, 1f, h - context.dpf(22f))
        path.close()
        val wob = Ink.wobble(path, context.dpf(0.8f), 6f, 4)
        canvas.save(); canvas.translate(off, off); canvas.drawPath(wob, pShadow); canvas.restore()
        val b = Portraits.get(context, portrait)
        canvas.save(); canvas.clipPath(wob)
        if (b != null) {
            canvas.drawBitmap(b, Framing.matrix(b.width, b.height, w, h, 0.5f, 0.5f, 1f), paint)
        } else canvas.drawColor(0xFF2B2443.toInt())
        canvas.restore()
        canvas.drawPath(wob, pInk)
    }
}

/** Round avatar showing the chef's face from the portrait. */
class Medallion(c: Context, var portrait: Int, var ring: Int = C.LILAC) : View(c) {
    /** Not unlocked yet: drawn dim and grey with a padlock. */
    var locked = false
    /** The one picked in a chooser: a thick raspberry ring and a tick, drawn over the portrait. */
    var picked = false
    private val pSel = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = C.RASP; it.strokeWidth = c.dpf(4f) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pInk = Ink.inkPaint(c, c.dpf(2.2f))
    private val pFill = Ink.fillPaint(C.INK)
    override fun onDraw(canvas: Canvas) {
        val off = context.dpf(4f)
        val d = Math.min(width, height) - off - context.dpf(2f)
        val cx = d / 2 + context.dpf(1f); val cy = cx
        canvas.drawCircle(cx + off, cy + off, d / 2, pFill)
        val clip = Path(); clip.addCircle(cx, cy, d / 2, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        pFill.color = ring; canvas.drawCircle(cx, cy, d / 2, pFill); pFill.color = C.INK
        val b = Portraits.get(context, portrait)
        if (b != null) {
            // face sits around (182,108) of the 364x268 portrait; show a 150-wide square around it
            val sx = b.width / 364f
            val src = android.graphics.Rect(((182 - 72) * sx).toInt(), ((108 - 66) * sx).toInt(), ((182 + 72) * sx).toInt(), ((108 + 78) * sx).toInt())
            if (locked) { val cm = android.graphics.ColorMatrix(); cm.setSaturation(0f); paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm); paint.alpha = 90 }
            else { paint.colorFilter = null; paint.alpha = 255 }
            canvas.drawBitmap(b, src, RectF(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2), paint)
        }
        canvas.restore()
        canvas.drawCircle(cx, cy, d / 2, pInk)
        if (picked) {
            canvas.drawCircle(cx, cy, d / 2 - pSel.strokeWidth / 2, pSel)
            // a tick badge on the lower right
            val br = d * 0.17f; val bx = cx + d * 0.36f; val by = cy + d * 0.36f
            pFill.color = C.RASP; canvas.drawCircle(bx, by, br, pFill); canvas.drawCircle(bx, by, br, pInk); pFill.color = C.INK
            val tick = Path(); tick.moveTo(bx - br * 0.5f, by); tick.lineTo(bx - br * 0.1f, by + br * 0.4f); tick.lineTo(bx + br * 0.5f, by - br * 0.4f)
            val w = pSel.strokeWidth; pSel.color = C.CARD; pSel.strokeWidth = context.dpf(2f)
            canvas.drawPath(tick, pSel); pSel.color = C.RASP; pSel.strokeWidth = w
        }
        if (locked) {
            // a little padlock in the middle
            val u = d / 10f
            pFill.color = C.CARD
            canvas.drawRoundRect(RectF(cx - 2.2f * u, cy - 0.6f * u, cx + 2.2f * u, cy + 2.6f * u), u * 0.6f, u * 0.6f, pFill)
            canvas.drawRoundRect(RectF(cx - 2.2f * u, cy - 0.6f * u, cx + 2.2f * u, cy + 2.6f * u), u * 0.6f, u * 0.6f, pInk)
            val arc = Path(); arc.moveTo(cx - 1.3f * u, cy - 0.6f * u); arc.lineTo(cx - 1.3f * u, cy - 1.6f * u)
            arc.cubicTo(cx - 1.3f * u, cy - 3.2f * u, cx + 1.3f * u, cy - 3.2f * u, cx + 1.3f * u, cy - 1.6f * u); arc.lineTo(cx + 1.3f * u, cy - 0.6f * u)
            canvas.drawPath(arc, pInk)
            pFill.color = C.INK
        }
    }
}

/** Raspberry name ribbon with folded ends and the name in white lettering. */
class Ribbon(c: Context, var text: String) : View(c) {
    private val pFill = Ink.fillPaint(C.RASP)
    private val pDark = Ink.fillPaint(0xFFC2405F.toInt())
    private val pInk = Ink.inkPaint(c, c.dpf(1.6f))
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    init { pText.typeface = F.script(c); pText.color = C.WHITE; pText.textAlign = Paint.Align.CENTER }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val tail = w * 0.13f
        fun end(left: Boolean): Path {
            val p = Path()
            if (left) { p.moveTo(0f, h * 0.3f); p.lineTo(tail * 1.3f, h * 0.3f); p.lineTo(tail * 1.3f, h * 0.92f); p.lineTo(0f, h * 0.92f); p.lineTo(tail * 0.5f, h * 0.61f); p.close() }
            else { p.moveTo(w, h * 0.3f); p.lineTo(w - tail * 1.3f, h * 0.3f); p.lineTo(w - tail * 1.3f, h * 0.92f); p.lineTo(w, h * 0.92f); p.lineTo(w - tail * 0.5f, h * 0.61f); p.close() }
            return Ink.wobble(p, 0.8f, 5f, if (left) 1 else 2)
        }
        val l = end(true); val r = end(false)
        canvas.drawPath(l, pDark); canvas.drawPath(l, pInk); canvas.drawPath(r, pDark); canvas.drawPath(r, pInk)
        val band = Path(); band.addRect(tail, h * 0.08f, w - tail, h * 0.78f, Path.Direction.CW)
        val wb = Ink.wobble(band, 0.8f, 5f, 3)
        canvas.drawPath(wb, pFill); canvas.drawPath(wb, pInk)
        pText.textSize = h * 0.62f
        var size = pText.textSize
        while (pText.measureText(text) > w - tail * 2.6f && size > 10f) { size -= 2f; pText.textSize = size }
        canvas.drawText(text, w / 2, h * 0.62f, pText)
    }
}

// ---------------------------------------------------------------------------------
// Servings wheel: drag up/down, or tap to type

class ServingsWheel(c: Context, var value: Int, private val onChange: (Int) -> Unit, private val onTap: () -> Unit) : View(c) {
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = InkBox(c, C.CARD, C.BUTTER, 16f, 2f, 3f, 6)
    private var downY = 0f
    private var startVal = 0
    private var moved = false
    init { pText.textAlign = Paint.Align.CENTER; pText.color = C.INK; background = bg }

    override fun onDraw(canvas: Canvas) {
        val w = width - bg.offsetPx.toFloat(); val h = height - bg.offsetPx.toFloat()
        pText.typeface = F.semibold(context); pText.textSize = context.dpf(11f); pText.alpha = 80
        if (value > 1) canvas.drawText((value - 1).toString(), w / 2, h * 0.2f + context.dpf(4f), pText)
        canvas.drawText((value + 1).toString(), w / 2, h * 0.92f, pText)
        pText.alpha = 255; pText.typeface = F.black(context); pText.textSize = context.dpf(24f)
        canvas.drawText(value.toString(), w / 2, h * 0.5f + context.dpf(8f), pText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; startVal = value; moved = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val steps = ((downY - e.y) / context.dpf(16f)).toInt()
                if (Math.abs(downY - e.y) > context.dpf(6f)) moved = true
                val v = Math.max(1, Math.min(99, startVal + steps))
                if (v != value) { value = v; invalidate(); onChange(v) }
            }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); if (!moved) onTap() }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

// ---------------------------------------------------------------------------------
// Slider (filter strength)

class Slider(c: Context, var value: Float, private val color: Int, private val onChange: (Float, Boolean) -> Unit) : View(c) {
    private val pTrack = Ink.fillPaint(0xFF3B2F45.toInt())
    private val pOn = Ink.fillPaint(color)
    private val pKnob = Ink.fillPaint(C.NIGHT_TEXT)
    private val pRing = Ink.inkPaint(c, c.dpf(2f), color)
    override fun onDraw(canvas: Canvas) {
        val r = context.dpf(10f); val y = height / 2f; val l = r; val w = width - 2 * r
        canvas.drawRoundRect(l, y - context.dpf(2f), l + w, y + context.dpf(2f), 4f, 4f, pTrack)
        canvas.drawRoundRect(l, y - context.dpf(2f), l + w * value, y + context.dpf(2f), 4f, 4f, pOn)
        canvas.drawCircle(l + w * value, y, r, pKnob); canvas.drawCircle(l + w * value, y, r, pRing)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = context.dpf(10f)
        value = Math.max(0f, Math.min(1f, (e.x - r) / (width - 2 * r)))
        invalidate()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        onChange(value, e.actionMasked == MotionEvent.ACTION_UP)
        return true
    }
}

/** Countdown ring with a play / pause mark, Pistachio green. */
class TimerRing(c: Context) : View(c) {
    var progress = 0f
    var running = false
    private val pBack = Ink.inkPaint(c, c.dpf(6f), 0xFF3A4D22.toInt())
    private val pFront = Ink.inkPaint(c, c.dpf(6f), C.TIMER)
    private val pMark = Ink.fillPaint(C.TIMER)
    private val pBar = Ink.inkPaint(c, c.dpf(5f), C.TIMER)
    override fun onDraw(canvas: Canvas) {
        val s = Math.min(width, height).toFloat()
        val r = s / 2 - context.dpf(5f)
        val cx = width / 2f; val cy = height / 2f
        canvas.drawCircle(cx, cy, r, pBack)
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * progress, false, pFront)
        if (running) {
            canvas.drawLine(cx - s * 0.09f, cy - s * 0.15f, cx - s * 0.09f, cy + s * 0.15f, pBar)
            canvas.drawLine(cx + s * 0.09f, cy - s * 0.15f, cx + s * 0.09f, cy + s * 0.15f, pBar)
        } else {
            val p = Path(); p.moveTo(cx - s * 0.1f, cy - s * 0.16f); p.lineTo(cx + s * 0.16f, cy); p.lineTo(cx - s * 0.1f, cy + s * 0.16f); p.close()
            canvas.drawPath(p, pMark)
        }
    }
}


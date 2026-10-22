package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * The hand-drawn look: every outline goes through [Ink.wobble] so lines waver like
 * pen on paper, and shapes get a flat colour block offset behind them (riso style).
 */
object Ink {
    private val parsed = HashMap<String, Path>()

    /** Parses the absolute M/L/C/Z paths in [IconData]. */
    fun parse(d: String): Path {
        val hit = parsed[d]
        if (hit != null) return hit
        val p = Path()
        val tok = d.replace("M", " M ").replace("L", " L ").replace("C", " C ").replace("Z", " Z ").trim().split(Regex("\\s+"))
        var i = 0
        var cmd = 'M'
        fun num(): Float { val v = tok[i].toFloat(); i++; return v }
        while (i < tok.size) {
            val t = tok[i]
            if (t.length == 1 && Character.isLetter(t[0])) { cmd = t[0]; i++; if (cmd == 'Z') { p.close(); continue } }
            when (cmd) {
                'M' -> { p.moveTo(num(), num()); cmd = 'L' }
                'L' -> p.lineTo(num(), num())
                'C' -> p.cubicTo(num(), num(), num(), num(), num(), num())
                else -> i++
            }
        }
        parsed[d] = p
        return p
    }

    private fun noise(d: Float, seed: Int): Float {
        val s = seed * 1.37f
        return ((Math.sin((d * 0.11f + s).toDouble()) + 0.6 * Math.sin((d * 0.27f + s * 1.7f).toDouble()) +
                0.35 * Math.sin((d * 0.61f + s * 2.3f).toDouble())) / 1.95).toFloat()
    }

    /** Resamples [src] every [step] units and pushes each point sideways by a smooth noise of [amp]. */
    fun wobble(src: Path, amp: Float, step: Float, seed: Int): Path {
        val out = Path()
        val pm = PathMeasure(src, false)
        val pos = FloatArray(2); val tan = FloatArray(2)
        var contour = 0
        do {
            val len = pm.length
            if (len > 0f) {
                val n = Math.max(2, (len / step).toInt())
                var lastX = 0f; var lastY = 0f
                for (k in 0..n) {
                    val d = len * k / n
                    pm.getPosTan(d, pos, tan)
                    val off = amp * noise(d, seed + contour * 7)
                    val x = pos[0] - tan[1] * off
                    val y = pos[1] + tan[0] * off
                    if (k == 0) out.moveTo(x, y)
                    else out.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
                    lastX = x; lastY = y
                }
                out.lineTo(lastX, lastY)
                if (pm.isClosed) out.close()
            }
            contour++
        } while (pm.nextContour())
        return out
    }

    fun inkPaint(c: Context, width: Float, color: Int = C.INK): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = color
        return p
    }

    fun fillPaint(color: Int): Paint { val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.FILL; p.color = color; return p }

    /** A wobbly rounded rectangle, a bit irregular in its corners like a hand-cut card. */
    fun box(w: Float, h: Float, r: Float, amp: Float, seed: Int): Path {
        val p = Path()
        val rr = Math.min(r, Math.min(w, h) / 2f)
        val radii = floatArrayOf(rr * 1.0f, rr * 0.92f, rr * 1.12f, rr * 0.96f, rr * 0.9f, rr * 1.08f, rr * 1.04f, rr * 0.94f)
        for (i in radii.indices) radii[i] = Math.min(radii[i], Math.min(w, h) / 2f)
        p.addRoundRect(RectF(0f, 0f, w, h), radii, Path.Direction.CW)
        return if (amp <= 0f) p else wobble(p, amp, 6f, seed)
    }
}

/** One icon of the kit: ink line + optional colour block offset behind it. */
class IconDrawable(private val c: Context, val name: String, var fill: Int = 0, var ink: Int = C.INK,
                   private val stroke: Float = 6f, private val offset: Float = 5f) : Drawable() {
    private val inkPath: Path = Ink.wobble(Ink.parse(IconData.ink[name] ?: IconData.ink["star"]!!), 1.3f, 3f, name.hashCode() and 15)
    private val fillPath: Path? = run {
        val d = IconData.fill[name] ?: if (name in CLOSED) IconData.ink[name] else null
        if (d == null) null else Ink.parse(d)
    }
    private val pInk = Ink.inkPaint(c, stroke)
    private val pFill = Ink.fillPaint(0)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = Math.min(b.width(), b.height()) / 112f
        canvas.save()
        canvas.translate(b.left + (b.width() - 112f * s) / 2f + 6f * s, b.top + (b.height() - 112f * s) / 2f + 6f * s)
        canvas.scale(s, s)
        if (fill != 0 && fillPath != null) {
            pFill.color = fill
            canvas.save(); canvas.translate(offset, offset * 0.8f); canvas.drawPath(fillPath, pFill); canvas.restore()
        }
        pInk.color = ink
        canvas.drawPath(inkPath, pInk)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { pInk.alpha = alpha; pFill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        val CLOSED = hashSetOf("shield", "flame", "leaf", "heart", "star", "spark", "crown", "lotus", "box", "play", "drum")
    }
}

/** A plain view showing an icon. */
class IconView(c: Context, name: String, fill: Int = 0, ink: Int = C.INK, stroke: Float = 6f, offset: Float = 5f) : View(c) {
    val icon = IconDrawable(c, name, fill, ink, stroke, offset)
    init { background = null }
    override fun onDraw(canvas: Canvas) { icon.setBounds(0, 0, width, height); icon.draw(canvas) }
}

fun Context.icon(name: String, sizeDp: Int, fill: Int = 0, ink: Int = C.INK, stroke: Float = 6f): IconView {
    val v = IconView(this, name, fill, ink, stroke)
    v.layoutParams = android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    return v
}

/**
 * Card / chip / button background: paper fill, wobbly ink outline, and a flat colour
 * block offset to the bottom-right. The drawable leaves room for that offset.
 */
class InkBox(private val c: Context, var fill: Int = C.CARD, var shadow: Int = C.BUTTER, private val radiusDp: Float = 20f,
             private val strokeDp: Float = 2.2f, private val offsetDp: Float = 5f, private val seed: Int = 1,
             var dashed: Boolean = false, var strokeColor: Int = C.INK) : Drawable() {
    private var cached: Path? = null
    private var cw = 0; private var ch = 0
    private val pFill = Ink.fillPaint(fill)
    private val pInk = Ink.inkPaint(c, c.dpf(strokeDp))

    val offsetPx: Int get() = c.dp(offsetDp)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val off = c.dpf(offsetDp)
        val half = c.dpf(strokeDp) / 2f + c.dpf(1f)
        val w = b.width() - off - half * 2; val h = b.height() - off - half * 2
        if (w <= 0 || h <= 0) return
        if (cached == null || cw != b.width() || ch != b.height()) {
            cached = Ink.box(w, h, c.dpf(radiusDp), c.dpf(0.9f), seed)
            cw = b.width(); ch = b.height()
        }
        val p = cached!!
        canvas.save()
        canvas.translate(b.left + half, b.top + half)
        if (shadow != 0 && off > 0) {
            pFill.color = shadow
            canvas.save(); canvas.translate(off, off); canvas.drawPath(p, pFill); canvas.restore()
        }
        pFill.color = fill
        if (fill != 0) canvas.drawPath(p, pFill)
        pInk.color = strokeColor
        pInk.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(c.dpf(5f), c.dpf(4f)), 0f) else null
        if (strokeDp > 0f) canvas.drawPath(p, pInk)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getPadding(padding: android.graphics.Rect): Boolean { padding.set(0, 0, 0, 0); return false }
}

/** Tier shield with its letter (S, A, B, C, D). */
class ShieldView(c: Context, var tier: String, var color: Int) : View(c) {
    private val shape = Ink.wobble(Ink.parse(IconData.ink["shield"]!!), 1.2f, 3f, 3)
    private val pInk = Ink.inkPaint(c, 6f)
    private val pFill = Ink.fillPaint(color)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF().also { shape.computeBounds(it, true) }
    private val glyph = android.graphics.Rect()
    init { pText.typeface = F.black(c); pText.textAlign = Paint.Align.LEFT; pText.color = C.INK; pText.textSkewX = -0.2f }
    override fun onDraw(canvas: Canvas) {
        val s = Math.min(width, height) / 112f
        canvas.save()
        canvas.translate((width - 112f * s) / 2f + 6f * s, (height - 112f * s) / 2f + 6f * s)
        canvas.scale(s, s)
        pFill.color = C.INK
        canvas.save(); canvas.translate(5f, 5f); canvas.drawPath(shape, pFill); canvas.restore()
        pFill.color = color
        canvas.drawPath(shape, pFill)
        canvas.drawPath(shape, pInk)
        // the letter's own ink centred on the shield, a touch above the middle where the shield is widest
        pText.textSize = 46f
        pText.getTextBounds(tier, 0, tier.length, glyph)
        val cx = box.centerX(); val cy = box.top + box.height() * 0.46f
        canvas.drawText(tier, cx - glyph.exactCenterX(), cy - glyph.exactCenterY(), pText)
        canvas.restore()
    }
}

/** Effort as a row of little tilted dice faces. */
class DiceView(c: Context, var value: Int, var max: Int = 5, var color: Int = C.RASP) : View(c) {
    private val pInk = Ink.inkPaint(c, c.dpf(1.8f))
    private val pFill = Ink.fillPaint(color)
    var onPick: ((Int) -> Unit)? = null
    override fun onDraw(canvas: Canvas) {
        val cell = height.toFloat()
        val gap = cell * 0.28f
        val sz = cell * 0.82f
        for (i in 0 until max) {
            canvas.save()
            val x = i * (sz + gap) + sz / 2 + cell * 0.08f
            canvas.translate(x, cell / 2)
            canvas.rotate(if (i % 2 == 0) -5f else 6f)
            val r = RectF(-sz / 2, -sz / 2, sz / 2, sz / 2)
            if (i < value) { pFill.color = color; canvas.drawRoundRect(r, sz * 0.26f, sz * 0.26f, pFill) }
            canvas.drawRoundRect(r, sz * 0.26f, sz * 0.26f, pInk)
            canvas.restore()
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val sz = h * 0.82f; val gap = h * 0.28f
        setMeasuredDimension((max * (sz + gap) + h * 0.2f).toInt(), h)
    }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val pick = onPick ?: return false
        if (e.action == android.view.MotionEvent.ACTION_UP) {
            val cell = height * 1.1f
            val v = Math.max(1, Math.min(max, (e.x / cell).toInt() + 1))
            value = v; invalidate(); pick(v)
        }
        return true
    }
}

/** Striped fame bar inside an ink outline. */
class FameBar(c: Context, var progress: Float) : View(c) {
    private val pInk = Ink.inkPaint(c, c.dpf(2f))
    private val pFill = Ink.fillPaint(C.CARD)
    private val pStripe = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        val inset = context.dpf(1.2f)
        val box = RectF(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(box, r, r, pFill)
        val stripe = context.dpf(6f)
        pStripe.shader = android.graphics.LinearGradient(0f, 0f, stripe, stripe, intArrayOf(C.RASP, C.RASP, C.RASP_LIGHT, C.RASP_LIGHT),
            floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.REPEAT)
        canvas.save()
        val clip = Path(); clip.addRoundRect(box, r, r, Path.Direction.CW); canvas.clipPath(clip)
        canvas.drawRect(box.left, box.top, box.left + box.width() * Math.max(0f, Math.min(1f, progress)), box.bottom, pStripe)
        canvas.restore()
        canvas.drawRoundRect(box, r, r, pInk)
    }
}

/** Cream paper with a faint grain, used as every screen's background. */
class PaperDrawable(c: Context, private val base: Int = C.PAPER) : Drawable() {
    private val paint = Paint()
    init {
        paint.shader = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    override fun draw(canvas: Canvas) {
        canvas.drawColor(base)
        canvas.drawRect(bounds, paint)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private var tile: Bitmap? = null
        fun grain(): Bitmap {
            val t = tile
            if (t != null) return t
            val n = 160
            val px = IntArray(n * n)
            val rnd = java.util.Random(7)
            for (i in px.indices) {
                val a = (rnd.nextFloat() * rnd.nextFloat() * 34).toInt()
                px[i] = (a shl 24) or 0x8C6B4D
            }
            val b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
            tile = b
            return b
        }
    }
}

/** Torn-paper edge (paper colour below a ragged line), laid over the bottom of hero photos. */
class TornEdge(c: Context, private val color: Int = C.PAPER) : View(c) {
    private val p = Ink.fillPaint(color)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val path = Path()
        path.moveTo(0f, h * 0.5f)
        val rnd = java.util.Random(11)
        var x = 0f
        while (x < w) {
            x += context.dpf(4f + rnd.nextFloat() * 6f)
            path.lineTo(Math.min(x, w), h * (0.3f + rnd.nextFloat() * 0.4f))
        }
        path.lineTo(w, h); path.lineTo(0f, h); path.close()
        canvas.drawPath(path, p)
    }
}

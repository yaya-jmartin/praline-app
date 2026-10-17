package com.yaya.praline

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Praliné palette: cream paper, brown ink, bright cookbook colours. */
object C {
    const val PAPER = 0xFFFBF0DE.toInt()
    const val CARD = 0xFFFFF9EE.toInt()
    const val INK = 0xFF2A1A14.toInt()
    const val INK2 = 0xFF6B4F42.toInt()
    const val FAINT = 0xFFB89E84.toInt()
    const val DASH = 0xFFCDB79C.toInt()
    const val RASP = 0xFFF0547A.toInt()
    const val RASP_LIGHT = 0xFFF77B98.toInt()
    const val PIST = 0xFFA6CF4A.toInt()
    const val BUTTER = 0xFFFFC93C.toInt()
    const val BLUE = 0xFF6A6CF5.toInt()
    const val LILAC = 0xFFC49BFF.toInt()
    const val MINT = 0xFF5ED3B2.toInt()
    const val TANG = 0xFFFF8A4C.toInt()
    const val CARAMEL = 0xFFC77A3A.toInt()
    const val TIMER = 0xFFB6F36A.toInt()
    const val TIMER_BG = 0xFF1E2A12.toInt()
    const val TIMER_BTN = 0xFF2C3C1B.toInt()
    const val TIMER_SOFT = 0xFFCFE7A6.toInt()
    const val STAT_RED = 0xFFB23A2C.toInt()
    const val STAT_TEXT = 0xFF7A2A20.toInt()
    const val NIGHT = 0xFF120D18.toInt()
    const val NIGHT_TEXT = 0xFFF3E6D3.toInt()
    const val NIGHT_MUTED = 0xFFCBB8A6.toInt()
    const val BEIGE = 0xFFE8D9C2.toInt()
    /** Engineer gear: brass plates on steel. */
    const val BRASS = 0xFFE0A93E.toInt()
    const val STEEL = 0xFF4E5D6C.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}

object F {
    private val cache = HashMap<String, Typeface>()
    private fun load(c: Context, file: String, fallback: Typeface): Typeface {
        val hit = cache[file]
        if (hit != null) return hit
        val tf = try { Typeface.createFromAsset(c.assets, "fonts/$file.ttf") } catch (e: Exception) { fallback }
        cache[file] = tf
        return tf
    }
    fun script(c: Context) = load(c, "meaculpa", Typeface.create("cursive", Typeface.NORMAL))
    fun regular(c: Context) = load(c, "fraunces_regular", Typeface.SERIF)
    fun semibold(c: Context) = load(c, "fraunces_semibold", Typeface.create(Typeface.SERIF, Typeface.BOLD))
    fun black(c: Context) = load(c, "fraunces_black", Typeface.create(Typeface.SERIF, Typeface.BOLD))
    fun italic(c: Context) = load(c, "fraunces_italic", Typeface.create(Typeface.SERIF, Typeface.ITALIC))
    fun light(c: Context) = load(c, "fraunces_light", Typeface.SERIF)
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)
fun LinearLayout.LayoutParams.m(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams { setMargins(l, t, r, b); return this }
fun flp(w: Int = MATCH, h: Int = WRAP, gravity: Int = Gravity.TOP or Gravity.START): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity)
fun FrameLayout.LayoutParams.m(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): FrameLayout.LayoutParams { setMargins(l, t, r, b); return this }

fun Context.vbox(): LinearLayout { val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; return l }
fun Context.hbox(): LinearLayout { val l = LinearLayout(this); l.orientation = LinearLayout.HORIZONTAL; l.gravity = Gravity.CENTER_VERTICAL; return l }
fun Context.space(w: Int = 0, h: Int = 0): View { val v = View(this); v.layoutParams = LinearLayout.LayoutParams(w, h); return v }

/** Body text in Fraunces. */
fun Context.body(s: CharSequence, size: Float = 15f, color: Int = C.INK, weight: Int = 400): TextView {
    val t = TextView(this)
    t.text = s
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    t.setTextColor(color)
    t.typeface = when {
        weight >= 800 -> F.black(this)
        weight >= 600 -> F.semibold(this)
        weight == 300 -> F.light(this)
        weight < 0 -> F.italic(this)
        else -> F.regular(this)
    }
    t.includeFontPadding = true
    return t
}

/** Italic notes. */
fun Context.note(s: CharSequence, size: Float = 12.5f, color: Int = C.INK2): TextView = body(s, size, color, -1)

/** The lettering font, for titles. Extra padding so loops and descenders never get clipped. */
fun Context.script(s: CharSequence, size: Float = 34f, color: Int = C.INK): TextView {
    val t = TextView(this)
    t.text = s
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    t.setTextColor(color)
    t.typeface = F.script(this)
    t.setLineSpacing(0f, 1.05f)
    t.setPadding(dp(size * 0.1f), dp(size * 0.1f), dp(size * 0.14f), dp(size * 0.08f))
    return t
}

/** Small spaced capitals. */
fun Context.label(s: String, color: Int = C.INK2, size: Float = 10.5f): TextView {
    val t = body(s.uppercase(), size, color, 600)
    t.letterSpacing = 0.14f
    return t
}

fun TextView.single(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }

fun View.onClick(f: () -> Unit): View {
    setOnClickListener(object : View.OnClickListener { override fun onClick(v: View?) { f() } })
    return this
}

fun View.onLongClick(f: () -> Unit): View {
    setOnLongClickListener(object : View.OnLongClickListener { override fun onLongClick(v: View?): Boolean { f(); return true } })
    return this
}

/** Squash a little on touch, like pressing a paper button. */
fun View.pressable(): View {
    setOnTouchListener(object : View.OnTouchListener {
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            return false
        }
    })
    return this
}

fun fmtClock(totalSec: Long): String {
    val s = Math.max(0L, totalSec)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

fun fmtDuration(min: Int): String = when {
    min <= 0 -> "?"
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60}"
}

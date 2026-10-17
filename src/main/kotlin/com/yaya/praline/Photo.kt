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
 * Keeping saves the untouched original and the filtered picture into the photos folder,
 * then hands both names back through [onKeep].
 */
class PhotoScreen(a: MainActivity, val file: File, startKey: String, startStrength: Float,
                  val onKeep: (orig: String, filtered: String, key: String, strength: Float) -> Unit) : Screen(a) {
    override val dark = true
    var key = startKey
    var strength = startStrength
    private var source: Bitmap? = null     // ~1600 px, upright
    private var small: Bitmap? = null      // ~720 px, for the live preview
    private var thumb: Bitmap? = null
    private var preview: RoundPhoto? = null
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
        thumbs.clear(); labels.clear()

        val top = a.hbox()
        val back = a.roundIcon("x", 38, C.CARD, 15)
        back.onClick { a.pop() }
        top.addView(back, lp(a.dp(38), a.dp(38)))
        val title = a.script("Cast a light", 38f, C.NIGHT_TEXT)
        title.gravity = Gravity.CENTER
        top.addView(title, lp(0, WRAP, 1f))
        top.addView(a.space(a.dp(38), 1))
        col.addView(top)

        val pv = RoundPhoto(a, 20f)
        pv.placeholderIcon = "cam"; pv.placeholderColor = C.LILAC
        preview = pv
        col.addView(pv, lp(MATCH, 0, 1f).m(t = a.dp(8), b = a.dp(12)))

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
                Photos.post { if (g == generation || quality == 2) { preview?.bitmap = out; preview?.invalidate() } }
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
                    onKeep(origName, outName, k, st)
                    if (a.top() === this@PhotoScreen) a.pop()
                }
            }
        })
    }
}

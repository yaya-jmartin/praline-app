package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * The painted world map. The scenery is a set of pre-painted slices (assets/missions/<world>/bg_*.jpg); everything
 * that changes (the lit trail, the dusk ahead, the spots, the lock, the party) and the small effects (sun rays,
 * the Calice's beam, forge sparks, the campfire, water glints, drifting sand) are drawn live on top.
 *
 * Coordinates in map.json are in "map px" for a 412-wide map; [s] scales them to this view.
 * Only the slices near the visible part of the map are kept in memory.
 */
class MissionMap(c: Context, val dir: String, var progress: Missions.Progress) : View(c) {
    private val json: JSONObject = try { JSONObject(c.assets.open("$dir/map.json").bufferedReader().use { it.readText() }) } catch (e: Exception) { JSONObject() }
    val mapW = json.optDouble("width", 412.0).toFloat()
    val mapH = json.optDouble("height", 3150.0).toFloat()
    private var s = 1f

    private val trailX: FloatArray
    private val trailY: FloatArray
    private val trailLen: FloatArray   // cumulative length at each point
    class SpotPos(val x: Float, val y: Float, val t: Float, val left: Boolean, val icon: String, val mx: Float, val my: Float, val poleX: Float, val poleTop: Float, val poleBottom: Float)
    val spots = ArrayList<SpotPos>()

    /** What the screen shows when a spot is tapped. */
    var onSpot: ((Int) -> Unit)? = null
    /** The part of the map on screen (set by the scrolling parent), in view px. */
    var viewTop = 0; var viewH = 0

    // pictures
    private class Slice(val file: String, val top: Float, val h: Float) { var bmp: Bitmap? = null; var loading = false }
    private val slices = ArrayList<Slice>()
    private val sprites = HashMap<String, Bitmap?>()

    // paints
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).also { it.textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()
    private val started = SystemClock.uptimeMillis()

    init {
        val tr = json.optJSONArray("trail")
        val n = tr?.length() ?: 0
        trailX = FloatArray(n); trailY = FloatArray(n); trailLen = FloatArray(n)
        for (i in 0 until n) {
            val p = tr!!.getJSONArray(i)
            trailX[i] = p.getDouble(0).toFloat(); trailY[i] = p.getDouble(1).toFloat()
            if (i > 0) trailLen[i] = trailLen[i - 1] + Math.hypot((trailX[i] - trailX[i - 1]).toDouble(), (trailY[i] - trailY[i - 1]).toDouble()).toFloat()
        }
        val sp = json.optJSONArray("spots")
        val along = FloatArray(sp?.length() ?: 0)
        for (i in 0 until (sp?.length() ?: 0)) {
            val o = sp!!.getJSONObject(i)
            val m = o.optJSONObject("medal") ?: JSONObject()
            val pole = o.optJSONObject("pole") ?: JSONObject()
            val x = o.optDouble("x").toFloat(); val y = o.optDouble("y").toFloat()
            spots.add(SpotPos(x, y, o.optDouble("t").toFloat(), o.optString("side") == "left", o.optString("icon"),
                m.optDouble("x", x.toDouble()).toFloat(), m.optDouble("y", (y - 34).toDouble()).toFloat(),
                pole.optDouble("x", (x - 14).toDouble()).toFloat(), pole.optDouble("top", (y - 64).toDouble()).toFloat(), pole.optDouble("bottom", y.toDouble()).toFloat()))
            along[i] = o.optDouble("t").toFloat()
        }
        Missions.spotAlong = along
        val sl = json.optJSONArray("slices")
        for (i in 0 until (sl?.length() ?: 0)) { val o = sl!!.getJSONObject(i); slices.add(Slice(o.getString("file"), o.getDouble("top").toFloat(), o.getDouble("height").toFloat())) }
        pText.typeface = F.semibold(c)
        isClickable = true
        contentDescription = "world map"
    }

    // ---- size and memory -----------------------------------------------------------------
    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm)
        s = w / mapW
        setMeasuredDimension(w, (mapH * s).toInt())
    }

    /** The y (view px) of the party, to scroll it into view. */
    fun partyY(): Int = (pointAt(progress.along)[1] * s).toInt()

    private fun sampleFor(widthPx: Int): Int = if (widthPx > 0 && widthPx * 2 <= 824) 2 else 1

    private fun sprite(name: String): Bitmap? {
        if (sprites.containsKey(name)) return sprites[name]
        val b = try {
            val o = BitmapFactory.Options()
            context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it, null, o) }
        } catch (e: Exception) { null }
        sprites[name] = b
        return b
    }

    /** Keeps the slices on or near the screen decoded; the rest are let go. */
    private fun keepSlices() {
        val top = (viewTop - viewH) / s; val bottom = (viewTop + 2 * viewH) / s
        for (sl in slices) {
            val near = sl.top + sl.h >= top && sl.top <= bottom
            if (near && sl.bmp == null && !sl.loading) {
                sl.loading = true
                val sample = sampleFor(width)
                Photos.run {
                    val b = try {
                        val o = BitmapFactory.Options(); o.inSampleSize = sample; o.inPreferredConfig = Bitmap.Config.RGB_565
                        context.assets.open("$dir/${sl.file}").use { BitmapFactory.decodeStream(it, null, o) }
                    } catch (e: Throwable) { null }
                    Photos.post { sl.bmp = b; sl.loading = false; invalidate() }
                }
            } else if (!near && sl.bmp != null) { sl.bmp?.recycle(); sl.bmp = null }
        }
    }

    /** Lets every picture go (leaving the map, or Android asking for memory). */
    fun release() {
        for (sl in slices) { sl.bmp?.recycle(); sl.bmp = null }
        for (b in sprites.values) b?.recycle()
        sprites.clear()
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); release() }

    // ---- trail geometry --------------------------------------------------------------------
    /** Point at [t] (0..1 of the trail's length), in map px. */
    fun pointAt(t: Float): FloatArray {
        val n = trailX.size
        if (n == 0) return floatArrayOf(mapW / 2, mapH / 2)
        val want = Math.max(0f, Math.min(1f, t)) * trailLen[n - 1]
        var i = 1
        while (i < n - 1 && trailLen[i] < want) i++
        val seg = Math.max(1e-3f, trailLen[i] - trailLen[i - 1])
        val f = Math.max(0f, Math.min(1f, (want - trailLen[i - 1]) / seg))
        return floatArrayOf(trailX[i - 1] + (trailX[i] - trailX[i - 1]) * f, trailY[i - 1] + (trailY[i] - trailY[i - 1]) * f)
    }

    private fun trailPath(from: Float, to: Float): Path {
        path.reset()
        val n = trailX.size
        if (n < 2) return path
        val total = trailLen[n - 1]
        val a = pointAt(from); path.moveTo(a[0] * s, a[1] * s)
        for (i in 0 until n) { val l = trailLen[i] / total; if (l > from && l < to) path.lineTo(trailX[i] * s, trailY[i] * s) }
        val b = pointAt(to); path.lineTo(b[0] * s, b[1] * s)
        return path
    }

    // ---- drawing --------------------------------------------------------------------------
    override fun onDraw(cv: Canvas) {
        if (viewH == 0) { viewH = resources.displayMetrics.heightPixels }
        keepSlices()
        val now = SystemClock.uptimeMillis() - started
        val visTop = (viewTop - 40 * s); val visBottom = (viewTop + viewH + 40 * s)

        // painted scenery
        for (sl in slices) {
            val b = sl.bmp ?: continue
            rect.set(0f, sl.top * s, width.toFloat(), (sl.top + sl.h) * s + 1)
            if (rect.bottom < visTop || rect.top > visBottom) continue
            cv.drawBitmap(b, null, rect, pBmp)
        }

        val party = pointAt(progress.along)
        // waiting at a spot, the party stands just before its dais so the medallion and the pill stay clear
        val stand = if (progress.waiting || progress.complete) pointAt(Math.max(0f, progress.along - 0.022f)) else party
        drawRays(cv, now)
        drawTrail(cv, party)
        drawFog(cv, party, now)
        drawForge(cv, now)
        drawCamp(cv, now)
        drawWater(cv, now)
        drawCapstone(cv, now)
        drawMotes(cv, now, visTop, visBottom)
        drawSpots(cv, now)
        drawLock(cv)
        drawParty(cv, stand, now)

        if (isAttachedToWindow && android.animation.ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
    }

    private fun fx(name: String): JSONObject? = json.optJSONObject("fx")?.optJSONObject(name)

    /** Warm shafts of light fanning down from the sun. */
    private fun drawRays(cv: Canvas, now: Long) {
        val r = fx("rays") ?: return
        val ox = r.optDouble("x").toFloat() * s; val oy = r.optDouble("y").toFloat() * s
        if (oy - 10 > viewTop + viewH) return
        val len = r.optDouble("length", 1500.0).toFloat() * s
        val a0 = r.optDouble("angle_from", 92.0).toFloat(); val a1 = r.optDouble("angle_to", 172.0).toFloat()
        pFill.shader = RadialGradient(ox, oy, len, intArrayOf(0x66FFE6A8, 0x22FFD27A, 0x00FFD27A), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        for (k in 0 until 7) {
            val mid = a0 + (a1 - a0) * (k + 0.5f) / 7f
            val wdg = 2.2f + (k % 3) * 1.1f
            val breathe = 0.55f + 0.45f * Math.sin(now / 2600.0 + k * 1.7).toFloat()
            pFill.alpha = (255 * breathe).toInt()
            path.reset(); path.moveTo(ox, oy)
            val ra = Math.toRadians((mid - wdg).toDouble()); val rb = Math.toRadians((mid + wdg).toDouble())
            path.lineTo(ox + (Math.cos(ra) * len).toFloat(), oy + (Math.sin(ra) * len).toFloat())
            path.lineTo(ox + (Math.cos(rb) * len).toFloat(), oy + (Math.sin(rb) * len).toFloat())
            path.close()
            cv.drawPath(path, pFill)
        }
        pFill.shader = null; pFill.alpha = 255
        // the sun's breathing halo
        val sun = fx("sun")
        if (sun != null) {
            val sx = sun.optDouble("x").toFloat() * s; val sy = sun.optDouble("y").toFloat() * s
            val hr = sun.optDouble("halo_r", 110.0).toFloat() * s * (0.92f + 0.08f * Math.sin(now / 1800.0).toFloat())
            pFill.shader = RadialGradient(sx, sy, hr, intArrayOf(0x88FFF2C0.toInt(), 0x33FFD27A, 0x00FFD27A), floatArrayOf(0.2f, 0.55f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(sx, sy, hr, pFill); pFill.shader = null
        }
    }

    /** Gold where the party has walked (with footprints), a dim dotted track ahead. */
    private fun drawTrail(cv: Canvas, party: FloatArray) {
        val at = progress.along
        if (at < 1f) {
            val ahead = trailPath(at, 1f)
            pStroke.pathEffect = null
            pStroke.color = 0x473A1440; pStroke.strokeWidth = 15 * s; cv.drawPath(ahead, pStroke)
            pStroke.pathEffect = DashPathEffect(floatArrayOf(1 * s, 7 * s), 0f)
            pStroke.color = 0x732A0E30; pStroke.strokeWidth = 1.6f * s; cv.drawPath(ahead, pStroke)
            pStroke.pathEffect = null
        }
        if (at > 0f) {
            val lit = trailPath(0f, at)
            pStroke.color = 0x40FFB030; pStroke.strokeWidth = 34 * s; cv.drawPath(lit, pStroke)
            pStroke.color = 0x55FFB030; pStroke.strokeWidth = 22 * s; cv.drawPath(lit, pStroke)
            pStroke.color = 0xBFFFD25A.toInt(); pStroke.strokeWidth = 13 * s; cv.drawPath(lit, pStroke)
            pStroke.color = 0xF2FFF6CC.toInt(); pStroke.strokeWidth = 3.2f * s; cv.drawPath(lit, pStroke)
            // footprints in two lanes
            val n = trailX.size
            if (n > 1) {
                val total = trailLen[n - 1]
                pFill.color = 0x8CA8580E.toInt()
                var d = 6f; var k = 0
                while (d < at * total - 10) {
                    val p = pointAt(d / total); val q = pointAt((d + 2) / total)
                    val dx = q[0] - p[0]; val dy = q[1] - p[1]; val l = Math.max(0.01f, Math.hypot(dx.toDouble(), dy.toDouble()).toFloat())
                    val nx = -dy / l; val ny = dx / l
                    val off = (if (k % 2 == 0) 6.5f else -6.5f) + (if (k % 4 < 2) 2f else -2f) * 0.5f
                    val fx = (p[0] + nx * off) * s; val fy = (p[1] + ny * off) * s
                    if (fy > viewTop - 20 && fy < viewTop + viewH + 20) { rect.set(fx - 2.3f * s, fy - 1.3f * s, fx + 2.3f * s, fy + 1.3f * s); cv.drawOval(rect, pFill) }
                    d += 9f; k++
                }
            }
        }
    }

    /** Dusk over the unexplored part of the map, deepening ahead of the party. */
    private fun drawFog(cv: Canvas, party: FloatArray, now: Long) {
        if (progress.complete) return
        val py = party[1]
        val top = 300f
        if (py <= top) return
        val y0 = py * s; val y1 = top * s
        val stops = floatArrayOf(0f, Math.min(0.99f, 250f / (py - top)), Math.min(0.995f, 420f / (py - top)), 0.92f, 1f)
        for (i in 1 until stops.size) if (stops[i] < stops[i - 1]) stops[i] = stops[i - 1]
        pFill.shader = LinearGradient(0f, y0, 0f, y1, intArrayOf(0x00462A78, 0x24462A78, 0x6B42246E, 0x3D50286E, 0x0050286E), stops, Shader.TileMode.CLAMP)
        cv.drawRect(0f, y1, width.toFloat(), y0, pFill)
        pFill.shader = null
        // slow wisps just past the next spot
        val nx = if (progress.done < spots.size) spots[progress.done] else return
        for (k in 0 until 2) {
            val wx = (nx.x + (if (k == 0) -60 else 70) + 30 * Math.sin(now / 14000.0 * 6.28 + k).toFloat()) * s
            val wy = (nx.y - 90 - k * 60) * s
            pFill.shader = RadialGradient(wx, wy, 130 * s, intArrayOf(0x55D6BEF0, 0x00D6BEF0), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            rect.set(wx - 130 * s, wy - 35 * s, wx + 130 * s, wy + 35 * s)
            cv.drawOval(rect, pFill)
        }
        pFill.shader = null
    }

    /** The Calice's beam onto the anvil, the forge sparks and the kiln's glow. Brighter once the chapter is done. */
    private fun drawForge(cv: Canvas, now: Long) {
        val f = fx("flower_focus") ?: return
        val e = fx("beam_end") ?: return
        val fx0 = f.optDouble("x").toFloat() * s; val fy0 = f.optDouble("y").toFloat() * s
        val ex = e.optDouble("x").toFloat() * s; val ey = e.optDouble("y").toFloat() * s
        if (Math.min(fy0, ey) - 100 * s > viewTop + viewH || Math.max(fy0, ey) + 100 * s < viewTop) return
        val lit = progress.complete
        val pulse = 0.75f + 0.25f * Math.sin(now / 420.0).toFloat()
        val boost = if (lit) 1.4f else 1f
        pStroke.color = 0x33FFC860; pStroke.strokeWidth = 22 * s * pulse * boost; cv.drawLine(fx0, fy0, ex, ey, pStroke)
        pStroke.color = 0x88FFE08A.toInt(); pStroke.strokeWidth = 8 * s * pulse * boost; cv.drawLine(fx0, fy0, ex, ey, pStroke)
        pStroke.color = 0xF0FFFFF0.toInt(); pStroke.strokeWidth = 2.6f * s * boost; cv.drawLine(fx0, fy0, ex, ey, pStroke)
        // focus point and impact flare
        pFill.shader = RadialGradient(fx0, fy0, 26 * s * pulse, intArrayOf(0xEEFFFFFF.toInt(), 0x00FFF0C0), null, Shader.TileMode.CLAMP)
        cv.drawCircle(fx0, fy0, 26 * s * pulse, pFill)
        pFill.shader = RadialGradient(ex, ey, 30 * s * pulse * boost, intArrayOf(0xEEFFF6D0.toInt(), 0x66FF8A30, 0x00FF6A20), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(ex, ey, 30 * s * pulse * boost, pFill)
        pFill.shader = null
        // sparks fly off the anvil
        for (k in 0 until 16) {
            val ph = ((now / 900.0 + k / 16.0) % 1.0).toFloat()
            val dir = ((k * 37) % 21 - 10) / 10f
            val x = ex + dir * 44 * s * ph
            val y = ey - 52 * s * ph + 38 * s * ph * ph
            pFill.color = if (k % 3 == 0) 0xFFFFF2B0.toInt() else 0xFFFFA040.toInt()
            pFill.alpha = (255 * (1 - ph)).toInt()
            cv.drawCircle(x, y, (1.6f - ph) * s, pFill)
        }
        pFill.alpha = 255
        val kg = fx("kiln_glow")
        if (kg != null) {
            val kx = kg.optDouble("x").toFloat() * s; val ky = kg.optDouble("y").toFloat() * s
            val r = 22 * s * (0.85f + 0.15f * Math.sin(now / 300.0).toFloat())
            pFill.shader = RadialGradient(kx, ky, r, intArrayOf(0xCCFFB040.toInt(), 0x00FF6A20), null, Shader.TileMode.CLAMP)
            cv.drawCircle(kx, ky, r, pFill); pFill.shader = null
        }
    }

    /** The caravan's campfire and the lanterns, flickering. */
    private fun drawCamp(cv: Canvas, now: Long) {
        val f = fx("campfire_flame")
        if (f != null) {
            val x = f.optDouble("x").toFloat() * s; val y = f.optDouble("y").toFloat() * s
            if (y > viewTop - 80 && y < viewTop + viewH + 80) {
                val fl = 0.85f + 0.15f * Math.sin(now / 90.0).toFloat() + 0.06f * Math.sin(now / 37.0).toFloat()
                pFill.shader = RadialGradient(x, y - 8 * s, 40 * s, intArrayOf(0x66FF9A30, 0x00FF7A20), null, Shader.TileMode.CLAMP)
                cv.drawCircle(x, y - 8 * s, 40 * s, pFill); pFill.shader = null
                flame(cv, x, y, 12 * s, 28 * s * fl, 0xFFFF8A2A.toInt())
                flame(cv, x, y, 7 * s, 18 * s * fl, 0xFFFFD25A.toInt())
            }
        }
        val ls = json.optJSONObject("fx")?.optJSONArray("lanterns") ?: return
        for (i in 0 until ls.length()) {
            val o = ls.getJSONObject(i)
            val x = o.optDouble("x").toFloat() * s; val y = o.optDouble("y").toFloat() * s
            if (y < viewTop - 40 || y > viewTop + viewH + 40) continue
            val r = 14 * s * (0.8f + 0.2f * Math.sin(now / 160.0 + i * 2).toFloat())
            pFill.shader = RadialGradient(x, y, r, intArrayOf(0xAAFFD27A.toInt(), 0x00FFB040), null, Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r, pFill); pFill.shader = null
        }
    }

    private fun flame(cv: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        path.reset()
        path.moveTo(x, y - h)
        path.cubicTo(x + w * 0.9f, y - h * 0.45f, x + w, y - w * 0.2f, x, y)
        path.cubicTo(x - w, y - w * 0.2f, x - w * 0.9f, y - h * 0.45f, x, y - h)
        pFill.color = color
        cv.drawPath(path, pFill)
    }

    /** Glints and slow ripples on the oasis pools. */
    private fun drawWater(cv: Canvas, now: Long) {
        val fxo = json.optJSONObject("fx") ?: return
        val ws = fxo.optJSONArray("water")
        if (ws != null) for (i in 0 until ws.length()) {
            val o = ws.getJSONObject(i)
            val x = o.optDouble("x").toFloat() * s; val y = o.optDouble("y").toFloat() * s
            val rx = o.optDouble("rx").toFloat() * s; val ry = o.optDouble("ry").toFloat() * s
            if (y + ry < viewTop || y - ry > viewTop + viewH) continue
            val ph = ((now / 3200.0 + i * 0.5) % 1.0).toFloat()
            pStroke.color = 0xFFFFFFFF.toInt(); pStroke.alpha = (110 * (1 - ph)).toInt(); pStroke.strokeWidth = 1.2f * s
            rect.set(x - rx * 0.5f * ph, y - ry * 0.5f * ph, x + rx * 0.5f * ph, y + ry * 0.5f * ph)
            cv.drawOval(rect, pStroke)
            pStroke.alpha = 255
        }
        val gs = fxo.optJSONArray("glints") ?: return
        for (i in 0 until gs.length()) {
            val o = gs.getJSONObject(i)
            val x = o.optDouble("x").toFloat() * s; val y = o.optDouble("y").toFloat() * s
            if (y < viewTop - 20 || y > viewTop + viewH + 20) continue
            val a = Math.max(0.0, Math.sin(now / 700.0 + i * 1.9)).toFloat()
            if (a < 0.05f) continue
            val r = 5 * s * a
            pStroke.color = 0xFFFFFFFF.toInt(); pStroke.alpha = (230 * a).toInt(); pStroke.strokeWidth = 1.2f * s
            cv.drawLine(x - r, y, x + r, y, pStroke); cv.drawLine(x, y - r, x, y + r, pStroke)
            pStroke.alpha = 255
        }
    }

    /** The pyramid's capstone, floating. */
    private fun drawCapstone(cv: Canvas, now: Long) {
        val o = fx("capstone") ?: return
        val y = o.optDouble("y").toFloat() * s
        if (y < viewTop - 120 || y > viewTop + viewH + 60) return
        val b = sprite(o.optString("png", "capstone.png")) ?: return
        val bob = 7 * s * Math.sin(now / 5000.0 * 6.283).toFloat()
        drawSprite(cv, b, o.optDouble("x").toFloat() * s, y + bob, 64f, 124f)
    }

    /** Sand motes and sparkles drifting over the map. */
    private fun drawMotes(cv: Canvas, now: Long, top: Float, bottom: Float) {
        for (k in 0 until 70) {
            val bx = ((k * 97) % 412).toFloat()
            val by = ((k * 431) % mapH.toInt()).toFloat()
            val t = now / 1000f
            val x = (bx + 18 * Math.sin(t / 3.0 + k).toFloat() + (t * 6 + k * 11) % 40 - 20) * s
            val y = (by - (t * 4 + k * 7) % 60) * s
            if (y < top || y > bottom) continue
            val a = 0.35f + 0.35f * Math.sin(t * 1.3 + k * 2.1).toFloat()
            pFill.color = if (k % 5 == 0) 0xFFBFF5FF.toInt() else 0xFFFFE9B8.toInt()
            pFill.alpha = (255 * Math.max(0f, a)).toInt()
            cv.drawCircle(x, y, (if (k % 5 == 0) 1.6f else 1.1f) * s, pFill)
        }
        pFill.alpha = 255
    }

    /** Draws a sprite painted at 2x of map px, anchored at (ax, ay) sprite px, scaled by [scale]. */
    private fun drawSprite(cv: Canvas, b: Bitmap, x: Float, y: Float, ax: Float, ay: Float, alpha: Int = 255, scale: Float = 1f) {
        val k = s / 2f * scale
        val w = b.width * k; val h = b.height * k
        val sx = ax * k; val sy = ay * k
        rect.set(x - sx, y - sy, x - sx + w, y - sy + h)
        pBmp.alpha = alpha
        cv.drawBitmap(b, null, rect, pBmp)
        pBmp.alpha = 255
    }


    private fun drawSpots(cv: Canvas, now: Long) {
        val done = progress.done
        for (i in spots.indices) {
            val sp = spots[i]
            val y = sp.y * s
            if (y < viewTop - 140 * s || y > viewTop + viewH + 60 * s) continue
            val state = if (i < done) 0 else if (i == done) 1 else 2
            val x = sp.x * s
            if (state == 1) {
                val g = 60 * s * (0.85f + 0.27f * (0.5f + 0.5f * Math.sin(now / 2600.0 * 6.283).toFloat()))
                pFill.shader = RadialGradient(x, y, g, intArrayOf(0xBFFF96DC.toInt(), 0x59E0479E, 0x00E0479E), floatArrayOf(0f, 0.35f, 0.7f), Shader.TileMode.CLAMP)
                cv.drawCircle(x, y, g, pFill); pFill.shader = null
            }
            val dais = sprite(when (state) { 0 -> "dais_done.png"; 1 -> "dais_next.png"; else -> "dais_locked.png" })
            if (dais != null) drawSprite(cv, dais, x, y, 150f, 90f)
            // pennant pole and flag
            val px = sp.poleX * s
            pStroke.color = C.INK; pStroke.strokeWidth = 3.2f * s; cv.drawLine(px, sp.poleTop * s, px, sp.poleBottom * s, pStroke)
            pStroke.color = 0xFF8A5432.toInt(); pStroke.strokeWidth = 1.8f * s; cv.drawLine(px, sp.poleTop * s, px, sp.poleBottom * s, pStroke)
            val flag = when (state) { 0 -> C.BUTTER; 1 -> 0xFFE0479E.toInt(); else -> 0xFF8C7AA8.toInt() }
            val wave = 2 * s * Math.sin(now / 500.0 + i).toFloat()
            path.reset(); val ft = sp.poleTop * s
            path.moveTo(px, ft); path.lineTo(px + 24 * s, ft + 4 * s + wave); path.lineTo(px + 17 * s, ft + 9 * s); path.lineTo(px + 24 * s, ft + 14 * s + wave); path.lineTo(px, ft + 15 * s); path.close()
            pFill.color = flag; cv.drawPath(path, pFill)
            pStroke.color = C.INK; pStroke.strokeWidth = 1.2f * s; cv.drawPath(path, pStroke)
            // medallion
            val mx = sp.mx * s; val my = sp.my * s; val r = 17 * s
            pFill.color = if (state == 2) 0xFFEFE6D8.toInt() else C.CARD; cv.drawCircle(mx, my, r, pFill)
            val icon = sprite(sp.icon)
            if (icon != null) drawSprite(cv, icon, mx, my, 64f, 64f, if (state == 2) 140 else 255, 0.42f)
            pStroke.color = when (state) { 0 -> C.BUTTER; 1 -> 0xFFE0479E.toInt(); else -> 0xFF8C7AA8.toInt() }
            pStroke.strokeWidth = 3 * s; cv.drawCircle(mx, my, r, pStroke)
            pStroke.color = C.INK; pStroke.strokeWidth = 1.3f * s; cv.drawCircle(mx, my, r + 1.6f * s, pStroke)
            if (state == 0) {
                pFill.color = 0xFF5ED3B2.toInt(); cv.drawCircle(mx + 13 * s, my - 13 * s, 6 * s, pFill)
                pStroke.color = C.WHITE; pStroke.strokeWidth = 1.8f * s
                path.reset(); path.moveTo(mx + 10 * s, my - 13 * s); path.lineTo(mx + 12.5f * s, my - 10.5f * s); path.lineTo(mx + 16.5f * s, my - 15.5f * s)
                cv.drawPath(path, pStroke)
            }
            if (state == 2) {
                // a small padlock on locked medallions
                pFill.color = 0xFF6A5A70.toInt(); rect.set(mx + 8 * s, my - 20 * s, mx + 18 * s, my - 12 * s); cv.drawRoundRect(rect, 2 * s, 2 * s, pFill)
                pStroke.color = 0xFF6A5A70.toInt(); pStroke.strokeWidth = 1.6f * s; rect.set(mx + 9.5f * s, my - 25 * s, mx + 16.5f * s, my - 17 * s); cv.drawArc(rect, 180f, 180f, false, pStroke)
            }
            // label
            label(cv, i, sp, state)
            if (state == 1) {
                val w = 54 * s; val h = 15 * s; val cy = my - 28 * s
                rect.set(mx - w / 2, cy - h / 2, mx + w / 2, cy + h / 2)
                pFill.color = 0xFFE0479E.toInt(); cv.drawRoundRect(rect, 7 * s, 7 * s, pFill)
                pStroke.color = C.INK; pStroke.strokeWidth = 1.2f * s; cv.drawRoundRect(rect, 7 * s, 7 * s, pStroke)
                pText.typeface = F.black(context); pText.textSize = 7.5f * s; pText.color = C.WHITE; pText.letterSpacing = 0.1f
                cv.drawText("MUST COOK", mx, cy + 2.7f * s, pText)
                pText.letterSpacing = 0f; pText.typeface = F.semibold(context)
            }
        }
    }

    private fun label(cv: Canvas, i: Int, sp: SpotPos, state: Int) {
        val name = Missions.SUNFORGE.spots.getOrNull(i)?.label ?: return
        pText.textSize = 10 * s
        val w = pText.measureText(name) + 14 * s
        val h = 17 * s
        val lx = if (sp.left) sp.x * s - 24 * s - w else sp.x * s + 24 * s
        val ly = sp.y * s - 6.5f * s
        rect.set(lx + 2.5f * s, ly + 2.5f * s, lx + w + 2.5f * s, ly + h + 2.5f * s)
        pFill.color = when (state) { 0 -> C.BUTTER; 1 -> 0xFFE0479E.toInt(); else -> 0xFFA898C0.toInt() }
        cv.drawRoundRect(rect, 7 * s, 7 * s, pFill)
        rect.set(lx, ly, lx + w, ly + h)
        pFill.color = when (state) { 0 -> 0xFFFFF3D0.toInt(); 1 -> 0xFFFFF0F7.toInt(); else -> 0xFFEFE6D8.toInt() }
        cv.drawRoundRect(rect, 7 * s, 7 * s, pFill)
        pStroke.color = C.INK; pStroke.strokeWidth = 1.5f * s; cv.drawRoundRect(rect, 7 * s, 7 * s, pStroke)
        pText.color = if (state == 2) 0xFF6A5A70.toInt() else C.INK
        cv.drawText(name, lx + w / 2, ly + 12.2f * s, pText)
    }

    /** The chained lock across the trail just past the next spot. */
    private fun drawLock(cv: Canvas) {
        if (progress.complete || progress.done >= spots.size) return
        val t = Math.min(1f, spots[progress.done].t + 0.045f)
        if (t >= 0.999f) return
        val p = pointAt(t)
        val b = sprite("lock.png") ?: return
        drawSprite(cv, b, p[0] * s, p[1] * s, 80f, 80f)
    }

    /** The two chefs' figurines, bobbing, with a soft ring under them. */
    private fun drawParty(cv: Canvas, p: FloatArray, now: Long) {
        val x = p[0] * s; val y = p[1] * s
        if (y < viewTop - 120 || y > viewTop + viewH + 60) return
        val ph = ((now / 1800.0) % 1.0).toFloat()
        pStroke.color = 0xFFFFD25A.toInt(); pStroke.alpha = (200 * (1 - ph)).toInt(); pStroke.strokeWidth = 2 * s
        rect.set(x - (22 + 20 * ph) * s, y - (8 + 7 * ph) * s, x + (22 + 20 * ph) * s, y + (8 + 7 * ph) * s)
        cv.drawOval(rect, pStroke); pStroke.alpha = 255
        val two = Store.peer != null
        val a = sprite("chef_a.png"); val b = sprite("chef_b.png")
        val bob1 = 2.5f * s * Math.sin(now / 2200.0 * 6.283).toFloat()
        val bob2 = 2.5f * s * Math.sin(now / 2200.0 * 6.283 + 1.4).toFloat()
        if (two) {
            if (a != null) drawSprite(cv, a, x - 16 * s, y + bob1, 72f, 132f)
            if (b != null) drawSprite(cv, b, x + 16 * s, y + 6 * s + bob2, 72f, 132f)
        } else if (a != null) drawSprite(cv, a, x, y + bob1, 72f, 132f)
    }

    // ---- taps ---------------------------------------------------------------------------
    private var downX = 0f; private var downY = 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < 20 * s) {
                    val hit = spotAt(e.x / s, e.y / s)
                    if (hit >= 0) { onSpot?.invoke(hit); return true }
                }
            }
        }
        return true
    }

    /** The spot under a tap (on its dais, medallion or label), or -1. */
    fun spotAt(mx: Float, my: Float): Int {
        var best = -1; var bd = 40f
        for (i in spots.indices) {
            val sp = spots[i]
            val d = Math.min(Math.hypot((mx - sp.x).toDouble(), (my - sp.y).toDouble()), Math.hypot((mx - sp.mx).toDouble(), (my - sp.my).toDouble())).toFloat()
            if (d < bd) { bd = d; best = i }
        }
        return best
    }
}

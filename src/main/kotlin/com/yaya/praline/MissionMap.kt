package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The party mission map: a [SceneView] playing a world pack, with the mission's own layer on top of the scenery
 * and its effects: the trail (gold where the party walked, dim ahead), the dusk over the unexplored part, the spots
 * (dais, pennant, medallion, label, MUST COOK), the lock past the next spot and the party's figures.
 *
 * The pack gives the trail and the spots; the mission chrome (dais, lock, figures) is the app's own
 * (assets/missions/chrome/), the same in every world. Positions are in map units.
 */
class MissionMap(c: Context, world: WorldPack.World, var progress: Missions.Progress) : SceneView(c, world) {
    val mapW get() = world.width
    val mapH get() = world.height

    private val trailX = world.trailX
    private val trailY = world.trailY
    private val trailLen = FloatArray(trailX.size)   // cumulative length at each point
    val spots get() = world.spots

    /** What the screen shows when a spot is tapped (its index). */
    var onSpot: ((Int) -> Unit)? = null
    /** How many chefs walk the trail (1..4). */
    var chefs = if (Store.peer != null) 2 else 1

    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).also { it.textAlign = Paint.Align.CENTER }
    private val path = Path()

    init {
        for (i in 1 until trailX.size) trailLen[i] = trailLen[i - 1] + Math.hypot((trailX[i] - trailX[i - 1]).toDouble(), (trailY[i] - trailY[i - 1]).toDouble()).toFloat()
        val along = FloatArray(world.spots.size)
        for (i in along.indices) along[i] = world.spots[i].along
        Missions.spotAlong = along
        pText.typeface = F.semibold(c)
        lit = progress.complete
        contentDescription = "world map"
    }

    /** The y (view px) of the party, to scroll it into view. */
    fun partyY(): Int = (pointAt(progress.along)[1] * s).toInt()

    // ---- trail geometry -------------------------------------------------------------------------
    /** Point at [t] (0..1 of the trail's length), in map units. */
    fun pointAt(t: Float): FloatArray {
        val n = trailX.size
        if (n == 0) return floatArrayOf(mapW / 2, mapH / 2)
        if (n == 1) return floatArrayOf(trailX[0], trailY[0])
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
        val total = Math.max(1e-3f, trailLen[n - 1])
        val a = pointAt(from); path.moveTo(a[0] * s, a[1] * s)
        for (i in 0 until n) { val l = trailLen[i] / total; if (l > from && l < to) path.lineTo(trailX[i] * s, trailY[i] * s) }
        val b = pointAt(to); path.lineTo(b[0] * s, b[1] * s)
        return path
    }

    // ---- the mission layer ----------------------------------------------------------------------
    override fun drawStage(cv: Canvas) {
        lit = progress.complete
        val party = pointAt(progress.along)
        // waiting at a spot, the party stands just before its dais so the medallion and the pill stay clear
        val stand = if (progress.waiting || progress.complete) pointAt(Math.max(0f, progress.along - 0.022f)) else party
        drawTrail(cv)
        drawFog(cv, party)
        drawSpots(cv)
        drawLock(cv)
        drawParty(cv, stand)
    }

    /** Gold where the party has walked (with footprints), a dim dotted track ahead. */
    private fun drawTrail(cv: Canvas) {
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
    private fun drawFog(cv: Canvas, party: FloatArray) {
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

    /** A chrome sprite painted at 2× map units, anchored at (ax, ay) sprite px, scaled by [scale]. */
    private fun drawChrome(cv: Canvas, b: Bitmap, x: Float, y: Float, ax: Float, ay: Float, alpha: Int = 255, scale: Float = 1f) {
        val k = s / 2f * scale
        rect.set(x - ax * k, y - ay * k, x - ax * k + b.width * k, y - ay * k + b.height * k)
        pBmp.alpha = alpha
        cv.drawBitmap(b, null, rect, pBmp)
        pBmp.alpha = 255
    }

    private fun chrome(name: String): Bitmap? = appPicture("missions/chrome/$name")

    /** Medallion position for a spot (the pack gives the spot; the app places its chrome around it). */
    private fun medalY(sp: WorldPack.Spot) = sp.y - 34
    private fun poleX(sp: WorldPack.Spot) = sp.x - 14

    private fun drawSpots(cv: Canvas) {
        val done = progress.done
        for (i in spots.indices) {
            val sp = spots[i]
            if (!onScreen(sp.y - 140, sp.y + 60, 0f)) continue
            val state = if (i < done) 0 else if (i == done) 1 else 2
            val x = sp.x * s; val y = sp.y * s
            if (state == 1) {
                val g = 60 * s * (0.85f + 0.27f * (0.5f + 0.5f * Math.sin(now / 2600.0 * 6.283).toFloat()))
                pFill.shader = RadialGradient(x, y, g, intArrayOf(0xBFFF96DC.toInt(), 0x59E0479E, 0x00E0479E), floatArrayOf(0f, 0.35f, 0.7f), Shader.TileMode.CLAMP)
                cv.drawCircle(x, y, g, pFill); pFill.shader = null
            }
            val dais = chrome(when (state) { 0 -> "dais_done.png"; 1 -> "dais_next.png"; else -> "dais_locked.png" })
            if (dais != null) drawChrome(cv, dais, x, y, 150f, 90f)
            // pennant pole and flag
            val px = poleX(sp) * s; val ft = (sp.y - 64) * s; val fb = (sp.y + 2) * s
            pStroke.color = C.INK; pStroke.strokeWidth = 3.2f * s; cv.drawLine(px, ft, px, fb, pStroke)
            pStroke.color = 0xFF8A5432.toInt(); pStroke.strokeWidth = 1.8f * s; cv.drawLine(px, ft, px, fb, pStroke)
            val flag = when (state) { 0 -> C.BUTTER; 1 -> 0xFFE0479E.toInt(); else -> 0xFF8C7AA8.toInt() }
            val wave = 2 * s * Math.sin(now / 500.0 + i).toFloat()
            path.reset()
            path.moveTo(px, ft); path.lineTo(px + 24 * s, ft + 4 * s + wave); path.lineTo(px + 17 * s, ft + 9 * s); path.lineTo(px + 24 * s, ft + 14 * s + wave); path.lineTo(px, ft + 15 * s); path.close()
            pFill.color = flag; cv.drawPath(path, pFill)
            pStroke.color = C.INK; pStroke.strokeWidth = 1.2f * s; cv.drawPath(path, pStroke)
            // medallion
            val mx = x; val my = medalY(sp) * s; val r = 17 * s
            pFill.color = if (state == 2) 0xFFEFE6D8.toInt() else C.CARD; cv.drawCircle(mx, my, r, pFill)
            val lib = world.library[sp.icon]
            val icon = if (lib != null) picture(lib, 2 * r) else null
            if (icon != null) {
                rect.set(mx - r * 0.79f, my - r * 0.79f, mx + r * 0.79f, my + r * 0.79f)   // the 1.2 icon size: 128 px at 0.21 units per px
                pBmp.alpha = if (state == 2) 140 else 255; cv.drawBitmap(icon, null, rect, pBmp); pBmp.alpha = 255
            }
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
            label(cv, sp, state)
            if (state == 1 && !sp.story) {
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

    private fun label(cv: Canvas, sp: WorldPack.Spot, state: Int) {
        val name = sp.name
        if (name.isEmpty()) return
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

    /** The chained lock across the trail just past the next recipe spot. */
    private fun drawLock(cv: Canvas) {
        if (progress.complete || progress.done >= spots.size || spots[progress.done].story) return
        val t = Math.min(1f, spots[progress.done].along + 0.045f)
        if (t >= 0.999f) return
        val p = pointAt(t)
        val b = chrome("lock.png") ?: return
        drawChrome(cv, b, p[0] * s, p[1] * s, 80f, 80f)
    }

    /** The party's figurines (up to four), bobbing, with a soft ring under them. */
    private fun drawParty(cv: Canvas, p: FloatArray) {
        val x = p[0] * s; val y = p[1] * s
        if (y < viewTop - 120 || y > viewTop + viewH + 60) return
        val ph = ((now / 1800.0) % 1.0).toFloat()
        val n = Math.max(1, Math.min(4, chefs))
        val ring = 22 + 6 * (n - 1)
        pStroke.color = 0xFFFFD25A.toInt(); pStroke.alpha = (200 * (1 - ph)).toInt(); pStroke.strokeWidth = 2 * s
        rect.set(x - (ring + 20 * ph) * s, y - (8 + 7 * ph) * s, x + (ring + 20 * ph) * s, y + (8 + 7 * ph) * s)
        cv.drawOval(rect, pStroke); pStroke.alpha = 255
        val a = chrome("chef_a.png"); val b = chrome("chef_b.png")
        // side by side, the second one slightly forward, like the 1.2 pair
        val gap = if (n == 2) 32f else 22f
        for (k in 0 until n) {
            val fig = (if (k % 2 == 0) a else b) ?: continue
            val dx = (k - (n - 1) / 2f) * gap
            val dy = if (k % 2 == 1) 6f else 0f
            val bob = 2.5f * s * Math.sin(now / 2200.0 * 6.283 + k * 1.4).toFloat()
            drawChrome(cv, fig, x + dx * s, y + dy * s + bob, 72f, 132f)
        }
    }

    // ---- taps -----------------------------------------------------------------------------------
    override fun onTap(mx: Float, my: Float): Boolean {
        val hit = spotAt(mx, my)
        if (hit >= 0) { onSpot?.invoke(hit); return true }
        return false
    }

    /** The spot under a tap (on its dais or medallion), or -1. */
    fun spotAt(mx: Float, my: Float): Int {
        var best = -1; var bd = 40f
        for (i in spots.indices) {
            val sp = spots[i]
            val d = Math.min(Math.hypot((mx - sp.x).toDouble(), (my - sp.y).toDouble()), Math.hypot((mx - sp.x).toDouble(), (my - medalY(sp)).toDouble())).toFloat()
            if (d < bd) { bd = d; best = i }
        }
        return best
    }
}

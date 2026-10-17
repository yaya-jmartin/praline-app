package com.yaya.praline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Dungeon-light photo filters, after Yaya's isometric diorama references:
 * a two-tone gradient map (cool shadows, warm light), a torch-light pool,
 * bloom on the highlights, a vignette, soft tilt-shift edges and film grain.
 */
class PhotoFilter(val key: String, val name: String, val blurb: String, val ui: Int,
                  val shadow: Int, val mid: Int, val high: Int, val glow: Int, val contrast: Float, val sat: Float)

object Filters {
    val all = listOf(
        PhotoFilter("torch", "Torchlight", "night-blue shadows, torch-warm light", C.TANG, 0x121a33, 0xc2562c, 0xffe0a8, 0xff8a2a, 1.35f, 1.15f),
        PhotoFilter("grotto", "Grotto", "teal water glow", C.MINT, 0x04202e, 0x1f8a8c, 0xeafff6, 0x3dffd8, 1.3f, 1.0f),
        PhotoFilter("mimic", "Mimic", "purple shadows, warm light", C.LILAC, 0x1a0838, 0x9b3aa8, 0xffd2a6, 0xb84dff, 1.35f, 1.1f),
        PhotoFilter("tavern", "Tavern", "golden wood and candles", C.BUTTER, 0x2c1408, 0xd08a40, 0xfff3cc, 0xffb84d, 1.2f, 1.0f),
        PhotoFilter("moon", "Moonlit", "blue night, one warm candle", C.BLUE, 0x070c2a, 0x3d4fb0, 0xffe6b8, 0xffb46b, 1.3f, 0.9f))

    fun get(key: String): PhotoFilter? { for (f in all) if (f.key == key) return f; return null }

    private fun r(c: Int) = ((c shr 16) and 255) / 255f
    private fun g(c: Int) = ((c shr 8) and 255) / 255f
    private fun b(c: Int) = (c and 255) / 255f

    private fun blur(src: Bitmap, factor: Int): Bitmap {
        val w = Math.max(1, src.width / factor); val h = Math.max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val smaller = Bitmap.createScaledBitmap(small, Math.max(1, w / 2), Math.max(1, h / 2), true)
        val back = Bitmap.createScaledBitmap(smaller, w, h, true)
        return Bitmap.createScaledBitmap(back, src.width, src.height, true)
    }

    /** Applies [f] at [strength] (0 = original, 1 = full spell). */
    fun apply(src: Bitmap, f: PhotoFilter?, strength: Float): Bitmap {
        if (f == null || strength <= 0.01f) return src.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width; val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val sr = r(f.shadow); val sg = g(f.shadow); val sb = b(f.shadow)
        val mr = r(f.mid); val mg = g(f.mid); val mb = b(f.mid)
        val hr = r(f.high); val hg = g(f.high); val hb = b(f.high)
        val gr = r(f.glow); val gg = g(f.glow); val gb = b(f.glow)
        val outR = FloatArray(n); val outG = FloatArray(n); val outB = FloatArray(n)
        val brightPx = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            var cr = ((c shr 16) and 255) / 255f; var cg = ((c shr 8) and 255) / 255f; var cb = (c and 255) / 255f
            var l = cr * 0.299f + cg * 0.587f + cb * 0.114f
            cr = l + (cr - l) * f.sat; cg = l + (cg - l) * f.sat; cb = l + (cb - l) * f.sat
            cr = clamp((cr - 0.5f) * f.contrast + 0.5f); cg = clamp((cg - 0.5f) * f.contrast + 0.5f); cb = clamp((cb - 0.5f) * f.contrast + 0.5f)
            l = cr * 0.299f + cg * 0.587f + cb * 0.114f
            val tr: Float; val tg: Float; val tb: Float
            if (l < 0.5f) { val t = l / 0.5f; tr = sr + (mr - sr) * t; tg = sg + (mg - sg) * t; tb = sb + (mb - sb) * t }
            else { val t = (l - 0.5f) / 0.5f; tr = mr + (hr - mr) * t; tg = mg + (hg - mg) * t; tb = mb + (hb - mb) * t }
            outR[i] = cr * 0.25f + tr * 0.75f + (cr - l) * 0.45f
            outG[i] = cg * 0.25f + tg * 0.75f + (cg - l) * 0.45f
            outB[i] = cb * 0.25f + tb * 0.75f + (cb - l) * 0.45f
            val bright = clamp((l - 0.6f) / 0.4f)
            brightPx[i] = (255 shl 24) or ((cr * bright * 255).toInt() shl 16) or ((cg * bright * 255).toInt() shl 8) or (cb * bright * 255).toInt()
        }
        val bloomBmp = blur(Bitmap.createBitmap(brightPx, w, h, Bitmap.Config.ARGB_8888), Math.max(2, w / 36))
        val bloom = IntArray(n); bloomBmp.getPixels(bloom, 0, w, 0, 0, w, h)
        val res = IntArray(n)
        for (y in 0 until h) {
            val dy = (y - h * 0.48f) / (h / 2f)
            val py = (y - h * 0.35f) / (h * 0.55f)
            for (x in 0 until w) {
                val i = y * w + x
                val bc = bloom[i]
                val blr = ((bc shr 16) and 255) / 255f * 1.3f; val blg = ((bc shr 8) and 255) / 255f * 1.3f; val blb = (bc and 255) / 255f * 1.3f
                var cr = 1 - (1 - outR[i]) * (1 - blr * gr)
                var cg = 1 - (1 - outG[i]) * (1 - blg * gg)
                var cb = 1 - (1 - outB[i]) * (1 - blb * gb)
                val dx = (x - w / 2f) / (w / 2f)
                val px2 = (x - w * 0.38f) / (w * 0.55f)
                val pool = Math.exp(-(px2 * px2 + py * py).toDouble()).toFloat()
                cr = cr * (0.55f + 0.6f * pool) + gr * pool * 0.1f
                cg = cg * (0.55f + 0.6f * pool) + gg * pool * 0.1f
                cb = cb * (0.55f + 0.6f * pool) + gb * pool * 0.1f
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val v = Math.max(0.15f, Math.min(1f, 1 - (d - 0.45f) * 0.9f))
                cr = cr * v + sr * (1 - v) * 0.9f; cg = cg * v + sg * (1 - v) * 0.9f; cb = cb * v + sb * (1 - v) * 0.9f
                res[i] = (255 shl 24) or (to255(cr) shl 16) or (to255(cg) shl 8) or to255(cb)
            }
        }
        val graded = Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888)
        // tilt-shift: soften the top and bottom edges
        val soft = blur(graded, Math.max(2, w / 160))
        val sp = IntArray(n); soft.getPixels(sp, 0, w, 0, 0, w, h)
        val rnd = java.util.Random(1)
        for (y in 0 until h) {
            val m = clamp((Math.abs(y - h * 0.5f) / (h * 0.5f) - 0.45f) * 2.2f)
            for (x in 0 until w) {
                val i = y * w + x
                val a = res[i]; val s = sp[i]
                val grain = (rnd.nextGaussian() * 5).toFloat()
                val o = px[i]
                res[i] = (255 shl 24) or (mix(a, s, o, 16, m, grain, strength) shl 16) or
                        (mix(a, s, o, 8, m, grain, strength) shl 8) or mix(a, s, o, 0, m, grain, strength)
            }
        }
        return Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun mix(a: Int, s: Int, o: Int, sh: Int, m: Float, grain: Float, strength: Float): Int {
        val fa = ((a shr sh) and 255).toFloat(); val fs = ((s shr sh) and 255).toFloat()
        val filt = fa + (fs - fa) * m + grain
        val orig = ((o shr sh) and 255).toFloat()
        return Math.max(0, Math.min(255, (orig + (filt - orig) * strength).toInt()))
    }

    private fun clamp(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    private fun to255(v: Float) = Math.max(0, Math.min(255, (v * 255).toInt()))

    /** Loads a photo upright and no bigger than [maxSide]. */
    fun load(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = sample
        var bmp = BitmapFactory.decodeFile(file.path, o2) ?: return null
        val rot = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        val scale = Math.min(1f, maxSide.toFloat() / Math.max(bmp.width, bmp.height))
        if (rot != 0f || scale < 1f) {
            val m = Matrix(); m.postScale(scale, scale); m.postRotate(rot)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    fun save(bmp: Bitmap, file: File) {
        val out = FileOutputStream(file)
        try { bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) } finally { out.close() }
    }
}

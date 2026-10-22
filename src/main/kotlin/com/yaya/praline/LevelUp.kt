package com.yaya.praline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The level-up moment (1.4): the phone shakes, the screen flashes full white at full brightness, and a
 * magical chime rings. The chime is made on the phone (no sound file); it stays quiet when the phone is
 * on silent or vibrate, and the shake is skipped when the phone has no vibrator.
 */
object LevelUp {
    private const val RATE = 22050

    fun play(a: MainActivity) {
        shake(a)
        flash(a)
        chime(a)
    }

    fun shake(c: Context) {
        try {
            @Suppress("DEPRECATION")
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            val pattern = longArrayOf(0, 60, 50, 60, 50, 140)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        } catch (e: Exception) { }
    }

    /** A white veil over everything, the window at full brightness while it shows. */
    fun flash(a: MainActivity) {
        val veil = View(a)
        veil.setBackgroundColor(0xFFFFFFFF.toInt())
        veil.alpha = 0f
        veil.contentDescription = "level up flash"
        a.host.addView(veil, FrameLayout.LayoutParams(MATCH, MATCH))
        val w = a.window
        val before = w.attributes.screenBrightness
        fun bright(v: Float) { val p = w.attributes; p.screenBrightness = v; w.attributes = p }
        bright(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        veil.animate().alpha(1f).setDuration(140).withEndAction {
            veil.animate().alpha(0f).setStartDelay(260).setDuration(700).withEndAction {
                (veil.parent as? android.view.ViewGroup)?.removeView(veil)
                bright(before)
            }.start()
        }.start()
    }

    /** A rising sparkle: four bell notes and a shimmer on top. */
    fun samples(): ShortArray {
        val notes = doubleArrayOf(1046.5, 1318.5, 1568.0, 2093.0)
        val step = (RATE * 0.11).toInt()
        val ring = (RATE * 0.9).toInt()
        val n = step * (notes.size - 1) + ring
        val mix = DoubleArray(n)
        for ((k, f) in notes.withIndex()) {
            val start = k * step
            for (i in 0 until ring) {
                val t = i.toDouble() / RATE
                val env = Math.exp(-t * 5.0) * Math.min(1.0, t * 200.0)
                val s = Math.sin(2 * Math.PI * f * t) + 0.35 * Math.sin(2 * Math.PI * f * 2.0 * t) + 0.12 * Math.sin(2 * Math.PI * f * 3.01 * t)
                mix[start + i] += s * env
            }
        }
        // the shimmer: a soft high tremolo over the last notes
        for (i in step * 2 until n) {
            val t = (i - step * 2).toDouble() / RATE
            mix[i] += 0.18 * Math.sin(2 * Math.PI * 3136.0 * t) * Math.exp(-t * 3.5) * (0.5 + 0.5 * Math.sin(2 * Math.PI * 14 * t))
        }
        var peak = 1e-9; for (x in mix) peak = Math.max(peak, Math.abs(x))
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = (mix[i] / peak * 0.7 * Short.MAX_VALUE).toInt().toShort()
        return out
    }

    fun chime(c: Context) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        Thread(object : Runnable {
            override fun run() {
                try {
                    val pcm = samples()
                    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    val fmt = AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    val track = AudioTrack(attrs, fmt, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep(pcm.size * 1000L / RATE + 200)
                    track.release()
                } catch (e: Exception) { }
            }
        }).start()
    }
}

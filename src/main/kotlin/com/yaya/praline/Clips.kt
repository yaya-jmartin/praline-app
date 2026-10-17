package com.yaya.praline

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.VideoView
import java.io.File
import java.io.FileOutputStream

/** Short video clips on steps: stored next to the photos as .mp4 with a .mp4.jpg thumbnail. */
object Clips {
    /** Clips longer than this are refused, so the two phones can still swap them quickly. */
    const val MAX_BYTES = 60L * 1024 * 1024

    fun newName(): String = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".mp4"

    /** Copies [src] into the photos folder and makes its thumbnail. Returns the clip's name, or null. Runs off the main thread. */
    fun keep(a: MainActivity, src: File): String? {
        if (!src.exists() || src.length() == 0L || src.length() > MAX_BYTES) return null
        val name = newName()
        val dst = Store.photoFile(a, name)
        try { src.inputStream().use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } } } catch (e: Exception) { dst.delete(); return null }
        val thumb = frame(dst)
        if (thumb != null) try { Filters.save(thumb, Store.photoFile(a, "$name.jpg")) } catch (e: Throwable) { }
        return name
    }

    fun frame(f: File): Bitmap? {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(f.absolutePath)
            val b = m.getFrameAtTime(300_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = 640f / Math.max(b.width, b.height)
            if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        } catch (e: Throwable) { null } finally { try { m.release() } catch (e: Throwable) { } }
    }

    /** Thumbnail with a play mark. */
    fun thumb(a: MainActivity, name: String, sizeDp: Int): FrameLayout {
        val f = FrameLayout(a)
        val p = RoundPhoto(a, 12f); p.outline = true; p.placeholderIcon = "play"; p.placeholderColor = C.LILAC
        p.show("$name.jpg", 300)
        f.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        val play = a.roundIcon("play", Math.max(24, sizeDp / 2), C.CARD, Math.max(10, sizeDp / 5), 10f)
        f.addView(play, flp(a.dp(Math.max(24, sizeDp / 2)), a.dp(Math.max(24, sizeDp / 2)), Gravity.CENTER))
        f.contentDescription = "step clip"
        f.rotation = -2f
        return f
    }

    /** A muted clip that loops by itself (inside the quest's current step). */
    fun loop(a: MainActivity, name: String): View {
        val box = FrameLayout(a)
        box.background = InkBox(a, C.INK, C.LILAC, 16f, 2f, 3f, 5)
        val f = Store.photoFile(a, name)
        if (!f.exists()) { box.addView(thumb(a, name, 120), FrameLayout.LayoutParams(MATCH, MATCH)); return box }
        val v = VideoView(a)
        v.setVideoPath(f.absolutePath)
        v.setOnPreparedListener(object : android.media.MediaPlayer.OnPreparedListener {
            override fun onPrepared(mp: android.media.MediaPlayer) { mp.isLooping = true; mp.setVolume(0f, 0f); v.start() }
        })
        v.setOnErrorListener(object : android.media.MediaPlayer.OnErrorListener { override fun onError(mp: android.media.MediaPlayer?, w: Int, x: Int): Boolean = true })
        box.addView(v, flp(MATCH, MATCH, Gravity.CENTER).m(a.dp(4), a.dp(4), a.dp(7), a.dp(7)))
        box.contentDescription = "step clip"
        box.onClick { play(a, name) }
        return box
    }

    /** Plays the clip big, with sound, in a sheet. */
    fun play(a: MainActivity, name: String) {
        val s = Sheet(a, null)
        val f = Store.photoFile(a, name)
        if (!f.exists()) { a.toast("This clip hasn't arrived on this phone yet"); return }
        val v = VideoView(a)
        v.setVideoPath(f.absolutePath)
        v.setOnPreparedListener(object : android.media.MediaPlayer.OnPreparedListener { override fun onPrepared(mp: android.media.MediaPlayer) { mp.isLooping = true; v.start() } })
        v.setOnErrorListener(object : android.media.MediaPlayer.OnErrorListener { override fun onError(mp: android.media.MediaPlayer?, w: Int, x: Int): Boolean { a.toast("Couldn't play this clip"); return true } })
        val box = FrameLayout(a)
        box.addView(v, flp(MATCH, MATCH, Gravity.CENTER))
        s.body.addView(box, lp(MATCH, (a.resources.displayMetrics.heightPixels * 0.58f).toInt()))
        s.button("Close") { v.stopPlayback() }
        s.onDismiss { try { v.stopPlayback() } catch (e: Exception) { } }
        s.show()
    }
}

package com.yaya.praline

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.TextureView
import android.widget.FrameLayout
import java.io.File

/** Lets the camera app write the shot straight into Praliné's private cache. */
class PhotoProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.yaya.praline.photos"
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTH/shot/$name")
        fun shotFile(c: Context, name: String): File { val d = File(c.cacheDir, "shots"); d.mkdirs(); return File(d, name) }
    }
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw IllegalArgumentException()
        if (name.contains("/") || name.contains("..")) throw IllegalArgumentException()
        return shotFile(context!!, name)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(if (mode.contains("w")) "rwt" else "r"))
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor {
        val f = file(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }
    override fun getType(uri: Uri) = if ((uri.lastPathSegment ?: "").endsWith(".mp4")) "video/mp4" else "image/jpeg"
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}

/** Scale for a texture of [pw]x[ph] shown centre-cropped in a [vw]x[vh] view without distortion. */
fun cropMatrix(vw: Float, vh: Float, pw: Float, ph: Float): android.graphics.Matrix {
    val s = Math.max(vw / pw, vh / ph)
    val m = android.graphics.Matrix()
    m.setScale(pw * s / vw, ph * s / vh, vw / 2f, vh / 2f)
    return m
}

/**
 * Index of the camera preview size to use: the sharpest one up to 1080p with a short side of at least
 * 480 px, preferring the view's shape [want] (long side / short side) among similar sizes. -1 if none.
 */
fun pickPreviewSize(sizes: List<IntArray>, want: Float): Int {
    var best = -1; var bestScore = -Float.MAX_VALUE
    for (i in sizes.indices) {
        val w = sizes[i][0]; val h = sizes[i][1]
        if (w * h > 1920 * 1088) continue
        val short = Math.min(w, h); val long = Math.max(w, h)
        val shape = Math.abs(long.toFloat() / short - Math.max(want, 1f / Math.max(want, 0.01f)))
        // sharpness first (a tiny size can't read a code), the right shape only as a tie-break
        val score = (if (short >= 480) 10f else 0f) + (w * h) / (1920f * 1080f) * 4f - shape * 0.5f
        if (score > bestScore) { bestScore = score; best = i }
    }
    return best
}

private val qrHints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true,
    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))

/** Reads a QR code from a camera frame, trying both binarizers and the inverted picture (phone screens glare). */
fun qrText(src: com.google.zxing.LuminanceSource): String? {
    for (s in arrayOf(src, src.invert())) {
        val bin = arrayOf<com.google.zxing.Binarizer>(com.google.zxing.common.HybridBinarizer(s), com.google.zxing.common.GlobalHistogramBinarizer(s))
        for (b in bin) try { return com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(b), qrHints).text } catch (e: Exception) { }
    }
    return null
}

/** Live camera preview that reads QR codes (for pairing the second phone). */
@Suppress("DEPRECATION")
class QrScanner(c: Context, private val onCode: (String) -> Unit) : FrameLayout(c), TextureView.SurfaceTextureListener {
    private val tex = TextureView(c)
    private var cam: android.hardware.Camera? = null
    @Volatile private var busy = false
    @Volatile private var found = false
    private val main = Handler(Looper.getMainLooper())

    init { addView(tex, LayoutParams(MATCH, MATCH)); tex.surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { open(st) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { close(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private var previewW = 0
    private var previewH = 0

    private fun open(st: SurfaceTexture) {
        try {
            var back = 0
            val info = android.hardware.Camera.CameraInfo()
            for (i in 0 until android.hardware.Camera.getNumberOfCameras()) {
                android.hardware.Camera.getCameraInfo(i, info)
                if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) { back = i; break }
            }
            android.hardware.Camera.getCameraInfo(back, info)
            val camera = android.hardware.Camera.open(back) ?: return
            cam = camera
            val params = camera.parameters
            // a sharp preview size; the crop in fit() takes care of the shape. (1.1 picked the size closest
            // to the square view, which on some phones was 176x144, too blurry for a QR code to read.)
            val portrait = info.orientation == 90 || info.orientation == 270
            val sizes = params.supportedPreviewSizes
            val dims = ArrayList<IntArray>()
            for (sz in sizes) dims.add(intArrayOf(sz.width, sz.height))
            val pick = pickPreviewSize(dims, if (portrait) height.toFloat() / Math.max(1, width) else width.toFloat() / Math.max(1, height))
            val best: android.hardware.Camera.Size? = if (pick >= 0) sizes[pick] else null
            if (best != null) params.setPreviewSize(best.width, best.height)
            val modes = params.supportedFocusModes
            if (modes != null && modes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            else if (modes != null && modes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_AUTO)) params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_AUTO
            camera.parameters = params
            camera.setDisplayOrientation(info.orientation)
            camera.setPreviewTexture(st)
            val size = camera.parameters.previewSize
            previewW = if (portrait) size.height else size.width
            previewH = if (portrait) size.width else size.height
            post(object : Runnable { override fun run() { fit() } })
            camera.setPreviewCallback(object : android.hardware.Camera.PreviewCallback {
                override fun onPreviewFrame(data: ByteArray?, cm: android.hardware.Camera?) {
                    if (data == null || busy || found) return
                    busy = true
                    val w = size.width; val h = size.height
                    Thread(object : Runnable { override fun run() { decode(data, w, h); busy = false } }).start()
                }
            })
            camera.startPreview()
        } catch (e: Exception) { close() }
    }

    /** Crops the preview to fill the view at its true shape (the old view stretched it, and QR codes wouldn't read). */
    fun fit() {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || previewW <= 0 || previewH <= 0) return
        tex.setTransform(cropMatrix(vw, vh, previewW.toFloat(), previewH.toFloat()))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) focusOnce()
        return true
    }

    /** One focus pass, then back to continuous focus (a bare autoFocus would lock it there for good). */
    private fun focusOnce() {
        val camera = cam ?: return
        try {
            camera.autoFocus(object : android.hardware.Camera.AutoFocusCallback {
                override fun onAutoFocus(ok: Boolean, cm: android.hardware.Camera?) {
                    main.postDelayed(object : Runnable { override fun run() { try { if (cam === camera) camera.cancelAutoFocus() } catch (x: Exception) { } } }, 1500)
                }
            })
        } catch (x: Exception) { }
    }


    private fun decode(data: ByteArray, w: Int, h: Int) {
        try {
            val src = com.google.zxing.PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val text = qrText(src) ?: return
            if (!found) { found = true; main.post(object : Runnable { override fun run() { onCode(text) } }) }
        } catch (e: Exception) { }
    }


    fun close() {
        val camera = cam ?: return
        cam = null
        try { camera.setPreviewCallback(null); camera.stopPreview(); camera.release() } catch (e: Exception) { }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); close() }
}

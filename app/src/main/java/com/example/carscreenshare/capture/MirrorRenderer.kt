package com.example.carscreenshare.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.carscreenshare.Box
import com.example.carscreenshare.DrivingState
import com.example.carscreenshare.MirrorGeometry
import com.example.carscreenshare.ParkedGate
import com.example.carscreenshare.Point

/**
 * Copies the phone screen onto the Android Auto surface.
 *
 * Pipeline: MediaProjection -> VirtualDisplay (mirrors the phone) -> ImageReader ->
 * Bitmap -> drawn with a Canvas onto the car surface. Going through a Bitmap rather than
 * pointing the VirtualDisplay straight at the car surface means this class is the only thing
 * that ever draws on the car display, so it can always replace the picture with the
 * "paused while driving" screen.
 *
 * Everything runs on one background thread; public methods just post to it.
 */
object MirrorRenderer {
    private const val TAG = "MirrorRenderer"
    private const val TICK_MS = 250L

    private val thread = HandlerThread("mirror-renderer").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var appContext: Context

    // Car side
    private var carSurface: Surface? = null
    private var carWidth = 0
    private var carHeight = 0
    private var visibleArea: Box? = null

    // Phone side
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frame: Bitmap? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var wakeLock: PowerManager.WakeLock? = null

    /** Snapshot used from other threads to translate car touches into phone coordinates. */
    private data class Mapping(val dest: Box, val phoneWidth: Int, val phoneHeight: Int)

    @Volatile private var mapping: Mapping? = null
    private var lastShownState: ParkedGate.State? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) reconfigure()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val tick = object : Runnable {
        override fun run() {
            val state = DrivingState.gate.state()
            if (state != lastShownState) redraw()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun init(context: Context) {
        handler.post {
            if (::appContext.isInitialized) return@post
            appContext = context.applicationContext
            displayManager().registerDisplayListener(displayListener, handler)
            handler.post(tick)
        }
    }

    // ---- Car surface ---------------------------------------------------------------------

    fun onCarSurfaceAvailable(surface: Surface, width: Int, height: Int) = handler.post {
        carSurface = surface
        carWidth = width
        carHeight = height
        reconfigureNow()
    }

    fun onVisibleAreaChanged(area: Rect) = handler.post {
        visibleArea = Box(area.left, area.top, area.right, area.bottom).takeUnless { it.isEmpty }
        reconfigureNow()
    }

    fun onCarSurfaceDestroyed() = handler.post {
        carSurface = null
        mapping = null
        virtualDisplay?.surface = null
        updateWakeLock()
    }

    // ---- Screen capture ------------------------------------------------------------------

    fun attachProjection(mediaProjection: MediaProjection) = handler.post {
        detachNow()
        projection = mediaProjection
        val metrics = phoneMetrics()
        // A VirtualDisplay may only be created once per projection on Android 14+, so it is
        // created right away and later resized / pointed at new buffers as needed.
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "car-screen-share",
            2, 2, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, handler,
        )
        reconfigureNow()
    }

    fun detachProjection() = handler.post { detachNow() }

    /** Returns phone screen coordinates for a point on the car display, or null. */
    fun carToPhone(x: Float, y: Float): Point? {
        val m = mapping ?: return null
        return MirrorGeometry.carToPhone(x, y, m.dest, m.phoneWidth, m.phoneHeight)
    }

    fun carToPhoneScale(): Float = mapping?.let { MirrorGeometry.carToPhoneScale(it.dest, it.phoneWidth) } ?: 1f

    fun phoneSize(): Pair<Int, Int>? = mapping?.let { it.phoneWidth to it.phoneHeight }

    fun isParked(): Boolean = DrivingState.gate.state() == ParkedGate.State.PARKED

    // ---- Internals (renderer thread only) ------------------------------------------------

    private fun reconfigure() = handler.post { reconfigureNow() }

    private fun reconfigureNow() {
        updateWakeLock()
        val surface = carSurface
        val vd = virtualDisplay
        if (surface == null || vd == null || carWidth <= 0 || carHeight <= 0) {
            mapping = null
            redraw()
            return
        }
        val metrics = phoneMetrics()
        val area = visibleArea ?: Box(0, 0, carWidth, carHeight)
        val dest = MirrorGeometry.fit(metrics.widthPixels, metrics.heightPixels, area)
        if (dest.isEmpty) return
        mapping = Mapping(dest, metrics.widthPixels, metrics.heightPixels)

        val (w, h) = MirrorGeometry.captureSize(dest, metrics.widthPixels, metrics.heightPixels)
        if (imageReader == null || w != frameWidth || h != frameHeight) {
            val old = imageReader
            @SuppressLint("WrongConstant")
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ onFrame(it) }, handler)
            vd.resize(w, h, metrics.densityDpi)
            vd.surface = reader.surface
            old?.close()
            imageReader = reader
            frameWidth = w
            frameHeight = h
            frame = null
        }
        redraw()
    }

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            if (reader !== imageReader) return
            val plane = image.planes[0]
            // Rows can be padded, so the bitmap may be wider than the frame; the extra
            // columns are cropped off when drawing.
            val bitmapWidth = plane.rowStride / plane.pixelStride
            var bmp = frame
            if (bmp == null || bmp.width != bitmapWidth || bmp.height != image.height) {
                bmp = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
                frame = bmp
            }
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
        } finally {
            image.close()
        }
        redraw()
    }

    /** Draws whatever should currently be on the car display. */
    private fun redraw() {
        val state = DrivingState.gate.state()
        lastShownState = state
        val surface = carSurface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "Car surface not ready", e)
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            val area = visibleArea ?: Box(0, 0, carWidth, carHeight)
            val bmp = frame
            val m = mapping
            when {
                projection == null -> drawMessage(
                    canvas, area,
                    "Not sharing",
                    "Open Car Screen Share on your phone and tap Start sharing",
                )
                state == ParkedGate.State.MOVING -> drawMessage(
                    canvas, area,
                    "Paused while driving",
                    "Screen sharing resumes when the car is parked",
                )
                state == ParkedGate.State.UNKNOWN -> drawMessage(
                    canvas, area,
                    "Waiting for vehicle speed",
                    "Sharing starts once the car or the phone's GPS confirms you're parked",
                )
                bmp != null && m != null -> canvas.drawBitmap(
                    bmp,
                    Rect(0, 0, frameWidth, frameHeight),
                    Rect(m.dest.left, m.dest.top, m.dest.right, m.dest.bottom),
                    paint,
                )
                else -> drawMessage(canvas, area, "Starting…", "")
            }
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't post frame", e)
            }
        }
    }

    private fun drawMessage(canvas: Canvas, area: Box, title: String, subtitle: String) {
        val cx = (area.left + area.right) / 2f
        val cy = (area.top + area.bottom) / 2f
        textPaint.textSize = area.height / 12f
        subTextPaint.textSize = area.height / 24f
        canvas.drawText(title, cx, cy, textPaint)
        if (subtitle.isNotEmpty()) canvas.drawText(subtitle, cx, cy + subTextPaint.textSize * 2, subTextPaint)
    }

    private fun detachNow() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection = null
        frame = null
        frameWidth = 0
        frameHeight = 0
        mapping = null
        updateWakeLock()
        redraw()
    }

    /** Keeps the phone screen on while it is being shown in the car (otherwise the mirror goes black). */
    @Suppress("DEPRECATION")
    private fun updateWakeLock() {
        if (!::appContext.isInitialized) return
        val needed = projection != null && carSurface != null
        val lock = wakeLock ?: (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "CarScreenShare:mirror")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (needed && !lock.isHeld) lock.acquire() else if (!needed && lock.isHeld) lock.release()
    }

    private fun displayManager() = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    @Suppress("DEPRECATION")
    private fun phoneMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        displayManager().getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }
}

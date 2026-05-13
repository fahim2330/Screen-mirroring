// ============================================================
// GRAFONIA SCREEN MIRROR — StreamManager
// Captures screen frames via ImageReader and encodes to JPEG
// ============================================================
package com.grafonia.screenmirror.stream

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import com.grafonia.screenmirror.websocket.WebSocketManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class StreamManager(
    private val mediaProjection: MediaProjection,
    private val metrics: DisplayMetrics,
    private val wsManager: WebSocketManager,
) {
    companion object {
        private const val TAG = "GrafoniaStream"
        private const val VIRTUAL_DISPLAY_NAME = "GrafoniaCapture"
        // Scale factor to reduce resolution and bandwidth
        private const val SCALE = 0.75f
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val captureExecutor = Executors.newSingleThreadScheduledExecutor()
    private var captureTask: ScheduledFuture<*>? = null

    private val width = (metrics.widthPixels * SCALE).toInt()
    private val height = (metrics.heightPixels * SCALE).toInt()
    private val density = metrics.densityDpi

    var jpegQuality: Int = 60   // 0-100
    var targetFps: Int = 15     // Frames per second

    // ──────────────────────────────────────────────
    // Start Capture
    // ──────────────────────────────────────────────
    fun start() {
        Log.d(TAG, "Starting stream: ${width}x${height} @ ${targetFps}fps Q=$jpegQuality")

        // ImageReader buffers one frame at a time
        imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888,
            2 // Max images in queue
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        scheduleCapture()
    }

    // ──────────────────────────────────────────────
    // Stop Capture
    // ──────────────────────────────────────────────
    fun stop() {
        captureTask?.cancel(false)
        captureExecutor.shutdown()
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        Log.d(TAG, "Stream stopped")
    }

    // ──────────────────────────────────────────────
    // Schedule Periodic Frame Capture
    // ──────────────────────────────────────────────
    private fun scheduleCapture() {
        val intervalMs = (1000L / targetFps.coerceIn(1, 30))

        captureTask = captureExecutor.scheduleAtFixedRate(
            ::captureAndSend,
            0L,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    // ──────────────────────────────────────────────
    // Capture One Frame and Send
    // ──────────────────────────────────────────────
    private fun captureAndSend() {
        val reader = imageReader ?: return
        if (!wsManager.isConnected()) return

        var image: android.media.Image? = null
        try {
            // Acquire latest frame (non-blocking)
            image = reader.acquireLatestImage() ?: return

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            // Create Bitmap from buffer
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)

            // Crop to exact dimensions if needed
            val cropped = if (bmp.width != width || bmp.height != height) {
                Bitmap.createBitmap(bmp, 0, 0, width, height)
            } else bmp

            // Encode to JPEG
            val jpegBytes = compressToJpeg(cropped, jpegQuality)

            // Send via WebSocket
            wsManager.sendFrame(jpegBytes)

            // Recycle bitmaps
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "captureAndSend error: ${e.message}")
        } finally {
            image?.close() // MUST close to release the buffer
        }
    }

    // ──────────────────────────────────────────────
    // JPEG Compression
    // ──────────────────────────────────────────────
    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────
    // Update settings at runtime
    // ──────────────────────────────────────────────
    fun updateSettings(quality: Int, fps: Int) {
        jpegQuality = quality
        if (fps != targetFps) {
            targetFps = fps
            // Reschedule with new FPS
            captureTask?.cancel(false)
            scheduleCapture()
        }
    }
}

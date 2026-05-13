// ============================================================
// GRAFONIA SCREEN MIRROR — ScreenCaptureService
// Foreground service that keeps screen capture alive in background
// ============================================================
package com.grafonia.screenmirror.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.grafonia.screenmirror.stream.StreamManager
import com.grafonia.screenmirror.ui.MainActivity
import com.grafonia.screenmirror.websocket.WebSocketManager
import org.json.JSONObject

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "GrafoniaService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "grafonia_stream"
        private const val CHANNEL_NAME = "Grafonia Screen Mirror"

        // Actions
        const val ACTION_START = "com.grafonia.START"
        const val ACTION_STOP  = "com.grafonia.STOP"

        // Broadcasts sent back to MainActivity
        const val ACTION_STATUS_UPDATE = "com.grafonia.STATUS_UPDATE"
        const val ACTION_STOPPED       = "com.grafonia.STOPPED"

        // Extras
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val EXTRA_SERVER_URL   = "server_url"
        const val EXTRA_DEVICE_NAME  = "device_name"
        const val EXTRA_STATUS       = "status"
        const val EXTRA_VIEWERS      = "viewers"

        // Settings accessible from MainActivity
        @Volatile var jpegQuality: Int = 60
        @Volatile var targetFps: Int   = 15
    }

    private var mediaProjection: MediaProjection? = null
    private var wsManager: WebSocketManager? = null
    private var streamManager: StreamManager? = null
    private var viewerCount = 0

    // ──────────────────────────────────────────────
    // Service Lifecycle
    // ──────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP  -> handleStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    // ──────────────────────────────────────────────
    // Start Streaming
    // ──────────────────────────────────────────────
    private fun handleStart(intent: Intent) {
        val resultCode  = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        val serverUrl   = intent.getStringExtra(EXTRA_SERVER_URL) ?: return
        val deviceName  = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Android Device"

        if (resultData == null) {
            Log.e(TAG, "No projection result data")
            return
        }

        // Start as foreground service
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        // Build device info JSON
        val deviceInfo = JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("resolution", "${getScreenWidth()}x${getScreenHeight()}")
        }

        // Setup WebSocket
        wsManager = WebSocketManager(
            serverUrl = serverUrl,
            deviceName = deviceName,
            deviceInfo = deviceInfo,
            onConnected = {
                Log.d(TAG, "WS connected, starting stream")
                broadcastStatus("Streaming…", viewerCount)
                updateNotification("Streaming to $serverUrl")
                startCapture()
            },
            onDisconnected = { reason ->
                Log.d(TAG, "WS disconnected: $reason")
                broadcastStatus("Reconnecting…", 0)
                updateNotification("Reconnecting…")
            },
            onViewerCountChanged = { count ->
                viewerCount = count
                broadcastStatus("Streaming…", count)
                updateNotification("Streaming · $count viewer${if (count != 1) "s" else ""}")
            },
            onError = { msg ->
                Log.e(TAG, "WS error: $msg")
                broadcastStatus("Error: $msg", 0)
            }
        )

        // Setup MediaProjection
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        broadcastStatus("Connecting…", 0)
        wsManager!!.connect()
    }

    // ──────────────────────────────────────────────
    // Start Screen Capture
    // ──────────────────────────────────────────────
    private fun startCapture() {
        val metrics = getDisplayMetrics()

        streamManager = StreamManager(
            mediaProjection = mediaProjection!!,
            metrics = metrics,
            wsManager = wsManager!!
        ).apply {
            jpegQuality = Companion.jpegQuality
            targetFps   = Companion.targetFps
        }

        streamManager!!.start()
    }

    // ──────────────────────────────────────────────
    // Stop Streaming
    // ──────────────────────────────────────────────
    private fun handleStop() {
        cleanup()
        broadcastStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        streamManager?.stop()
        streamManager = null
        wsManager?.disconnect()
        wsManager = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    // ──────────────────────────────────────────────
    // Broadcasts to MainActivity
    // ──────────────────────────────────────────────
    private fun broadcastStatus(status: String, viewers: Int) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_VIEWERS, viewers)
            setPackage(packageName)
        })
    }

    private fun broadcastStopped() {
        sendBroadcast(Intent(ACTION_STOPPED).apply {
            setPackage(packageName)
        })
    }

    // ──────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Grafonia screen mirroring active"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grafonia Screen Mirror")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ──────────────────────────────────────────────
    // Display Metrics
    // ──────────────────────────────────────────────
    private fun getDisplayMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                it.widthPixels = bounds.width()
                it.heightPixels = bounds.height()
                it.densityDpi = resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getMetrics(it)
            }
        }
    }

    private fun getScreenWidth(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            wm.currentWindowMetrics.bounds.width()
        else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(m)
            m.widthPixels
        }
    }

    private fun getScreenHeight(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            wm.currentWindowMetrics.bounds.height()
        else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(m)
            m.heightPixels
        }
    }
}

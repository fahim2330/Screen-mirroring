// ============================================================
// GRAFONIA SCREEN MIRROR — WebSocketManager
// Manages WebSocket connection with auto-reconnect
// ============================================================
package com.grafonia.screenmirror.websocket

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val serverUrl: String,
    private val deviceName: String,
    private val deviceInfo: JSONObject,
    private val onConnected: () -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    private val onViewerCountChanged: (count: Int) -> Unit,
    private val onError: (msg: String) -> Unit
) {
    companion object {
        private const val TAG = "GrafoniaWS"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECTS = 20
        private const val PING_INTERVAL_MS = 10L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var shouldReconnect = true
    private var reconnectCount = 0
    private var reconnectRunnable: Runnable? = null

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ──────────────────────────────────────────────
    // Connect
    // ──────────────────────────────────────────────
    fun connect() {
        if (isConnected) return

        Log.d(TAG, "Connecting to $serverUrl")

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, wsListener)
    }

    // ──────────────────────────────────────────────
    // Send binary frame
    // ──────────────────────────────────────────────
    fun sendFrame(jpegBytes: ByteArray): Boolean {
        if (!isConnected || webSocket == null) return false

        return try {
            webSocket!!.send(jpegBytes.toByteString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendFrame failed: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    // Send JSON message
    // ──────────────────────────────────────────────
    private fun sendJson(obj: JSONObject) {
        try {
            webSocket?.send(obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendJson failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Disconnect
    // ──────────────────────────────────────────────
    fun disconnect() {
        shouldReconnect = false
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        isConnected = false
        webSocket?.close(1000, "User stopped streaming")
        webSocket = null
        client.dispatcher.cancelAll()
    }

    fun isConnected() = isConnected

    // ──────────────────────────────────────────────
    // WebSocket Listener
    // ──────────────────────────────────────────────
    private val wsListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened")
            isConnected = true
            reconnectCount = 0

            // Register as sender
            val registerMsg = JSONObject().apply {
                put("type", "register_sender")
                put("deviceName", deviceName)
                put("deviceInfo", deviceInfo)
            }
            sendJson(registerMsg)

            onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                when (msg.getString("type")) {
                    "registered" -> Log.d(TAG, "Sender registered. Viewers: ${msg.optInt("viewers")}")
                    "viewer_count" -> onViewerCountChanged(msg.optInt("viewers", 0))
                    "pong" -> { /* latency tracking could go here */ }
                    else -> Log.d(TAG, "Unknown message: ${msg.getString("type")}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message parse error: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Server shouldn't send binary to sender, but handle gracefully
            Log.w(TAG, "Unexpected binary message from server")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            isConnected = false
            onDisconnected(reason)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            isConnected = false
            onDisconnected(t.message ?: "Connection failed")
            onError(t.message ?: "WebSocket error")
            scheduleReconnect()
        }
    }

    // ──────────────────────────────────────────────
    // Auto Reconnect
    // ──────────────────────────────────────────────
    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectCount >= MAX_RECONNECTS) return

        reconnectCount++
        Log.d(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms (attempt $reconnectCount)")

        val runnable = Runnable {
            if (shouldReconnect) connect()
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, RECONNECT_DELAY_MS)
    }
}

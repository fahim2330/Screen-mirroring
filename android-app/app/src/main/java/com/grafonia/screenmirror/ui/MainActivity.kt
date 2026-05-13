// ============================================================
// GRAFONIA SCREEN MIRROR — MainActivity
// Entry point: handles permissions, UI, and service control
// ============================================================
package com.grafonia.screenmirror.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.grafonia.screenmirror.R
import com.grafonia.screenmirror.databinding.ActivityMainBinding
import com.grafonia.screenmirror.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isStreaming = false
    private var serverUrl = ""

    // ── MediaProjection Permission Launcher ──
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startScreenCapture(result.resultCode, result.data!!)
        } else {
            showError("Screen capture permission denied")
        }
    }

    // ── Notification Permission Launcher (Android 13+) ──
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestMediaProjection()
        else showError("Notification permission required for background streaming")
    }

    // ── Broadcast Receiver for service status updates ──
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenCaptureService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS) ?: return
                    val viewers = intent.getIntExtra(ScreenCaptureService.EXTRA_VIEWERS, 0)
                    runOnUiThread { updateStatus(status, viewers) }
                }
                ScreenCaptureService.ACTION_STOPPED -> {
                    runOnUiThread { onStreamStopped() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupUI()
        registerStatusReceiver()
    }

    // ──────────────────────────────────────────────
    // UI Setup
    // ──────────────────────────────────────────────
    private fun setupUI() {
        // Stream button
        binding.btnStream.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }

        // QR Scan button
        binding.btnScanQr.setOnClickListener {
            val integrator = com.google.zxing.integration.android.IntentIntegrator(this)
            integrator.setPrompt("Scan Grafonia QR Code")
            integrator.setBeepEnabled(false)
            integrator.setOrientationLocked(true)
            integrator.initiateScan()
        }

        // Quality seekbar
        binding.seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val quality = progress.coerceIn(10, 100)
                binding.tvQualityValue.text = "$quality%"
                ScreenCaptureService.jpegQuality = quality
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekQuality.progress = 60
        binding.tvQualityValue.text = "60%"

        // FPS seekbar
        binding.seekFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps = (progress + 1).coerceIn(1, 30)
                binding.tvFpsValue.text = "$fps fps"
                ScreenCaptureService.targetFps = fps
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekFps.progress = 14 // ~15 fps default
        binding.tvFpsValue.text = "15 fps"

        // Default server URL hint
        binding.etServerUrl.hint = "ws://192.168.x.x:3000"
    }

    // ──────────────────────────────────────────────
    // Stream Control
    // ──────────────────────────────────────────────
    private fun startStreaming() {
        serverUrl = binding.etServerUrl.text.toString().trim()

        if (serverUrl.isBlank()) {
            showError("Please enter the server WebSocket URL")
            return
        }

        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            showError("URL must start with ws:// or wss://")
            return
        }

        // On Android 13+, ask for notification permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, serverUrl)
            putExtra(ScreenCaptureService.EXTRA_DEVICE_NAME,
                "${Build.MANUFACTURER} ${Build.MODEL}")
        }

        ContextCompat.startForegroundService(this, serviceIntent)

        isStreaming = true
        onStreamStarted()
    }

    private fun stopStreaming() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        isStreaming = false
        onStreamStopped()
    }

    // ──────────────────────────────────────────────
    // UI State Updates
    // ──────────────────────────────────────────────
    private fun onStreamStarted() {
        binding.btnStream.text = "■ STOP STREAMING"
        binding.btnStream.setBackgroundColor(
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
        binding.statusCard.visibility = View.VISIBLE
        binding.tvStatus.text = "Connecting to server…"
        binding.tvViewers.text = "0 viewers"
        binding.statusDot.setBackgroundResource(R.drawable.dot_yellow)
    }

    private fun onStreamStopped() {
        isStreaming = false
        binding.btnStream.text = "▶ START STREAMING"
        binding.btnStream.setBackgroundColor(
            ContextCompat.getColor(this, R.color.cyan)
        )
        binding.statusCard.visibility = View.GONE
        binding.statusDot.setBackgroundResource(R.drawable.dot_red)
    }

    private fun updateStatus(status: String, viewers: Int) {
        binding.tvStatus.text = status
        binding.tvViewers.text = "$viewers viewer${if (viewers != 1) "s" else ""}"

        val dotRes = when {
            status.contains("streaming", ignoreCase = true) ||
            status.contains("connected", ignoreCase = true) -> R.drawable.dot_green
            status.contains("connecting", ignoreCase = true) -> R.drawable.dot_yellow
            else -> R.drawable.dot_red
        }
        binding.statusDot.setBackgroundResource(dotRes)
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
        binding.root.postDelayed({ binding.tvError.visibility = View.GONE }, 4000)
    }

    // ──────────────────────────────────────────────
    // QR Scan Result
    // ──────────────────────────────────────────────
    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = com.google.zxing.integration.android.IntentIntegrator
            .parseActivityResult(requestCode, resultCode, data)

        if (result != null && result.contents != null) {
            // QR code scanned, fill URL field
            binding.etServerUrl.setText(result.contents)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ──────────────────────────────────────────────
    // Broadcast Receiver
    // ──────────────────────────────────────────────
    private fun registerStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_STATUS_UPDATE)
            addAction(ScreenCaptureService.ACTION_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}

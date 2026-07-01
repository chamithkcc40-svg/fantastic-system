package com.audiobridge.streamer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // ── UI References ────────────────────────────────────────────────
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var streamToggleBtn: FrameLayout
    private lateinit var toggleBtnInner: LinearLayout
    private lateinit var toggleIcon: TextView
    private lateinit var toggleLabel: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var hotspotIpText: TextView
    private lateinit var pingText: TextView
    private lateinit var bitrateText: TextView

    // ── Service & Projection ─────────────────────────────────────────
    private var audioService: AudioStreamService? = null
    private var isBound = false
    private var isStreaming = false
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // ── Service Connection ───────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudioStreamService.LocalBinder
            audioService = localBinder.getService()
            isBound = true
            startStatsPolling()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    // ── Permission Launchers ─────────────────────────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification permission result — not blocking */ }

    private val recordPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchMediaProjection()
        else showToast("Microphone permission required for audio capture.")
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val ip = ipInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 5005

            if (ip.isEmpty()) {
                showToast("Enter the PC's IP address first.")
                setStreamingState(false)
                return@registerForActivityResult
            }

            val serviceIntent = Intent(this, AudioStreamService::class.java).apply {
                putExtra(AudioStreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioStreamService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioStreamService.EXTRA_TARGET_IP, ip)
                putExtra(AudioStreamService.EXTRA_TARGET_PORT, port)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            setStreamingState(true)
        } else {
            showToast("Screen capture permission denied.")
            setStreamingState(false)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        buildUI()

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        displayHotspotIp()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ── UI Builder (programmatic, no XML layouts required) ───────────
    private fun buildUI() {
        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt()) // Pure AMOLED black
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dpToPx(20), dpToPx(60), dpToPx(20), dpToPx(32))
        }

        // ── Header ───────────────────────────────────────────────────
        val headerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(4), 0, 0, dpToPx(8))
        }

        val appLabel = TextView(this).apply {
            text = "AudioBridge"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.04f
        }

        val appSubLabel = TextView(this).apply {
            text = "System audio → PC over LAN"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            setPadding(0, dpToPx(2), 0, 0)
        }

        headerLayout.addView(appLabel)
        headerLayout.addView(appSubLabel)
        rootLayout.addView(headerLayout)

        // ── Status Card ───────────────────────────────────────────────
        val statusCard = createCard().apply { setPadding(dpToPx(18), dpToPx(16), dpToPx(18), dpToPx(16)) }
        val statusRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        statusDot = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).also {
                it.marginEnd = dpToPx(10)
            }
            background = makeCircle(0xFF444444.toInt())
        }

        statusText = TextView(this).apply {
            text = "Idle"
            textSize = 15f
            setTextColor(0xFFAAAAAA.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        hotspotIpText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.gravity = android.view.Gravity.END }
            gravity = android.view.Gravity.END
        }

        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        statusRow.addView(hotspotIpText)
        statusCard.addView(statusRow)
        rootLayout.addView(statusCard, marginedParams(dpToPx(0), dpToPx(20), dpToPx(0), 0))

        // ── Stats Row ─────────────────────────────────────────────────
        val statsRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val pingCard = createCard().apply {
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
        }
        val pingInner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        pingText = TextView(this).apply {
            text = "—"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        val pingLabel = TextView(this).apply {
            text = "Packets/s"
            textSize = 11f
            setTextColor(0xFF666666.toInt())
        }
        pingInner.addView(pingText)
        pingInner.addView(pingLabel)
        pingCard.addView(pingInner)

        val bitrateCard = createCard().apply {
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
        }
        val bitrateInner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        bitrateText = TextView(this).apply {
            text = "—"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        val bitrateLabel = TextView(this).apply {
            text = "KB/s"
            textSize = 11f
            setTextColor(0xFF666666.toInt())
        }
        bitrateInner.addView(bitrateText)
        bitrateInner.addView(bitrateLabel)
        bitrateCard.addView(bitrateInner)

        val pingParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginEnd = dpToPx(8)
        }
        val bitrateParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dpToPx(8)
        }
        statsRow.addView(pingCard, pingParams)
        statsRow.addView(bitrateCard, bitrateParams)
        rootLayout.addView(statsRow, marginedParams(0, 0, 0, dpToPx(20)))

        // ── IP / Port Inputs ──────────────────────────────────────────
        val inputsCard = createCard().apply { setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18)) }
        val inputsInner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val ipLabel = TextView(this).apply {
            text = "PC IP ADDRESS"
            textSize = 10f
            setTextColor(0xFF666666.toInt())
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        ipInput = EditText(this).apply {
            hint = "192.168.43.xxx"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF444444.toInt())
            setBackgroundColor(0x00000000)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(0, dpToPx(6), 0, dpToPx(8))
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }

        val divider1 = View(this).apply {
            setBackgroundColor(0xFF2A2A2A.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
            ).also { it.bottomMargin = dpToPx(16) }
        }

        val portLabel = TextView(this).apply {
            text = "UDP PORT"
            textSize = 10f
            setTextColor(0xFF666666.toInt())
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dpToPx(4), 0, 0)
        }

        portInput = EditText(this).apply {
            setText("5005")
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(0, dpToPx(6), 0, 0)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }

        inputsInner.addView(ipLabel)
        inputsInner.addView(ipInput)
        inputsInner.addView(divider1)
        inputsInner.addView(portLabel)
        inputsInner.addView(portInput)
        inputsCard.addView(inputsInner)
        rootLayout.addView(inputsCard, marginedParams(0, 0, 0, dpToPx(24)))

        // ── Spacer ────────────────────────────────────────────────────
        val spacer = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rootLayout.addView(spacer)

        // ── Main Toggle Button ────────────────────────────────────────
        streamToggleBtn = FrameLayout(this).apply {
            background = makeRoundedRect(0xFF1A1A1A.toInt(), dpToPx(28).toFloat())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(88)
            )
            isClickable = true
            isFocusable = true
        }

        toggleBtnInner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        toggleIcon = TextView(this).apply {
            text = "▶"
            textSize = 22f
            setTextColor(0xFF00D4AA.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dpToPx(14) }
        }

        toggleLabel = TextView(this).apply {
            text = "Connect & Stream"
            textSize = 19f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        toggleBtnInner.addView(toggleIcon)
        toggleBtnInner.addView(toggleLabel)
        streamToggleBtn.addView(toggleBtnInner)

        streamToggleBtn.setOnClickListener { onToggleClicked() }

        rootLayout.addView(streamToggleBtn, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(88)
        ))

        setContentView(rootLayout)
    }

    // ── Toggle Logic ─────────────────────────────────────────────────
    private fun onToggleClicked() {
        if (isStreaming) {
            stopStreaming()
        } else {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchMediaProjection()
        }
    }

    private fun launchMediaProjection() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun stopStreaming() {
        val stopIntent = Intent(this, AudioStreamService::class.java)
        stopService(stopIntent)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        audioService = null
        setStreamingState(false)
    }

    private fun setStreamingState(streaming: Boolean) {
        isStreaming = streaming
        runOnUiThread {
            if (streaming) {
                streamToggleBtn.background = makeRoundedRect(0xFF00D4AA.toInt(), dpToPx(28).toFloat())
                toggleIcon.text = "■"
                toggleIcon.setTextColor(0xFF000000.toInt())
                toggleLabel.text = "Stop Streaming"
                toggleLabel.setTextColor(0xFF000000.toInt())
                statusDot.background = makeCircle(0xFF00D4AA.toInt())
                statusText.text = "Streaming"
                statusText.setTextColor(0xFF00D4AA.toInt())
            } else {
                streamToggleBtn.background = makeRoundedRect(0xFF1A1A1A.toInt(), dpToPx(28).toFloat())
                toggleIcon.text = "▶"
                toggleIcon.setTextColor(0xFF00D4AA.toInt())
                toggleLabel.text = "Connect & Stream"
                toggleLabel.setTextColor(0xFFFFFFFF.toInt())
                statusDot.background = makeCircle(0xFF444444.toInt())
                statusText.text = "Idle"
                statusText.setTextColor(0xFFAAAAAA.toInt())
                pingText.text = "—"
                bitrateText.text = "—"
            }
        }
    }

    // ── Stats Polling ─────────────────────────────────────────────────
    private fun startStatsPolling() {
        lifecycleScope.launch {
            while (isBound && audioService != null) {
                val stats = audioService?.getStats()
                if (stats != null) {
                    runOnUiThread {
                        pingText.text = stats.packetsPerSec.toString()
                        bitrateText.text = "%.1f".format(stats.kbPerSec)
                    }
                }
                delay(1000)
            }
        }
    }

    // ── Hotspot IP Display ───────────────────────────────────────────
    private fun displayHotspotIp() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
            if (ip != "0.0.0.0") {
                hotspotIpText.text = "My IP: $ip"
            }
        } catch (_: Exception) {}
    }

    // ── UI Helpers ────────────────────────────────────────────────────
    private fun createCard(): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = makeRoundedRect(0xFF111111.toInt(), dpToPx(20).toFloat())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun marginedParams(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0)
            : android.widget.LinearLayout.LayoutParams {
        return android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).also {
            it.setMargins(l, t, r, b)
        }
    }

    private fun makeRoundedRect(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun makeCircle(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

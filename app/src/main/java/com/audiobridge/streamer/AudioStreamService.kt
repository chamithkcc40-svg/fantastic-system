package com.audiobridge.streamer

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

data class StreamStats(
    val packetsPerSec: Int,
    val kbPerSec: Float
)

@RequiresApi(Build.VERSION_CODES.Q)
class AudioStreamService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_IP   = "target_ip"
        const val EXTRA_TARGET_PORT = "target_port"

        private const val NOTIFICATION_ID       = 1001
        private const val CHANNEL_ID            = "audio_bridge_channel"

        // ── Audio Config ──────────────────────────────────────────────
        // 44100 Hz stereo 16-bit PCM → ~172 KB/s raw
        const val SAMPLE_RATE      = 44100
        const val CHANNEL_CONFIG   = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT     = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS         = 2
        const val BITS_PER_SAMPLE  = 16

        // UDP MTU-safe chunk: 1400 bytes keeps us below 1472 byte LAN MTU ceiling
        private const val UDP_CHUNK_BYTES = 1400
    }

    // ── Binder ────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamService = this@AudioStreamService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── Internal State ────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var udpSocket: DatagramSocket? = null
    private var streamJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Stats ─────────────────────────────────────────────────────────
    private val packetCount = AtomicLong(0)
    private val byteCount   = AtomicLong(0)
    private var statsSnapshot = StreamStats(0, 0f)
    private var statsJob: Job? = null

    // ── Service Lifecycle ─────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
        val targetIp   = intent.getStringExtra(EXTRA_TARGET_IP) ?: return START_NOT_STICKY
        val targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 5005)

        startForeground(NOTIFICATION_ID, buildNotification(targetIp, targetPort))
        acquireWakeLock()

        val projManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projManager.getMediaProjection(resultCode, resultData)

        startStreaming(targetIp, targetPort)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    // ── Streaming ─────────────────────────────────────────────────────
    private fun startStreaming(targetIp: String, targetPort: Int) {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Use 4× minimum for low-latency headroom
        val bufferSize = maxOf(minBufSize * 4, UDP_CHUNK_BYTES * 8)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        udpSocket = DatagramSocket()
        udpSocket?.broadcast = false

        startStatsJob()

        streamJob = serviceScope.launch {
            val destAddr = InetAddress.getByName(targetIp)
            val pcmBuffer = ByteArray(UDP_CHUNK_BYTES)
            var totalRead = 0

            audioRecord?.startRecording()

            while (isActive) {
                val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: break
                if (read > 0) {
                    totalRead += read
                    val packet = DatagramPacket(pcmBuffer, read, destAddr, targetPort)
                    try {
                        udpSocket?.send(packet)
                        packetCount.incrementAndGet()
                        byteCount.addAndGet(read.toLong())
                    } catch (e: Exception) {
                        // Socket closed on stop — exit loop
                        if (!isActive) break
                    }
                }
            }
        }
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        statsJob?.cancel()
        serviceScope.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        udpSocket?.close()
        udpSocket = null

        mediaProjection?.stop()
        mediaProjection = null

        wakeLock?.release()
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Stats ─────────────────────────────────────────────────────────
    private fun startStatsJob() {
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val packets = packetCount.getAndSet(0)
                val bytes   = byteCount.getAndSet(0)
                statsSnapshot = StreamStats(
                    packetsPerSec = packets.toInt(),
                    kbPerSec      = bytes / 1024f
                )
            }
        }
    }

    fun getStats(): StreamStats = statsSnapshot

    // ── Wake Lock ─────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioBridge::StreamLock"
        ).apply { acquire(4 * 60 * 60 * 1000L) } // 4 hour max
    }

    // ── Notification ──────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active audio bridge to PC"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val stopIntent = Intent(this, AudioStreamService::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 0, stopIntent, pendingFlags)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioBridge Active")
            .setContentText("Streaming to $ip:$port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPi
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}

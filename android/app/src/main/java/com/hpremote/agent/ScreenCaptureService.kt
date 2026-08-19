package com.hpremote.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1
        private const val MIN_FRAME_INTERVAL_MS = 150L
        private const val JPEG_QUALITY = 60

        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_PAIR_CODE = "pairCode"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var lastFrameSentAt = 0L

    private var capturedWidth = 0
    private var capturedHeight = 0

    private var micRecord: AudioRecord? = null
    private var playbackRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        val pairCode = intent.getStringExtra(EXTRA_PAIR_CODE)

        if (resultData == null || serverUrl.isNullOrBlank() || pairCode.isNullOrBlank()) {
            Log.e(TAG, "missing required extras, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        RelayConnection.connect(serverUrl, pairCode, onRegistered = {
            // Only safe to send once the server has this connection marked as the
            // device for this session - sending earlier gets silently dropped.
            RelayConnection.sendInfo(capturedWidth, capturedHeight)
        })
        RelayConnection.commandListener = { msg ->
            RemoteAccessibilityService.instance?.handleCommand(msg)
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        startCapture(projection)
        startAudioCapture(projection)
        return START_NOT_STICKY
    }

    private fun startCapture(projection: MediaProjection) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager().defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        capturedWidth = width
        capturedHeight = height

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "hp_remote-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        val now = System.currentTimeMillis()
        val image = reader.acquireLatestImage() ?: return
        if (now - lastFrameSentAt < MIN_FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastFrameSentAt = now

        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(plane.buffer)

            val cropped = if (rowPadding == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            cropped.recycle()
            RelayConnection.sendFrame(out.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "failed to encode frame", e)
        } finally {
            image.close()
        }
    }

    /**
     * Captures mic input and (on Android 10+) the device's own playback audio,
     * mixes them into one mono PCM16 stream and relays it. Either source is
     * silently skipped if unavailable (no mic permission, or API < 29 for
     * playback capture) rather than failing the whole screen share.
     */
    private fun startAudioCapture(projection: MediaProjection) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no RECORD_AUDIO permission, skipping audio capture")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL, AUDIO_ENCODING)
        if (minBuf <= 0) return

        micRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL, AUDIO_ENCODING, minBuf
            ).also { it.startRecording() }
        } catch (e: Exception) {
            Log.w(TAG, "mic capture unavailable", e)
            null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            playbackRecord = try {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AUDIO_ENCODING)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AUDIO_CHANNEL)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
                    .also { it.startRecording() }
            } catch (e: Exception) {
                Log.w(TAG, "system audio capture unavailable", e)
                null
            }
        }

        if (micRecord == null && playbackRecord == null) return

        audioRunning = true
        audioThread = Thread({ runAudioLoop(minBuf) }, "AudioCaptureThread").apply { start() }
    }

    private fun runAudioLoop(bufferSize: Int) {
        val micBuf = ShortArray(bufferSize / 2)
        val playBuf = ShortArray(bufferSize / 2)
        while (audioRunning) {
            val micRead = micRecord?.read(micBuf, 0, micBuf.size) ?: 0
            val playRead = playbackRecord?.read(playBuf, 0, playBuf.size) ?: 0
            val n = maxOf(micRead, playRead)
            if (n <= 0) continue

            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val m = if (i < micRead) micBuf[i].toInt() else 0
                val p = if (i < playRead) playBuf[i].toInt() else 0
                val sum = (m + p).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i * 2] = (sum and 0xFF).toByte()
                out[i * 2 + 1] = ((sum shr 8) and 0xFF).toByte()
            }
            RelayConnection.sendAudio(out)
        }
    }

    private fun stopAudioCapture() {
        audioRunning = false
        // Stop the recorders first so a blocking read() on the audio thread
        // returns immediately instead of only noticing audioRunning once new
        // data happens to arrive (which, with the mic idle, could be never -
        // leaving the mic privacy indicator on indefinitely).
        micRecord?.stop()
        playbackRecord?.stop()
        audioThread?.join(500)
        audioThread = null
        micRecord?.release()
        playbackRecord?.release()
        micRecord = null
        playbackRecord = null
    }

    private fun windowManager() = getSystemService(android.view.WindowManager::class.java)

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        RelayConnection.commandListener = null
        RelayConnection.disconnect()
        handlerThread.quitSafely()
    }
}

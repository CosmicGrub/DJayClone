package com.oblivion.djayclone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service owning the live-mix capture: MediaProjection-backed
 * AudioPlaybackCaptureConfiguration -> AudioRecord -> a bounded channel ->
 * a WAV file streamed to app-private storage.
 *
 * Bound-and-started: startForegroundService() keeps it alive independent of
 * any Activity's lifecycle, bindService() from RecordingViewModel gets a live
 * StateFlow of progress.
 *
 * Safety properties (v0.18.0):
 *  - Self-only: capture is restricted to this app's own UID, so other apps'
 *    audio (a video in split screen, a notification) is never recorded.
 *  - Crash-safe: the WAV header is re-patched every ~2 s of audio and the file
 *    synced every ~10 s, so a kill loses at most a few seconds and the file is
 *    playable at every moment. Files live in noBackupFilesDir (never in the
 *    Auto Backup set) and an unfinished one is offered back on next launch.
 *  - Timeline-true: the clock is frames written; if the writer ever falls
 *    behind and the queue overflows, the dropped span is written back as
 *    silence so the file stays exactly as long as real time.
 *  - Honest: storage left is measured every 5 s and a stalled capture is
 *    detected within 2 s and stopped visibly instead of running a dead timer.
 */
class RecordingService : Service() {

    data class ServiceState(
        val isRecording: Boolean = false,
        val elapsedMs: Long = 0L,
        val finishedFile: File? = null, // non-null once stopRecording() has finalized the WAV
        val error: String? = null,
        val storageRemainingMs: Long? = null,
        val storageLevel: StorageBudget.Level = StorageBudget.Level.OK,
        val gapCount: Int = 0,
    )

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pcmChannel = Channel<ByteArray>(capacity = 64)
    private val droppedBytes = AtomicLong(0)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var writerJob: Job? = null
    private var tickerJob: Job? = null
    private var outputFile: File? = null
    private var markerFile: File? = null
    private var startTimeMs: Long = 0L
    @Volatile private var lastFrameAt: Long? = null
    @Volatile private var stopRequested = false
    @Volatile private var bytesPerSec = 176_400L
    private var lastNotifiedText: String? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop pressed in the notification shade: explicit system-surface action.
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let {
            androidx.core.content.IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }

        // The foreground service (type mediaProjection) must be running BEFORE
        // getMediaProjection() on API 34.
        startForeground(NOTIFICATION_ID, buildNotification(recording = false, elapsedMs = 0, level = StorageBudget.Level.OK), foregroundServiceType())

        if (resultData == null || captureJob != null) return START_NOT_STICKY

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The OS/user can revoke the projection (status-bar chip) -
                // finalize cleanly instead of leaving AudioRecord broken.
                stopRecording()
            }
        }, null)

        startCapture(projection)
        return START_NOT_STICKY
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture(projection: MediaProjection) {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            // Only this app's own players: the decks share this UID, nothing
            // else's audio can leak into the mix.
            .addMatchingUid(android.os.Process.myUid())
            .build()

        val outRate = getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val sampleRate = CaptureSpec.chooseRate(outRate)
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuf <= 0) {
            _state.value = _state.value.copy(error = "This device doesn't support the audio format needed to record.")
            stopSelfCleanly()
            return
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: UnsupportedOperationException) {
            _state.value = _state.value.copy(error = "Recording setup failed: ${e.message}")
            stopSelfCleanly()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            _state.value = _state.value.copy(error = "Audio recorder failed to initialize.")
            record.release()
            stopSelfCleanly()
            return
        }

        val rate = record.sampleRate // negotiated rate, not assumed
        val channelCount = 2
        val blockAlign = channelCount * 2
        bytesPerSec = StorageBudget.bytesPerSecond(rate, channelCount)

        // ~20 s of audio of queue before anything is dropped.
        pcmChannel = Channel(capacity = (20 * bytesPerSec / minBuf).toInt().coerceAtLeast(64))
        droppedBytes.set(0)

        val dir = recordingsDir(this)
        val file = File(dir, "rec_${System.currentTimeMillis()}.wav")
        val marker = File(dir, file.name + ".active")
        outputFile = file
        markerFile = marker
        marker.createNewFile()
        val raf = RandomAccessFile(file, "rw")
        raf.write(WavHeader.build(rate, channelCount, 16, 0))

        audioRecord = record
        record.startRecording()
        startTimeMs = SystemClock.elapsedRealtime()
        lastFrameAt = null
        stopRequested = false
        _state.value = ServiceState(isRecording = true, elapsedMs = 0L)
        pushNotification(0, StorageBudget.Level.OK, force = true)

        captureJob = serviceScope.launch {
            val readBuf = ByteArray(minBuf)
            while (isActive && !stopRequested) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    lastFrameAt = SystemClock.elapsedRealtime()
                    // Bounded: a persistently slow writer drops here rather than
                    // stalling capture; the dropped span is written back as
                    // silence so the timeline stays true.
                    if (!pcmChannel.trySend(readBuf.copyOf(n)).isSuccess) droppedBytes.addAndGet(n.toLong())
                } else if (n < 0) {
                    val code = n
                    stopRecording("Capture stopped unexpectedly (audio error $code) - what was recorded is saved.")
                    break
                }
            }
        }

        writerJob = serviceScope.launch {
            var dataBytes = 0L
            var lastPatchBytes = 0L
            var lastSync = SystemClock.elapsedRealtime()
            var gaps = 0
            val zeros = ByteArray(16384)
            fun patch() {
                raf.seek(0)
                raf.write(WavHeader.build(rate, channelCount, 16, dataBytes))
                raf.seek(WavHeader.SIZE + dataBytes)
            }
            try {
                for (buf in pcmChannel) {
                    val gap = (droppedBytes.getAndSet(0) / blockAlign) * blockAlign
                    if (gap > 0) {
                        var left = gap
                        while (left > 0) {
                            val n = minOf(left, zeros.size.toLong()).toInt()
                            raf.write(zeros, 0, n)
                            left -= n
                        }
                        dataBytes += gap
                        gaps++
                    }
                    val room = WavHeader.maxDataBytes(blockAlign) - dataBytes
                    val take = minOf(buf.size.toLong(), room).toInt()
                    if (take > 0) raf.write(buf, 0, take)
                    dataBytes += take
                    _state.value = _state.value.copy(
                        elapsedMs = CaptureClock.elapsedMs(dataBytes / blockAlign, rate),
                        gapCount = gaps,
                    )
                    if (dataBytes - lastPatchBytes >= 2 * bytesPerSec) {
                        patch(); lastPatchBytes = dataBytes
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSync >= 10_000) {
                        raf.fd.sync(); lastSync = now
                    }
                    if (take < buf.size || WavHeader.isAtLimit(dataBytes, blockAlign)) {
                        stopRecording("Stopped at the 4 GB WAV limit - your recording is saved.")
                    }
                }
            } finally {
                try {
                    patch()
                    raf.fd.sync()
                } catch (_: Exception) { }
                try { raf.close() } catch (_: Exception) { }
                marker.delete()
            }
        }

        tickerJob = serviceScope.launch {
            var tick = 0
            while (isActive && !stopRequested) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                if (CaptureWatchdog.isStalled(now, startTimeMs, lastFrameAt)) {
                    stopRecording("Capture stopped unexpectedly - what was recorded is saved.")
                    break
                }
                if (tick % 5 == 0) {
                    val free = android.os.StatFs(dir.path).availableBytes
                    val stop = StorageBudget.shouldStop(free)
                    val remaining = StorageBudget.remainingMs(free, StorageBudget.RESERVE_BYTES, bytesPerSec, 2)
                    val level = StorageBudget.level(remaining, stop)
                    _state.value = _state.value.copy(storageRemainingMs = remaining, storageLevel = level)
                    if (stop) {
                        stopRecording("Stopped: storage nearly full - your recording up to this point is saved.")
                        break
                    }
                }
                if (tick % 10 == 0) pushNotification(_state.value.elapsedMs, _state.value.storageLevel, force = false)
                tick++
            }
        }
    }

    /** Stops capture, finalizes the WAV header, and transitions to the
     * stopped/pending-save state. Safe to call more than once. [reason], if
     * given, is shown as the error line while the take stays Save/Discard-able. */
    fun stopRecording(reason: String? = null) {
        if (stopRequested) return
        stopRequested = true
        if (reason != null) _state.value = _state.value.copy(error = reason)
        try { audioRecord?.stop() } catch (_: IllegalStateException) { } // unblocks an in-flight read()
        captureJob?.cancel()
        tickerJob?.cancel()
        pcmChannel.close() // lets the writer drain and exit

        serviceScope.launch {
            writerJob?.join()
            audioRecord?.release()
            audioRecord = null
            val finished = outputFile
            // Nulled so a later onStartCommand() sees "not capturing" (a
            // cancelled Job is still non-null).
            captureJob = null
            writerJob = null
            tickerJob = null
            outputFile = null
            markerFile = null
            _state.value = _state.value.copy(isRecording = false, finishedFile = finished)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Service stays alive (bound) so RecordingViewModel can read the
            // finished-file state until the user Saves or Discards.
        }
    }

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        running = false
        try { audioRecord?.stop() } catch (_: IllegalStateException) { }
        audioRecord?.release()
        mediaProjection?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun pushNotification(elapsedMs: Long, level: StorageBudget.Level, force: Boolean) {
        val text = NotificationText.compose(true, elapsedMs, level)
        // Only re-post when the storage warning flips; the system ticks the
        // clock itself via the chronometer.
        val key = if (level == StorageBudget.Level.CRITICAL || level == StorageBudget.Level.STOP) "low" else "ok"
        if (!force && lastNotifiedText == key) return
        lastNotifiedText = key
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(true, elapsedMs, level))
    }

    private fun buildNotification(recording: Boolean, elapsedMs: Long, level: StorageBudget.Level): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mix recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NotificationText.title(recording))
            .setContentText(if (recording) {
                if (level == StorageBudget.Level.CRITICAL || level == StorageBudget.Level.STOP) "Recording your mix · Low storage"
                else "Recording your mix"
            } else NotificationText.compose(false, elapsedMs, level))
            .setSmallIcon(R.drawable.ic_rec)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - elapsedMs)
                .addAction(0, "Stop", stop)
        }
        return b.build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.oblivion.djayclone.action.STOP_RECORDING"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001

        /** True while a service instance exists (process-local). */
        @Volatile var running: Boolean = false

        /** noBackupFilesDir, not cacheDir/filesDir: the OS can't clear it under
         * storage pressure, and a multi-GB take must never enter Auto Backup. */
        fun recordingsDir(context: Context): File =
            File(context.noBackupFilesDir, "recordings").also { it.mkdirs() }
    }
}

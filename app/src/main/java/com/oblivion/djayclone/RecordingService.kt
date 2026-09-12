package com.oblivion.djayclone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

/**
 * Foreground service owning the live-mix capture: MediaProjection-backed
 * AudioPlaybackCaptureConfiguration -> AudioRecord -> a bounded channel ->
 * a WAV file streamed to app-private cache storage.
 *
 * Bound-and-started: startForegroundService() keeps it alive independent of
 * any Activity's lifecycle (so backgrounding the app mid-recording doesn't
 * kill capture - the whole reason a foreground service is required at all),
 * bindService() from RecordingViewModel gets a live StateFlow of progress.
 *
 * Capture sits entirely downstream of both decks' ExoPlayer instances and
 * MixerScreen's crossfader/gain math - it captures the app's *actual*
 * summed USAGE_MEDIA output, exactly as routed to whatever output device is
 * connected right now. Nothing here needs to know decks or a crossfader
 * exist; a track ending or being swapped mid-recording just becomes silence
 * or the new track in the capture, correctly, with no special-casing.
 */
class RecordingService : Service() {

    data class ServiceState(
        val isRecording: Boolean = false,
        val elapsedMs: Long = 0L,
        val finishedFile: File? = null, // non-null once stopRecording() has finalized the WAV
        val error: String? = null,
    )

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pcmChannel = Channel<ByteArray>(capacity = 32)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var writerJob: Job? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // This service is only ever started (by RecordingViewModel) once the
        // caller has already confirmed Build.VERSION.SDK_INT >= Q - the
        // capture APIs below simply don't exist on older devices. Re-checked
        // here defensively (and in a form Android Lint recognizes as a
        // version gate) rather than trusting the caller blindly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let {
            androidx.core.content.IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }

        // Order matters and is enforced by the OS on API 34: the foreground
        // service (with foregroundServiceType=mediaProjection) must already
        // be running BEFORE MediaProjectionManager.getMediaProjection() is
        // called, not after. Calling startForeground() first, unconditionally,
        // satisfies that even on the (extremely unlikely) restart path where
        // resultData is missing.
        startForeground(NOTIFICATION_ID, buildNotification(recording = false), foregroundServiceType())

        if (resultData == null || captureJob != null) {
            // No projection data (bad restart) or already capturing - nothing
            // new to do; leave the existing state as-is rather than restart
            // a second capture session on top of the first.
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The OS/user can revoke the projection out from under us (the
                // status-bar "Stop casting" chip) - treat it exactly like a
                // manual stopRecording() so we finalize cleanly instead of
                // leaving AudioRecord in a broken state for the next read().
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
            .build()

        val sampleRate = 44100
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

        val actualSampleRate = record.sampleRate // negotiated rate, not assumed
        val channelCount = 2 // CHANNEL_IN_STEREO

        val file = File(cacheDir, "djayclone_rec_${System.currentTimeMillis()}.wav")
        outputFile = file
        val raf = RandomAccessFile(file, "rw")
        writeWavHeader(raf, totalPcmBytes = 0L, sampleRate = actualSampleRate, channelCount = channelCount)

        audioRecord = record
        record.startRecording()
        startTimeMs = System.currentTimeMillis()
        stopRequested = false
        _state.value = ServiceState(isRecording = true, elapsedMs = 0L)

        captureJob = serviceScope.launch {
            val readBuf = ByteArray(minBuf)
            while (isActive && !stopRequested) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    pcmChannel.trySend(readBuf.copyOf(n))
                    // Bounded channel: a persistently slow writer (contended
                    // storage) drops the newest frames here rather than
                    // blocking this read loop - backpressure must land on
                    // the file, never stall capture of the live mix.
                } else if (n < 0) {
                    break // AudioRecord error code (e.g. ERROR_DEAD_OBJECT)
                }
            }
        }

        writerJob = serviceScope.launch {
            var totalBytes = 0L
            for (buf in pcmChannel) {
                raf.write(buf)
                totalBytes += buf.size
                val elapsed = System.currentTimeMillis() - startTimeMs
                _state.value = _state.value.copy(elapsedMs = elapsed)
                if (elapsed % 30_000 < 200) checkStorageHeadroom() // ~ every 30s
            }
            writeWavHeader(raf, totalPcmBytes = totalBytes, sampleRate = actualSampleRate, channelCount = channelCount)
            raf.close()
        }
    }

    private fun checkStorageHeadroom() {
        val stat = android.os.StatFs(cacheDir.path)
        val freeBytes = stat.availableBytes
        if (freeBytes < 50L * 1024 * 1024) {
            _state.value = _state.value.copy(error = "Low storage - recording stopped automatically.")
            stopRecording()
        }
    }

    /** Stops capture, finalizes the WAV header, and transitions to the
     * stopped/pending-save state. Safe to call more than once (e.g. both a
     * manual Stop tap and a race with MediaProjection.Callback.onStop()). */
    fun stopRecording() {
        if (stopRequested) return
        stopRequested = true
        audioRecord?.stop() // unblocks any in-flight blocking read()
        captureJob?.cancel()
        pcmChannel.close() // lets the writer's `for (buf in pcmChannel)` loop drain and exit

        serviceScope.launch {
            writerJob?.join()
            audioRecord?.release()
            audioRecord = null
            val finished = outputFile
            // Real bug, found via an actual second-recording-in-one-session
            // test: captureJob was cancel()'d above but never nulled out.
            // onStartCommand()'s "already capturing" guard checks
            // captureJob != null, and a cancelled Job is still non-null - so
            // a second recording attempt (before this service instance gets
            // torn down) would silently skip startCapture() entirely,
            // startForeground()'s notification and the whole permission/
            // projection consent flow notwithstanding. Nulling these here,
            // once teardown is actually complete, is what lets a later
            // onStartCommand() correctly recognize "not capturing" and
            // proceed. (RecordingViewModel.save()/discard() also now fully
            // stopService()s once a session ends, which sidesteps this too
            // by giving the next recording a truly fresh instance - but
            // fixing the guard itself here is the actual root cause fix,
            // not just a workaround for it.)
            captureJob = null
            writerJob = null
            outputFile = null
            _state.value = _state.value.copy(isRecording = false, finishedFile = finished)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Service stays alive (bound) so RecordingViewModel can still
            // read the finished-file state; it unbinds (and this then stops)
            // once the user Saves or Discards.
        }
    }

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(recording: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mix recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DJayClone")
            .setContentText(if (recording) "Recording your mix" else "Preparing to record")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001

        /** 44-byte canonical PCM WAV header. Called twice: once with
         * totalPcmBytes=0 as a placeholder before any audio is written, once
         * more (via RandomAccessFile.seek(0), implicit since this always
         * writes from the file's current position which the caller resets)
         * to patch in the real size once recording stops. */
        fun writeWavHeader(raf: RandomAccessFile, totalPcmBytes: Long, sampleRate: Int, channelCount: Int) {
            val byteRate = sampleRate * channelCount * 2 // 16-bit = 2 bytes/sample
            val blockAlign = channelCount * 2
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt((36 + totalPcmBytes).toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // Subchunk1Size (PCM)
            header.putShort(1) // AudioFormat = PCM
            header.putShort(channelCount.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(16) // BitsPerSample
            header.put("data".toByteArray())
            header.putInt(totalPcmBytes.toInt())
            raf.seek(0)
            raf.write(header.array())
            raf.seek(raf.length().coerceAtLeast(44))
        }
    }
}

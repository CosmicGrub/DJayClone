package com.oblivion.djayclone

import android.app.Application
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

data class RecordingUiState(
    // Recording captures the app's own mixed system-audio output, which
    // needs AudioPlaybackCaptureConfiguration (API 29+). Below that there's
    // no fallback capture mechanism (see Stage 6 design notes), so the
    // feature is simply unavailable rather than shown-then-broken.
    val isAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val hasPendingRecording: Boolean = false, // stopped, unsaved, awaiting Save/Discard
    val isSaving: Boolean = false,
    val error: String? = null,
    /** Time of recording the free space allows (Save counted); null until measured. */
    val storageRemainingMs: Long? = null,
    val storageLevel: StorageBudget.Level = StorageBudget.Level.OK,
    val gapCount: Int = 0,
    /** The pending take is one found after a crash/kill, not one just stopped. */
    val recovered: Boolean = false,
)

/**
 * Mix-level recording state - deliberately NOT inside DeckViewModel (which
 * is per-deck, instantiated twice) and not plain remember{} state in
 * MixerScreen (this has a real lifecycle - a bound Service, a MediaProjection
 * token, a pending file - that needs deterministic cleanup and to survive
 * configuration changes, exactly what AndroidViewModel already gives
 * DeckViewModel). Fetched with the default key since there is exactly one
 * recording session for the whole mix, not one per deck.
 *
 * Capture itself lives entirely in RecordingService, downstream of both
 * decks and the crossfader - this class only orchestrates permission/
 * projection setup, binds to observe the service's progress, and handles
 * the Save/Discard -> MediaStore export step once stopped.
 */
class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    init {
        recoverUnfinishedTake()
        refreshIdleStorage()
    }

    private var boundService: RecordingService? = null
    private var pendingFile: File? = null
    private var serviceConnection: ServiceConnection? = null
    private var collectJob: Job? = null

    /** Called by MainActivity once RECORD_AUDIO/POST_NOTIFICATIONS have been
     * granted (or skipped where optional) and the MediaProjection consent
     * intent has returned. */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            _state.value = _state.value.copy(error = "Recording permission was declined.")
            return
        }
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
        }
        context.startForegroundService(intent)
        bindToService()
    }

    fun onPermissionDenied(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    private fun bindToService() {
        val context = getApplication<Application>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as? RecordingService.LocalBinder)?.getService() ?: return
                boundService = service
                collectJob = viewModelScope.launch {
                    service.state.collect { s ->
                        if (s.finishedFile != null) pendingFile = s.finishedFile
                        _state.value = _state.value.copy(
                            isRecording = s.isRecording,
                            elapsedMs = s.elapsedMs,
                            hasPendingRecording = s.finishedFile != null,
                            error = s.error ?: _state.value.error,
                            storageRemainingMs = s.storageRemainingMs ?: _state.value.storageRemainingMs,
                            storageLevel = s.storageLevel,
                            gapCount = s.gapCount,
                        )
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        serviceConnection = connection
        context.bindService(Intent(context, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** No-op if not currently recording - guards against a rapid double-tap
     * racing a second stop onto an already-stopping/stopped session. */
    fun stop() {
        if (!_state.value.isRecording) return
        boundService?.stopRecording()
    }

    fun discard() {
        pendingFile?.delete()
        pendingFile = null
        _state.value = _state.value.copy(hasPendingRecording = false, recovered = false, elapsedMs = 0L)
        unbindService()
        stopRecordingService()
        recoverUnfinishedTake() // an older unfinished take may be waiting behind this one
    }

    /** Free space for the idle "Space for about ..." line. */
    fun refreshIdleStorage() {
        if (_state.value.isRecording) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = RecordingService.recordingsDir(getApplication())
            val free = android.os.StatFs(dir.path).availableBytes
            val ms = StorageBudget.remainingMs(free, StorageBudget.RESERVE_BYTES, StorageBudget.bytesPerSecond(48000, 2), 2)
            _state.value = _state.value.copy(storageRemainingMs = ms, storageLevel = StorageBudget.level(ms, StorageBudget.shouldStop(free)))
        }
    }

    /**
     * A take left behind by a crash, a kill or a reboot: any WAV in the
     * recordings folder that is not the one being recorded right now. Its
     * header is repaired from the real file length (the writer re-patches it
     * every ~2 s, so at most a few seconds are missing) and it is offered
     * through the normal Save/Discard row. Under a second of audio is junk
     * and deleted. One take at a time; the rest queue behind it.
     */
    private fun recoverUnfinishedTake() {
        if (RecordingService.running || pendingFile != null || _state.value.isRecording) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = RecordingService.recordingsDir(getApplication())
            val candidates = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: return@launch
            for (f in candidates) {
                val marker = File(dir, f.name + ".active")
                try {
                    val head = ByteArray(WavHeader.SIZE)
                    val ok = f.inputStream().use { it.read(head) == WavHeader.SIZE }
                    val fixed = if (ok) WavHeader.repaired(head, f.length()) else null
                    val info = fixed?.let { WavHeader.parse(it) }
                    val audioMs = info?.let { WavHeader.durationMs(it, f.length() - WavHeader.SIZE) } ?: 0L
                    if (fixed == null || info == null || audioMs < 1000) {
                        f.delete(); marker.delete()
                        continue
                    }
                    java.io.RandomAccessFile(f, "rw").use { it.seek(0); it.write(fixed) }
                    marker.delete()
                    pendingFile = f
                    _state.value = _state.value.copy(hasPendingRecording = true, elapsedMs = audioMs, recovered = true)
                    return@launch
                } catch (_: Exception) {
                    // unreadable: leave it for a later launch rather than delete data we could not inspect
                }
            }
        }
    }

    fun save() {
        val file = pendingFile ?: return
        if (_state.value.isSaving) return
        val needed = StorageBudget.saveNeeds(file.length())
        val free = android.os.StatFs(file.parentFile?.path ?: getApplication<Application>().filesDir.path).availableBytes
        if (free < needed) {
            _state.value = _state.value.copy(error = "Not enough free space to save (needs ${needed / (1024 * 1024)} MB). Free some space and try again.")
            return
        }
        _state.value = _state.value.copy(isSaving = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val resolver = context.contentResolver
            val name = "DJayClone_Mix_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/DJayClone")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            var uri: android.net.Uri? = null
            try {
                uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Could not open output stream for the new recording")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                file.delete()
                pendingFile = null
                _state.value = _state.value.copy(isSaving = false, hasPendingRecording = false, recovered = false, elapsedMs = 0L)
                unbindService()
                stopRecordingService()
                recoverUnfinishedTake()
            } catch (e: IOException) {
                // Don't leave an orphaned IS_PENDING row in the user's Music
                // library if the copy itself failed partway through.
                uri?.let { resolver.delete(it, null, null) }
                _state.value = _state.value.copy(isSaving = false, error = "Save failed: ${e.message}")
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun unbindService() {
        serviceConnection?.let {
            try {
                getApplication<Application>().unbindService(it)
            } catch (_: IllegalArgumentException) {
                // Already unbound (e.g. service process died) - fine to ignore.
            }
        }
        serviceConnection = null
        collectJob?.cancel()
        boundService = null
    }

    /**
     * Fully releases the service once a recording session is genuinely
     * over (Saved or Discarded) - unbindService() alone only drops OUR
     * binding; the service was also started via startForegroundService(),
     * so without this it keeps running as an orphaned foreground process
     * indefinitely. Confirmed for real: starting a second recording while
     * the first's service instance was still alive hit onStartCommand()'s
     * stale "already capturing" guard and silently never captured any
     * audio, while still showing a convincing permission/notification flow.
     *
     * Deliberately a separate call at the two "session is over" call sites
     * (save()/discard()) rather than folded into unbindService() itself -
     * onCleared() also calls unbindService(), and stopping the service
     * there would kill a still-in-progress recording just because the
     * ViewModel got cleared (e.g. the Activity finishing while backgrounded
     * mid-recording) - defeating the entire point of this being a
     * foreground service in the first place (see this class's own doc
     * comment: capture must survive that).
     */
    private fun stopRecordingService() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, RecordingService::class.java))
    }

    override fun onCleared() {
        unbindService()
        super.onCleared()
    }
}

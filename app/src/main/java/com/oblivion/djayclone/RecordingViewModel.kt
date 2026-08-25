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
        _state.value = _state.value.copy(hasPendingRecording = false)
        unbindService()
    }

    fun save() {
        val file = pendingFile ?: return
        if (_state.value.isSaving) return
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
                _state.value = _state.value.copy(isSaving = false, hasPendingRecording = false)
                unbindService()
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

    override fun onCleared() {
        unbindService()
        super.onCleared()
    }
}

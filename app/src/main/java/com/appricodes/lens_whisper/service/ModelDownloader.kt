package com.appricodes.lens_whisper.service

import android.app.DownloadManager
import android.content.Context
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Downloading(val modelId: String, val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState()
    data class Completed(val modelId: String) : ModelDownloadState()
    data class Failed(val modelId: String, val message: String) : ModelDownloadState()
    data class Cancelled(val modelId: String) : ModelDownloadState()
}

/**
 * Downloads one on-device model file at a time through the system's DownloadManager (see
 * [SystemDownloads] for why), into [OfflineModelsRepository.modelsDir] as `<fileName>.part`,
 * renamed to its final name only once complete — so a half-downloaded model is never mistaken
 * for a usable one.
 *
 * Progress is published on a process-wide [StateFlow] polled from DownloadManager while this
 * process lives. The pending download's id is kept in SharedPreferences, so [resume] — called at
 * app start and by [DownloadCompleteReceiver] — can finish the job after the app was closed.
 */
object ModelDownloader {

    private const val PREFS = "model_download"
    private const val KEY_ID = "download_id"
    private const val KEY_MODEL = "model_id"
    private const val KEY_FILE = "file_name"
    private const val KEY_SIZE = "size_bytes"
    private const val KEY_URL = "url"
    private const val KEY_TITLE = "title"
    private const val KEY_ATTEMPT = "attempt"
    private const val POLL_INTERVAL_MS = 500L

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private class Pending(
        val id: Long, val modelId: String, val fileName: String, val sizeBytes: Long,
        val url: String, val title: String, val attempt: Int
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(context: Context): Pending? {
        val p = prefs(context)
        val id = p.getLong(KEY_ID, -1L)
        if (id < 0) return null
        return Pending(
            id, p.getString(KEY_MODEL, null) ?: return null, p.getString(KEY_FILE, null) ?: return null,
            p.getLong(KEY_SIZE, 0L), p.getString(KEY_URL, null) ?: return null, p.getString(KEY_TITLE, "").orEmpty(),
            p.getInt(KEY_ATTEMPT, 0)
        )
    }

    private fun clearPending(context: Context) = prefs(context).edit().clear().apply()

    /** Starts downloading [model], replacing any model download still in progress. */
    @Synchronized
    fun start(context: Context, model: OfflineModelInfo) {
        val app = context.applicationContext
        pollJob?.cancel()
        pending(app)?.let { SystemDownloads.remove(app, it.id) }
        clearPending(app)

        val title = "Downloading ${model.displayName}"
        val id = SystemDownloads.enqueue(app, model.downloadUrl, title, OfflineModelsRepository.MODELS_DIR, model.fileName + ".part")
        if (id == null) {
            _state.value = ModelDownloadState.Failed(model.id, "The phone's storage isn't available.")
            return
        }
        prefs(app).edit()
            .putLong(KEY_ID, id).putString(KEY_MODEL, model.id)
            .putString(KEY_FILE, model.fileName).putLong(KEY_SIZE, model.sizeBytes)
            .putString(KEY_URL, model.downloadUrl).putString(KEY_TITLE, title).putInt(KEY_ATTEMPT, 0)
            .apply()
        _state.value = ModelDownloadState.Downloading(model.id, 0, model.sizeBytes)
        resume(app)
    }

    @Synchronized
    fun cancel(context: Context) {
        val app = context.applicationContext
        val p = pending(app) ?: return
        pollJob?.cancel()
        SystemDownloads.remove(app, p.id)
        clearPending(app)
        _state.value = ModelDownloadState.Cancelled(p.modelId)
    }

    /** Follows the pending download, if there is one and nothing is following it yet. Cheap
     *  when there is none — it only reads SharedPreferences. */
    @Synchronized
    fun resume(context: Context) {
        val app = context.applicationContext
        if (pollJob?.isActive == true || pending(app) == null) return
        pollJob = scope.launch {
            while (isActive && step(app)) delay(POLL_INTERVAL_MS)
        }
    }

    /** One look at the pending download; returns whether it is still running. */
    @Synchronized
    private fun step(app: Context): Boolean {
        val p = pending(app) ?: return false
        val status = SystemDownloads.query(app, p.id)
        when (status?.status) {
            null -> {
                // Gone from DownloadManager: cancelled from the system's Downloads UI.
                clearPending(app)
                _state.value = ModelDownloadState.Cancelled(p.modelId)
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                val part = status.localFile ?: OfflineModelsRepository.modelsDir(app)?.let { File(it, p.fileName + ".part") }
                val target = part?.let { File(it.parentFile, p.fileName) }
                target?.delete()
                if (part != null && target != null && part.renameTo(target)) {
                    // The entry's own path no longer exists, so this only drops it from the list.
                    SystemDownloads.remove(app, p.id)
                    clearPending(app)
                    _state.value = ModelDownloadState.Completed(p.modelId)
                } else {
                    SystemDownloads.remove(app, p.id)
                    clearPending(app)
                    _state.value = ModelDownloadState.Failed(p.modelId, "Could not finalize the downloaded file.")
                }
            }
            DownloadManager.STATUS_FAILED -> {
                SystemDownloads.remove(app, p.id)
                if (SystemDownloads.isRetryable(status.reason) && p.attempt < SystemDownloads.MAX_RETRIES) {
                    val retryId = SystemDownloads.enqueue(app, p.url, p.title, OfflineModelsRepository.MODELS_DIR, p.fileName + ".part")
                    if (retryId != null) {
                        prefs(app).edit().putLong(KEY_ID, retryId).putInt(KEY_ATTEMPT, p.attempt + 1).apply()
                        return true
                    }
                }
                clearPending(app)
                _state.value = ModelDownloadState.Failed(p.modelId, SystemDownloads.failureMessage(status.reason))
            }
            else -> {
                // Pending, running, or paused waiting for a network — all "still downloading".
                val total = if (status.total > 0) status.total else p.sizeBytes
                _state.value = ModelDownloadState.Downloading(p.modelId, status.downloaded.coerceAtLeast(0), total)
                return true
            }
        }
        return false
    }
}

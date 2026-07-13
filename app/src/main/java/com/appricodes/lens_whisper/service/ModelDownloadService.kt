package com.appricodes.lens_whisper.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.appricodes.lens_whisper.R
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Downloading(val modelId: String, val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState()
    data class Completed(val modelId: String) : ModelDownloadState()
    data class Failed(val modelId: String, val message: String) : ModelDownloadState()
    data class Cancelled(val modelId: String) : ModelDownloadState()
}

/**
 * Downloads a single on-device model file to [Context.getFilesDir] as a foreground service, so
 * the download keeps running while the app is backgrounded or the screen is locked. Progress is
 * published on a process-wide [StateFlow] that [com.appricodes.lens_whisper.ModelManagementActivity]
 * collects directly (same process, no binder needed).
 */
class ModelDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val STATE_EMIT_INTERVAL_MS = 150L

        const val ACTION_START = "com.appricodes.lens_whisper.action.START_MODEL_DOWNLOAD"
        const val ACTION_CANCEL = "com.appricodes.lens_whisper.action.CANCEL_MODEL_DOWNLOAD"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_SIZE_BYTES = "size_bytes"
        private const val EXTRA_DISPLAY_NAME = "display_name"

        private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
        val state: StateFlow<ModelDownloadState> = _state

        fun start(context: Context, model: OfflineModelInfo) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, model.id)
                putExtra(EXTRA_URL, model.downloadUrl)
                putExtra(EXTRA_FILE_NAME, model.fileName)
                putExtra(EXTRA_SIZE_BYTES, model.sizeBytes)
                putExtra(EXTRA_DISPLAY_NAME, model.displayName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ModelDownloadService::class.java).apply { action = ACTION_CANCEL })
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // A dedicated, background-priority thread — not the shared Dispatchers.IO pool — so the
    // read/write loop's sustained CPU use (TLS decrypt + disk writes at LAN speeds) doesn't
    // starve the UI/render thread of CPU and cause the progress dialog to stutter.
    private val downloadDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ModelDownload")
    }.asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + downloadDispatcher)
    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                val url = intent.getStringExtra(EXTRA_URL)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                if (modelId == null || url == null || fileName == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)
                val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: fileName
                startDownload(modelId, url, fileName, sizeBytes, displayName)
            }
            ACTION_CANCEL -> downloadJob?.cancel()
        }
        return START_NOT_STICKY
    }

    private fun startDownload(modelId: String, url: String, fileName: String, sizeBytes: Long, displayName: String) {
        downloadJob?.cancel()

        startForegroundCompat(buildNotification(displayName, 0))
        _state.value = ModelDownloadState.Downloading(modelId, 0, sizeBytes)

        downloadJob = serviceScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val tempFile = File(filesDir, "$fileName.part")
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server error ${response.code}")
                    val body = response.body
                    val total = if (sizeBytes > 0) sizeBytes else body.contentLength()
                    var downloaded = 0L
                    var lastNotifyAt = 0L
                    var lastStateEmitAt = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                // Throttled: at LAN/wifi speeds this loop can run hundreds of times
                                // per second, and emitting on every chunk was driving Compose
                                // recomposition of the progress dialog that often, causing UI lag.
                                val now = System.currentTimeMillis()
                                if (now - lastStateEmitAt > STATE_EMIT_INTERVAL_MS) {
                                    lastStateEmitAt = now
                                    _state.value = ModelDownloadState.Downloading(modelId, downloaded, total)
                                }
                                if (now - lastNotifyAt > 400) {
                                    lastNotifyAt = now
                                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                                    updateNotification(displayName, percent)
                                }
                            }
                        }
                    }
                    _state.value = ModelDownloadState.Downloading(modelId, downloaded, total)
                }

                if (!isActive) throw CancellationException("Download cancelled")

                val finalFile = File(filesDir, fileName)
                finalFile.delete()
                if (!tempFile.renameTo(finalFile)) throw IOException("Could not finalize downloaded file")
                _state.value = ModelDownloadState.Completed(modelId)
            } catch (e: CancellationException) {
                tempFile.delete()
                _state.value = ModelDownloadState.Cancelled(modelId)
            } catch (e: Exception) {
                Log.e("ModelDownloadService", "Download failed for $modelId", e)
                tempFile.delete()
                _state.value = ModelDownloadState.Failed(modelId, e.localizedMessage ?: "Download failed")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(displayName: String, percent: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(displayName, percent))
    }

    private fun buildNotification(displayName: String, percent: Int): android.app.Notification {
        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading $displayName")
            .setContentText(if (percent in 0..100) "$percent%" else "Downloading…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        downloadDispatcher.close()
    }
}

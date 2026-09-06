package com.appricodes.lens_whisper.service

import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.appricodes.lens_whisper.R
import com.appricodes.lens_whisper.data.ProductDatabase
import com.appricodes.lens_whisper.data.ProductDbInfo
import com.appricodes.lens_whisper.data.ProductDbSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Where a product-database download has got to. Mirrors [ModelDownloadState]'s shape so the
 *  Settings UI reads the same way for both kinds of download. */
sealed class ProductDbDownloadState {
    data object Idle : ProductDbDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ProductDbDownloadState()
    /** After the bytes are in and while the file is being checked — on a database this size that is
     *  a genuinely long step of its own, not an instant, so it gets its own state rather than
     *  leaving the progress bar sitting at 100% looking hung. */
    data object Finishing : ProductDbDownloadState()
    data object Completed : ProductDbDownloadState()
    data class Failed(val message: String) : ProductDbDownloadState()
    data object Cancelled : ProductDbDownloadState()
}

/**
 * Downloads an offline product database (see [com.appricodes.lens_whisper.data.ProductDbSource])
 * in the foreground, so a download measured in hundreds of megabytes survives the user leaving the
 * app — which, at these sizes over a phone connection, they certainly will.
 *
 * **Why this is not [ModelDownloadService].** That service is built around `OfflineModelInfo` and a
 * model id, and it does a plain byte-for-byte fetch. This one has to gunzip on the fly, verify a
 * SHA-256 of the *unpacked* result, and check free space against an installed size that is several
 * times the download size. Bending the model downloader to do both would put two sets of conditional
 * behaviour into the one path that must not break — model downloads are how the app's main feature
 * is installed — for no gain. The download loop's shape (throttled state emission, `.part` temp
 * file, atomic rename, foreground notification) is deliberately the same, and any fix to one is
 * worth looking at for the other.
 *
 * **Verification is not optional here.** A truncated 700 MB file is still a valid-looking SQLite
 * header, so a partial download would install cleanly and then return wrong answers or throw deep
 * inside a query. The checksum is what makes "installed" mean "complete".
 */
class ProductDbDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "product_db_downloads"
        private const val NOTIFICATION_ID = 4301
        private const val STATE_EMIT_INTERVAL_MS = 150L

        const val ACTION_START = "com.appricodes.lens_whisper.action.START_PRODUCT_DB_DOWNLOAD"
        const val ACTION_CANCEL = "com.appricodes.lens_whisper.action.CANCEL_PRODUCT_DB_DOWNLOAD"

        private const val EXTRA_SIZE_BYTES = "size_bytes"
        private const val EXTRA_SHA256 = "sha256"

        private val _state = MutableStateFlow<ProductDbDownloadState>(ProductDbDownloadState.Idle)
        val state: StateFlow<ProductDbDownloadState> = _state

        /** [info] is what [ProductDbSource.probe] just reported — the size to show progress
         *  against and, when the server publishes one, the checksum to verify against. */
        fun start(context: Context, info: ProductDbInfo) {
            val intent = Intent(context, ProductDbDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SIZE_BYTES, info.sizeBytes)
                putExtra(EXTRA_SHA256, info.sha256)
            }
            // minSdk is 26, so startForegroundService is always the right call here.
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, ProductDbDownloadService::class.java).apply { action = ACTION_CANCEL }
            )
        }

        /** Lets the UI drop a finished/failed state once it has been shown, so reopening the screen
         *  does not re-announce an outcome from ten minutes ago. */
        fun acknowledgeState() {
            _state.value = ProductDbDownloadState.Idle
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // No read timeout: a slow connection pulling hundreds of megabytes must not be killed
        // mid-stream just for being slow.
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Product database downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDownload(
                sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L),
                expectedSha256 = intent.getStringExtra(EXTRA_SHA256).orEmpty()
            )
            ACTION_CANCEL -> downloadJob?.cancel()
        }
        return START_NOT_STICKY
    }

    private fun startDownload(sizeBytes: Long, expectedSha256: String) {
        downloadJob?.cancel()
        startForegroundCompat(buildNotification(0))
        _state.value = ProductDbDownloadState.Downloading(0, sizeBytes)

        downloadJob = serviceScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val targetDir = ProductDatabase.directory(this@ProductDbDownloadService).apply { mkdirs() }
            val tempFile = File(targetDir, ProductDbSource.LOCAL_FILE_NAME + ".part")
            val gzipped = ProductDbSource.isGzipped
            try {
                // When the file is served gzipped, what lands on disk is several times what comes
                // down the wire, so the space check has to allow for the unpacked size — running out
                // of room with the download already finished would waste the whole transfer. The
                // multiplier is a deliberate over-estimate; being wrong in the cautious direction
                // only costs a clearer error message.
                val needed = if (gzipped) sizeBytes * 4 else sizeBytes
                if (needed > 0 && availableSpaceFor(targetDir) < needed + SPACE_HEADROOM_BYTES) {
                    throw IOException("Not enough free space on the phone for this database.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var expectedNetworkBytes = 0L
                client.newCall(Request.Builder().url(ProductDbSource.DATABASE_URL).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server error ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    expectedNetworkBytes = body.contentLength()
                    val total = if (sizeBytes > 0) sizeBytes else body.contentLength()
                    var downloaded = 0L
                    var lastNotifyAt = 0L
                    var lastStateEmitAt = 0L

                    // Progress is counted on the *network* stream, so the bar tracks what the user
                    // is actually waiting for and matches the size they agreed to; the digest is
                    // taken on the *unpacked* stream, which is what the catalog's checksum covers.
                    val counting = object : java.io.FilterInputStream(body.byteStream()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val read = super.read(b, off, len)
                            if (read > 0) {
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastStateEmitAt > STATE_EMIT_INTERVAL_MS) {
                                    lastStateEmitAt = now
                                    _state.value = ProductDbDownloadState.Downloading(downloaded, total)
                                }
                                if (now - lastNotifyAt > 400) {
                                    lastNotifyAt = now
                                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                                    updateNotification(percent)
                                }
                            }
                            return read
                        }
                    }

                    val source: InputStream = if (gzipped) GZIPInputStream(counting, 64 * 1024) else counting
                    source.use { input ->
                        DigestOutputStream(FileOutputStream(tempFile).buffered(), digest).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    _state.value = ProductDbDownloadState.Downloading(downloaded, total)
                    // Checked before the checksum because it is both the likelier failure and the
                    // clearer answer: the connection dropped part-way through.
                    if (expectedNetworkBytes > 0 && downloaded != expectedNetworkBytes) {
                        throw IOException("The download ended early - only part of the database arrived.")
                    }
                }

                if (!isActive) throw CancellationException("Download cancelled")

                _state.value = ProductDbDownloadState.Finishing
                updateNotification(100)

                if (expectedSha256.isNotBlank()) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                        // A truncated database still looks like a valid SQLite file, so without this
                        // it would install and then answer wrongly. See the class doc.
                        throw IOException("The downloaded database was incomplete or damaged.")
                    }
                }
                // Belt and braces, and the only check available when the server publishes no
                // checksum sidecar: the file must actually open and answer a query.
                if (!ProductDatabase.isUsableDatabase(tempFile)) {
                    throw IOException("The downloaded file was not a usable product database.")
                }

                val finalFile = ProductDatabase.file(this@ProductDbDownloadService)
                // Any open handle must go before the file underneath it is replaced.
                ProductDatabase.invalidate()
                finalFile.delete()
                if (!tempFile.renameTo(finalFile)) throw IOException("Could not finalize the downloaded database")
                _state.value = ProductDbDownloadState.Completed
            } catch (e: CancellationException) {
                tempFile.delete()
                _state.value = ProductDbDownloadState.Cancelled
            } catch (e: Exception) {
                Log.e("ProductDbDownload", "Product database download failed", e)
                tempFile.delete()
                _state.value = ProductDbDownloadState.Failed(e.localizedMessage ?: "Download failed")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Free space, counting what the system could reclaim from other apps' caches if asked —
     *  `File.usableSpace` alone under-reports on a phone whose storage is mostly cache, and would
     *  refuse a download that would in fact have fit. Falls back to the plain figure if the storage
     *  manager cannot answer for this path. */
    private fun availableSpaceFor(dir: File): Long = try {
        val storageManager = getSystemService(android.os.storage.StorageManager::class.java)
        val uuid = storageManager.getUuidForPath(dir)
        maxOf(storageManager.getAllocatableBytes(uuid), dir.usableSpace)
    } catch (e: Exception) {
        dir.usableSpace
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(percent: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(percent))
    }

    private fun buildNotification(percent: Int): android.app.Notification {
        // A cancel action on the notification itself, same as ModelDownloadService's: this download
        // can run for a long time, and the user should be able to stop it without hunting for the
        // app first.
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ProductDbDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading the product database")
            .setContentText(if (percent in 0..100) "$percent%" else "Starting…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

/** Never fill the phone right to the brim — a device with literally no free space left misbehaves
 *  in ways far worse than a failed download. */
private const val SPACE_HEADROOM_BYTES = 200L * 1024 * 1024

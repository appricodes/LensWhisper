package com.appricodes.lens_whisper.service

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import com.appricodes.lens_whisper.data.ProductDatabase
import com.appricodes.lens_whisper.data.ProductDbInfo
import com.appricodes.lens_whisper.data.ProductDbSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
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
 * Downloads the offline product database (see [ProductDbSource]) through the system's
 * DownloadManager (see [SystemDownloads] for why) into a staging file, then checks it and
 * installs it into [ProductDatabase.directory] — unpacking it first when it is served gzipped.
 *
 * **Why this is not [ModelDownloader].** That one just renames a finished file into place. This
 * one has to unpack, verify a SHA-256 of the *unpacked* result, and check free space against an
 * installed size that can be several times the download size.
 *
 * **Verification is not optional here.** A truncated 700 MB file is still a valid-looking SQLite
 * header, so a partial download would install cleanly and then return wrong answers or throw deep
 * inside a query. The checksum is what makes "installed" mean "complete".
 *
 * The pending download (id, expected checksum, size) is kept in SharedPreferences, and the staging
 * file stays until it has been installed or rejected, so if the app is closed mid-download or
 * mid-check, [resume] — at app start, or from [DownloadCompleteReceiver] — simply picks it up again.
 */
object ProductDbDownloader {

    private const val PREFS = "product_db_download"
    private const val KEY_ID = "download_id"
    private const val KEY_SHA = "sha256"
    private const val KEY_SIZE = "size_bytes"
    private const val KEY_ATTEMPT = "attempt"
    private const val TITLE = "Downloading the product database"
    private const val STAGING_DIR = "product_db"
    private const val STAGING_NAME = ProductDbSource.LOCAL_FILE_NAME + ".download"
    private const val POLL_INTERVAL_MS = 500L

    /** Never fill the phone right to the brim — a device with literally no free space left
     *  misbehaves in ways far worse than a failed download. */
    private const val SPACE_HEADROOM_BYTES = 200L * 1024 * 1024

    private val _state = MutableStateFlow<ProductDbDownloadState>(ProductDbDownloadState.Idle)
    val state: StateFlow<ProductDbDownloadState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private class Pending(val id: Long, val sha256: String, val sizeBytes: Long, val attempt: Int)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(context: Context): Pending? {
        val p = prefs(context)
        val id = p.getLong(KEY_ID, -1L)
        return if (id < 0) null else Pending(id, p.getString(KEY_SHA, "").orEmpty(), p.getLong(KEY_SIZE, 0L), p.getInt(KEY_ATTEMPT, 0))
    }

    private fun clearPending(context: Context) = prefs(context).edit().clear().apply()

    private fun tempFile(context: Context) = File(ProductDatabase.directory(context), ProductDbSource.LOCAL_FILE_NAME + ".part")

    /** [info] is what [ProductDbSource.probe] just reported — the size to show progress
     *  against and, when the server publishes one, the checksum to verify against. */
    @Synchronized
    fun start(context: Context, info: ProductDbInfo) {
        val app = context.applicationContext
        pollJob?.cancel()
        pending(app)?.let { SystemDownloads.remove(app, it.id) }
        clearPending(app)

        // The staging copy and the installed copy exist side by side while it is checked, and a
        // gzipped download unpacks to several times its own size, so the space check covers both.
        // The multiplier is a deliberate over-estimate; being wrong in the cautious direction only
        // costs a clearer error message.
        val size = info.sizeBytes
        val needed = if (ProductDbSource.isGzipped) size * 5 else size * 2
        val targetDir = ProductDatabase.directory(app).apply { mkdirs() }
        if (size > 0 && availableSpaceFor(app, targetDir) < needed + SPACE_HEADROOM_BYTES) {
            _state.value = ProductDbDownloadState.Failed("Not enough free space on the phone for this database.")
            return
        }
        val id = SystemDownloads.enqueue(app, ProductDbSource.DATABASE_URL, TITLE, STAGING_DIR, STAGING_NAME)
        if (id == null) {
            _state.value = ProductDbDownloadState.Failed("The phone's storage isn't available.")
            return
        }
        prefs(app).edit().putLong(KEY_ID, id).putString(KEY_SHA, info.sha256).putLong(KEY_SIZE, size).putInt(KEY_ATTEMPT, 0).apply()
        _state.value = ProductDbDownloadState.Downloading(0, size)
        resume(app)
    }

    @Synchronized
    fun cancel(context: Context) {
        val app = context.applicationContext
        val p = pending(app) ?: return
        pollJob?.cancel()
        SystemDownloads.remove(app, p.id)
        tempFile(app).delete()
        clearPending(app)
        _state.value = ProductDbDownloadState.Cancelled
    }

    /** Lets the UI drop a finished/failed state once it has been shown, so reopening the screen
     *  does not re-announce an outcome from ten minutes ago. */
    fun acknowledgeState() {
        _state.value = ProductDbDownloadState.Idle
    }

    /** Follows the pending download (and installs it once complete), if there is one and nothing
     *  is following it yet. Cheap when there is none — it only reads SharedPreferences. */
    @Synchronized
    fun resume(context: Context) {
        val app = context.applicationContext
        if (pollJob?.isActive == true || pending(app) == null) return
        pollJob = scope.launch {
            while (isActive) {
                val p = pending(app) ?: break
                val status = SystemDownloads.query(app, p.id)
                when (status?.status) {
                    null -> {
                        // Gone from DownloadManager: cancelled from the system's Downloads UI.
                        clearPending(app)
                        _state.value = ProductDbDownloadState.Cancelled
                        break
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        install(app, p, status.localFile ?: File(app.getExternalFilesDir(STAGING_DIR), STAGING_NAME))
                        break
                    }
                    DownloadManager.STATUS_FAILED -> {
                        SystemDownloads.remove(app, p.id)
                        if (SystemDownloads.isRetryable(status.reason) && p.attempt < SystemDownloads.MAX_RETRIES) {
                            val retryId = SystemDownloads.enqueue(app, ProductDbSource.DATABASE_URL, TITLE, STAGING_DIR, STAGING_NAME)
                            if (retryId != null) {
                                prefs(app).edit().putLong(KEY_ID, retryId).putInt(KEY_ATTEMPT, p.attempt + 1).apply()
                                delay(POLL_INTERVAL_MS)
                                continue
                            }
                        }
                        clearPending(app)
                        _state.value = ProductDbDownloadState.Failed(SystemDownloads.failureMessage(status.reason))
                        break
                    }
                    else -> {
                        val total = if (status.total > 0) status.total else p.sizeBytes
                        _state.value = ProductDbDownloadState.Downloading(status.downloaded.coerceAtLeast(0), total)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Unpacks (if gzipped) and checksums the staged download into a temp file next to the
     *  database, checks it opens, and swaps it in. Removing the download afterwards also deletes
     *  the staging file. */
    private suspend fun install(app: Context, p: Pending, staged: File) {
        _state.value = ProductDbDownloadState.Finishing
        val temp = tempFile(app)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val raw: InputStream = FileInputStream(staged).buffered(64 * 1024)
            val source = if (ProductDbSource.isGzipped) GZIPInputStream(raw, 64 * 1024) else raw
            source.use { input ->
                DigestOutputStream(FileOutputStream(temp).buffered(), digest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!currentCoroutineContext().isActive) throw CancellationException("Cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (p.sha256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(p.sha256.trim(), ignoreCase = true)) {
                    // A truncated database still looks like a valid SQLite file, so without this
                    // it would install and then answer wrongly. See the class doc.
                    throw IOException("The downloaded database was incomplete or damaged.")
                }
            }
            // Belt and braces, and the only check available when the server publishes no checksum.
            if (!ProductDatabase.isUsableDatabase(temp)) {
                throw IOException("The downloaded file was not a usable product database.")
            }
            val finalFile = ProductDatabase.file(app)
            // Any open handle must go before the file underneath it is replaced.
            ProductDatabase.invalidate()
            finalFile.delete()
            if (!temp.renameTo(finalFile)) throw IOException("Could not finalize the downloaded database")
            SystemDownloads.remove(app, p.id)
            clearPending(app)
            _state.value = ProductDbDownloadState.Completed
        } catch (e: CancellationException) {
            // cancel() has already cleaned up; or the process is going away, in which case the
            // staged file is still there for resume() to install next time.
            temp.delete()
            throw e
        } catch (e: Exception) {
            Log.e("ProductDbDownload", "Product database install failed", e)
            temp.delete()
            SystemDownloads.remove(app, p.id)
            clearPending(app)
            _state.value = ProductDbDownloadState.Failed(e.localizedMessage ?: "Download failed")
        }
    }

    /** Free space, counting what the system could reclaim from other apps' caches if asked —
     *  `File.usableSpace` alone under-reports on a phone whose storage is mostly cache, and would
     *  refuse a download that would in fact have fit. Falls back to the plain figure if the storage
     *  manager cannot answer for this path. */
    private fun availableSpaceFor(context: Context, dir: File): Long = try {
        val storageManager = context.getSystemService(android.os.storage.StorageManager::class.java)
        val uuid = storageManager.getUuidForPath(dir)
        maxOf(storageManager.getAllocatableBytes(uuid), dir.usableSpace)
    } catch (e: Exception) {
        dir.usableSpace
    }
}

package com.appricodes.lens_whisper.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * The thin layer over Android's [DownloadManager] both downloaders share.
 *
 * Big downloads (on-device models of several GB, the product database of hundreds of MB) go
 * through the system's own download service rather than one of ours: it keeps going when the user
 * leaves the app or locks the screen, resumes after a dropped connection, and shows its own
 * progress notification — all without this app holding a foreground service, so the app needs
 * neither the FOREGROUND_SERVICE/FOREGROUND_SERVICE_DATA_SYNC permissions nor POST_NOTIFICATIONS.
 *
 * Files land in the app's own external files directory (the only place DownloadManager can write
 * for an app without storage permissions); it is private to the app on every version this app
 * supports and is deleted with it.
 */
internal object SystemDownloads {

    class Status(val status: Int, val downloaded: Long, val total: Long, val reason: Int, val localFile: File?)

    private fun manager(context: Context): DownloadManager = context.getSystemService(DownloadManager::class.java)

    /** Queues [url] for download into `getExternalFilesDir(subDir)/fileName`, replacing any stale
     *  file of that name first (DownloadManager would otherwise pick a new name beside it). */
    fun enqueue(context: Context, url: String, title: String, subDir: String, fileName: String): Long? {
        val dir = context.getExternalFilesDir(subDir) ?: return null
        File(dir, fileName).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, subDir, fileName)
        return manager(context).enqueue(request)
    }

    /** The download's current status, or null when DownloadManager no longer knows it — which
     *  is what happens when the user cancels it from the system's own Downloads UI. */
    fun query(context: Context, id: Long): Status? =
        manager(context).query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            Status(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                localFile = uri?.let { Uri.parse(it).path }?.let(::File)
            )
        }

    /** Cancels the download if it is still running and forgets it; DownloadManager also deletes
     *  whatever file is still at the download's own path. */
    fun remove(context: Context, id: Long) {
        runCatching { manager(context).remove(id) }
    }

    /** How many times a download that failed on its connection is started again from scratch. */
    const val MAX_RETRIES = 3

    /** Failures worth simply starting again: the connection broke, and DownloadManager couldn't
     *  pick up where it left off — which it never can on a server that sends no ETag. Not space,
     *  storage or HTTP errors, which would only fail the same way again. */
    fun isRetryable(reason: Int) = reason == DownloadManager.ERROR_HTTP_DATA_ERROR ||
        reason == DownloadManager.ERROR_CANNOT_RESUME || reason == DownloadManager.ERROR_UNKNOWN

    /** A sentence for TalkBack from a failed download's [DownloadManager.COLUMN_REASON]. */
    fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space on the phone."
        DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ERROR -> "The phone's storage could not be written to."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "The connection was interrupted."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "The server refused the download."
        in 400..599 -> "Server error $reason."
        else -> "Download failed."
    }
}

/**
 * Wakes the downloaders when the system reports a finished download, so a model file is put in
 * place (and the product database checked and installed) even if the app was closed meanwhile.
 * Only a nudge: each downloader re-reads its own pending download's status from DownloadManager
 * before acting, so a stray or spoofed broadcast can't make it do anything. App start does the
 * same check (see [com.appricodes.lens_whisper.LensWhisperApplication]) in case this one is missed.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ModelDownloader.resume(context)
        ProductDbDownloader.resume(context)
    }
}

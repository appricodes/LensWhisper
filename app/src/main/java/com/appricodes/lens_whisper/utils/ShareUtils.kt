package com.appricodes.lens_whisper.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares a captured photo (Explore/Read Document's "Share photo") or plain text (Read Document's
 *  "Share text") via the system share sheet. [imageFile] must be under the cache dir — see
 *  `res/xml/file_paths.xml`, the only path this app's `FileProvider` exposes. */
object ShareUtils {
    fun shareImage(context: Context, imageFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share photo").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
        })
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share text").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
        })
    }
}

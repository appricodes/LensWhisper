package com.appricodes.lens_whisper.utils

import android.content.Context
import android.util.Log
import com.appricodes.lens_whisper.ui.components.CameraController

/**
 * Deletes the working-copy photos the app writes to its cache dir: every camera capture
 * ([CameraController.CAPTURE_FILE_PREFIX]) and the files Explore, Read Document and the file-mode
 * Describe/Explore buttons write to hand an image to the next screen. Each is timestamped, so none
 * is ever overwritten. Before this ran they piled up until the system happened to clear the cache,
 * which contradicted the Privacy Policy's "held only for as long as the analysis takes".
 *
 * Run once per process start, from [com.appricodes.lens_whisper.LensWhisperApplication]. Only
 * files older than [MIN_AGE_MS] go: a screen the system restores after killing the process can
 * still be pointing at a file it wrote moments earlier. Anything else in the cache (LiteRT-LM's
 * own cache files, for one) is left alone — only these exact prefixes are touched.
 */
object CaptureCacheCleaner {
    private const val TAG = "CaptureCacheCleaner"
    private const val MIN_AGE_MS = 5 * 60 * 1000L

    // "explore_" also covers "explore_describe_".
    private val PREFIXES = listOf(CameraController.CAPTURE_FILE_PREFIX, "explore_", "read_document_", "file_describe_")

    fun sweepInBackground(context: Context) {
        val cacheDir = context.applicationContext.cacheDir
        Thread({
            val cutoff = System.currentTimeMillis() - MIN_AGE_MS
            var deleted = 0
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".jpg") && PREFIXES.any { file.name.startsWith(it) } &&
                    file.lastModified() < cutoff && file.delete()
                ) deleted++
            }
            if (deleted > 0) Log.i(TAG, "Deleted $deleted old capture file(s)")
        }, "CaptureCacheCleaner").start()
    }
}

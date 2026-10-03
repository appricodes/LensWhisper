package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.service.ModelDownloadState

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "unknown size"
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.1f GB".format(gb)
    return "%.0f MB".format(bytes / 1_000_000.0)
}

/**
 * "Here's the size, download now?" step shown before starting a model download — from the model
 * picker popup, from `ModelManagementActivity`'s per-card Download button, and from
 * [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController.checkDownloadedOrRedirect]
 * when a model-specific button is tapped before its model is downloaded.
 */
@Composable
fun DownloadPromptDialog(model: OfflineModelInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download ${model.displayName}?") },
        text = { Text("This model is ${formatSize(model.sizeBytes)}. It will be downloaded to your device and used for on-device analysis.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Blocking download-progress dialog — extracted from the original inline `AlertDialog` in
 * `ModelManagementActivity` so the model picker popup and the download-redirect flow can reuse it
 * without duplicating the progress/cancel wiring. [onContinueInBackground], when supplied, adds a
 * button that hides this dialog without cancelling the download — the system's download manager
 * keeps it running either way (see `ModelDownloader`), this only controls whether the UI blocks on
 * it.
 */
@Composable
fun DownloadProgressDialog(
    activeDownload: ModelDownloadState.Downloading,
    displayName: String,
    onCancel: () -> Unit,
    onContinueInBackground: (() -> Unit)? = null
) {
    val progress = if (activeDownload.totalBytes > 0) {
        (activeDownload.bytesDownloaded.toFloat() / activeDownload.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    AlertDialog(
        onDismissRequest = { onContinueInBackground?.invoke() },
        title = { Text("Downloading $displayName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${formatSize(activeDownload.bytesDownloaded)} of ${formatSize(activeDownload.totalBytes)}")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel Download") } },
        dismissButton = if (onContinueInBackground != null) {
            { TextButton(onClick = onContinueInBackground) { Text("Continue in Background") } }
        } else null
    )
}

/**
 * Shown when the user taps a button belonging to a model that's already downloading right now
 * (e.g. they backed the progress dialog into the background, then tapped that model's button) —
 * re-showing the "download now?" prompt for something already downloading would make no sense.
 */
@Composable
fun StillDownloadingDialog(model: OfflineModelInfo, onShowProgress: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${model.displayName} is still downloading") },
        text = { Text("Wait for the download to finish, or choose a different model from the model list.") },
        confirmButton = { TextButton(onClick = onShowProgress) { Text("Show Progress") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

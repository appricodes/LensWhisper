package com.appricodes.lens_whisper.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.utils.AccessibilityUtils

/**
 * Blocking "Loading <model>…" alert shown while [selectedModelId] is actually loading into
 * memory ([OfflineInferenceEngine.preload], triggered right when the model is selected in
 * [ModelPickerPopup]/`ModelManagementActivity`) — auto-dismisses once
 * [OfflineInferenceEngine.loadState] reports Ready for it, at which point the model's buttons are
 * genuinely ready to use. A no-dismiss-request `AlertDialog` naturally blocks touches to the grid
 * underneath for the duration, which is the "not ready to receive button presses yet" requirement.
 *
 * If [OfflineInferenceEngine.lowMemoryPressure] flips true partway through — a real signal from
 * the OS, not a guess — the dialog's text is upgraded to say so explicitly. If loading fails
 * outright (including the load/inference timeouts in [OfflineInferenceEngine] treating an
 * abnormally long attempt as a probable memory thrash), a dismissible error dialog explains why
 * instead of just a TalkBack announcement, since this can happen well after the user has moved
 * their attention elsewhere.
 *
 * Call once, in composition, from any screen that can trigger a model switch (`CameraActivity`,
 * `MediaDescriberScreen`, `ModelManagementActivity`, `SettingsActivity`).
 */
@Composable
fun ModelLoadingObserver(context: Context, selectedModelId: String, offlineModels: List<OfflineModelInfo>) {
    val state by OfflineInferenceEngine.loadState.collectAsState()
    val lowMemory by OfflineInferenceEngine.lowMemoryPressure.collectAsState()
    val loading = (state as? OfflineInferenceEngine.LoadState.Loading)?.takeIf { it.modelId == selectedModelId }
    val failed = (state as? OfflineInferenceEngine.LoadState.Failed)?.takeIf { it.modelId == selectedModelId }

    var dismissedFailureForModelId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(failed?.modelId) {
        if (failed != null) AccessibilityUtils.announceToTalkBack(context, "Failed to load model. ${failed.message}")
    }

    if (loading != null) {
        val displayName = offlineModels.find { it.id == loading.modelId }?.displayName ?: "model"
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Loading $displayName…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text("This can take up to ~10 seconds the first time.")
                    }
                    if (lowMemory) {
                        Text(
                            "⚠ Your device just reported it's critically low on memory — this may fail or take a long time.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (failed != null && failed.modelId != dismissedFailureForModelId) {
        val displayName = offlineModels.find { it.id == failed.modelId }?.displayName ?: "the model"
        AlertDialog(
            onDismissRequest = { dismissedFailureForModelId = failed.modelId },
            title = { Text("Couldn't load $displayName") },
            text = { Text(failed.message) },
            confirmButton = {
                Button(onClick = { dismissedFailureForModelId = failed.modelId }) { Text("OK") }
            }
        )
    }
}

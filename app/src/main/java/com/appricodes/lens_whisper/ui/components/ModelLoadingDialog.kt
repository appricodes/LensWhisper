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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * outright (not enough free memory, a timeout treated as a probable memory thrash, or the app
 * having been killed mid-load last time), the engine has already switched the app to Basic tasks;
 * this announces that, shows a dismissible dialog explaining why — it can happen well after the
 * user has moved their attention elsewhere — and hands the new selection to [onSelectedModelChanged]
 * so the screen's own model state follows.
 *
 * Only a screen that is actually showing reacts: the load state is collected with the lifecycle,
 * and the screen that shows a failure acknowledges it, so it is shown exactly once.
 *
 * Call once, in composition, from any screen that can trigger a model switch (`CameraActivity`,
 * `MediaDescriberScreen`, `ModelManagementActivity`).
 */
@Composable
fun ModelLoadingObserver(
    context: Context,
    selectedModelId: String,
    offlineModels: List<OfflineModelInfo>,
    onSelectedModelChanged: (String) -> Unit
) {
    val state by OfflineInferenceEngine.loadState.collectAsStateWithLifecycle()
    val lowMemory by OfflineInferenceEngine.lowMemoryPressure.collectAsState()
    val loading = (state as? OfflineInferenceEngine.LoadState.Loading)?.takeIf { it.modelId == selectedModelId }
    val failed = (state as? OfflineInferenceEngine.LoadState.Failed)?.takeIf { it.modelId == selectedModelId || it.switchedToBasic }

    var shownFailure by remember { mutableStateOf<OfflineInferenceEngine.LoadState.Failed?>(null) }
    LaunchedEffect(failed) {
        val failure = failed ?: return@LaunchedEffect
        shownFailure = failure
        OfflineInferenceEngine.acknowledgeFailure(failure)
        if (failure.switchedToBasic) onSelectedModelChanged("mlkit")
        val name = offlineModels.find { it.id == failure.modelId }?.displayName ?: "the model"
        AccessibilityUtils.announceToTalkBack(
            context,
            "Couldn't load $name. ${failure.message}" + if (failure.switchedToBasic) " Switched to Basic tasks." else ""
        )
    }
    // The dialog below is visual — TalkBack doesn't reliably announce a freshly-shown AlertDialog's
    // text on every OEM/TalkBack version on its own, and this app announces every other state
    // transition explicitly rather than relying on that, so this does too. Fires whenever loading
    // genuinely starts, whatever triggered it — an explicit selection in ModelPickerPopup, or the
    // proactive preload CameraActivity/MediaDescriberScreen now kick off as soon as they open.
    LaunchedEffect(loading?.modelId) {
        if (loading != null) {
            val displayName = offlineModels.find { it.id == loading.modelId }?.displayName ?: "model"
            AccessibilityUtils.announceToTalkBack(context, "Loading $displayName.")
        }
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

    shownFailure?.let { failure ->
        val displayName = offlineModels.find { it.id == failure.modelId }?.displayName ?: "the model"
        AlertDialog(
            onDismissRequest = { shownFailure = null },
            title = { Text("Couldn't load $displayName") },
            text = {
                Text(failure.message + if (failure.switchedToBasic) "\n\nSwitched to Basic tasks." else "")
            },
            confirmButton = {
                Button(onClick = { shownFailure = null }) { Text("OK") }
            }
        )
    }
}

package com.appricodes.lens_whisper.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController
import com.appricodes.lens_whisper.utils.DeviceMemoryDetector
import kotlinx.coroutines.launch

/**
 * Inline quick-switch dropdown anchored to [ModelTopBar]'s model name — replaces the old direct
 * navigation to `ModelManagementActivity` on tap. Lists MLKit, Online, and every catalog model;
 * `ModelManagementActivity` remains the separate "manage downloads" destination (full
 * descriptions, delete, image-size setting) reached via this popup's "Manage models…" item.
 *
 * Reuses [AnalysisSessionController.checkDownloadedOrRedirect] for the download-prompt flow so
 * there's one download-gating implementation, not a second one just for this popup.
 */
@Composable
fun ModelPickerPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    prefs: PreferencesManager,
    controller: AnalysisSessionController,
    selectedModelId: String,
    onModelSelected: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onNeedCredits: () -> Unit,
    /** MLKit only operates on a live camera feed — hidden on file-based screens (the Video/Image
     *  describer) where it would have nothing to do. */
    showMlkitOption: Boolean = true
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { OfflineModelsRepository(context) }
    // Repository already returns these smallest-first; this popup lists MLKit, then these
    // (small -> large), then Online last — same order as `ModelManagementActivity`.
    val offlineModels = remember { repository.loadModels() }
    var memoryWarningModel by remember { mutableStateOf<OfflineModelInfo?>(null) }
    var showLocalServerInfo by remember { mutableStateOf(false) }

    fun selectModel(id: String) {
        onModelSelected(id)
        val model = offlineModels.find { it.id == id }
        if (model != null) coroutineScope.launch { OfflineInferenceEngine.preload(context, model) }
    }

    fun selectOffline(model: OfflineModelInfo) {
        controller.checkDownloadedOrRedirect(model) {
            if (DeviceMemoryDetector.mightNotRun(context, model) && !prefs.isMemoryWarningSuppressed(model.id)) {
                memoryWarningModel = model
            } else {
                selectModel(model.id)
            }
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showMlkitOption) {
            DropdownMenuItem(
                text = { Text("MLKit (Offline)" + if (selectedModelId == "mlkit") " ✓" else "") },
                onClick = { onDismiss(); onModelSelected("mlkit") }
            )
        }
        offlineModels.forEach { model ->
            val isDownloaded = repository.isDownloaded(model)
            val label = buildString {
                append(model.displayName)
                if (!isDownloaded) append(" (not downloaded)")
                if (DeviceMemoryDetector.mightNotRun(context, model)) append(" ⚠ low memory")
                if (selectedModelId == model.id) append(" ✓")
            }
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); selectOffline(model) })
        }
        DropdownMenuItem(
            text = { Text("Online Describer" + if (selectedModelId == "online") " ✓" else "") },
            onClick = {
                onDismiss()
                val eligible = prefs.freeCredits > 0 || prefs.purchasedCredits > 0
                if (eligible) onModelSelected("online") else onNeedCredits()
            }
        )
        DropdownMenuItem(
            text = { Text("Local Server" + if (selectedModelId == "local_server") " ✓" else "") },
            onClick = {
                onDismiss()
                onModelSelected("local_server")
                if (!prefs.hasSeenLocalServerIntro) {
                    prefs.hasSeenLocalServerIntro = true
                    showLocalServerInfo = true
                }
            }
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Manage models…") }, onClick = { onDismiss(); onOpenModelManager() })
    }

    memoryWarningModel?.let { model ->
        MemoryWarningDialog(
            model = model,
            onContinue = { suppress ->
                if (suppress) prefs.suppressMemoryWarning(model.id)
                selectModel(model.id)
                memoryWarningModel = null
            },
            onCancel = { memoryWarningModel = null }
        )
    }

    if (showLocalServerInfo) {
        LocalServerInfoDialog(onDismiss = { showLocalServerInfo = false })
    }
}

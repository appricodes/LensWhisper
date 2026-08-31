package com.appricodes.lens_whisper.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appricodes.lens_whisper.ModelManagementActivity
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController

/**
 * Top bar shared by [com.appricodes.lens_whisper.CameraActivity] and the Video/Image describer:
 * a model-name button (opens [ModelPickerPopup] for a quick switch) and a More button (opens
 * [MoreMenu]).
 *
 * [showModelSelector]/[showMoreButton] let [com.appricodes.lens_whisper.SettingsActivity] reuse
 * this bar — switching models or reaching the More menu (which itself leads to Settings) from
 * inside Settings has no obvious use, and Settings is one of the More menu's own destinations.
 */
@Composable
fun ModelTopBar(
    prefs: PreferencesManager,
    controller: AnalysisSessionController,
    selectedModelId: String,
    onModelSelected: (String) -> Unit,
    showMoreMenuOpenCameraOption: Boolean = false,
    showMoreMenuVolumeKeyToggle: Boolean = false,
    showMlkitOption: Boolean = true,
    showModelSelector: Boolean = true,
    showMoreButton: Boolean = true,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }

    var selectedModelName by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    fun refresh() {
        selectedModelName = when (selectedModelId) {
            "mlkit" -> "Basic tasks"
            LocalServerFeature.MODEL_ID -> "Local Server"
            else -> offlineModelsRepository.loadModels().find { it.id == selectedModelId }?.displayName ?: "No Model Selected"
        }
    }

    if (showModelSelector) {
        LaunchedEffect(selectedModelId) { refresh() }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showModelSelector) {
            Text(
                text = selectedModelName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showModelPicker = true }
                    .clearAndSetSemantics { contentDescription = "Selected model: $selectedModelName. Double tap to change." }
            )
            ModelPickerPopup(
                expanded = showModelPicker,
                onDismiss = { showModelPicker = false },
                prefs = prefs,
                controller = controller,
                selectedModelId = selectedModelId,
                onModelSelected = { onModelSelected(it); refresh() },
                onOpenModelManager = { context.startActivity(Intent(context, ModelManagementActivity::class.java)) },
                showMlkitOption = showMlkitOption
            )
        }

        if (showMoreButton) {
            Button(onClick = { showMoreMenu = true }) {
                Text("More")
            }
            MoreMenu(
                expanded = showMoreMenu,
                onDismiss = { showMoreMenu = false },
                prefs = prefs,
                showOpenCameraOption = showMoreMenuOpenCameraOption,
                selectedModelId = selectedModelId,
                onModelDeleted = { onModelSelected("mlkit"); refresh() },
                showVolumeKeyToggle = showMoreMenuVolumeKeyToggle
            )
        }
    }
}

/**
 * Resyncs a screen's own `selectedModelId` Compose state from [PreferencesManager.selectedModelId]
 * on every resume — call once, in composition, from [com.appricodes.lens_whisper.CameraActivity]
 * and the Video/Image describer, right next to wherever each seeds that state with
 * `remember { mutableStateOf(prefs.selectedModelId) }`.
 *
 * That seed only runs once, on first composition; it does not track later changes to the
 * preference. `ModelManagementActivity` (reached via this same top bar's "Manage Advanced Models")
 * writes `prefs.selectedModelId` directly — switching models, or falling back to `"mlkit"` when
 * the currently-selected model gets deleted — while the calling screen is merely paused, not
 * destroyed, underneath it. Without this, returning to that screen leaves its `selectedModelId`
 * stale: the top bar's own [ModelTopBar] ON_RESUME `refresh()` above only recomputes the display
 * name from whatever `selectedModelId` it's *handed*, so a stale caller means a stale display too,
 * and the stale id also keeps driving [com.appricodes.lens_whisper.ui.models.ModelButtonProviderRegistry]'s
 * provider choice, [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController.gateForSelectedModel]'s
 * gate, and the model-preload effect — i.e. exactly "the model shown at the top doesn't match the
 * one that actually runs."
 */
@Composable
fun ResyncSelectedModelOnResume(prefs: PreferencesManager, onResync: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResync(prefs.selectedModelId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

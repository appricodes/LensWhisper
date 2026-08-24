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
import com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController

/**
 * Top bar shared by [com.appricodes.lens_whisper.CameraActivity] and the Video/Image describer:
 * a model-name button (opens [ModelPickerPopup] for a quick switch), the remaining-credits badge
 * (opens [CreditsDialog]), and a More button (opens [MoreMenu]) — all in one row, replacing the
 * old plain "tap name to open Manage Models" behavior.
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
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }

    var selectedModelName by remember { mutableStateOf("") }
    var totalCredits by remember { mutableStateOf(0) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    fun refresh() {
        selectedModelName = when (selectedModelId) {
            "mlkit" -> "MLKit (Offline)"
            "online" -> "Online Describer"
            else -> offlineModelsRepository.loadModels().find { it.id == selectedModelId }?.displayName ?: "No Model Selected"
        }
        totalCredits = prefs.freeCredits + prefs.purchasedCredits
    }

    LaunchedEffect(selectedModelId) { refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            onNeedCredits = { showCreditsDialog = true },
            showMlkitOption = showMlkitOption
        )

        Button(onClick = { showCreditsDialog = true }) { Text("$totalCredits Credits") }

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

    if (showCreditsDialog) {
        CreditsDialog(
            prefs = prefs,
            onDismiss = { showCreditsDialog = false },
            onCreditsChanged = { refresh() }
        )
    }
}

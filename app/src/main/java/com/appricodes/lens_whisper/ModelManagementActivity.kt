package com.appricodes.lens_whisper

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.offline.OfflineTranslator
import com.appricodes.lens_whisper.service.ModelDownloadService
import com.appricodes.lens_whisper.service.ModelDownloadState
import com.appricodes.lens_whisper.ui.components.DownloadProgressDialog
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.ui.components.LocalServerInfoDialog
import com.appricodes.lens_whisper.ui.components.MemoryWarningDialog
import com.appricodes.lens_whisper.ui.components.ModelLoadingObserver
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.DeviceMemoryDetector
import com.appricodes.lens_whisper.utils.OfflineImageSize
import kotlinx.coroutines.launch

/** Lets the user pick MLKit or an on-device catalog model (and, while
 *  [LocalServerFeature] is switched on - it is not - the Local Server). */
class ModelManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferencesManager(this)
        setContent {
            LensWhisperTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ModelManagementScreen(prefs = prefs, onClose = { finish() })
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "unknown size"
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.1f GB".format(gb)
    return "%.0f MB".format(bytes / 1_000_000.0)
}

@Composable
private fun ModelManagementScreen(prefs: PreferencesManager, onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { OfflineModelsRepository(context) }
    // Smallest-first, matching ModelPickerPopup - listed below MLKit.
    val offlineModels = remember { repository.loadModels() }

    var selectedId by remember { mutableStateOf(prefs.selectedModelId) }
    var downloadedVersion by remember { mutableStateOf(0) }
    var modelPendingDelete by remember { mutableStateOf<OfflineModelInfo?>(null) }
    var memoryWarningModel by remember { mutableStateOf<OfflineModelInfo?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var isClearingAll by remember { mutableStateOf(false) }
    var showLocalServerInfo by remember { mutableStateOf(false) }
    var offlineImageSize by remember { mutableStateOf(prefs.offlineImageSize) }
    var imageSizeDropdownExpanded by remember { mutableStateOf(false) }

    val downloadState by ModelDownloadService.state.collectAsState()
    LaunchedEffect(downloadState) {
        if (downloadState is ModelDownloadState.Completed) downloadedVersion++
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val activeDownload = downloadState as? ModelDownloadState.Downloading

    fun selectMlkit() {
        prefs.selectedModelId = "mlkit"
        selectedId = "mlkit"
        OfflineInferenceEngine.release()
        AccessibilityUtils.announceToTalkBack(context, "Basic tasks selected.")
    }

    fun selectLocalServer() {
        prefs.selectedModelId = LocalServerFeature.MODEL_ID
        selectedId = LocalServerFeature.MODEL_ID
        OfflineInferenceEngine.release()
        AccessibilityUtils.announceToTalkBack(context, "Local Server selected.")
        if (!prefs.hasSeenLocalServerIntro) {
            prefs.hasSeenLocalServerIntro = true
            showLocalServerInfo = true
        }
    }

    // Selecting an on-device model starts loading it right away — see ModelLoadingObserver
    // below, which shows a blocking "Loading…" alert until it's actually ready to use, instead
    // of that ~10s cost surfacing as unexplained lag the first time a button is tapped.
    fun selectOffline(model: OfflineModelInfo) {
        prefs.selectedModelId = model.id
        selectedId = model.id
        AccessibilityUtils.announceToTalkBack(context, "${model.displayName} selected.")
        coroutineScope.launch { OfflineInferenceEngine.preload(context, model) }
    }

    fun requestSelectOffline(model: OfflineModelInfo) {
        if (DeviceMemoryDetector.mightNotRun(context, model) && !prefs.isMemoryWarningSuppressed(model.id)) {
            memoryWarningModel = model
        } else {
            selectOffline(model)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Manage AI Models",
                fontSize = 24.sp,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            TextButton(onClick = onClose) { Text("Close", fontSize = 18.sp) }
        }

        val anyDownloaded = offlineModels.any { remember(it.id, downloadedVersion) { repository.isDownloaded(it) } }
        if (anyDownloaded) {
            OutlinedButton(
                onClick = { showClearAllConfirm = true },
                enabled = !isClearingAll,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(if (isClearingAll) "Clearing…" else "Clear All Downloaded Models")
            }
        }

        // Order: MLKit (always available, no download) -> on-device catalog models, smallest to
        // largest.
        MlkitCard(isSelected = selectedId == "mlkit", onSelect = { selectMlkit() })

        // isDownloaded hits the filesystem (File.exists()/.length()), so it's memoized on
        // (model.id, downloadedVersion) rather than recomputed on every recomposition. It used
        // to sit in the same scope as downloadState, so it re-ran on every progress tick (many
        // times a second) on the *main* thread — right while the download thread was writing
        // to the same storage, which stalled the UI thread on disk I/O and was the real source
        // of the lag. downloadedVersion only bumps on Completed/Delete, so this now only hits
        // disk when a model's on-disk state can actually have changed.
        offlineModels.forEach { model ->
            val isDownloaded = remember(model.id, downloadedVersion) { repository.isDownloaded(model) }
            val isDownloadingThis = activeDownload?.modelId == model.id
            val downloadingOtherModel = activeDownload != null && !isDownloadingThis
            val failure = (downloadState as? ModelDownloadState.Failed)?.takeIf { it.modelId == model.id }

            OfflineModelCard(
                model = model,
                isDownloaded = isDownloaded,
                isSelected = selectedId == model.id,
                isDownloadingThis = isDownloadingThis,
                downloadingOtherModel = downloadingOtherModel,
                failureMessage = failure?.message,
                mightNotRun = remember(model.id) { DeviceMemoryDetector.mightNotRun(context, model) },
                onSelect = { requestSelectOffline(model) },
                onDownload = {
                    requestNotificationPermissionIfNeeded()
                    ModelDownloadService.start(context, model)
                },
                onDelete = { modelPendingDelete = model }
            )
        }

        if (LocalServerFeature.ENABLED) {
            HorizontalDivider()
            LocalServerCard(
                isSelected = selectedId == LocalServerFeature.MODEL_ID,
                onSelect = { selectLocalServer() }
            )
        }

        HorizontalDivider()
        Text(
            text = "The model selected here is used by the Camera screen and Video/Image Describer's " +
                "analysis and Explore. Everything runs on this device - no photo is ever uploaded. " +
                "On-device models take a moment to load the first time after being selected, and use " +
                "more memory than Basic Models.",
            fontSize = 13.sp,
            color = Color.Gray
        )
        Text(
            text = "Accuracy differs a lot between these models, and none of them is reliable. Read " +
                "each description before choosing, and never rely on any of them for medicines, money, " +
                "documents, or moving around safely.",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "On-Device Image Size", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.semantics { heading() })
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { imageSizeDropdownExpanded = true }
                    .padding(16.dp)
                    .clearAndSetSemantics { contentDescription = "On-device image size is $offlineImageSize. Double tap to change." },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(text = offlineImageSize, fontSize = 18.sp)
                DropdownMenu(expanded = imageSizeDropdownExpanded, onDismissRequest = { imageSizeDropdownExpanded = false }) {
                    OfflineImageSize.ALL.forEach { tier ->
                        DropdownMenuItem(text = { Text(tier) }, onClick = {
                            offlineImageSize = tier
                            prefs.offlineImageSize = tier
                            imageSizeDropdownExpanded = false
                            AccessibilityUtils.announceToTalkBack(context, "On-device image size set to $tier.")
                        })
                    }
                }
            }
            Text(
                text = "Controls the image resolution sent to on-device models. Smaller is faster " +
                    "but less detailed; larger is slower but more detailed. Only affects models whose " +
                    "on-device engine supports multiple resolutions.",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }
    }

    modelPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { modelPendingDelete = null },
            title = { Text("Delete download?") },
            text = { Text("This frees up ${formatSize(pending.sizeBytes)} of storage. You'll need to download ${pending.displayName} again to use it.") },
            confirmButton = {
                Button(onClick = {
                    val freedBytes = pending.sizeBytes
                    if (prefs.selectedModelId == pending.id) {
                        // Falls back to MLKit, same as deleting the selected model via the More
                        // menu on the Camera screen — there's nothing sensible to keep it
                        // selected as once its file is gone.
                        OfflineInferenceEngine.release()
                        prefs.selectedModelId = "mlkit"
                        selectedId = "mlkit"
                    }
                    repository.deleteDownload(pending)
                    downloadedVersion++
                    modelPendingDelete = null
                    Toast.makeText(context, "Freed ${formatSize(freedBytes)} of storage.", Toast.LENGTH_SHORT).show()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { modelPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    var downloadDialogHiddenHere by remember { mutableStateOf(false) }
    LaunchedEffect(activeDownload?.modelId) { downloadDialogHiddenHere = false }
    if (activeDownload != null && !downloadDialogHiddenHere) {
        val downloadingModel = offlineModels.find { it.id == activeDownload.modelId }
        DownloadProgressDialog(
            activeDownload = activeDownload,
            displayName = downloadingModel?.displayName ?: "model",
            onCancel = { ModelDownloadService.cancel(context) },
            onContinueInBackground = { downloadDialogHiddenHere = true }
        )
    }

    memoryWarningModel?.let { model ->
        MemoryWarningDialog(
            model = model,
            onContinue = { suppress ->
                if (suppress) prefs.suppressMemoryWarning(model.id)
                selectOffline(model)
                memoryWarningModel = null
            },
            onCancel = { memoryWarningModel = null }
        )
    }

    if (showClearAllConfirm) {
        val downloadedModels = offlineModels.filter { remember(it.id, downloadedVersion) { repository.isDownloaded(it) } }
        val totalFreed = downloadedModels.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear all downloaded models?") },
            text = {
                Text(
                    "This deletes every downloaded on-device AI model (freeing ${formatSize(totalFreed)}) and any " +
                        "downloaded MLKit translation language pack — including ones downloaded automatically for " +
                        "the \"Translate Offline Results\" setting. You'll need to download them again to use them. " +
                        "This doesn't affect Basic tasks' built-in object/text/color detection, which needs no download."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showClearAllConfirm = false
                    isClearingAll = true
                    coroutineScope.launch {
                        if (downloadedModels.any { it.id == prefs.selectedModelId }) {
                            OfflineInferenceEngine.release()
                            prefs.selectedModelId = "mlkit"
                            selectedId = "mlkit"
                        }
                        downloadedModels.forEach { repository.deleteDownload(it) }
                        OfflineTranslator.deleteAllDownloadedModels()
                        downloadedVersion++
                        isClearingAll = false
                        Toast.makeText(context, "Cleared ${formatSize(totalFreed)} of downloaded models.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Clear All") }
            },
            dismissButton = { TextButton(onClick = { showClearAllConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showLocalServerInfo) {
        LocalServerInfoDialog(onDismiss = { showLocalServerInfo = false })
    }

    ModelLoadingObserver(context = context, selectedModelId = selectedId, offlineModels = offlineModels)
}

@Composable
private fun ModelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SelectedLabel(name: String) {
    Text(
        text = "Selected",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$name is selected." }
    )
}

/**
 * Groups a card's informational text (name, description, status lines) into a single TalkBack
 * stop instead of one stop per `Text` — `mergeDescendants` concatenates the merged children's
 * text automatically, in the order they're composed, so this needs no hand-written summary
 * string to keep in sync. The action buttons stay outside this modifier so they remain
 * individually reachable/actionable.
 */
private fun Modifier.mergedInfoBlock(): Modifier = this.semantics(mergeDescendants = true) {}

@Composable
private fun MlkitCard(isSelected: Boolean, onSelect: () -> Unit) {
    ModelCard {
        Column(modifier = Modifier.mergedInfoBlock(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Basic tasks", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(text = "Detects and names objects and their colors, reads text, explores a photo by touch, and measures light level. Always available, nothing to download.", fontSize = 15.sp, color = Color.Gray)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            if (isSelected) SelectedLabel("Basic tasks") else Button(onClick = onSelect) { Text("Select") }
        }
    }
}

@Composable
private fun LocalServerCard(isSelected: Boolean, onSelect: () -> Unit) {
    ModelCard {
        Column(modifier = Modifier.mergedInfoBlock(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Local Server", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(
                text = "Analyzes photos on a computer of yours on the same network instead of the cloud " +
                    "— private, free, and no waiting on a server you don't control. Requires setting up " +
                    "the LensWhisper Desktop companion app first (see \"About Local Server\" in the More menu).",
                fontSize = 15.sp,
                color = Color.Gray
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            if (isSelected) SelectedLabel("Local Server") else Button(onClick = onSelect) { Text("Select") }
        }
    }
}

@Composable
private fun OfflineModelCard(
    model: OfflineModelInfo,
    isDownloaded: Boolean,
    isSelected: Boolean,
    isDownloadingThis: Boolean,
    downloadingOtherModel: Boolean,
    failureMessage: String?,
    mightNotRun: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    ModelCard {
        Column(modifier = Modifier.mergedInfoBlock(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = model.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            if (model.description.isNotBlank()) {
                Text(text = model.description, fontSize = 15.sp, color = Color.Gray)
            }
            Text(
                text = if (isDownloaded) "Downloaded — ${formatSize(model.sizeBytes)}" else "Download size: ${formatSize(model.sizeBytes)}",
                fontSize = 14.sp
            )
            if (mightNotRun) {
                Text(
                    text = "⚠ This model may not run well on your device's memory.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (failureMessage != null) {
                Text(text = "Download failed: $failureMessage", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
            }
            if (isDownloadingThis) {
                Text(text = "Downloading…", fontSize = 14.sp, color = Color.Gray)
            } else if (downloadingOtherModel) {
                Text(text = "Another model is downloading right now.", fontSize = 13.sp, color = Color.Gray)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (isDownloaded) {
                if (isSelected) SelectedLabel(model.displayName) else Button(onClick = onSelect) { Text("Select") }
                // Deleting the currently-selected model is allowed — the caller falls back the
                // selection to MLKit afterward (see ModelManagementScreen's delete handler).
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            } else {
                Button(onClick = onDownload, enabled = !downloadingOtherModel && !isDownloadingThis) { Text("Download") }
            }
        }
    }
}

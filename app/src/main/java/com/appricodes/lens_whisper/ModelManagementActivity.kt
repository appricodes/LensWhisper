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
import androidx.compose.material3.LinearProgressIndicator
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
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.service.ModelDownloadService
import com.appricodes.lens_whisper.service.ModelDownloadState
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.OfflineImageSize

/** Lets the user pick the Online Describer or download/select an on-device model. */
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
    val repository = remember { OfflineModelsRepository(context) }
    val offlineModels = remember { repository.loadModels() }

    var selectedId by remember { mutableStateOf(prefs.selectedModelId) }
    var downloadedVersion by remember { mutableStateOf(0) }
    var modelPendingDelete by remember { mutableStateOf<OfflineModelInfo?>(null) }
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
            Text(text = "Manage Advanced AI Models", fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Close", fontSize = 18.sp) }
        }

        val eligibleForOnline = prefs.isLoggedIn || prefs.freeCredits > 0 || prefs.purchasedCredits > 0
        OnlineDescriberCard(
            isSelected = selectedId == "online",
            eligible = eligibleForOnline,
            onSelect = {
                prefs.selectedModelId = "online"
                selectedId = "online"
                OfflineInferenceEngine.release()
                AccessibilityUtils.announceToTalkBack(context, "Online Describer selected.")
            },
            onLogin = { context.startActivity(Intent(context, LoginActivity::class.java)) },
            onPurchase = { Toast.makeText(context, "Purchasing credits isn't available yet.", Toast.LENGTH_SHORT).show() }
        )

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
                onSelect = {
                    prefs.selectedModelId = model.id
                    selectedId = model.id
                    AccessibilityUtils.announceToTalkBack(context, "${model.displayName} selected.")
                },
                onDownload = {
                    requestNotificationPermissionIfNeeded()
                    ModelDownloadService.start(context, model)
                },
                onDelete = { modelPendingDelete = model }
            )
        }

        HorizontalDivider()
        Text(
            text = "The model selected here is used by Advanced Camera and Video/Image Describer's " +
                "analysis and Explore. On-device models run slower to load the first time (up to ~10s) " +
                "and use more memory than the Online Describer.",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "On-Device Image Size", fontSize = 18.sp, color = Color.Gray)
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
                    if (prefs.selectedModelId == pending.id) OfflineInferenceEngine.release()
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

    if (activeDownload != null) {
        val downloadingModel = offlineModels.find { it.id == activeDownload.modelId }
        val progress = if (activeDownload.totalBytes > 0) {
            (activeDownload.bytesDownloaded.toFloat() / activeDownload.totalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Downloading ${downloadingModel?.displayName ?: "model"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${formatSize(activeDownload.bytesDownloaded)} of ${formatSize(activeDownload.totalBytes)}")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { ModelDownloadService.cancel(context) }) { Text("Cancel") }
            }
        )
    }
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

@Composable
private fun OnlineDescriberCard(
    isSelected: Boolean,
    eligible: Boolean,
    onSelect: () -> Unit,
    onLogin: () -> Unit,
    onPurchase: () -> Unit
) {
    ModelCard {
        Text(text = "Online Describer", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = "Sends photos to our server for AI analysis.", fontSize = 15.sp, color = Color.Gray)
        if (!eligible) {
            Text(
                text = "You need to log in to receive free credits, or purchase credits without logging in.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (eligible) {
                if (isSelected) SelectedLabel("Online Describer") else Button(onClick = onSelect) { Text("Select") }
            } else {
                Button(onClick = onLogin) { Text("Login") }
                OutlinedButton(onClick = onPurchase) { Text("Purchase Credits") }
            }
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
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    ModelCard {
        Text(text = model.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (model.description.isNotBlank()) {
            Text(text = model.description, fontSize = 15.sp, color = Color.Gray)
        }
        Text(
            text = if (isDownloaded) "Downloaded — ${formatSize(model.sizeBytes)}" else "Download size: ${formatSize(model.sizeBytes)}",
            fontSize = 14.sp
        )
        Text(
            text = if (model.supportsExplore) "Supports Explore mode." else "Does not support Explore mode.",
            fontSize = 13.sp,
            color = Color.Gray
        )
        if (failureMessage != null) {
            Text(text = "Download failed: $failureMessage", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        }
        if (isDownloadingThis) {
            Text(text = "Downloading…", fontSize = 14.sp, color = Color.Gray)
        } else if (downloadingOtherModel) {
            Text(text = "Another model is downloading right now.", fontSize = 13.sp, color = Color.Gray)
        }
        if (isDownloaded && isSelected) {
            Text(text = "Select a different model to delete this one.", fontSize = 13.sp, color = Color.Gray)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (isDownloaded) {
                if (isSelected) SelectedLabel(model.displayName) else Button(onClick = onSelect) { Text("Select") }
                if (!isSelected) OutlinedButton(onClick = onDelete) { Text("Delete") }
            } else {
                Button(onClick = onDownload, enabled = !downloadingOtherModel && !isDownloadingThis) { Text("Download") }
            }
        }
    }
}

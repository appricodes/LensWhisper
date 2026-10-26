package com.appricodes.lens_whisper.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.appricodes.lens_whisper.CameraActivity
import com.appricodes.lens_whisper.HelpActivity
import com.appricodes.lens_whisper.LegalActivity
import com.appricodes.lens_whisper.MediaDescriberActivity
import com.appricodes.lens_whisper.ModelManagementActivity
import com.appricodes.lens_whisper.SettingsActivity
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.legal.LegalTexts
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.utils.AccessibilityUtils

/**
 * The "More" popup shared by [com.appricodes.lens_whisper.CameraActivity] and the Video/Image
 * describer: Open file / (optionally Open Camera) / Manage models / Settings / Help, plus a
 * "Delete <model>" entry when the currently selected model is a downloaded on-device one.
 *
 * Opening a file always leads to the Video/Image describer, as the sole activity in a fresh task
 * - closing it (Back) closes the app rather than returning to whichever screen opened it.
 *
 * [showOpenCameraOption] adds an "Open Camera" item that reopens [CameraActivity] — only
 * meaningful (and only passed true) from the Video/Image describer, which has no camera itself.
 *
 * [selectedModelId]/[onModelDeleted] drive the delete entry: shown only when the selected model
 * is a downloaded on-device catalog model (never for "basic_tasks"/"local_server", which have nothing
 * to delete). Deleting falls back the caller to `"basic_tasks"` via [onModelDeleted], since (unlike
 * `ModelManagementActivity`, which only lets you delete a model you're *not* currently on) this
 * entry only ever appears for the *selected* model.
 */
@Composable
fun MoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    prefs: PreferencesManager,
    showOpenCameraOption: Boolean = false,
    selectedModelId: String? = null,
    onModelDeleted: () -> Unit = {},
    showVolumeKeyToggle: Boolean = false
) {
    val context = LocalContext.current
    val repository = remember { OfflineModelsRepository(context) }
    var confirmDeleteVisible by remember { mutableStateOf(false) }
    var volKeysEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }
    var showLocalServerInfo by remember { mutableStateOf(false) }

    val selectedOfflineModel = remember(selectedModelId) {
        if (selectedModelId == null || selectedModelId == "basic_tasks" || selectedModelId == LocalServerFeature.MODEL_ID) null
        else repository.loadModels().find { it.id == selectedModelId }?.takeIf { repository.isDownloaded(it) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val openMedia = Intent(context, MediaDescriberActivity::class.java).apply {
                data = uri
                // Fresh task, no activities underneath — Back from the describer closes the
                // app instead of returning to whichever screen opened it.
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
            // A login gate used to branch here — see LOGIN_AND_PURCHASES_REMOVED.md at the repo root.
            context.startActivity(openMedia)
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Open File") }, onClick = {
            onDismiss()
            filePickerLauncher.launch(arrayOf("video/*", "image/*"))
        })
        if (showOpenCameraOption) {
            DropdownMenuItem(text = { Text("Open Camera") }, onClick = {
                onDismiss()
                val intent = Intent(context, CameraActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(intent)
            })
        }
        if (showVolumeKeyToggle) {
            DropdownMenuItem(
                text = { Text(if (volKeysEnabled) "Volume Key Shortcuts: On" else "Volume Key Shortcuts: Off") },
                onClick = {
                    val newState = !volKeysEnabled
                    volKeysEnabled = newState
                    prefs.volumeShortcutsEnabled = newState
                    onDismiss()
                    AccessibilityUtils.announceToTalkBack(context, if (newState) "Volume key shortcuts enabled" else "Volume key shortcuts disabled")
                }
            )
        }
        DropdownMenuItem(text = { Text("Manage Advanced AI Models") }, onClick = {
            onDismiss()
            context.startActivity(Intent(context, ModelManagementActivity::class.java))
        })
        DropdownMenuItem(text = { Text("Settings") }, onClick = {
            onDismiss()
            context.startActivity(Intent(context, SettingsActivity::class.java))
        })
        DropdownMenuItem(text = { Text("Help") }, onClick = {
            onDismiss()
            context.startActivity(Intent(context, HelpActivity::class.java))
        })
        DropdownMenuItem(text = { Text("Legal Information") }, onClick = {
            onDismiss()
            context.startActivity(Intent(context, LegalActivity::class.java))
        })
        DropdownMenuItem(text = { Text("Source Code on GitHub") }, onClick = {
            onDismiss()
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://${LegalTexts.SOURCE_CODE_URL}")))
            } catch (e: android.content.ActivityNotFoundException) {
                AccessibilityUtils.announceToTalkBack(context, "No browser found. The source code is at ${LegalTexts.SOURCE_CODE_URL}")
            }
        })
        if (LocalServerFeature.ENABLED && selectedModelId == LocalServerFeature.MODEL_ID) {
            DropdownMenuItem(text = { Text("About Local Server") }, onClick = {
                onDismiss()
                showLocalServerInfo = true
            })
        }
        selectedOfflineModel?.let { model ->
            DropdownMenuItem(text = { Text("Delete ${model.displayName}") }, onClick = {
                onDismiss()
                confirmDeleteVisible = true
            })
        }
    }

    if (confirmDeleteVisible && selectedOfflineModel != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVisible = false },
            title = { Text("Delete ${selectedOfflineModel.displayName}?") },
            text = { Text("This removes the downloaded model file. You'll need to download it again to use it.") },
            confirmButton = {
                TextButton(onClick = {
                    if (prefs.selectedModelId == selectedOfflineModel.id) OfflineInferenceEngine.release()
                    repository.deleteDownload(selectedOfflineModel)
                    prefs.selectedModelId = "basic_tasks"
                    confirmDeleteVisible = false
                    onModelDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteVisible = false }) { Text("Cancel") } }
        )
    }

    if (showLocalServerInfo) {
        LocalServerInfoDialog(onDismiss = { showLocalServerInfo = false })
    }
}

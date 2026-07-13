package com.appricodes.lens_whisper.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.appricodes.lens_whisper.AdvancedCameraActivity
import com.appricodes.lens_whisper.HelpActivity
import com.appricodes.lens_whisper.LoginActivity
import com.appricodes.lens_whisper.MediaDescriberActivity
import com.appricodes.lens_whisper.ModelManagementActivity
import com.appricodes.lens_whisper.SettingsActivity
import com.appricodes.lens_whisper.SimpleCameraActivity
import com.appricodes.lens_whisper.data.PreferencesManager

/**
 * The "More" popup shared by Simple Camera, Advanced Camera and the Video/Image describer:
 * Open file / Settings / Help. Opening a file always leads to the (online) Video/Image
 * describer, as the sole activity in a fresh task — closing it (Back) closes the app rather
 * than returning to whichever camera screen opened it; if the user isn't logged in yet, Login
 * runs first and forwards there afterwards.
 *
 * [showOpenCameraOption] adds an extra "Open Camera" item that reopens whichever camera mode
 * was last used — only meaningful (and only passed true) from the Video/Image describer.
 */
@Composable
fun MoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    prefs: PreferencesManager,
    showOpenCameraOption: Boolean = false
) {
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val openMedia = Intent(context, MediaDescriberActivity::class.java).apply {
                data = uri
                // Fresh task, no activities underneath — Back from the describer closes the
                // app instead of returning to whichever camera screen opened it.
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
            if (prefs.isLoggedIn) {
                context.startActivity(openMedia)
            } else {
                context.startActivity(Intent(context, LoginActivity::class.java).putExtra("PENDING_INTENT", openMedia))
            }
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
                val cameraActivity = if (prefs.lastCameraMode == "advanced") AdvancedCameraActivity::class.java else SimpleCameraActivity::class.java
                val intent = Intent(context, cameraActivity).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(intent)
            })
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
    }
}

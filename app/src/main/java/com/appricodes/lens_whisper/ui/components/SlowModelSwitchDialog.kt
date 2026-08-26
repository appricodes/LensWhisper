package com.appricodes.lens_whisper.ui.components

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.utils.AccessibilityUtils

/**
 * The one-time "the default model seems slow on this device — switch to the smaller one?" prompt
 * — see [com.appricodes.lens_whisper.offline.SegmentationSpeedGate] for when this actually fires.
 * Call from composition with [show] driven by that gate's callback; either answer marks
 * [PreferencesManager.hasShownSlowModelSwitchPrompt] so this never asks again, though the user can
 * still change the size manually in Settings at any time.
 */
@Composable
fun SlowModelSwitchDialog(context: Context, prefs: PreferencesManager, show: Boolean, onDone: () -> Unit) {
    if (!show) return

    // AlertDialog's own appearance is usually announced by TalkBack on its own, but every other
    // state transition in this app is announced explicitly rather than relying on that — this is
    // no different, and it's a real yes/no decision a blind user needs to actually hear.
    LaunchedEffect(Unit) {
        AccessibilityUtils.announceToTalkBack(
            context,
            "Object detection seems slow on this device. A dialog is asking whether to switch to a smaller, faster model."
        )
    }

    fun markShown() {
        prefs.hasShownSlowModelSwitchPrompt = true
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Switch to a faster model?") },
        text = { Text("Object detection is taking a while on this device. Switch to the smaller, faster model? You can change this later in Settings.") },
        confirmButton = {
            Button(onClick = {
                prefs.segmentationModelSize = OfflineSegmentationDetector.SIZE_SMALL
                markShown()
                AccessibilityUtils.announceToTalkBack(context, "Switched to the smaller, faster model.")
                onDone()
            }) { Text("Switch") }
        },
        dismissButton = {
            TextButton(onClick = {
                markShown()
                onDone()
            }) { Text("Keep Current") }
        }
    )
}

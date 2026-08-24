package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.appricodes.lens_whisper.domain.OfflineModelInfo

/**
 * Shown before selecting a model [com.appricodes.lens_whisper.utils.DeviceMemoryDetector] flags as
 * possibly too large for this device's RAM — from the model picker popup and
 * `ModelManagementActivity`. [onContinue] receives whether the "don't warn me again" checkbox was
 * checked so the caller can persist it via `PreferencesManager.suppressMemoryWarning`.
 */
@Composable
fun MemoryWarningDialog(model: OfflineModelInfo, onContinue: (suppress: Boolean) -> Unit, onCancel: () -> Unit) {
    var dontWarnAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("This model may not run well on your device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${model.displayName} is large relative to this device's memory and may run slowly, fail to load, or cause the app to be closed by the system.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontWarnAgain, onCheckedChange = { dontWarnAgain = it })
                    Text("Don't warn me about this model again")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onContinue(dontWarnAgain) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

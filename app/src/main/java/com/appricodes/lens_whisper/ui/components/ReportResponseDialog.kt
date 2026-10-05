package com.appricodes.lens_whisper.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.network.ResponseReporter
import com.appricodes.lens_whisper.ui.analysis.ReportableResponse
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import kotlinx.coroutines.launch

/**
 * Reports an on-device model's answer to the developer (see [ResponseReporter]). Says exactly what
 * will be sent before anything is; the photo goes only if the box is ticked, and it starts unticked.
 * A failed send keeps the dialog open, with what was typed, so the user can try again.
 */
@Composable
fun ReportResponseDialog(report: ReportableResponse, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoAvailable = remember(report) { ResponseReporter.hasPhoto(report) }
    var message by remember { mutableStateOf("") }
    var includePhoto by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun send() {
        sending = true
        failed = false
        AccessibilityUtils.announceToTalkBack(context, "Sending report.")
        scope.launch {
            val ok = ResponseReporter.send(context, report, message.trim(), includePhoto && photoAvailable)
            sending = false
            if (ok) {
                Toast.makeText(context, "Report sent. Thank you.", Toast.LENGTH_LONG).show()
                onDismiss()
            } else {
                failed = true
                AccessibilityUtils.announceToTalkBack(context, SEND_FAILED)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Report this answer") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Tell the developer about an answer from ${report.modelName} that was offensive, harmful " +
                        "or wrong. This sends the question the model was asked, the model's name, its answer " +
                        "and your message to the developer by email" +
                        if (photoAvailable) ", and the photo too if you tick the box below." else "."
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (it.length <= MAX_MESSAGE_LENGTH) message = it },
                    label = { Text("What was wrong? (optional)") },
                    enabled = !sending,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (photoAvailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = includePhoto,
                                enabled = !sending,
                                role = Role.Checkbox,
                                onValueChange = { includePhoto = it }
                            )
                    ) {
                        Checkbox(checked = includePhoto, onCheckedChange = null, enabled = !sending)
                        Text("Include the photo")
                    }
                }
                if (failed) {
                    Text(SEND_FAILED, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { send() }, enabled = !sending) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") }
        }
    )
}

private const val MAX_MESSAGE_LENGTH = 2000
private const val SEND_FAILED = "The report couldn't be sent. Check the internet connection and try again."

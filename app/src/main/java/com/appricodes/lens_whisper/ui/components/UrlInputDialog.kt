package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun UrlInputDialog(
    showDialog: Boolean,
    initialUrlHistoryList: List<String>,
    onDismiss: () -> Unit,
    onUrlSubmit: (String) -> Unit,
    onHistoryRemove: (String) -> Unit
) {
    if (!showDialog) return
    var urlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Web URL") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = urlInput, 
                    onValueChange = { urlInput = it }, 
                    label = { Text("URL or Domain") }, 
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), 
                    keyboardActions = KeyboardActions(onGo = { onUrlSubmit(urlInput) })
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                initialUrlHistoryList.forEach { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), 
                        horizontalArrangement = Arrangement.SpaceBetween, 
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = url, modifier = Modifier.weight(1f).clickable { onUrlSubmit(url) }, color = Color.Gray)
                        TextButton(onClick = { onHistoryRemove(url) }) { Text("Remove") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onUrlSubmit(urlInput) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
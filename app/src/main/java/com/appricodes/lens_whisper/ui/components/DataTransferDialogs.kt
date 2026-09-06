package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.data.ExportFileRef
import com.appricodes.lens_whisper.data.ExportSelection

/**
 * Settings' "Export My Data" window: what to include, and the plain-language warning that this is
 * the moment data leaves the app's private storage.
 *
 * There is no destination to choose any more. The export always goes to the device's own Downloads
 * folder — the cloud-provider option this dialog used to offer was removed on request, so nothing
 * the app does can put these notes on someone else's server.
 *
 * Every item starts ticked: the common case is "export everything", and a user who wants a subset
 * can untick. [onExport] is only reachable while at least one item is ticked.
 *
 * The notice is not boilerplate to scroll past: up to this point every note has lived in
 * app-private storage that no other app can read (see
 * [com.appricodes.lens_whisper.data.BarcodeNoteStore]). An export deliberately breaks that by
 * writing into Downloads, a folder every app on the phone can see — and the file, encrypted though
 * it is (see [com.appricodes.lens_whisper.data.ExportCrypto], which is candid about being
 * obfuscation rather than strong secrecy), is then the user's to look after.
 */
@Composable
fun ExportDataDialog(
    selection: ExportSelection,
    isBusy: Boolean,
    onSelectionChange: (ExportSelection) -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isBusy) onCancel() },
        title = { Text("Export My Data") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionHeading("What to include")
                CheckRow(
                    label = "Your written notes about barcodes",
                    checked = selection.textNotes,
                    enabled = !isBusy,
                    onToggle = { onSelectionChange(selection.copy(textNotes = it)) }
                )
                CheckRow(
                    label = "Your voice notes about barcodes",
                    checked = selection.voiceNotes,
                    enabled = !isBusy,
                    onToggle = { onSelectionChange(selection.copy(voiceNotes = it)) }
                )

                SectionHeading("Where it goes")
                Text(
                    text = "Into this phone's Downloads folder. That is the only place the app can put " +
                        "it — nothing is sent over the internet and nothing goes to any cloud service.",
                    fontSize = 15.sp
                )

                SectionHeading("Before you export")
                Text(
                    text = "Until now these notes have been stored inside the app, where other apps cannot " +
                        "reach them. Exporting takes them out of that protection and puts them in Downloads, " +
                        "which every app on the phone can see.",
                    fontSize = 15.sp
                )
                Text(
                    text = "The exported file is scrambled so that ordinary apps cannot simply open it, but it " +
                        "is not strongly protected: anyone with a copy of this app can unscramble it. If your " +
                        "notes contain anything private, keeping that file safe — and deleting it once you are " +
                        "done with it — is up to you. If you later copy it somewhere else yourself, it is then " +
                        "subject to whatever you copied it to.",
                    fontSize = 15.sp
                )

                if (isBusy) {
                    Text(text = "Exporting…", fontSize = 15.sp, color = Color.Gray)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onExport,
                enabled = !isBusy && !selection.isEmpty,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "I accept, and export my data to Downloads now."
                }
            ) { Text("Accept and Export") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isBusy) { Text("Cancel") }
        }
    )
}

/**
 * Settings' "Import My Data" window: the `.fdb` files found in Downloads, offered as a spoken list
 * so importing is one choice rather than a trip through a file browser — which matters a great deal
 * for this app's users.
 *
 * [availableFiles] can legitimately be empty even when the user is sure the file is there: on
 * Android 10 and newer, scoped storage only lets the app see Downloads files it created itself, so
 * one copied over from another phone will not be listed (see
 * [com.appricodes.lens_whisper.data.DataTransferManager.listExportsInDownloads]). That is exactly
 * what [onChooseOtherFile] is for, and the text below says so rather than leaving the user to
 * wonder why their file is missing.
 */
@Composable
fun ImportDataDialog(
    availableFiles: List<ExportFileRef>,
    isBusy: Boolean,
    onImportFile: (ExportFileRef) -> Unit,
    onChooseOtherFile: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isBusy) onCancel() },
        title = { Text("Import My Data") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "If a barcode in the file already has a note here, the imported one replaces it, " +
                        "and an imported voice note replaces the recording saved for that barcode. Notes for " +
                        "barcodes the file says nothing about are left exactly as they are — nothing is wiped.",
                    fontSize = 15.sp
                )

                SectionHeading("Files found in Downloads")
                if (availableFiles.isEmpty()) {
                    Text(
                        text = "No export files were found in Downloads. If you copied one here from another " +
                            "phone, Android may not let the app see it directly — use Choose a Different File " +
                            "below and pick it yourself.",
                        fontSize = 15.sp
                    )
                } else {
                    for (file in availableFiles) {
                        FileRow(file = file, enabled = !isBusy, onClick = { onImportFile(file) })
                    }
                }

                if (isBusy) {
                    Text(text = "Importing…", fontSize = 15.sp, color = Color.Gray)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onChooseOtherFile,
                enabled = !isBusy,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Choose a different file. Opens the phone's own file chooser."
                }
            ) { Text("Choose a Different File") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isBusy) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun FileRow(file: ExportFileRef, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(14.dp)
            .clearAndSetSemantics {
                contentDescription = "${file.label}. Double tap to import this file."
            }
    ) {
        Text(text = file.name, fontSize = 16.sp)
        Text(text = file.label.substringAfter(", "), fontSize = 13.sp, color = Color.Gray)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .clearAndSetSemantics {
                val status = if (checked) "ticked" else "not ticked"
                contentDescription = "$label, $status. Double tap to toggle."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onToggle)
    }
}

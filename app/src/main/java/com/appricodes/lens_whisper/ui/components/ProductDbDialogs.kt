package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.data.ProductDbInfo
import com.appricodes.lens_whisper.data.ProductDbSource

/**
 * The confirmation the user has to give before the product database is downloaded — "پس از تایید
 * کاربر", the explicit requirement this feature was asked for with.
 *
 * There is only one database and one URL (see [ProductDbSource]), so there is nothing to choose:
 * this dialog exists purely so that a download this large is never a surprise. It names the size
 * the server just reported, says which server it comes from, credits the source projects and their
 * licence, and warns — as everything else in this app that describes the world does — that the
 * information is not guaranteed correct. It also states plainly that only the download is online:
 * once the database is on the phone, looking a barcode up in it sends nothing at all.
 *
 * [isUpdate] switches the wording for a user who already has a copy, where the meaningful question
 * is "spend this much data again to refresh it?" rather than "install this?".
 */
@Composable
fun ProductDbConfirmDialog(
    info: ProductDbInfo,
    isUpdate: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val size = ProductDbSource.formatBytes(info.sizeBytes)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (isUpdate) "Update the Product Database?" else "Download the Product Database?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isUpdate) {
                        "This will download a fresh copy, $size, from ${ProductDbSource.hostName}, " +
                            "replacing the one already on your phone."
                    } else {
                        "This will download $size from ${ProductDbSource.hostName} and keep it on your phone."
                    },
                    fontSize = 16.sp
                )
                Text(
                    text = "Use Wi-Fi if you can. A download this size over mobile data may cost you money.",
                    fontSize = 16.sp
                )
                Text(
                    text = "Once it is installed, scanning a product barcode is answered on your own phone. " +
                        "Nothing is sent anywhere, and it works with no signal at all.",
                    fontSize = 16.sp
                )
                Text(
                    text = "The data comes from the Open Food Facts and Open Products Facts projects, " +
                        "published under the Open Database Licence. It is contributed by the public and is " +
                        "not guaranteed to be correct or up to date. Do not rely on it for anything " +
                        "important — particularly not for allergies, medicines, or safety.",
                    fontSize = 15.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Download now. $size from ${ProductDbSource.hostName}."
                }
            ) { Text(if (isUpdate) "Update" else "Download") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

/** Confirmation before removing an installed database — a several-hundred-megabyte download is not
 *  something to lose to a mis-tap. */
@Composable
fun ProductDbRemoveDialog(
    installedSize: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Remove the Product Database?") },
        text = {
            Text(
                "It will be deleted from this phone, freeing $installedSize. Scanning still works — " +
                    "product names will come from the internet again, if you have allowed that. You can " +
                    "download it again at any time.",
                fontSize = 16.sp
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Progress while the database downloads. The user can leave — the system's download manager
 *  carries on with its own notification — so this is a window onto it, not a modal that has to be
 *  kept open. */
@Composable
fun ProductDbProgressDialog(
    bytesDownloaded: Long,
    totalBytes: Long,
    isFinishing: Boolean,
    onCancel: () -> Unit,
    onHide: () -> Unit
) {
    val percent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    AlertDialog(
        onDismissRequest = onHide,
        title = { Text("Downloading") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Product database",
                    fontSize = 16.sp,
                    modifier = Modifier.semantics { heading() }
                )
                if (isFinishing) {
                    Text(
                        text = "Download finished. Checking the database — this can take a moment and must " +
                            "not be interrupted.",
                        fontSize = 15.sp
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (totalBytes > 0) {
                    Text(
                        text = "$percent per cent, ${ProductDbSource.formatBytes(bytesDownloaded)} of " +
                            "${ProductDbSource.formatBytes(totalBytes)}.",
                        fontSize = 15.sp
                    )
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    // The server did not report a size, so there is no percentage to give. Saying
                    // how much has arrived is still more use than a bare spinner.
                    Text(
                        text = "${ProductDbSource.formatBytes(bytesDownloaded)} downloaded so far.",
                        fontSize = 15.sp
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "You can leave this screen — the download carries on in the background.",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onHide) { Text("Continue in Background") }
        },
        dismissButton = {
            // Deliberately unavailable while finishing: cancelling half way through the check leaves
            // a partial file that would be rejected anyway, and the wording above has just told the
            // user not to interrupt it.
            TextButton(onClick = onCancel, enabled = !isFinishing) { Text("Cancel Download") }
        }
    )
}

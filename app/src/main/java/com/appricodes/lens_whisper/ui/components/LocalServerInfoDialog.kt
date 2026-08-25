package com.appricodes.lens_whisper.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/** Where "Download Server" / "Send Link" point — a guide page on the existing backend host (no
 *  packaged installer exists yet; the page explains current setup = building `desktop/` per its
 *  README, and can swap in a real installer download later without this URL changing). */
const val LOCAL_SERVER_GUIDE_URL = "https://lw.wikiponder.com/local_server_setup.html"

/**
 * "About Local Server" explainer. Shown once automatically the first time the user selects the
 * Local Server model (see `ModelManagementActivity`'s/`ModelPickerPopup`'s `selectLocalServer`,
 * gated on [com.appricodes.lens_whisper.data.PreferencesManager.hasSeenLocalServerIntro]), and
 * re-openable any time afterward via the "About Local Server" item [MoreMenu] shows while Local
 * Server is selected.
 */
@Composable
fun LocalServerInfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Run image analysis on your own computer instead of the cloud — private, free, " +
                        "and works without waiting on a network round trip once it's running.",
                    fontSize = 15.sp
                )
                Text(
                    "To use this, install and start the LensWhisper Desktop companion app on a " +
                        "computer on your home network. This app will then find it automatically.",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, LOCAL_SERVER_GUIDE_URL.toUri()))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Download Server") }
                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, LOCAL_SERVER_GUIDE_URL)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Send download link"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send Link") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

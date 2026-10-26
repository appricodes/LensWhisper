package com.appricodes.lens_whisper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme

/** How the app works, in the terms a first-time user needs. */
class HelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LensWhisperTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HelpScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun HelpScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Help", fontSize = 26.sp, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Close", fontSize = 18.sp) }
        }

        Text(
            text = "Everything that looks at your photos runs on your own phone. Your photos are never "
                + "uploaded and we never see them. The one exception is Scan Barcode, described below, "
                + "and even there only a barcode number is ever sent — never a picture.",
            fontSize = 18.sp
        )
        Text(
            text = "Basic tasks is built in and needs no download. Detect Objects and Identify "
                + "Objects and Colours use a dedicated on-device detector to name the objects in "
                + "view (and their colours). Explore Photo by Touch opens the photo full-screen so "
                + "you can move a finger over it and hear what's there, or tap once for more "
                + "options, including a list of everything found and roughly where it is.",
            fontSize = 18.sp
        )
        Text(
            text = "Light Level plays a tone matching the "
                + "brightness in front of the camera. Tap the button to start it, and tap again "
                + "to stop.",
            fontSize = 18.sp
        )
        Text(
            text = "Scan Barcode keeps looking until it finds a QR code or a product barcode, with a "
                + "quiet tone every couple of seconds while it searches. A QR code is read out "
                + "directly. For a product barcode, Settings offers an optional product database you "
                + "can download once and keep on the phone — with it installed, products are named "
                + "without going online at all, and the barcode never leaves your phone. Failing that, "
                + "the app can look the product up online: it asks your permission the first time, "
                + "sends only the barcode number, and works without the lookup if you would rather it "
                + "didn't.",
            fontSize = 18.sp
        )
        Text(
            text = "Once a barcode is found you can leave yourself a note about it, either recorded "
                + "in your own voice or spoken and turned into text. Scan the same barcode again "
                + "another day and your note comes straight back. Settings can save all your notes "
                + "into a file in the Downloads folder, and read one back in again — that file is the "
                + "only way they ever leave the app, and it never goes online.",
            fontSize = 18.sp
        )
        Text(
            text = "The Advanced AI models give fuller, conversational descriptions and can answer "
                + "your own spoken questions. Each has to be downloaded once, and they differ a lot "
                + "in accuracy and speed — open Manage Advanced AI Models to read what each one is "
                + "good and bad at before choosing.",
            fontSize = 18.sp
        )
        Text(
            text = "The camera is only switched on for the instant a photo is taken — the exception "
                + "being Scan Barcode, which needs it running while it searches, and stops it as "
                + "soon as a barcode is found. If you turn the flash on, it fires at the moment of a "
                + "photo rather than staying lit; during a barcode scan it stays on for the search.",
            fontSize = 18.sp
        )
        Text(
            text = "All descriptions are generated by AI and are often wrong. Never rely on them for "
                + "medicines, money, documents, food, or moving around safely — see Legal "
                + "Information in the More menu for the full notice.",
            fontSize = 18.sp
        )
    }
}

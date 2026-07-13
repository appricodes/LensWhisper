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

/** Placeholder help screen — content to be filled in later. */
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
            text = "Simple Camera Describer lets you take a photo and hear its objects, text, colors, " +
                "or a light-level tone, all without an internet connection. Tap a button for a single " +
                "reading, or hold it down for continuous updates every few seconds.",
            fontSize = 18.sp
        )
        Text(
            text = "Advanced Camera Mode and the Video/Image Describer use an online AI model for richer, " +
                "conversational descriptions — sign in with Google to use them.",
            fontSize = 18.sp
        )
        Text(
            text = "More help content is on the way.",
            fontSize = 18.sp
        )
    }
}

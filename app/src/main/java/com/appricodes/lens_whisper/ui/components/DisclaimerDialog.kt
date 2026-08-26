package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.appricodes.lens_whisper.legal.LegalTexts

/**
 * The AI-limitations notice, in both the shapes the app needs it:
 *
 * - **Acceptance gate** ([onAccept] non-null): shown once on first run, before the app can be
 *   used. Not dismissible — no back button, no tap-outside — because the whole point is that the
 *   user has actually been told. [onDecline] closes the app instead of silently letting them in.
 * - **Read-only** ([onAccept] null): the same text with only a Close button, reachable any time
 *   from the main screen's summary line and from [com.appricodes.lens_whisper.LegalActivity].
 *
 * One composable rather than two so the accepted text and the re-readable text can never drift.
 *
 * The body is rendered one [Text] per paragraph by [LegalBody] - with TalkBack, a single text node
 * for the whole notice would be a single stop in the swipe order, leaving no way to move through it
 * a point at a time.
 */
@Composable
fun DisclaimerDialog(
    onDismiss: () -> Unit = {},
    onAccept: (() -> Unit)? = null,
    onDecline: () -> Unit = {}
) {
    val isGate = onAccept != null
    AlertDialog(
        onDismissRequest = { if (!isGate) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isGate,
            dismissOnClickOutside = !isGate
        ),
        title = { Text(LegalTexts.DISCLAIMER_TITLE, fontSize = 20.sp) },
        text = {
            LegalBody(
                text = LegalTexts.DISCLAIMER_BODY,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            if (onAccept != null) {
                Button(onClick = onAccept) { Text("I understand and accept") }
            } else {
                Button(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (isGate) {
                TextButton(onClick = onDecline) { Text("Decline and exit") }
            }
        }
    )
}

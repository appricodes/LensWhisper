package com.appricodes.lens_whisper.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.ads.AdConsentManager
import com.appricodes.lens_whisper.ads.RewardedAdManager
import com.appricodes.lens_whisper.utils.AccessibilityUtils

/**
 * "Support Development": the entire replacement for the old credits dialog. Everything the app can
 * do is free and runs on-device, so this asks for nothing and unlocks nothing — it plays one
 * optional video advertisement, and that is all it does.
 *
 * The sequence is strictly consent → start SDK → load → show, all of it beginning only when the
 * user taps the button here. See [AdConsentManager] for why none of it may happen earlier.
 */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }

    fun announce(message: String) = AccessibilityUtils.announceToTalkBack(context, message)

    fun startAdFlow() {
        val activity = context as? Activity ?: return
        busy = true
        announce("Preparing an advertisement.")
        AdConsentManager.ensureConsentThenReady(
            activity = activity,
            onReady = {
                RewardedAdManager.load(
                    context = activity,
                    onLoaded = {
                        RewardedAdManager.showAd(
                            activity = activity,
                            onFinished = {
                                busy = false
                                announce("Thank you for supporting the app.")
                                onDismiss()
                            },
                            onUnavailable = {
                                busy = false
                                announce("No advertisement is available right now. Please try again later.")
                            }
                        )
                    },
                    onFailed = {
                        busy = false
                        announce("No advertisement is available right now. Please try again later.")
                    }
                )
            },
            onBlocked = { message ->
                busy = false
                announce(message)
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support Development", fontSize = 20.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "LensWhisper is free and has no paid features. Everything runs on your own " +
                        "device, so nothing you do here costs anything.\n\n" +
                        "If you would like to support the work, you can watch one short video " +
                        "advertisement. It unlocks nothing and changes nothing in the app — it is " +
                        "simply a way to help.\n\n" +
                        "The first time, you will be asked to consent to advertising. You can " +
                        "decline, and you can withdraw consent later from Legal Information. " +
                        "Only non-personalised advertising is used.",
                    fontSize = 16.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (!busy) startAdFlow() }, enabled = !busy) {
                Text(if (busy) "Please wait…" else "Watch a video ad")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

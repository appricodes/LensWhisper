package com.appricodes.lens_whisper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.legal.LegalTexts
import com.appricodes.lens_whisper.ui.components.LegalBody
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils

/**
 * Every legally-required document in one place, reached from the More menu: the AI transparency
 * notice (Art. 50 AI Act), the full first-run disclaimer, the privacy policy, the imprint (§5 DDG)
 * the third-party model licences, and a link to the app's public source code (AGPL-3.0).
 *
 * Two levels (index → one document) rather than one long scroll: with TalkBack, a single screen
 * holding all of these is thousands of words deep with no way to skip, whereas a short list of
 * headings is one swipe per document.
 */
class LegalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LensWhisperTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LegalScreen(onClose = { finish() })
                }
            }
        }
    }
}

private data class LegalSection(val title: String, val body: String)

private fun openSourceCode(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://${LegalTexts.SOURCE_CODE_URL}"))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        AccessibilityUtils.announceToTalkBack(
            context,
            "No browser found. The source code is at ${LegalTexts.SOURCE_CODE_URL}"
        )
    }
}

@Composable
private fun LegalScreen(onClose: () -> Unit) {
    val sections = remember {
        listOf(
            LegalSection(LegalTexts.AI_NOTICE_TITLE, LegalTexts.AI_NOTICE),
            LegalSection(LegalTexts.DISCLAIMER_TITLE, LegalTexts.DISCLAIMER_BODY),
            LegalSection(LegalTexts.PRIVACY_TITLE, LegalTexts.PRIVACY),
            LegalSection(LegalTexts.IMPRESSUM_TITLE, LegalTexts.IMPRESSUM),
            LegalSection(LegalTexts.LICENSES_TITLE, LegalTexts.LICENSES)
        )
    }
    var openSection by remember { mutableStateOf<LegalSection?>(null) }
    val context = LocalContext.current

    BackHandler(enabled = openSection != null) { openSection = null }

    val current = openSection
    if (current == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Legal Information",
                    fontSize = 26.sp,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                TextButton(onClick = onClose) { Text("Close", fontSize = 18.sp) }
            }

            sections.forEach { section ->
                HorizontalDivider()
                Text(
                    text = section.title,
                    fontSize = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openSection = section }
                        .padding(vertical = 16.dp)
                )
            }
            // The AGPL-3.0 the app is released under requires offering its source; this row opens
            // the public repository in the browser instead of just naming it in the licence text.
            HorizontalDivider()
            Text(
                text = "Source Code",
                fontSize = 19.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "open on GitHub in the browser") { openSourceCode(context) }
                    .padding(vertical = 16.dp)
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = current.title,
                    fontSize = 22.sp,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                TextButton(onClick = { openSection = null }) { Text("Back", fontSize = 18.sp) }
            }
            LegalBody(text = current.body, fontSize = 16.sp)
        }
    }
}

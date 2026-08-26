package com.appricodes.lens_whisper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.inference.runSelectedModelAnalysis
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.ui.components.ExploreGridOverlay
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.SpatialGridParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spatial "explore by touch" analysis of a single captured frame, launched from the camera
 * screen's or the Video/Image describer's Explore button. Finishes back to the caller once the
 * user picks (or backs out of) a grid cell.
 */
class ExploreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferencesManager(this)
        val imageUri = intent?.getStringExtra("IMAGE_URI")?.let { Uri.parse(it) }

        setContent {
            LensWhisperTheme {
                ExploreScreen(prefs = prefs, imageUri = imageUri, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ExploreScreen(
    prefs: PreferencesManager,
    imageUri: Uri?,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var gridMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }

    BackHandler { onClose() }

    LaunchedEffect(imageUri) {
        if (imageUri == null) {
            AccessibilityUtils.announceToTalkBack(context, "No image to explore.")
            onClose()
            return@LaunchedEffect
        }

        backgroundBitmap = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }

        AccessibilityUtils.announceToTalkBack(context, "Analyzing elements.")
        val beepJob = launch(Dispatchers.IO) {
            while (true) {
                ProcessingSoundPlayer.playWaitingTone(context)
                delay(1100)
            }
        }

        val jsonResponse = runSelectedModelAnalysis(
            context = context,
            prefs = prefs,
            config = PromptConfig("explore", "Explore", "", 1, 0.5),
            offlineImages = listOfNotNull(backgroundBitmap),
            localServerCall = {
                LocalServerDiscovery.callOrError(context, prefs) {
                    it.analyzeFrameSpatialGrid(
                        context = context, uri = imageUri, positionMs = 0L, gridSize = 6, isStaticImage = true, prefs = prefs
                    )
                }
            }
        )
        beepJob.cancel()
        isLoading = false

        if (jsonResponse.startsWith("ERROR:")) {
            AccessibilityUtils.announceToTalkBack(context, "Exploration failed.")
            onClose()
        } else {
            val parsed = SpatialGridParser.parse(jsonResponse, 6)
            if (parsed.isNotEmpty()) {
                gridMap = parsed
                AccessibilityUtils.announceToTalkBack(context, "Layout complete. Explore the screen elements.")
            } else {
                AccessibilityUtils.announceToTalkBack(context, "Analysis failed.")
                onClose()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        backgroundBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        ExploreGridOverlay(
            isExploreLoading = isLoading,
            exploreGridMap = gridMap,
            onCellClicked = { desc ->
                AccessibilityUtils.announceToTalkBack(context, desc)
                onClose()
            }
        )
    }
}

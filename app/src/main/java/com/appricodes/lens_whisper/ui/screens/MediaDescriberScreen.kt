package com.appricodes.lens_whisper.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.appricodes.lens_whisper.ExploreActivity
import com.appricodes.lens_whisper.ModelManagementActivity
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.inference.runSelectedModelAnalysis
import com.appricodes.lens_whisper.network.XaiVisionEngine
import com.appricodes.lens_whisper.ui.components.ActionButtonGrid
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.MediaPreviewArea
import com.appricodes.lens_whisper.ui.components.ModelCreditsHeader
import com.appricodes.lens_whisper.ui.components.MoreMenu
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ImageScaling
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Online AI description for a video/image file. The button grid is fully transparent — the
 * user watches the media undisturbed; touching the screen lets TalkBack read a button's label
 * (via its semantics), and tapping runs it.
 */
@Composable
fun MediaDescriberScreen(
    prefs: PreferencesManager,
    settings: AppSettings,
    apiClient: XaiVisionEngine,
    initialFileUri: Uri?,
    exoPlayer: ExoPlayer,
    onVoiceTriggerReady: (() -> Unit) -> Unit,
    onPlayPauseReady: (() -> Unit) -> Unit,
    onRepeatReady: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }

    var currentFileUri by remember { mutableStateOf(initialFileUri) }
    var isImageMode by remember { mutableStateOf(false) }
    var runtimeLoadedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isVideoPlayingState by remember { mutableStateOf(false) }

    var screenWidthPx by remember { mutableStateOf(0f) }
    var screenHeightPx by remember { mutableStateOf(0f) }
    var nativeMediaWidth by remember { mutableStateOf(1f) }
    var nativeMediaHeight by remember { mutableStateOf(1f) }

    var customPrompt1Text by remember { mutableStateOf("") }
    var customPrompt1Title by remember { mutableStateOf("Custom Prompt 1") }
    var customPrompt2Text by remember { mutableStateOf("") }
    var customPrompt2Title by remember { mutableStateOf("Custom Prompt 2") }
    var lastPrompt by remember { mutableStateOf<PromptConfig?>(null) }

    var volKeysEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }
    var activeNetworkJob by remember { mutableStateOf<Job?>(null) }
    var activeAudioFeedbackJob by remember { mutableStateOf<Job?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var dialogState by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showExploreUnsupportedDialog by remember { mutableStateOf(false) }

    fun announceBusy() {
        AccessibilityUtils.announceToTalkBack(context, "Please wait for the current analysis to finish.")
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(speechRecognizer) { onDispose { speechRecognizer.destroy() } }

    val togglePlayPauseAction = {
        if (!isImageMode) {
            if (exoPlayer.isPlaying) { exoPlayer.pause(); AccessibilityUtils.announceToTalkBack(context, "Paused") }
            else { exoPlayer.play(); AccessibilityUtils.announceToTalkBack(context, "Playing") }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { isVideoPlayingState = isPlaying }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val rot = videoSize.unappliedRotationDegrees
                    if (rot == 90 || rot == 270) { nativeMediaWidth = videoSize.height.toFloat(); nativeMediaHeight = videoSize.width * videoSize.pixelWidthHeightRatio }
                    else { nativeMediaWidth = videoSize.width * videoSize.pixelWidthHeightRatio; nativeMediaHeight = videoSize.height.toFloat() }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    val displayRotation = remember(context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) context.display?.rotation ?: android.view.Surface.ROTATION_0
        else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    }

    val uiRotationDegrees = remember(screenWidthPx, screenHeightPx, nativeMediaWidth, nativeMediaHeight, displayRotation) {
        if (screenHeightPx > screenWidthPx && nativeMediaWidth > nativeMediaHeight) 90f
        else if (screenWidthPx > screenHeightPx && nativeMediaHeight > nativeMediaWidth) if (displayRotation == android.view.Surface.ROTATION_270) 90f else -90f
        else 0f
    }

    val isRotated = uiRotationDegrees != 0f
    val virtualScreenWidth = if (isRotated) screenHeightPx else screenWidthPx
    val virtualScreenHeight = if (isRotated) screenWidthPx else screenHeightPx
    val virtualScreenAspect = if (virtualScreenHeight > 0) virtualScreenWidth / virtualScreenHeight else 1f
    val mediaAspect = nativeMediaWidth / nativeMediaHeight
    val mediaWidthDp = with(density) { (if (virtualScreenAspect > mediaAspect) virtualScreenHeight * mediaAspect else virtualScreenWidth).toDp() }
    val mediaHeightDp = with(density) { (if (virtualScreenAspect > mediaAspect) virtualScreenHeight else virtualScreenWidth / mediaAspect).toDp() }

    LaunchedEffect(currentFileUri) {
        currentFileUri?.let { uri ->
            activeNetworkJob?.cancel(); activeAudioFeedbackJob?.cancel(); isAnalyzing = false
            customPrompt1Text = ""; customPrompt1Title = "Custom Prompt 1"; customPrompt2Text = ""; customPrompt2Title = "Custom Prompt 2"
            val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true || uri.toString().endsWith(".jpg")
            if (isImage) {
                isImageMode = true; exoPlayer.stop()
                runCatching {
                    context.contentResolver.openInputStream(uri).use { stream ->
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, options)
                        nativeMediaWidth = options.outWidth.toFloat().coerceAtLeast(1f)
                        nativeMediaHeight = options.outHeight.toFloat().coerceAtLeast(1f)
                    }
                    context.contentResolver.openInputStream(uri).use { stream ->
                        runtimeLoadedImageBitmap = BitmapFactory.decodeStream(stream)
                    }
                }
            } else {
                isImageMode = false; runtimeLoadedImageBitmap = null
                exoPlayer.setMediaItem(MediaItem.fromUri(uri)); exoPlayer.prepare(); exoPlayer.playWhenReady = true
            }
        }
    }

    fun handleResponse(response: String) {
        activeAudioFeedbackJob?.cancel()
        if (response.startsWith("ERROR:")) {
            AccessibilityUtils.announceToTalkBack(context, "Error occurred.")
            dialogState = "Error" to response.removePrefix("ERROR:").trim()
        } else {
            AccessibilityUtils.announceToTalkBack(context, response)
            if (prefs.showResultsInDialog) dialogState = "AI Description" to response
        }
    }

    suspend fun extractVideoFrameBitmaps(uri: Uri, positionMs: Long, config: PromptConfig): List<Bitmap> =
        withContext(Dispatchers.IO) {
            val frames = mutableListOf<Bitmap>()
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val intervalMs = (config.framesInterval * 1000).toLong()
                for (i in (config.framesCount - 1) downTo 0) {
                    val targetTimeMs = positionMs - (i * intervalMs)
                    if (targetTimeMs >= 0) {
                        retriever.getFrameAtTime(targetTimeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { frames.add(it) }
                    }
                }
                retriever.release()
            } catch (e: Exception) { /* return whatever frames were extracted */ }
            frames
        }

    fun runAnalysis(config: PromptConfig, label: String) {
        val uri = currentFileUri
        if (uri == null) {
            AccessibilityUtils.announceToTalkBack(context, "No media source loaded")
            return
        }
        if (isAnalyzing) {
            announceBusy()
            return
        }
        isAnalyzing = true
        AccessibilityUtils.announceToTalkBack(context, "Analyzing $label")

        activeAudioFeedbackJob = coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                ProcessingSoundPlayer.playChime()
                delay(1100)
            }
        }

        val currentPlaybackPosition = exoPlayer.currentPosition
        activeNetworkJob = coroutineScope.launch {
            try {
                val offlineImages = if (prefs.selectedModelId == "online") {
                    emptyList()
                } else if (isImageMode) {
                    listOfNotNull(runtimeLoadedImageBitmap)
                } else {
                    extractVideoFrameBitmaps(uri, currentPlaybackPosition, config)
                }
                val response = runSelectedModelAnalysis(
                    context = context,
                    prefs = prefs,
                    config = config,
                    offlineImages = offlineImages,
                    onlineCall = {
                        if (isImageMode) apiClient.analyzeStaticImageFile(context, uri, config, prefs)
                        else apiClient.analyzeVideoFrame(context, uri, currentPlaybackPosition, config, prefs)
                    }
                )
                handleResponse(response)
            } finally {
                isAnalyzing = false
            }
        }
    }

    suspend fun extractCurrentFrameUri(): Uri? = withContext(Dispatchers.IO) {
        val uri = currentFileUri ?: return@withContext null
        if (isImageMode) return@withContext uri
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(exoPlayer.currentPosition * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            retriever.release()
            if (frame == null) return@withContext null
            val scaled = ImageScaling.scaleToMax(frame, 1600)
            val file = File(context.cacheDir, "explore_frame.jpg")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }

    val startVoiceRecognition = {
        val rawCode = prefs.selectedLanguageCode
        val localeCode = if (rawCode.startsWith("fa", ignoreCase = true) || prefs.selectedLanguage.equals("Persian", ignoreCase = true)) "fa-IR" else rawCode
        if (!isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
        AccessibilityUtils.announceToTalkBack(context, "Listening... Please speak your question now.")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeCode)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsd: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onResults(results: Bundle?) {
                val recognizedQuery = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!recognizedQuery.isNullOrBlank()) {
                    customPrompt2Text = customPrompt1Text; customPrompt2Title = customPrompt1Title
                    customPrompt1Text = recognizedQuery
                    customPrompt1Title = if (recognizedQuery.length > 20) recognizedQuery.take(17) + "..." else recognizedQuery
                    val config = PromptConfig("custom", "Voice Query", recognizedQuery, 1, 0.5)
                    lastPrompt = config
                    runAnalysis(config, "Voice Query")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    LaunchedEffect(Unit) {
        onVoiceTriggerReady(startVoiceRecognition)
        onPlayPauseReady(togglePlayPauseAction)
        onRepeatReady {
            val prompt = lastPrompt
            if (prompt != null) runAnalysis(prompt, prompt.title)
            else AccessibilityUtils.announceToTalkBack(context, "No previous action to repeat.")
        }
    }

    // Resume playback unconditionally when Explore finishes, matching the original in-place
    // Explore overlay's behavior (it always resumed, regardless of whether it was playing
    // before Explore paused it).
    val exploreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!isImageMode) exoPlayer.play()
    }

    fun onExplore() {
        if (isAnalyzing) {
            announceBusy()
            return
        }
        if (!offlineModelsRepository.isExploreSupported(prefs.selectedModelId)) {
            showExploreUnsupportedDialog = true
            return
        }
        isAnalyzing = true
        coroutineScope.launch {
            try {
                if (!isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
                AccessibilityUtils.announceToTalkBack(context, "Entering exploration mode. Analyzing elements.")
                val exploreUri = extractCurrentFrameUri()
                if (exploreUri == null) {
                    AccessibilityUtils.announceToTalkBack(context, "Could not capture the current frame.")
                    return@launch
                }
                exploreLauncher.launch(Intent(context, ExploreActivity::class.java).putExtra("IMAGE_URI", exploreUri.toString()))
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun onGridAction(cellLabel: String) {
        when (cellLabel) {
            "Microphone" -> startVoiceRecognition()
            "Explore" -> onExplore()
            "Vol Keys: On", "Vol Keys: Off" -> {
                val newState = !volKeysEnabled
                volKeysEnabled = newState
                prefs.volumeShortcutsEnabled = newState
                AccessibilityUtils.announceToTalkBack(context, if (newState) "Volume shortcuts enabled" else "Volume shortcuts disabled")
            }
            "Repeat" -> {
                val prompt = lastPrompt
                if (prompt != null) runAnalysis(prompt, prompt.title)
                else AccessibilityUtils.announceToTalkBack(context, "No previous action to repeat.")
            }
            "Play", "Pause" -> togglePlayPauseAction()
            else -> {
                val config = when (cellLabel) {
                    "Brief Narration" -> settings.prompts.find { it.id == "brief_narration" }
                    "Detailed Scene" -> settings.prompts.find { it.id == "detailed_scene" }
                    "People and Outfit" -> settings.prompts.find { it.id == "people_and_outfit" }
                    "Emotions" -> settings.prompts.find { it.id == "emotions" }
                    "Objects" -> settings.prompts.find { it.id == "objects" }
                    "Read Texts" -> settings.prompts.find { it.id == "read_texts" }
                    "Story (5 seconds)" -> settings.prompts.find { it.id == "story_5" }
                    "Story (10 seconds)" -> settings.prompts.find { it.id == "story_10" }
                    customPrompt1Title -> PromptConfig("custom", customPrompt1Title, customPrompt1Text, 1, 0.5)
                    customPrompt2Title -> PromptConfig("custom", customPrompt2Title, customPrompt2Text, 1, 0.5)
                    else -> null
                }
                if (config != null) {
                    lastPrompt = config
                    runAnalysis(config, cellLabel)
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { coords ->
            screenWidthPx = coords.size.width.toFloat(); screenHeightPx = coords.size.height.toFloat()
        }
    ) {
        MediaPreviewArea(
            isImageMode = isImageMode, runtimeLoadedImageBitmap = runtimeLoadedImageBitmap,
            exoPlayer = exoPlayer, uiRotationDegrees = uiRotationDegrees, mediaWidthDp = mediaWidthDp, mediaHeightDp = mediaHeightDp,
            screenWidthPx = screenWidthPx, screenHeightPx = screenHeightPx, isRotated = isRotated, density = density
        )

        val volKeysLabel = if (volKeysEnabled) "Vol Keys: On" else "Vol Keys: Off"
        val rows = buildList {
            add(
                listOf(
                    GridCell("Brief Narration", onClick = { onGridAction("Brief Narration") }),
                    GridCell("Detailed Scene", onClick = { onGridAction("Detailed Scene") }),
                    GridCell("People and Outfit", onClick = { onGridAction("People and Outfit") }),
                    GridCell("Emotions", onClick = { onGridAction("Emotions") })
                )
            )
            add(
                listOf(
                    GridCell("Objects", onClick = { onGridAction("Objects") }),
                    GridCell("Read Texts", onClick = { onGridAction("Read Texts") }),
                    GridCell("Story (5 seconds)", onClick = { onGridAction("Story (5 seconds)") }),
                    GridCell("Story (10 seconds)", onClick = { onGridAction("Story (10 seconds)") })
                )
            )
            add(
                listOf(
                    GridCell(customPrompt1Title, onClick = { onGridAction(customPrompt1Title) }),
                    GridCell(customPrompt2Title, onClick = { onGridAction(customPrompt2Title) }),
                    GridCell("Explore", contentDescription = "Explore. Analyze the layout of the current view in a grid.", onClick = { onGridAction("Explore") }),
                    GridCell(
                        "More",
                        contentDescription = "More options: open a file, settings, or help.",
                        onClick = { showMoreMenu = true },
                        trailingContent = { MoreMenu(expanded = showMoreMenu, onDismiss = { showMoreMenu = false }, prefs = prefs, showOpenCameraOption = true) }
                    )
                )
            )
            add(
                listOf(
                    GridCell("Microphone", contentDescription = "Microphone. Ask a question about what you're watching.", onClick = { onGridAction("Microphone") }),
                    GridCell(volKeysLabel, contentDescription = "Volume key shortcuts are ${if (volKeysEnabled) "on" else "off"}. Double tap to toggle.", onClick = { onGridAction(volKeysLabel) }),
                    GridCell("Repeat", contentDescription = "Repeat the last action.", onClick = { onGridAction("Repeat") }),
                    null
                )
            )
            if (!isImageMode) {
                val playPauseLabel = if (isVideoPlayingState) "Pause" else "Play"
                add(
                    listOf(
                        GridCell(
                            "Rewind",
                            contentDescription = "Rewind. Tap to go back 10 seconds, hold to keep rewinding.",
                            customModifier = seekGestureModifier(direction = -1, label = "Rewind", exoPlayer = exoPlayer, context = context)
                        ),
                        GridCell(playPauseLabel, contentDescription = playPauseLabel, onClick = { onGridAction(playPauseLabel) }),
                        GridCell(
                            "Fast Forward",
                            contentDescription = "Fast forward. Tap to jump ahead 10 seconds, hold to keep advancing.",
                            customModifier = seekGestureModifier(direction = 1, label = "Fast Forward", exoPlayer = exoPlayer, context = context)
                        ),
                        null
                    )
                )
            }
        }

        // Header sits above the grid (not layered under it) so its clickable name/credits
        // aren't shadowed by the grid's full-screen transparent touch cells; MediaPreviewArea
        // still fills the whole Box behind both.
        Column(modifier = Modifier.fillMaxSize()) {
            ModelCreditsHeader(prefs = prefs, backgroundColor = Color.Black.copy(alpha = 0.55f))
            ActionButtonGrid(rows = rows, transparent = true, modifier = Modifier.weight(1f))
        }
    }

    dialogState?.let { (title, msg) ->
        AlertDialog(
            onDismissRequest = { dialogState = null },
            title = { Text(title) },
            text = { Text(msg, fontSize = 18.sp) },
            confirmButton = { Button(onClick = { dialogState = null }) { Text("OK") } }
        )
    }

    if (showExploreUnsupportedDialog) {
        AlertDialog(
            onDismissRequest = { showExploreUnsupportedDialog = false },
            title = { Text("Explore Not Supported") },
            text = { Text("The selected model can't be used for the Explore feature. Choose a different model to use Explore.", fontSize = 18.sp) },
            confirmButton = {
                Button(onClick = {
                    showExploreUnsupportedDialog = false
                    context.startActivity(Intent(context, ModelManagementActivity::class.java))
                }) { Text("Open Model Manager") }
            },
            dismissButton = { Button(onClick = { showExploreUnsupportedDialog = false }) { Text("Cancel") } }
        )
    }
}

/** Tap = seek 10s, press-and-hold = continuous 30s/sec scrubbing, matching the original seek gesture. */
@Composable
private fun seekGestureModifier(direction: Int, label: String, exoPlayer: ExoPlayer, context: Context): Modifier {
    val coroutineScope = rememberCoroutineScope()
    return Modifier.pointerInput(label) {
        forEachGesture {
            awaitPointerEventScope {
                awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.changedToDown() } ?: return@awaitPointerEventScope
                var continuousScrubJob: Job? = null
                var isLongPressTriggered = false
                val longPressTimerJob = coroutineScope.launch {
                    delay(500)
                    isLongPressTriggered = true
                    AccessibilityUtils.announceToTalkBack(context, "Seeking continuously")
                    continuousScrubJob = launch {
                        while (true) {
                            if (direction < 0) exoPlayer.seekTo((exoPlayer.currentPosition - 30000).coerceAtLeast(0))
                            else exoPlayer.seekTo((exoPlayer.currentPosition + 30000).coerceAtMost(exoPlayer.duration))
                            delay(1000)
                        }
                    }
                }
                var pointerUpEvent = awaitPointerEvent(PointerEventPass.Main)
                while (pointerUpEvent.changes.any { it.pressed }) pointerUpEvent = awaitPointerEvent(PointerEventPass.Main)
                longPressTimerJob.cancel()
                continuousScrubJob?.cancel()
                val totalTimeText = AccessibilityUtils.formatTime(exoPlayer.duration)
                if (!isLongPressTriggered) {
                    if (direction < 0) exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                    else exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                }
                AccessibilityUtils.announceToTalkBack(context, "${AccessibilityUtils.formatTime(exoPlayer.currentPosition)} of $totalTimeText")
            }
        }
    }
}

package com.appricodes.lens_whisper.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.appricodes.lens_whisper.ExploreActivity
import com.appricodes.lens_whisper.ModelManagementActivity
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.ui.analysis.CapturedBundle
import com.appricodes.lens_whisper.ui.analysis.rememberAnalysisSessionController
import com.appricodes.lens_whisper.ui.components.ActionButtonGrid
import com.appricodes.lens_whisper.ui.components.DisclaimerDialog
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.MediaPreviewArea
import com.appricodes.lens_whisper.ui.components.ModelLoadingObserver
import com.appricodes.lens_whisper.ui.components.ModelTopBar
import com.appricodes.lens_whisper.ui.components.ResyncSelectedModelOnResume
import com.appricodes.lens_whisper.ui.models.MlkitButtonProvider
import com.appricodes.lens_whisper.ui.models.ModelButtonContext
import com.appricodes.lens_whisper.ui.models.ModelButtonProviderRegistry
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ImageScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Describes a video/image file with the same model-driven top bar + 3-row grid as
 * [com.appricodes.lens_whisper.CameraActivity], but the bottom row shows video transport controls
 * + Microphone instead of camera-switch/flash (there's no live camera here) — see
 * [buildBottomRow]. The button grid is fully transparent — the user watches the media
 * undisturbed; touching the screen lets TalkBack read a button's label (via its semantics), and
 * tapping runs it.
 */
@Composable
fun MediaDescriberScreen(
    prefs: PreferencesManager,
    settings: AppSettings,
    initialFileUri: Uri?,
    exoPlayer: ExoPlayer,
    onVoiceTriggerReady: (() -> Unit) -> Unit,
    onMicStopReady: (() -> Unit) -> Unit,
    onPlayPauseReady: (() -> Unit) -> Unit,
    onRepeatReady: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }
    val offlineModels = remember { offlineModelsRepository.loadModels() }
    val controller = rememberAnalysisSessionController(context, prefs)
    // No live camera here, so MLKit (capturePhoto = null) resolves to an empty grid if ever
    // selected — the model picker hides the MLKit option entirely (showMlkitOption = false below).
    val mlkitProvider = remember { MlkitButtonProvider(context, coroutineScope, capturePhoto = null) }

    var selectedModelId by remember { mutableStateOf(prefs.selectedModelId) }
    // See ResyncSelectedModelOnResume's own doc — without this, switching or deleting the selected
    // model from Manage Advanced Models and returning here leaves this screen's own selectedModelId
    // stuck on whatever was selected when this screen was first composed.
    ResyncSelectedModelOnResume(prefs) { selectedModelId = it }
    val (provider, selectedOfflineModel) = remember(selectedModelId) {
        ModelButtonProviderRegistry.forSelectedModel(selectedModelId, mlkitProvider, offlineModels)
    }
    fun gate(action: () -> Unit) = controller.gateForSelectedModel(selectedModelId, selectedOfflineModel, action)

    var disclaimerAccepted by remember { mutableStateOf(prefs.hasAcceptedDisclaimer) }

    var currentFileUri by remember { mutableStateOf(initialFileUri) }
    var isImageMode by remember { mutableStateOf(false) }
    var runtimeLoadedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isVideoPlayingState by remember { mutableStateOf(false) }

    var screenWidthPx by remember { mutableStateOf(0f) }
    var screenHeightPx by remember { mutableStateOf(0f) }
    var nativeMediaWidth by remember { mutableStateOf(1f) }
    var nativeMediaHeight by remember { mutableStateOf(1f) }

    val togglePlayPauseAction = {
        if (!isImageMode) {
            if (exoPlayer.isPlaying) { exoPlayer.pause(); AccessibilityUtils.announceToTalkBack(context, "Paused") }
            else { exoPlayer.play(); AccessibilityUtils.announceToTalkBack(context, "Playing") }
        }
    }

    LaunchedEffect(Unit) { onPlayPauseReady(togglePlayPauseAction) }

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

    val captureFrames: suspend (PromptConfig) -> CapturedBundle? = { config ->
        val uri = currentFileUri
        if (uri == null) {
            AccessibilityUtils.announceToTalkBack(context, "No media source loaded")
            null
        } else {
            AccessibilityUtils.announceToTalkBack(context, "Analyzing ${config.title}")
            val currentPlaybackPosition = exoPlayer.currentPosition
            val offlineImages = if (isImageMode) listOfNotNull(runtimeLoadedImageBitmap) else extractVideoFrameBitmaps(uri, currentPlaybackPosition, config)
            // File mode has no shutter — nothing was physically captured — so the waiting tone
            // starts right after the frame/image is actually in hand instead.
            controller.startProcessingChime()
            CapturedBundle(
                offlineImages,
                localServerCall = {
                    LocalServerDiscovery.callOrError(context, prefs) {
                        if (isImageMode) it.analyzeStaticImageFile(context, uri, config, prefs)
                        else it.analyzeVideoFrame(context, uri, currentPlaybackPosition, config, prefs)
                    }
                },
                localServerCallStreaming = {
                    LocalServerDiscovery.callOrErrorStreaming(context, prefs) {
                        if (isImageMode) it.analyzeStaticImageFileStreaming(context, uri, config, prefs)
                        else it.analyzeVideoFrameStreaming(context, uri, currentPlaybackPosition, config, prefs)
                    }
                }
            )
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

    // Resume playback unconditionally when Explore finishes, matching the original in-place
    // Explore overlay's behavior (it always resumed, regardless of whether it was playing
    // before Explore paused it).
    val exploreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!isImageMode) exoPlayer.play()
    }

    val ctx = ModelButtonContext(
        prefs = prefs,
        settings = settings,
        selectedOfflineModel = selectedOfflineModel,
        customPromptTexts = controller.customPromptTexts,
        customPromptTitles = controller.customPromptTitles,
        onRunPrompt = { config, label -> gate { controller.runAnalysis(config, label, captureFrames) } },
        onExplore = {
            gate {
                if (!isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
                controller.onExplore(
                    selectedModelId = selectedModelId,
                    captureFrame = { extractCurrentFrameUri() },
                    launchExplore = { uri -> exploreLauncher.launch(Intent(context, ExploreActivity::class.java).putExtra("IMAGE_URI", uri.toString())) }
                )
            }
        },
        onRepeat = { gate { controller.onRepeat(selectedModelId, captureFrames) } },
        // Both need a live camera capture (max resolution + a 720p segmentation copy from the same
        // shot) — this screen works from an already-picked video/image file, not a camera, so
        // there's nothing for either to capture from. See ModelButtonContext's doc.
        onDescribeTv = null,
        onExploreAndDescribe = null,
        onRecordCustomPrompt = { slotIndex ->
            gate {
                if (!isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
                controller.startVoiceRecognition(targetCustomSlot = slotIndex, captureFrames = captureFrames)
            }
        }
    )

    // Re-registers whenever the selected model changes — see the identical note in CameraActivity.
    LaunchedEffect(selectedModelId) {
        onVoiceTriggerReady {
            gate {
                if (!isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
                controller.startVoiceRecognition(captureFrames = captureFrames)
            }
        }
        onMicStopReady { controller.stopVoiceListening() }
        onRepeatReady { ctx.onRepeat() }
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

        val bottomRow = buildBottomRow(
            isImageMode = isImageMode,
            isVideoPlaying = isVideoPlayingState,
            exoPlayer = exoPlayer,
            context = context,
            onPlayPause = togglePlayPauseAction,
            onMicrophone = {
                gate {
                    if (!isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
                    controller.startVoiceRecognition(captureFrames = captureFrames)
                }
            }
        )

        // Header sits above the grid (not layered under it) so its clickable name/credits
        // aren't shadowed by the grid's full-screen transparent touch cells; MediaPreviewArea
        // still fills the whole Box behind both.
        Column(modifier = Modifier.fillMaxSize()) {
            ModelTopBar(
                prefs = prefs,
                controller = controller,
                selectedModelId = selectedModelId,
                onModelSelected = { id -> prefs.selectedModelId = id; selectedModelId = id },
                showMoreMenuOpenCameraOption = true,
                showMoreMenuVolumeKeyToggle = true,
                showMlkitOption = false,
                backgroundColor = Color.Black.copy(alpha = 0.55f)
            )
            ActionButtonGrid(rows = provider.rows(ctx) + listOf(bottomRow), transparent = true, modifier = Modifier.weight(1f))
        }
    }

    controller.ResultDialogs(onOpenModelManager = { context.startActivity(Intent(context, ModelManagementActivity::class.java)) })
    controller.ObserveDownloads()

    // This screen is a second, independent entry point into the app: Android routes a shared or
    // opened image/video straight here (see the VIEW intent filters in AndroidManifest), without
    // the camera screen ever running. The first-run notice therefore has to be gated here too,
    // otherwise sharing a photo into LensWhisper on a fresh install skips it entirely.
    if (!disclaimerAccepted) {
        DisclaimerDialog(
            onAccept = {
                prefs.hasAcceptedDisclaimer = true
                disclaimerAccepted = true
            },
            onDecline = { (context as? android.app.Activity)?.finishAndRemoveTask() }
        )
    }
    ModelLoadingObserver(context = context, selectedModelId = selectedModelId, offlineModels = offlineModels)

    // Proactively warms the selected on-device model as soon as this screen opens (or the
    // selection changes), rather than only lazily loading it the first time a button is pressed —
    // ensureEngine() is a quick no-op if it's already loaded, so this is cheap to re-run.
    // ModelLoadingObserver above turns this into both the visual dialog and the spoken
    // announcement. Not applicable to "mlkit" (this screen doesn't even offer it as an option —
    // see ModelPickerPopup's doc — but selectedModelId is a shared preference, so it's still worth
    // guarding here) or the Local Server (nothing here to preload — it runs on a separate machine).
    LaunchedEffect(selectedModelId) {
        if (selectedModelId != "mlkit" && selectedModelId != LocalServerFeature.MODEL_ID) {
            offlineModels.find { it.id == selectedModelId }?.let { OfflineInferenceEngine.preload(context, it) }
        }
    }
}

/**
 * Video mode: Rewind, Play/Pause, Fast Forward, Microphone — matching the accepted resolution
 * that this row shows video controls + mic instead of camera-switch/flash (no live camera here).
 * Image mode: just Microphone (no transport controls apply to a still image). Volume-key-shortcut
 * toggle lives in [com.appricodes.lens_whisper.ui.components.MoreMenu] on this screen instead of
 * a persistent button, since Rewind/Play/FF + Mic + Vol-Keys doesn't fit one 4-cell row.
 */
@Composable
private fun buildBottomRow(
    isImageMode: Boolean,
    isVideoPlaying: Boolean,
    exoPlayer: ExoPlayer,
    context: Context,
    onPlayPause: () -> Unit,
    onMicrophone: () -> Unit
): List<GridCell?> {
    val micCell = GridCell(
        label = "Microphone",
        contentDescription = "Microphone. Ask a question about what you're watching.",
        onClick = onMicrophone
    )
    if (isImageMode) return listOf(micCell, null, null, null)

    val playPauseLabel = if (isVideoPlaying) "Pause" else "Play"
    return listOf(
        GridCell(
            "Rewind",
            contentDescription = "Rewind. Tap to go back 10 seconds, hold to keep rewinding.",
            customModifier = seekGestureModifier(direction = -1, label = "Rewind", exoPlayer = exoPlayer, context = context)
        ),
        GridCell(playPauseLabel, contentDescription = playPauseLabel, onClick = onPlayPause),
        GridCell(
            "Fast Forward",
            contentDescription = "Fast forward. Tap to jump ahead 10 seconds, hold to keep advancing.",
            customModifier = seekGestureModifier(direction = 1, label = "Fast Forward", exoPlayer = exoPlayer, context = context)
        ),
        micCell
    )
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

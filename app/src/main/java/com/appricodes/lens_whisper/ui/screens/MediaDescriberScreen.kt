package com.appricodes.lens_whisper.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.appricodes.lens_whisper.SegmentationExploreActivity
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.offline.DualResCapture
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.offline.SegmentationIsolation
import com.appricodes.lens_whisper.ui.analysis.CapturedBundle
import com.appricodes.lens_whisper.ui.analysis.rememberAnalysisSessionController
import com.appricodes.lens_whisper.ui.components.ActionButtonGrid
import com.appricodes.lens_whisper.ui.components.AppLifecycleCameraHandler
import com.appricodes.lens_whisper.ui.components.DisclaimerDialog
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.MediaPreviewArea
import com.appricodes.lens_whisper.ui.components.ModelLoadingObserver
import com.appricodes.lens_whisper.ui.components.ModelTopBar
import com.appricodes.lens_whisper.ui.components.ResyncSelectedModelOnResume
import com.appricodes.lens_whisper.ui.icons.GridIcon
import com.appricodes.lens_whisper.ui.models.MlkitButtonProvider
import com.appricodes.lens_whisper.ui.models.ModelButtonContext
import com.appricodes.lens_whisper.ui.models.ModelButtonProviderRegistry
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ImageScaling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// "Describe TV Image"/"Explore and Describe" — matches CameraActivity's own
// SEGMENTATION_COPY_MAX_DIM constant (kept as a separate file-local constant there, same value).
private const val SEGMENTATION_COPY_MAX_DIM = 1280

/**
 * Describes a video/image file with the same model-driven top bar + 3-row grid as
 * [com.appricodes.lens_whisper.CameraActivity] — Basic Tasks and every on-device VLM catalog
 * button work here too, sourcing from the already-loaded image or the current video frame in
 * place of a fresh camera photo (see [captureFileFrame]/`captureFrames`/`captureMaxResPairFromFile`
 * below) — except Light Level Meter and Simple Color Detection, which stay camera-only (see
 * [MlkitButtonProvider]'s `hasLiveCamera`). The bottom row shows video transport controls +
 * Microphone instead of camera-switch/flash (there's no live camera here, and those two stay
 * unavailable in file mode by simply never being offered — see [buildBottomRow]). The button grid
 * is fully transparent — the user watches the media undisturbed; touching the screen lets TalkBack
 * read a button's label (via its semantics), and tapping runs it.
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
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedModelId by remember { mutableStateOf(prefs.selectedModelId) }
    // See ResyncSelectedModelOnResume's own doc — without this, switching or deleting the selected
    // model from Manage Advanced Models and returning here leaves this screen's own selectedModelId
    // stuck on whatever was selected when this screen was first composed.
    ResyncSelectedModelOnResume(prefs) { selectedModelId = it }

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

    // MlkitButtonProvider's own capturePhoto shape — (maxDim, highRes, onShutter) -> Bitmap — asks
    // only for "hand me a bitmap", so the exact same shape a live camera satisfies with a fresh
    // capture is satisfied here with the already-loaded image or the current video frame instead;
    // every button MlkitButtonProvider builds around it (Detect Objects, Identify Objects and
    // Colors, Explore Photo by Touch, Read Text, Read Document) then needs no changes of its own to
    // work from a file. `highRes` is ignored — there's no separate higher-fidelity source to ask
    // for here, unlike a live camera's sensor (see MlkitButtonProvider's own doc) — the file/frame's
    // own resolution, capped at maxDim, is already the best available. Throws (rather than
    // returning null) when there's genuinely nothing to capture, matching capturePhoto's own
    // non-nullable Bitmap contract — MlkitButtonProvider already treats any thrown exception here as
    // "Could not capture photo."
    val captureFileFrame: suspend (maxDim: Int, highRes: Boolean, onShutter: () -> Unit) -> Bitmap = { maxDim, _, onShutter ->
        val uri = currentFileUri ?: throw IllegalStateException("No media loaded")
        // No physical shutter in file mode — start the "model is working" cue as early as possible
        // instead, before the (for video) possibly-slow frame extraction below, mirroring
        // captureFrames' own "no shutter, so as soon as the frame is in hand" reasoning.
        onShutter()
        val source = if (isImageMode) {
            runtimeLoadedImageBitmap ?: throw IllegalStateException("No image loaded")
        } else {
            // ExoPlayer state must be read on this (composition) thread, before any dispatcher hop
            // — extractVideoFrameBitmaps below does its own withContext(IO) immediately.
            val positionMs = exoPlayer.currentPosition
            extractVideoFrameBitmaps(uri, positionMs, PromptConfig("_frame", "", "", 1, 0.0)).firstOrNull()
                ?: throw IllegalStateException("Could not extract video frame")
        }
        withContext(Dispatchers.Default) { ImageScaling.scaleToMax(source, maxDim) }
    }

    val mlkitProvider = remember {
        MlkitButtonProvider(context, coroutineScope, capturePhoto = captureFileFrame, hasLiveCamera = false)
    }
    mlkitProvider.ObserveContinuousLoop(prefs)
    // Mirrors CameraActivity's identical pairing of ObserveContinuousLoop with a pause handler —
    // continuous Read Text must stop polling once this screen backgrounds, same as it does there.
    // Reusing the "camera" handler here is harmless despite this screen having no camera of its own
    // (see this file's own doc): releaseAllCameras is a no-op when nothing is bound, and
    // MediaDescriberActivity.onResume already calls it defensively for the same reason.
    AppLifecycleCameraHandler(
        context = context,
        lifecycleOwner = lifecycleOwner,
        onPauseTasks = { mlkitProvider.stopContinuous() }
    )

    val (provider, selectedOfflineModel) = remember(selectedModelId) {
        ModelButtonProviderRegistry.forSelectedModel(selectedModelId, mlkitProvider, offlineModels)
    }
    fun gate(action: () -> Unit) = controller.gateForSelectedModel(selectedModelId, selectedOfflineModel, action)

    /**
     * File-mode counterpart of CameraActivity's `captureMaxResPair`: builds the same
     * [DualResCapture] pair "Describe TV Image"/"Explore and Describe" need (a downscaled copy to
     * segment, plus a full-detail file to isolate from) — but from the already-loaded image or an
     * extracted video frame instead of a fresh camera capture. There's no separate "sensor" to ask
     * for extra detail from here, so both roles are derived from the exact same source bitmap (see
     * [DualResCapture]'s own doc for why that's still correct). Unlike CameraActivity's version,
     * this never recycles the source bitmap: in image mode it IS runtimeLoadedImageBitmap, still
     * bound to the on-screen preview.
     */
    suspend fun captureMaxResPairFromFile(): DualResCapture? {
        val uri = currentFileUri ?: return null
        return try {
            val source = if (isImageMode) {
                runtimeLoadedImageBitmap
            } else {
                extractVideoFrameBitmaps(uri, exoPlayer.currentPosition, PromptConfig("_frame", "", "", 1, 0.0)).firstOrNull()
            } ?: return null
            val highResFile = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "file_describe_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                file
            }
            val lowRes = withContext(Dispatchers.Default) { ImageScaling.scaleToMax(source, SEGMENTATION_COPY_MAX_DIM) }
            DualResCapture(lowRes, highResFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** File-mode "Describe TV Image" — see CameraActivity's `runDescribeTv` for the camera version
     *  this mirrors; only the capture step differs. */
    suspend fun runDescribeTvFromFile() {
        controller.runGuarded {
            AccessibilityUtils.announceToTalkBack(context, "Analyzing Describe TV Image")
            val capture = captureMaxResPairFromFile()
            if (capture == null) {
                AccessibilityUtils.announceToTalkBack(context, "Could not load image.")
                return@runGuarded
            }
            controller.startProcessingChime()
            val instances = withContext(Dispatchers.Default) {
                OfflineSegmentationDetector.detect(context, capture.lowResBitmap)
            }
            val tvInstance = instances.filter { it.label == "tv" }.maxByOrNull { it.confidence }
            if (tvInstance == null) {
                controller.stopProcessingChime()
                AccessibilityUtils.announceToTalkBack(context, "No TV or screen found.")
                return@runGuarded
            }
            val isolated = withContext(Dispatchers.Default) {
                SegmentationIsolation.isolate(capture.lowResBitmap, tvInstance, capture.highResFile)
            }
            if (isolated == null) {
                controller.stopProcessingChime()
                AccessibilityUtils.announceToTalkBack(context, "Could not isolate the TV image.")
                return@runGuarded
            }
            val config = settings.prompts.find { it.id == "detailed_scene" }
            if (config == null) {
                controller.stopProcessingChime()
                return@runGuarded
            }
            controller.runAnalysisOnBitmap(config, isolated)
        }
    }

    /** File-mode "Explore and Describe" — see CameraActivity's `launchExploreAndDescribeFlow` for
     *  the camera version this mirrors; only the capture step differs. */
    suspend fun launchExploreAndDescribeFlowFromFile() {
        controller.runGuarded {
            AccessibilityUtils.announceToTalkBack(context, "Analyzing Explore and Describe")
            val capture = captureMaxResPairFromFile()
            if (capture == null) {
                AccessibilityUtils.announceToTalkBack(context, "Could not load image.")
                return@runGuarded
            }
            controller.startProcessingChime()
            val lowResUri = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "explore_describe_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { capture.lowResBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Uri.fromFile(file)
            }
            context.startActivity(
                Intent(context, SegmentationExploreActivity::class.java)
                    .putExtra("IMAGE_URI", lowResUri.toString())
                    .putExtra("HIGH_RES_IMAGE_URI", Uri.fromFile(capture.highResFile).toString())
                    .putExtra("VLM_DESCRIBE_MODE", true)
            )
        }
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

    val ctx = ModelButtonContext(
        prefs = prefs,
        settings = settings,
        selectedOfflineModel = selectedOfflineModel,
        customPromptTexts = controller.customPromptTexts,
        customPromptTitles = controller.customPromptTitles,
        onRunPrompt = { config, label -> gate { controller.runAnalysis(config, label, captureFrames) } },
        onRepeat = { gate { controller.onRepeat(selectedModelId, captureFrames) } },
        onDescribeTv = { gate { coroutineScope.launch { runDescribeTvFromFile() } } },
        onExploreAndDescribe = { gate { coroutineScope.launch { launchExploreAndDescribeFlowFromFile() } } },
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
                backgroundColor = Color.Black.copy(alpha = 0.55f)
            )
            ActionButtonGrid(rows = provider.rows(ctx) + listOf(bottomRow), transparent = true, modifier = Modifier.weight(1f))
        }
    }

    controller.ResultDialogs()
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
    // announcement. Not applicable to "mlkit" (Basic Tasks needs no in-memory model) or the Local
    // Server (nothing here to preload — it runs on a separate machine).
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
        icon = GridIcon.MICROPHONE,
        onClick = onMicrophone
    )
    if (isImageMode) return listOf(micCell, null, null, null)

    val playPauseLabel = if (isVideoPlaying) "Pause" else "Play"
    return listOf(
        GridCell(
            "Rewind",
            contentDescription = "Rewind. Tap to go back 10 seconds, hold to keep rewinding.",
            icon = GridIcon.REWIND,
            customModifier = seekGestureModifier(direction = -1, label = "Rewind", exoPlayer = exoPlayer, context = context)
        ),
        GridCell(
            playPauseLabel,
            contentDescription = playPauseLabel,
            icon = if (isVideoPlaying) GridIcon.PAUSE else GridIcon.PLAY,
            onClick = onPlayPause
        ),
        GridCell(
            "Fast Forward",
            contentDescription = "Fast forward. Tap to jump ahead 10 seconds, hold to keep advancing.",
            icon = GridIcon.FAST_FORWARD,
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

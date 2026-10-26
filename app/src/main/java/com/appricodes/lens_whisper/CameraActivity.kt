package com.appricodes.lens_whisper

import com.appricodes.lens_whisper.ui.components.CameraPermissionDeniedException
import com.appricodes.lens_whisper.ui.components.CAMERA_DENIED_MESSAGE
import com.appricodes.lens_whisper.ui.components.rememberPermissionRequester
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.camera.core.Camera
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.legal.LegalTexts
import com.appricodes.lens_whisper.media.HeadsetShortcutGestureHandler
import com.appricodes.lens_whisper.media.MediaSessionHandler
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.offline.DualResCapture
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.offline.SegmentationIsolation
import com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController
import com.appricodes.lens_whisper.ui.analysis.CapturedBundle
import com.appricodes.lens_whisper.ui.analysis.rememberAnalysisSessionController
import com.appricodes.lens_whisper.ui.components.ActionButtonGrid
import com.appricodes.lens_whisper.ui.components.AppLifecycleCameraHandler
import com.appricodes.lens_whisper.ui.components.CameraController
import com.appricodes.lens_whisper.ui.components.CameraExposureFlashHandler
import com.appricodes.lens_whisper.ui.components.CapturedFrame
import com.appricodes.lens_whisper.ui.components.DisclaimerDialog
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.ModelLoadingObserver
import com.appricodes.lens_whisper.ui.components.ModelTopBar
import com.appricodes.lens_whisper.ui.components.ResyncSelectedModelOnResume
import com.appricodes.lens_whisper.ui.icons.GridIcon
import com.appricodes.lens_whisper.ui.models.BasicTasksButtonProvider
import com.appricodes.lens_whisper.ui.models.ModelButtonContext
import com.appricodes.lens_whisper.ui.models.ModelButtonProviderRegistry
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.SpeechOutput
import com.appricodes.lens_whisper.utils.ImageScaling
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.VolumeKeyGestureHandler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The app's launcher: one screen for every camera-based model. No live camera preview is ever
 * shown, and since the camera rework the camera is not even powered except for the moment a photo
 * is taken — see [CameraController].
 *
 * There is no login and no gating on anything external: every model runs on this device.
 */
class CameraActivity : ComponentActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var volumeKeyHandler: VolumeKeyGestureHandler
    private lateinit var headsetMediaSession: MediaSessionHandler
    private lateinit var headsetGestureHandler: HeadsetShortcutGestureHandler
    private var globalRepeat: (() -> Unit)? = null
    private var globalVoiceTrigger: (() -> Unit)? = null
    private var globalMicStop: (() -> Unit)? = null
    // True only between onResume and onPause. Belt-and-braces alongside headsetMediaSession's own
    // setActive(false) on pause: confirmed on-device that a headset press can still reach this
    // Activity's session/onKeyDown right after navigating away (e.g. into Settings) on at least
    // one OEM Bluetooth stack, which without this check would fire a shortcut on a screen the user
    // can no longer see react to it.
    private var isForeground = false

    // Shared by the media-session path (onConfiguredShortcut below) and the onKeyDown/onKeyUp
    // fallback — see HeadsetShortcutGestureHandler's class doc for why both exist.
    private fun triggerHeadsetShortcut(event: KeyEvent): Boolean {
        if (!isForeground) return false
        return headsetGestureHandler.onKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)
        // Runtime permissions are requested only when first needed — the camera on the first
        // capture, the microphone on the first voice question, notifications when a download
        // starts — see PermissionRequester. Nothing is asked for at launch.

        volumeKeyHandler = VolumeKeyGestureHandler(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            onRepeat = { globalRepeat?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous action to repeat.") },
            onMicStart = { globalVoiceTrigger?.invoke() },
            onMicStop = { globalMicStop?.invoke() }
        )
        headsetGestureHandler = HeadsetShortcutGestureHandler(
            prefs = prefs,
            onRepeat = { globalRepeat?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous action to repeat.") },
            onMic = { globalVoiceTrigger?.invoke() }
        )
        // No hard-coded video/voice bindings here (unlike MediaDescriberActivity) — this screen
        // only cares about whichever two headset shortcuts the user actually configured, see
        // MediaSessionHandler's own doc for why the session still has to stay active regardless.
        headsetMediaSession = MediaSessionHandler(
            context = this,
            onConfiguredShortcut = { event -> triggerHeadsetShortcut(event) }
        )

        val appSettings = SettingsRepository(this).loadSettingsFromAssets()

        setContent {
            LensWhisperTheme {
                CameraScreen(
                    prefs = prefs,
                    settings = appSettings,
                    onRepeatReady = { globalRepeat = it },
                    onVoiceTriggerReady = { globalVoiceTrigger = it },
                    onMicStopReady = { globalMicStop = it },
                    onDecline = { finishAndRemoveTask() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        headsetMediaSession.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        headsetMediaSession.setActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        headsetMediaSession.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyHandler.onKeyDown(keyCode)) return true
        // Wired in-line "hook" buttons on some devices arrive here directly instead of through
        // the media session above — see HeadsetShortcutGestureHandler's class doc.
        if (event != null && triggerHeadsetShortcut(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyHandler.onKeyUp(keyCode)) return true
        if (event != null && triggerHeadsetShortcut(event)) return true
        return super.onKeyUp(keyCode, event)
    }
}

private const val MAX_CAPTURE_DIMENSION = 2500
private const val EXIT_CONFIRM_WINDOW_MS = 2000L

// "Describe TV Image"/"Explore and Describe" — the long edge of the 720p segmentation copy
// captureMaxResPair derives from the max-resolution original, matching CameraController's own
// RESOLUTION_720P convention (every other segmentation-driven button already detects at this size).
private const val SEGMENTATION_COPY_MAX_DIM = 1280

private fun cameraFacingName(lensFacing: Int) =
    if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) "front" else "back"

@Composable
private fun CameraScreen(
    prefs: PreferencesManager,
    settings: AppSettings,
    onRepeatReady: (() -> Unit) -> Unit,
    onVoiceTriggerReady: (() -> Unit) -> Unit,
    onMicStopReady: (() -> Unit) -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val permissions = rememberPermissionRequester()
    suspend fun requireCamera() {
        if (!permissions.request(Manifest.permission.CAMERA, CAMERA_DENIED_MESSAGE)) throw CameraPermissionDeniedException()
    }
    val cameraController = remember {
        CameraController(context) { permissions.request(Manifest.permission.CAMERA, CAMERA_DENIED_MESSAGE) }
    }
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }
    val offlineModels = remember { offlineModelsRepository.loadModels() }
    val controller = rememberAnalysisSessionController(context, prefs, permissions)

    // A short welcome phrase the instant the app's main screen first opens — LaunchedEffect(Unit)
    // fires once per composition, not on every resume (this Activity is merely paused, not
    // recomposed from scratch, when returning from another screen like Settings), so it plays on a
    // genuine app open, not a return trip.
    LaunchedEffect(Unit) { ProcessingSoundPlayer.playWelcome(context) }

    // The first-run acceptance gate. Nothing else on this screen is reachable until it is
    // answered — the dialog itself is not dismissible (see DisclaimerDialog).
    var disclaimerAccepted by remember { mutableStateOf(prefs.hasAcceptedDisclaimer) }
    var showDisclaimerReadOnly by remember { mutableStateOf(false) }

    var flashEnabled by remember { mutableStateOf(prefs.flashEnabled) }

    val basicTasksProvider = remember {
        BasicTasksButtonProvider(
            context, coroutineScope,
            // allowFlash ANDs with the user's own setting rather than overriding it — BasicTasksButtonProvider
            // uses it to force the flash off after a continuous run's first frame; every other
            // caller always passes true, letting prefs.flashEnabled decide as normal.
            capturePhoto = { maxDim, highRes, allowFlash, correctOrientation, onShutter ->
                cameraController.withCaptureSession(lifecycleOwner, prefs.flashEnabled && allowFlash, highRes, correctOrientation = correctOrientation) {
                    cameraController.capturePhoto(maxDim, onShutter)
                }
            }
        )
    }
    basicTasksProvider.ObserveContinuousLoop(prefs)

    var selectedModelId by remember { mutableStateOf(prefs.selectedModelId) }
    // See ResyncSelectedModelOnResume's own doc — without this, switching or deleting the selected
    // model from Manage Advanced Models and returning here leaves this screen's own selectedModelId
    // (and everything keyed on it: the top bar's display, the provider, the download gate, the
    // preload effect) stuck on whatever was selected when this screen was first composed.
    ResyncSelectedModelOnResume(prefs) { selectedModelId = it }
    val (provider, selectedOfflineModel) = remember(selectedModelId) {
        ModelButtonProviderRegistry.forSelectedModel(selectedModelId, basicTasksProvider, offlineModels)
    }
    var volumeShortcutsEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }

    // Light Level is the one mode that needs a live frame stream; everything else opens the camera
    // only for the instant of a capture.
    var activeCameraState by remember { mutableStateOf<Camera?>(null) }
    // Keyed on isLightActive, NOT on onAnalysisFrame: that property builds a fresh lambda on every
    // read, so using it as a key restarts this effect on every recomposition and rebinds the camera
    // in a loop.
    LaunchedEffect(basicTasksProvider.isLightActive) {
        // False only when Light Level just asked for the camera and was refused — leave the mode
        // rather than sit in it with no frames arriving.
        if (!cameraController.setAnalysisFrameSink(lifecycleOwner, basicTasksProvider.onAnalysisFrame)) {
            basicTasksProvider.stopActiveMode()
        }
        activeCameraState = cameraController.activeCamera
    }

    // Continuous Objects/Text/Colour polls every couple of seconds; hold one session open for the
    // whole mode rather than reopening the camera on every tick.
    LaunchedEffect(basicTasksProvider.isContinuousActive) {
        if (basicTasksProvider.isContinuousActive) {
            try {
                cameraController.withCaptureSession(lifecycleOwner, prefs.flashEnabled) { awaitCancellation() }
            } catch (e: CameraPermissionDeniedException) {
                basicTasksProvider.stopActiveMode()
            }
        }
    }

    AppLifecycleCameraHandler(
        context = context,
        lifecycleOwner = lifecycleOwner,
        onPauseTasks = { basicTasksProvider.stopContinuous() }
    )

    // releaseAllCameras (above, on pause) unbinds use cases but leaves this controller's analyzer
    // executor thread alive; that only goes away with the screen itself.
    DisposableEffect(cameraController) { onDispose { cameraController.shutdown() } }

    CameraExposureFlashHandler(activeLightMode = basicTasksProvider.isLightActive, activeCamera = activeCameraState)

    suspend fun captureSingleShot(config: PromptConfig): CapturedBundle? {
        requireCamera()
        AccessibilityUtils.announceToTalkBack(context, "Analyzing ${config.title}")
        // Immediate feedback that the tap registered — binding a cold camera can itself take a
        // second or two, and playShutter() below won't fire until that's actually done.
        ProcessingSoundPlayer.playCaptureAcknowledge(context)
        val frame = try {
            cameraController.withCaptureSession(lifecycleOwner, flashEnabled) {
                // onShutter fires the instant the shutter sound plays (see
                // CameraController.capturePhotoInternal), not after readout/decode/scale/file-write
                // — the waiting tone means "the model is working", which starts exactly then.
                cameraController.capturePhotoToFile(MAX_CAPTURE_DIMENSION) { controller.startProcessingChime() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return CapturedBundle(
            listOf(frame.bitmap),
            localServerCall = {
                LocalServerDiscovery.callOrError(context, prefs) { it.analyzeStaticImageFile(context, frame.uri, config, prefs) }
            },
            localServerCallStreaming = {
                LocalServerDiscovery.callOrErrorStreaming(context, prefs) { it.analyzeStaticImageFileStreaming(context, frame.uri, config, prefs) }
            }
        )
    }

    suspend fun captureStory(config: PromptConfig): CapturedBundle? {
        requireCamera()
        AccessibilityUtils.announceToTalkBack(context, "Recording ${config.title}, please hold steady.")
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        val frames = mutableListOf<CapturedFrame>()
        // One session for the whole burst — the camera opens once, not once per frame. The tone
        // generator is released in finally: a cancel-and-replace mid-burst would otherwise leak its
        // native AudioTrack, of which the system allows only a limited number.
        try {
            cameraController.withCaptureSession(lifecycleOwner, flashEnabled) {
                for (i in 0 until config.framesCount) {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    try {
                        frames.add(cameraController.capturePhotoToFile(MAX_CAPTURE_DIMENSION))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) { /* skip this frame */ }
                    if (i < config.framesCount - 1) delay((config.framesInterval * 1000).toLong())
                }
            }
        } finally {
            toneGen.release()
        }
        if (frames.isEmpty()) return null
        controller.startProcessingChime()
        return CapturedBundle(
            frames.map { it.bitmap },
            localServerCall = {
                LocalServerDiscovery.callOrError(context, prefs) {
                    if (frames.size > 1) it.analyzeMultipleStaticImages(context, frames.map { f -> f.uri }, config, prefs)
                    else it.analyzeStaticImageFile(context, frames.first().uri, config, prefs)
                }
            },
            localServerCallStreaming = {
                LocalServerDiscovery.callOrErrorStreaming(context, prefs) {
                    if (frames.size > 1) it.analyzeMultipleStaticImagesStreaming(context, frames.map { f -> f.uri }, config, prefs)
                    else it.analyzeStaticImageFileStreaming(context, frames.first().uri, config, prefs)
                }
            }
        )
    }

    val captureFrames: suspend (PromptConfig) -> CapturedBundle? = { config ->
        if (config.framesCount > 1) captureStory(config) else captureSingleShot(config)
    }

    /**
     * Captures once at the camera's maximum resolution and derives a 720p copy from it — shared by
     * "Describe TV Image" and "Explore and Describe" (see [DualResCapture]'s doc for why the
     * high-res side is a file, not a held bitmap). [onShutter] fires at the exact moment of
     * exposure, same as every other capture path — see [CameraController.capturePhotoInternal].
     */
    suspend fun captureMaxResPair(correctOrientation: Boolean = true, onShutter: () -> Unit): DualResCapture? {
        requireCamera()
        return try {
            val frame = cameraController.withCaptureSession(lifecycleOwner, flashEnabled, maxResolution = true, correctOrientation = correctOrientation) {
                // No maxDim cap here (Int.MAX_VALUE) — the whole point of this capture tier is to
                // keep the sensor's actual resolution intact for SegmentationIsolation to crop from.
                cameraController.capturePhotoToFile(Int.MAX_VALUE, onShutter)
            }
            val highResFile = frame.uri.path?.let { File(it) } ?: return null
            val lowRes = ImageScaling.scaleToMax(frame.bitmap, SEGMENTATION_COPY_MAX_DIM)
            // Free the full-resolution decode the instant the 720p copy exists — SegmentationIsolation
            // re-decodes only what it needs from highResFile later, so nothing needs this bitmap
            // again. Guarded: scaleToMax returns the same instance, unchanged, when it's already
            // within bounds, and recycling that would recycle what we're about to return as lowRes.
            if (lowRes !== frame.bitmap) frame.bitmap.recycle()
            DualResCapture(lowRes, highResFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * "Describe TV Image": captures the max-res/720p pair above, detects on the 720p copy, picks
     * the highest-confidence "tv" instance (COCO has no distinct "picture frame"/monitor class to
     * fall back to — see [com.appricodes.lens_whisper.offline.CocoLabels] — and COCO's `tv`
     * class already tends to fire on framed screens/monitors generally), isolates it from the
     * full-resolution original via [SegmentationIsolation], and asks the selected on-device model
     * to describe it in detail. Wrapped in [AnalysisSessionController.runGuarded] by its caller so
     * the whole pipeline — not just the final model call — gets the usual cancel-and-replace
     * handling: pressing another button mid-pipeline cancels this and starts that one instead of
     * being refused with "please wait".
     */
    suspend fun runDescribeTv() {
        controller.runGuarded {
            ProcessingSoundPlayer.playCaptureAcknowledge(context)
            // Tone starts the instant the shutter fires, same as every other segmentation-driven
            // button — it covers detection + isolation + the model call, not just the last of those.
            val capture = captureMaxResPair { controller.startProcessingChime() }
            if (capture == null) {
                controller.stopProcessingChime()
                AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                return@runGuarded
            }
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

    /**
     * "Explore and Describe": captures the same max-res/720p pair as [runDescribeTv], then opens
     * [SegmentationExploreActivity] in its describe-mode — same detection/hover UI as the Basic
     * Tasks "Explore Photo by Touch" screen, but a tap analyzes the touched object with the on
     * -device model instead of opening the options menu (see that Activity's own doc). Passes the
     * full-res *file* through the Intent (a Bitmap can't safely cross one, and holding the decoded
     * bitmap for the whole Explore session is exactly what [DualResCapture] avoids).
     */
    suspend fun launchExploreAndDescribeFlow() {
        controller.runGuarded {
            ProcessingSoundPlayer.playCaptureAcknowledge(context)
            // Tone starts the instant the shutter fires, same as runDescribeTv above — the user
            // should hear "the model is working" from the moment the photo is taken, not only once
            // SegmentationExploreActivity is fully on screen and has decoded the image back from
            // disk (Activity launch + bitmap decode is a real, noticeable gap). runGuarded's own
            // finally cancels this the moment this function returns; startActivity() below is
            // asynchronous, so that finally fires — and this tone stops — before the new Activity's
            // own composition (and its own tone) ever starts, avoiding two tones overlapping.
            // correctOrientation = false: Explore and Describe sends the captured image to the
            // model exactly as taken, unrotated — same reasoning as SegmentationExploreActivity's
            // own capture (see its captureNewPhoto).
            val capture = captureMaxResPair(correctOrientation = false) { controller.startProcessingChime() }
            if (capture == null) {
                controller.stopProcessingChime()
                AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                return@runGuarded
            }
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

    fun gate(action: () -> Unit) = controller.gateForSelectedModel(selectedModelId, selectedOfflineModel, action)

    val ctx = ModelButtonContext(
        prefs = prefs,
        settings = settings,
        selectedOfflineModel = selectedOfflineModel,
        customPromptTexts = controller.customPromptTexts,
        customPromptTitles = controller.customPromptTitles,
        onRunPrompt = { config, label -> gate { controller.runAnalysis(config, label, captureFrames) } },
        onRepeat = {
            if (selectedModelId == "basic_tasks") basicTasksProvider.repeatLastAction()
            else gate { controller.onRepeat(selectedModelId, captureFrames) }
        },
        onDescribeTv = { gate { coroutineScope.launch { runDescribeTv() } } },
        onExploreAndDescribe = { gate { coroutineScope.launch { launchExploreAndDescribeFlow() } } },
        onRecordCustomPrompt = { slotIndex ->
            gate { controller.startVoiceRecognition(targetCustomSlot = slotIndex, captureFrames = captureFrames) }
        }
    )

    // Re-registers whenever the selected model changes (not just once) — the Activity-level
    // `onKeyDown` callbacks it stores must always resolve gating (`gate`) against the *current*
    // model, not whatever was selected the first time this composable ran.
    LaunchedEffect(selectedModelId) {
        onVoiceTriggerReady {
            if (selectedModelId != "basic_tasks") {
                gate { controller.startVoiceRecognition(captureFrames = captureFrames) }
            }
        }
        onMicStopReady { controller.stopVoiceListening() }
        onRepeatReady { ctx.onRepeat() }

        // Seeds a sensible Repeat/headset-shortcut default on every model switch (this effect
        // also fires once on the very first composition, i.e. a fresh app open) — Detect Objects
        // for Basic Tasks, Brief Narration for every on-device VLM — instead of leaving Repeat
        // pointing at null ("No previous action to repeat") or a stale prompt left over from
        // whichever model was selected before switching. Switching the physical camera (front/
        // back) never touches selectedModelId, so it deliberately does NOT reset this.
        if (selectedModelId == "basic_tasks") {
            basicTasksProvider.resetRepeatToDefault(prefs)
        } else {
            settings.prompts.find { it.id == "brief_narration" }?.let { controller.seedDefaultPrompt(it) }
        }
    }

    var lastExitBackPressAt by remember { mutableStateOf(0L) }
    val activity = context as? Activity
    BackHandler {
        if (selectedModelId == "basic_tasks" && basicTasksProvider.stopActiveMode()) {
            AccessibilityUtils.announceToTalkBack(context, "Stopped.")
        } else {
            val now = System.currentTimeMillis()
            if (now - lastExitBackPressAt < EXIT_CONFIRM_WINDOW_MS) {
                activity?.finish()
            } else {
                lastExitBackPressAt = now
                AccessibilityUtils.announceToTalkBack(context, "Press back again to exit.")
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ModelTopBar(
            prefs = prefs,
            controller = controller,
            selectedModelId = selectedModelId,
            onModelSelected = { id -> prefs.selectedModelId = id; selectedModelId = id },
            onStopRepeatingTask = { basicTasksProvider.stopRepeatingTask() }
        )

        val hasFlash = cameraController.hasFlashUnitForLens()
        val flashLabel = if (flashEnabled) "Flash: On" else "Flash: Off"
        val micSupported = selectedModelId != "basic_tasks"
        val bottomRow = listOf(
            GridCell(
                label = if (volumeShortcutsEnabled) "Vol Keys: On" else "Vol Keys: Off",
                contentDescription = "Volume key shortcuts are ${if (volumeShortcutsEnabled) "on" else "off"}. Double tap to toggle.",
                icon = GridIcon.VOLUME_KEYS,
                onClick = {
                    val newState = !volumeShortcutsEnabled
                    prefs.volumeShortcutsEnabled = newState
                    volumeShortcutsEnabled = newState
                    AccessibilityUtils.announceToTalkBack(context, if (newState) "Volume key shortcuts enabled" else "Volume key shortcuts disabled")
                }
            ),
            GridCell(
                label = "Switch Camera",
                contentDescription = "Switch camera. Currently using the ${cameraFacingName(cameraController.lensFacing)} camera.",
                icon = GridIcon.SWITCH_CAMERA,
                onClick = {
                    coroutineScope.launch {
                        cameraController.toggleLensFacing()
                        activeCameraState = cameraController.activeCamera
                        AccessibilityUtils.announceToTalkBack(context, "Switched to the ${cameraFacingName(cameraController.lensFacing)} camera.")
                    }
                }
            ),
            GridCell(
                label = flashLabel,
                contentDescription = if (!hasFlash) {
                    "Flash. Not available on this camera."
                } else {
                    if (flashEnabled) "$flashLabel. Double tap to toggle. The flash fires only while a photo is taken."
                    else "$flashLabel. Double tap to toggle."
                },
                icon = if (flashEnabled) GridIcon.FLASH_ON else GridIcon.FLASH_OFF,
                enabled = hasFlash && !basicTasksProvider.isLightActive,
                onClick = {
                    val newState = !flashEnabled
                    flashEnabled = newState
                    prefs.flashEnabled = newState
                    AccessibilityUtils.announceToTalkBack(context, if (newState) "Flash on. It will fire when a photo is taken." else "Flash off")
                }
            ),
            GridCell(
                label = "Microphone",
                contentDescription = if (micSupported) "Microphone. Ask a question about what the camera sees." else "Microphone. Not available — Basic Models doesn't support custom questions.",
                icon = GridIcon.MICROPHONE,
                enabled = micSupported,
                onClick = { gate { controller.startVoiceRecognition(captureFrames = captureFrames) } }
            )
        )

        // The disclaimer summary sits directly above the bottom utility row (volume keys/camera
        // switch/flash/mic) — second-from-bottom overall, with every row the current provider
        // returns (for OfflineVlmButtonProvider: its 2 rows of predefined prompts, then its 4
        // custom-prompt buttons as their own row — see that provider's doc) rendered together
        // above it, unchanged in order. The two grids are weighted by their row counts so the
        // cells stay the same height across the split.
        val allRows = provider.rows(ctx) + listOf(bottomRow)
        val mainRows = allRows.dropLast(1)
        val lastRow = allRows.takeLast(1)

        ActionButtonGrid(rows = mainRows, modifier = Modifier.weight(mainRows.size.toFloat()))
        DisclaimerSummaryStrip(onClick = { showDisclaimerReadOnly = true })
        ActionButtonGrid(rows = lastRow, modifier = Modifier.weight(lastRow.size.toFloat()))
    }

    controller.ResultDialogs()
    controller.ObserveDownloads()
    ModelLoadingObserver(context = context, selectedModelId = selectedModelId, offlineModels = offlineModels) { selectedModelId = it }

    // Proactively warms the selected on-device model as soon as this screen opens (or the
    // selection changes), rather than only lazily loading it the first time a button is pressed —
    // preload() is a quick no-op if it's already loaded (and joins a load already in progress), so this is cheap to re-run.
    // ModelLoadingObserver above turns this into both the visual dialog and the spoken
    // announcement. Not applicable to "basic_tasks" (Basic Models needs no in-memory model) or the Local
    // Server (nothing here to preload — it runs on a separate machine) — switching to either now
    // releases whatever on-device engine was loaded, instead of leaving a 1-3GB+ model resident in
    // memory for the rest of the session with nothing left that will ever close it (confirmed
    // on-device: switching to Basic Models used to leave a big VLM's engine loaded, and repeating
    // that a few times compounded into an OOM crash — see OfflineInferenceEngine.release's doc).
    //
    // stopProcessingChime() first, synchronously — the waiting tone must not keep looping through
    // however long cancelActiveAnalysisAndJoin() below takes to actually unwind (a real, if usually
    // short, wait: it's joining a cancelled coroutine, not just requesting cancellation). Same
    // "stop it right away rather than waiting for the analysis job's own finally" reasoning as
    // stopProcessingChime's own doc.
    //
    // cancelActiveAnalysisAndJoin() runs next and is awaited, every time — an analysis that's
    // still using the *current* engine has to be fully torn down before either branch below
    // touches that engine (preloading a different one closes this one as its first step; release()
    // closes it directly), or the in-flight generation gets closed out from under it instead of
    // stopping cleanly. Confirmed on-device: without this, switching models mid-inference left the
    // old generation running until it failed several seconds later with a delayed error dialog.
    // releaseSuspending() (not release()) for the same reason — awaited here, not fire-and-forget.
    LaunchedEffect(selectedModelId) {
        controller.stopProcessingChime()
        controller.cancelActiveAnalysisAndJoin()
        if (selectedModelId != "basic_tasks" && selectedModelId != LocalServerFeature.MODEL_ID) {
            offlineModels.find { it.id == selectedModelId }?.let { OfflineInferenceEngine.preload(context, it) }
        } else {
            OfflineInferenceEngine.releaseSuspending()
        }
    }

    if (!disclaimerAccepted) {
        DisclaimerDialog(
            onAccept = {
                prefs.hasAcceptedDisclaimer = true
                disclaimerAccepted = true
            },
            onDecline = onDecline
        )
    } else if (showDisclaimerReadOnly) {
        DisclaimerDialog(onDismiss = { showDisclaimerReadOnly = false })
    }
}

/**
 * The always-visible one-line reminder that the descriptions are AI-generated, required to sit
 * directly under the first row of buttons. Tapping it re-opens the full notice with no accept
 * button — the acceptance already happened, this is purely a way to read it again.
 */
@Composable
private fun DisclaimerSummaryStrip(onClick: () -> Unit) {
    val context = LocalContext.current
    Text(
        text = LegalTexts.DISCLAIMER_SUMMARY,
        color = Color(0xFFFFD54F),
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A))
            .clickable { SpeechOutput.stop(context); onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

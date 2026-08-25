package com.appricodes.lens_whisper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.network.XaiVisionEngine
import com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController
import com.appricodes.lens_whisper.ui.analysis.CapturedBundle
import com.appricodes.lens_whisper.ui.analysis.rememberAnalysisSessionController
import com.appricodes.lens_whisper.ui.components.ActionButtonGrid
import com.appricodes.lens_whisper.ui.components.AppLifecycleCameraHandler
import com.appricodes.lens_whisper.ui.components.CameraController
import com.appricodes.lens_whisper.ui.components.CameraExposureFlashHandler
import com.appricodes.lens_whisper.ui.components.CapturedFrame
import com.appricodes.lens_whisper.ui.components.CreditsDialog
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.ModelLoadingObserver
import com.appricodes.lens_whisper.ui.components.ModelTopBar
import com.appricodes.lens_whisper.ui.models.MlkitButtonProvider
import com.appricodes.lens_whisper.ui.models.ModelButtonContext
import com.appricodes.lens_whisper.ui.models.ModelButtonProviderRegistry
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.VolumeKeyGestureHandler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's launcher: one screen for every camera-based model — replaces the old
 * `SimpleCameraActivity` (MLKit is now just a model choice, see [MlkitButtonProvider]) and
 * `AdvancedCameraActivity` (the on-device/online prompt flow, see `OfflineVlmButtonProvider`/
 * `OnlineButtonProvider`). No live camera preview is ever shown, matching both predecessors.
 *
 * No login wall here — MLKit works with zero login friction on a fresh install; login is only
 * triggered lazily via [AnalysisSessionController.gateForSelectedModel] when the user picks or
 * taps something belonging to the online model.
 */
class CameraActivity : ComponentActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var volumeKeyHandler: VolumeKeyGestureHandler
    private var globalRepeat: (() -> Unit)? = null
    private var globalVoiceTrigger: (() -> Unit)? = null
    private var globalMicStop: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            .launch(Manifest.permission.RECORD_AUDIO)

        volumeKeyHandler = VolumeKeyGestureHandler(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            onRepeat = { globalRepeat?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous action to repeat.") },
            onMicStart = { globalVoiceTrigger?.invoke() },
            onMicStop = { globalMicStop?.invoke() }
        )

        val appSettings = SettingsRepository(this).loadSettingsFromAssets()
        val apiClient = XaiVisionEngine()

        setContent {
            LensWhisperTheme {
                CameraScreen(
                    prefs = prefs,
                    settings = appSettings,
                    apiClient = apiClient,
                    onRepeatReady = { globalRepeat = it },
                    onVoiceTriggerReady = { globalVoiceTrigger = it },
                    onMicStopReady = { globalMicStop = it }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (volumeKeyHandler.onKeyDown(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (volumeKeyHandler.onKeyUp(keyCode)) true else super.onKeyUp(keyCode, event)
    }
}

private const val MAX_CAPTURE_DIMENSION = 2500
private const val EXIT_CONFIRM_WINDOW_MS = 2000L

private fun cameraFacingName(lensFacing: Int) =
    if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) "front" else "back"

@Composable
private fun CameraScreen(
    prefs: PreferencesManager,
    settings: AppSettings,
    apiClient: XaiVisionEngine,
    onRepeatReady: (() -> Unit) -> Unit,
    onVoiceTriggerReady: (() -> Unit) -> Unit,
    onMicStopReady: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraController = remember { CameraController(context) }
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }
    val offlineModels = remember { offlineModelsRepository.loadModels() }
    val controller = rememberAnalysisSessionController(context, prefs)

    var cameraPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        if (!granted) AccessibilityUtils.announceToTalkBack(context, "Camera permission is required.")
    }
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val mlkitProvider = remember {
        MlkitButtonProvider(context, coroutineScope, capturePhoto = { maxDim -> cameraController.capturePhoto(maxDim) })
    }
    mlkitProvider.ObserveContinuousLoop(prefs)

    var selectedModelId by remember { mutableStateOf(prefs.selectedModelId) }
    val (provider, selectedOfflineModel) = remember(selectedModelId) {
        ModelButtonProviderRegistry.forSelectedModel(selectedModelId, mlkitProvider, offlineModels)
    }
    var volumeShortcutsEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }

    var activeCameraState by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(cameraPermissionGranted, mlkitProvider.isLightActive) {
        if (cameraPermissionGranted) {
            cameraController.bind(lifecycleOwner, mlkitProvider.onAnalysisFrame)
            activeCameraState = cameraController.activeCamera
        }
    }
    LaunchedEffect(mlkitProvider.isLightActive) {
        if (mlkitProvider.isLightActive) cameraController.forceTorchOff()
    }

    AppLifecycleCameraHandler(
        context = context,
        lifecycleOwner = lifecycleOwner,
        onPauseTasks = { mlkitProvider.stopContinuous(); cameraController.forceTorchOff() },
        onBindCamera = {
            coroutineScope.launch {
                if (cameraPermissionGranted) {
                    cameraController.bind(lifecycleOwner, mlkitProvider.onAnalysisFrame)
                    activeCameraState = cameraController.activeCamera
                }
            }
        }
    )

    CameraExposureFlashHandler(activeLightMode = mlkitProvider.isLightActive, activeCamera = activeCameraState)

    suspend fun captureSingleShot(config: PromptConfig): CapturedBundle? {
        if (!cameraPermissionGranted) return null
        AccessibilityUtils.announceToTalkBack(context, "Analyzing ${config.title}")
        controller.startProcessingChime()
        val frame = try {
            cameraController.capturePhotoToFile(MAX_CAPTURE_DIMENSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return CapturedBundle(
            listOf(frame.bitmap),
            onlineCall = { apiClient.analyzeStaticImageFile(context, frame.uri, config, prefs) },
            onlineCallStreaming = { apiClient.analyzeStaticImageFileStreaming(context, frame.uri, config, prefs) },
            localServerCall = {
                LocalServerDiscovery.callOrError(context, prefs) { it.analyzeStaticImageFile(context, frame.uri, config, prefs) }
            },
            localServerCallStreaming = {
                LocalServerDiscovery.callOrErrorStreaming(context, prefs) { it.analyzeStaticImageFileStreaming(context, frame.uri, config, prefs) }
            }
        )
    }

    suspend fun captureStory(config: PromptConfig): CapturedBundle? {
        if (!cameraPermissionGranted) return null
        AccessibilityUtils.announceToTalkBack(context, "Recording ${config.title}, please hold steady.")
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        val frames = mutableListOf<CapturedFrame>()
        for (i in 0 until config.framesCount) {
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            try {
                frames.add(cameraController.capturePhotoToFile(MAX_CAPTURE_DIMENSION))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { /* skip this frame */ }
            if (i < config.framesCount - 1) delay((config.framesInterval * 1000).toLong())
        }
        toneGen.release()
        if (frames.isEmpty()) return null
        controller.startProcessingChime()
        return CapturedBundle(
            frames.map { it.bitmap },
            onlineCall = {
                if (frames.size > 1) apiClient.analyzeMultipleStaticImages(context, frames.map { it.uri }, config, prefs)
                else apiClient.analyzeStaticImageFile(context, frames.first().uri, config, prefs)
            },
            onlineCallStreaming = {
                if (frames.size > 1) apiClient.analyzeMultipleStaticImagesStreaming(context, frames.map { it.uri }, config, prefs)
                else apiClient.analyzeStaticImageFileStreaming(context, frames.first().uri, config, prefs)
            },
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

    suspend fun captureExploreUri(): Uri? {
        if (!cameraPermissionGranted) return null
        return try {
            cameraController.capturePhotoToFile(MAX_CAPTURE_DIMENSION).uri
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun gate(action: () -> Unit) = controller.gateForSelectedModel(selectedModelId, selectedOfflineModel, action)

    val ctx = ModelButtonContext(
        prefs = prefs,
        settings = settings,
        selectedOfflineModel = selectedOfflineModel,
        customPromptTexts = controller.customPromptTexts,
        customPromptTitles = controller.customPromptTitles,
        onRunPrompt = { config, label -> gate { coroutineScope.launch { controller.runAnalysis(config, label, captureFrames) } } },
        onExplore = {
            gate {
                controller.onExplore(
                    selectedModelId = selectedModelId,
                    captureFrame = { captureExploreUri() },
                    launchExplore = { uri -> context.startActivity(Intent(context, ExploreActivity::class.java).putExtra("IMAGE_URI", uri.toString())) }
                )
            }
        },
        onRepeat = {
            if (selectedModelId == "mlkit") mlkitProvider.repeatLastSpoken()
            else gate { coroutineScope.launch { controller.onRepeat(selectedModelId, captureFrames) } }
        },
        onRecordCustomPrompt = { slotIndex ->
            gate { controller.startVoiceRecognition(targetCustomSlot = slotIndex, captureFrames = captureFrames) }
        }
    )

    // Re-registers whenever the selected model changes (not just once) — the Activity-level
    // `onKeyDown` callbacks it stores must always resolve gating (`gate`) against the *current*
    // model, not whatever was selected the first time this composable ran.
    LaunchedEffect(selectedModelId) {
        onVoiceTriggerReady {
            if (selectedModelId != "mlkit") {
                gate { controller.startVoiceRecognition(captureFrames = captureFrames) }
            }
        }
        onMicStopReady { controller.stopVoiceListening() }
        onRepeatReady { ctx.onRepeat() }
    }

    var lastExitBackPressAt by remember { mutableStateOf(0L) }
    val activity = context as? Activity
    BackHandler {
        if (selectedModelId == "mlkit" && mlkitProvider.stopActiveMode()) {
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
            onModelSelected = { id -> prefs.selectedModelId = id; selectedModelId = id }
        )

        val hasFlash = activeCameraState?.cameraInfo?.hasFlashUnit() == true
        val flashLabel = if (cameraController.torchOn) "Flashlight: On" else "Flashlight: Off"
        val micSupported = selectedModelId != "mlkit"
        val bottomRow = listOf(
            GridCell(
                label = if (volumeShortcutsEnabled) "Vol Keys: On" else "Vol Keys: Off",
                contentDescription = "Volume key shortcuts are ${if (volumeShortcutsEnabled) "on" else "off"}. Double tap to toggle.",
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
                onClick = {
                    cameraController.toggleLensFacing()
                    coroutineScope.launch {
                        cameraController.bind(lifecycleOwner, mlkitProvider.onAnalysisFrame)
                        activeCameraState = cameraController.activeCamera
                        AccessibilityUtils.announceToTalkBack(context, "Switched to the ${cameraFacingName(cameraController.lensFacing)} camera.")
                    }
                }
            ),
            GridCell(
                label = flashLabel,
                contentDescription = if (!hasFlash) "Flashlight. Not available on this camera." else "$flashLabel. Double tap to toggle. Turns off automatically after one minute.",
                enabled = hasFlash && !mlkitProvider.isLightActive,
                onClick = {
                    cameraController.toggleTorch(coroutineScope) {
                        AccessibilityUtils.announceToTalkBack(context, "Flashlight turned off automatically.")
                    }
                    AccessibilityUtils.announceToTalkBack(context, if (cameraController.torchOn) "Flashlight on" else "Flashlight off")
                }
            ),
            GridCell(
                label = "Microphone",
                contentDescription = if (micSupported) "Microphone. Ask a question about what the camera sees." else "Microphone. Not available — MLKit doesn't support custom questions.",
                enabled = micSupported,
                onClick = { gate { controller.startVoiceRecognition(captureFrames = captureFrames) } }
            )
        )

        ActionButtonGrid(rows = provider.rows(ctx) + listOf(bottomRow), modifier = Modifier.weight(1f))
    }

    controller.ResultDialogs(onOpenModelManager = { context.startActivity(Intent(context, ModelManagementActivity::class.java)) })
    var showCreditsDialog by remember { mutableStateOf(false) }
    controller.OnlineGateDialog(onGetCredits = { showCreditsDialog = true })
    if (showCreditsDialog) {
        CreditsDialog(prefs = prefs, onDismiss = { showCreditsDialog = false })
    }
    controller.ObserveDownloads()
    ModelLoadingObserver(context = context, selectedModelId = selectedModelId, offlineModels = offlineModels)
}

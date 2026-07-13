package com.appricodes.lens_whisper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.inference.runSelectedModelAnalysis
import com.appricodes.lens_whisper.network.XaiVisionEngine
import com.appricodes.lens_whisper.ui.components.ActionButtonGrid
import com.appricodes.lens_whisper.ui.components.AppLifecycleCameraHandler
import com.appricodes.lens_whisper.ui.components.CameraController
import com.appricodes.lens_whisper.ui.components.CapturedFrame
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.ModelCreditsHeader
import com.appricodes.lens_whisper.ui.components.MoreMenu
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.VolumeShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Online AI description on the live camera — no preview is shown, just the button grid. */
class AdvancedCameraActivity : ComponentActivity() {
    private lateinit var prefs: PreferencesManager
    private lateinit var appSettings: AppSettings
    private lateinit var apiClient: XaiVisionEngine

    private var globalRepeat: (() -> Unit)? = null
    private var globalVoiceTrigger: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        if (!prefs.isLoggedIn) {
            val pending = Intent(this, AdvancedCameraActivity::class.java)
            startActivity(Intent(this, LoginActivity::class.java).putExtra("PENDING_INTENT", pending))
            finish()
            return
        }

        prefs.lastCameraMode = "advanced"

        appSettings = SettingsRepository(this).loadSettingsFromAssets()
        apiClient = XaiVisionEngine()

        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            .launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            LensWhisperTheme {
                AdvancedCameraScreen(
                    prefs = prefs,
                    settings = appSettings,
                    apiClient = apiClient,
                    onRepeatReady = { globalRepeat = it },
                    onVoiceTriggerReady = { globalVoiceTrigger = it }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val consumed = VolumeShortcuts.onKeyDown(
            this, prefs, keyCode,
            onRepeat = { globalRepeat?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous action to repeat.") },
            onPrimary = { globalVoiceTrigger?.invoke() }
        )
        return if (consumed) true else super.onKeyDown(keyCode, event)
    }
}

private const val MAX_CAPTURE_DIMENSION = 2500
private const val EXIT_CONFIRM_WINDOW_MS = 2000L

@Composable
private fun AdvancedCameraScreen(
    prefs: PreferencesManager,
    settings: AppSettings,
    apiClient: XaiVisionEngine,
    onRepeatReady: (() -> Unit) -> Unit,
    onVoiceTriggerReady: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraController = remember { CameraController(context) }
    val offlineModelsRepository = remember { OfflineModelsRepository(context) }

    var cameraPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        if (!granted) AccessibilityUtils.announceToTalkBack(context, "Camera permission is required.")
    }
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var lensFacingTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(cameraPermissionGranted, lensFacingTrigger) {
        if (cameraPermissionGranted) cameraController.bind(lifecycleOwner)
    }

    AppLifecycleCameraHandler(
        context = context,
        lifecycleOwner = lifecycleOwner,
        onPauseTasks = {},
        onBindCamera = { coroutineScope.launch { if (cameraPermissionGranted) cameraController.bind(lifecycleOwner) } }
    )

    var showMoreMenu by remember { mutableStateOf(false) }
    var volKeysEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }

    var customPrompt1Text by remember { mutableStateOf("") }
    var customPrompt1Title by remember { mutableStateOf("Custom Prompt 1") }
    var customPrompt2Text by remember { mutableStateOf("") }
    var customPrompt2Title by remember { mutableStateOf("Custom Prompt 2") }
    var lastPrompt by remember { mutableStateOf<PromptConfig?>(null) }

    var isAnalyzing by remember { mutableStateOf(false) }
    var activeAudioFeedbackJob by remember { mutableStateOf<Job?>(null) }
    var dialogState by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showExploreUnsupportedDialog by remember { mutableStateOf(false) }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(speechRecognizer) { onDispose { speechRecognizer.destroy() } }

    fun announceBusy() {
        AccessibilityUtils.announceToTalkBack(context, "Please wait for the current analysis to finish.")
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

    fun startProcessingChime() {
        activeAudioFeedbackJob = coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                ProcessingSoundPlayer.playChime()
                delay(1100)
            }
        }
    }

    suspend fun performAnalysis(config: PromptConfig, label: String, frames: List<CapturedFrame>) {
        val response = runSelectedModelAnalysis(
            context = context,
            prefs = prefs,
            config = config,
            offlineImages = frames.map { it.bitmap },
            onlineCall = {
                if (frames.size > 1) apiClient.analyzeMultipleStaticImages(context, frames.map { it.uri }, config, prefs)
                else apiClient.analyzeStaticImageFile(context, frames.first().uri, config, prefs)
            }
        )
        handleResponse(response)
    }

    fun runAnalysis(config: PromptConfig, label: String) {
        if (isAnalyzing) {
            announceBusy()
            return
        }
        isAnalyzing = true
        AccessibilityUtils.announceToTalkBack(context, "Analyzing $label")
        startProcessingChime()

        coroutineScope.launch {
            try {
                if (!cameraPermissionGranted) {
                    handleResponse("ERROR: Camera permission is required.")
                    return@launch
                }
                val frame = try {
                    cameraController.capturePhotoToFile(maxDim = MAX_CAPTURE_DIMENSION)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleResponse("ERROR: Could not capture photo.")
                    return@launch
                }
                performAnalysis(config, label, listOf(frame))
            } finally {
                isAnalyzing = false
            }
        }
    }

    val startVoiceRecognition: () -> Unit = {
        val rawCode = prefs.selectedLanguageCode
        val localeCode = if (rawCode.startsWith("fa", ignoreCase = true) || prefs.selectedLanguage.equals("Persian", ignoreCase = true)) "fa-IR" else rawCode
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
        onRepeatReady {
            val prompt = lastPrompt
            if (prompt != null) runAnalysis(prompt, prompt.title)
            else AccessibilityUtils.announceToTalkBack(context, "No previous action to repeat.")
        }
    }

    fun runStoryCapture(config: PromptConfig, label: String) {
        if (isAnalyzing) {
            announceBusy()
            return
        }
        isAnalyzing = true
        coroutineScope.launch {
            try {
                if (!cameraPermissionGranted) {
                    AccessibilityUtils.announceToTalkBack(context, "Camera permission is required.")
                    return@launch
                }
                AccessibilityUtils.announceToTalkBack(context, "Recording $label, please hold steady.")
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                val capturedFrames = mutableListOf<CapturedFrame>()
                for (i in 0 until config.framesCount) {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    try {
                        capturedFrames.add(cameraController.capturePhotoToFile(maxDim = MAX_CAPTURE_DIMENSION))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) { /* skip this frame */ }
                    if (i < config.framesCount - 1) delay((config.framesInterval * 1000).toLong())
                }
                toneGen.release()
                if (capturedFrames.isNotEmpty()) {
                    startProcessingChime()
                    performAnalysis(config, label, capturedFrames)
                } else {
                    AccessibilityUtils.announceToTalkBack(context, "Failed to capture frames.")
                }
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun onGridAction(cellLabel: String) {
        when (cellLabel) {
            "Microphone" -> startVoiceRecognition()
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
            "Switch Camera" -> {
                cameraController.toggleLensFacing()
                lensFacingTrigger++
                AccessibilityUtils.announceToTalkBack(context, "Switched camera.")
            }
            "Explore" -> {
                if (isAnalyzing) {
                    announceBusy()
                } else if (!offlineModelsRepository.isExploreSupported(prefs.selectedModelId)) {
                    showExploreUnsupportedDialog = true
                } else {
                    isAnalyzing = true
                    coroutineScope.launch {
                        try {
                            if (!cameraPermissionGranted) {
                                AccessibilityUtils.announceToTalkBack(context, "Camera permission is required.")
                                return@launch
                            }
                            AccessibilityUtils.announceToTalkBack(context, "Entering exploration mode. Analyzing elements.")
                            val frame = try {
                                cameraController.capturePhotoToFile(maxDim = MAX_CAPTURE_DIMENSION)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                                return@launch
                            }
                            context.startActivity(Intent(context, ExploreActivity::class.java).putExtra("IMAGE_URI", frame.uri.toString()))
                        } finally {
                            isAnalyzing = false
                        }
                    }
                }
            }
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
                    if (cellLabel.startsWith("Story")) runStoryCapture(config, cellLabel)
                    else runAnalysis(config, cellLabel)
                }
            }
        }
    }

    // This screen can be the app's effective root (reopened directly on cold start when it was
    // last used), so a single Back press must not silently exit — require a second press.
    var lastExitBackPressAt by remember { mutableStateOf(0L) }
    val activity = context as? Activity
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastExitBackPressAt < EXIT_CONFIRM_WINDOW_MS) {
            activity?.finish()
        } else {
            lastExitBackPressAt = now
            AccessibilityUtils.announceToTalkBack(context, "Press back again to exit.")
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ModelCreditsHeader(prefs = prefs)

        val volKeysLabel = if (volKeysEnabled) "Vol Keys: On" else "Vol Keys: Off"
        val rows = listOf(
            listOf(
                GridCell("Brief Narration", onClick = { onGridAction("Brief Narration") }),
                GridCell("Detailed Scene", onClick = { onGridAction("Detailed Scene") }),
                GridCell("People and Outfit", onClick = { onGridAction("People and Outfit") }),
                GridCell("Emotions", onClick = { onGridAction("Emotions") })
            ),
            listOf(
                GridCell("Objects", onClick = { onGridAction("Objects") }),
                GridCell("Read Texts", onClick = { onGridAction("Read Texts") }),
                GridCell("Story (5 seconds)", onClick = { onGridAction("Story (5 seconds)") }),
                GridCell("Story (10 seconds)", onClick = { onGridAction("Story (10 seconds)") })
            ),
            listOf(
                GridCell(customPrompt1Title, onClick = { onGridAction(customPrompt1Title) }),
                GridCell(customPrompt2Title, onClick = { onGridAction(customPrompt2Title) }),
                GridCell("Explore", contentDescription = "Explore. Analyze the layout of the current view in a grid.", onClick = { onGridAction("Explore") }),
                GridCell("Switch Camera", contentDescription = "Switch between front and back camera.", onClick = { onGridAction("Switch Camera") })
            ),
            listOf(
                GridCell("Microphone", contentDescription = "Microphone. Ask a question about what the camera sees.", onClick = { onGridAction("Microphone") }),
                GridCell(
                    "More",
                    contentDescription = "More options: open a file, settings, or help.",
                    onClick = { showMoreMenu = true },
                    trailingContent = { MoreMenu(expanded = showMoreMenu, onDismiss = { showMoreMenu = false }, prefs = prefs) }
                ),
                GridCell(volKeysLabel, contentDescription = "Volume key shortcuts are ${if (volKeysEnabled) "on" else "off"}. Double tap to toggle.", onClick = { onGridAction(volKeysLabel) }),
                GridCell("Repeat", contentDescription = "Repeat the last action.", onClick = { onGridAction("Repeat") })
            ),
            listOf(
                GridCell(
                    "Simple Camera Mode",
                    contentDescription = "Switch to simple camera mode with offline object, text, and color detection.",
                    onClick = {
                        // Set before navigating so SimpleCameraActivity's cold-start redirect
                        // (which reopens Advanced when lastCameraMode == "advanced") doesn't
                        // immediately bounce this explicit navigation back here.
                        prefs.lastCameraMode = "simple"
                        context.startActivity(Intent(context, SimpleCameraActivity::class.java))
                    }
                ),
                null,
                null,
                null
            )
        )
        ActionButtonGrid(rows = rows, modifier = Modifier.weight(1f))
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

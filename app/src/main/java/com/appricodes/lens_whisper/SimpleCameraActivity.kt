package com.appricodes.lens_whisper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.offline.OfflineColorDetector
import com.appricodes.lens_whisper.offline.OfflineLightDetector
import com.appricodes.lens_whisper.offline.OfflineObjectDetector
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.ui.components.ActionButtonGrid
import com.appricodes.lens_whisper.ui.components.AppLifecycleCameraHandler
import com.appricodes.lens_whisper.ui.components.CameraController
import com.appricodes.lens_whisper.ui.components.CameraExposureFlashHandler
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.MoreMenu
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.VolumeShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Launcher activity: fully offline, on-device describers. No camera preview is ever shown. */
class SimpleCameraActivity : ComponentActivity() {
    private lateinit var prefs: PreferencesManager
    private var globalRepeat: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        // On a fresh app launch, reopen whichever camera mode was used last. A config change
        // (savedInstanceState != null) or explicit in-app navigation back to this activity
        // should never redirect — only a true cold start of the launcher.
        if (savedInstanceState == null && prefs.lastCameraMode == "advanced") {
            startActivity(Intent(this, AdvancedCameraActivity::class.java))
            finish()
            return
        }
        prefs.lastCameraMode = "simple"

        setContent {
            LensWhisperTheme {
                SimpleCameraScreen(prefs = prefs, onRepeatReady = { globalRepeat = it })
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val repeat = {
            globalRepeat?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous result to repeat.")
        }
        val consumed = VolumeShortcuts.onKeyDown(this, prefs, keyCode, onRepeat = repeat, onPrimary = repeat)
        return if (consumed) true else super.onKeyDown(keyCode, event)
    }
}

private enum class SimpleMode { NONE, OBJECTS, TEXT, COLOR, LIGHT }

private const val CONTINUOUS_INTERVAL_MS = 3000L
private const val MAX_CAPTURE_DIMENSION = 2500
private const val EXIT_CONFIRM_WINDOW_MS = 2000L

@Composable
private fun SimpleCameraScreen(
    prefs: PreferencesManager,
    onRepeatReady: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraController = remember { CameraController(context) }

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

    var activeMode by remember { mutableStateOf(SimpleMode.NONE) }
    var continuousEnabled by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var volKeysEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }
    var showMoreMenu by remember { mutableStateOf(false) }

    var lastObjectsText by remember { mutableStateOf<String?>(null) }
    var lastTextText by remember { mutableStateOf<String?>(null) }
    var lastColorText by remember { mutableStateOf<String?>(null) }
    var lastSpokenText by remember { mutableStateOf<String?>(null) }

    fun speak(text: String) {
        AccessibilityUtils.announceToTalkBack(context, text)
        lastSpokenText = text
    }

    fun speakIfChanged(previous: String?, text: String, updatePrevious: (String) -> Unit) {
        if (text != previous) {
            VolumeShortcuts.interruptTalkBackSpeech(context)
            speak(text)
        }
        updatePrevious(text)
    }

    LaunchedEffect(Unit) {
        onRepeatReady {
            val text = lastSpokenText
            if (text != null) AccessibilityUtils.announceToTalkBack(context, text)
            else AccessibilityUtils.announceToTalkBack(context, "No previous result to repeat.")
        }
    }

    fun bindCamera() {
        coroutineScope.launch {
            if (!cameraPermissionGranted) return@launch
            if (activeMode == SimpleMode.LIGHT) {
                cameraController.bind(lifecycleOwner) { frame -> OfflineLightDetector.detectAndPlayTone(frame) }
            } else {
                cameraController.bind(lifecycleOwner)
            }
            activeCamera = cameraController.activeCamera
        }
    }

    // Only rebind when permission changes or Light mode's analysis use case needs to be
    // attached/detached — NOT on every mode change, since a plain tap on Objects/Text/Colors
    // (which stays on the same ImageCapture-only use case set) would otherwise race an
    // in-flight capturePhoto() against an unrelated unbind/rebind cycle.
    val needsAnalysis = activeMode == SimpleMode.LIGHT
    LaunchedEffect(cameraPermissionGranted, needsAnalysis) { bindCamera() }

    AppLifecycleCameraHandler(
        context = context,
        lifecycleOwner = lifecycleOwner,
        onPauseTasks = { continuousEnabled = false },
        onBindCamera = { bindCamera() }
    )

    CameraExposureFlashHandler(activeLightMode = activeMode == SimpleMode.LIGHT, activeCamera = activeCamera)

    suspend fun captureAndRun(mode: SimpleMode) {
        if (!cameraPermissionGranted) {
            AccessibilityUtils.announceToTalkBack(context, "Camera permission is required.")
            return
        }
        // A capture can race the camera's initial bind (or, momentarily, a lens-facing swap),
        // so retry once after a short wait before reporting failure to the user.
        val bitmap = try {
            cameraController.capturePhoto(maxDim = MAX_CAPTURE_DIMENSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                delay(400)
                cameraController.capturePhoto(maxDim = MAX_CAPTURE_DIMENSION)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                return
            }
        }
        when (mode) {
            SimpleMode.OBJECTS -> OfflineObjectDetector.detect(context, bitmap, prefs) { text ->
                if (continuousEnabled) speakIfChanged(lastObjectsText, text) { lastObjectsText = it }
                else { speak(text); lastObjectsText = text }
            }
            SimpleMode.COLOR -> OfflineColorDetector.detect(context, bitmap, prefs) { text ->
                if (continuousEnabled) speakIfChanged(lastColorText, text) { lastColorText = it }
                else { speak(text); lastColorText = text }
            }
            SimpleMode.TEXT -> OfflineTextRecognizer.recognize(bitmap, prefs.ocrLanguage) { text ->
                if (continuousEnabled) speakIfChanged(lastTextText, text) { lastTextText = it }
                else { speak(text); lastTextText = text }
            }
            else -> {}
        }
    }

    LaunchedEffect(activeMode, continuousEnabled) {
        if (continuousEnabled && (activeMode == SimpleMode.OBJECTS || activeMode == SimpleMode.TEXT || activeMode == SimpleMode.COLOR)) {
            while (isActive) {
                captureAndRun(activeMode)
                delay(CONTINUOUS_INTERVAL_MS)
            }
        }
    }

    fun selectSingleShot(mode: SimpleMode) {
        activeMode = mode
        continuousEnabled = false
        coroutineScope.launch { captureAndRun(mode) }
    }

    fun selectContinuous(mode: SimpleMode) {
        activeMode = mode
        continuousEnabled = true
        AccessibilityUtils.announceToTalkBack(context, "Continuous mode activated. Press back to stop.")
    }

    fun toggleLight() {
        activeMode = if (activeMode == SimpleMode.LIGHT) SimpleMode.NONE else SimpleMode.LIGHT
        continuousEnabled = false
    }

    // Since this is the launcher activity, a single Back press would otherwise close the whole
    // app immediately. Require a second press within EXIT_CONFIRM_WINDOW_MS instead.
    var lastExitBackPressAt by remember { mutableStateOf(0L) }
    val activity = context as? Activity

    BackHandler {
        if (activeMode != SimpleMode.NONE) {
            activeMode = SimpleMode.NONE
            continuousEnabled = false
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val rows = listOf(
            listOf(
                GridCell(
                    label = "Identify Objects",
                    contentDescription = "Identify objects in view. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(SimpleMode.OBJECTS) },
                    onLongClick = { selectContinuous(SimpleMode.OBJECTS) }
                ),
                GridCell(
                    label = "Read Text",
                    contentDescription = "Read text in view. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(SimpleMode.TEXT) },
                    onLongClick = { selectContinuous(SimpleMode.TEXT) }
                )
            ),
            listOf(
                GridCell(
                    label = "Detect Colors",
                    contentDescription = "Detect the main object's colors. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(SimpleMode.COLOR) },
                    onLongClick = { selectContinuous(SimpleMode.COLOR) }
                ),
                GridCell(
                    label = "Light Level",
                    contentDescription = if (activeMode == SimpleMode.LIGHT) "Light level meter is on. Double tap to stop." else "Light level meter. Double tap to hear tones matching the brightness in front of the camera.",
                    onClick = { toggleLight() }
                )
            ),
            listOf(
                GridCell(
                    label = if (volKeysEnabled) "Volume Keys: On" else "Volume Keys: Off",
                    contentDescription = "Volume key shortcuts are ${if (volKeysEnabled) "on" else "off"}. Double tap to toggle.",
                    onClick = {
                        val newState = !volKeysEnabled
                        volKeysEnabled = newState
                        prefs.volumeShortcutsEnabled = newState
                        AccessibilityUtils.announceToTalkBack(context, if (newState) "Volume key shortcuts enabled" else "Volume key shortcuts disabled")
                    }
                ),
                GridCell(
                    label = "Advanced Camera Mode",
                    contentDescription = "Switch to advanced camera mode with online AI descriptions.",
                    onClick = {
                        val target = Intent(context, AdvancedCameraActivity::class.java)
                        if (prefs.isLoggedIn) {
                            context.startActivity(target)
                        } else {
                            context.startActivity(Intent(context, LoginActivity::class.java).putExtra("PENDING_INTENT", target))
                        }
                    }
                )
            ),
            listOf(
                GridCell(
                    label = "More",
                    contentDescription = "More options: open a file, settings, or help.",
                    onClick = { showMoreMenu = true },
                    trailingContent = { MoreMenu(expanded = showMoreMenu, onDismiss = { showMoreMenu = false }, prefs = prefs) }
                )
            )
        )

        ActionButtonGrid(rows = rows)

        if (activeMode != SimpleMode.NONE) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val modeName = when (activeMode) {
                    SimpleMode.OBJECTS -> "Object detection"
                    SimpleMode.TEXT -> "Text reading"
                    SimpleMode.COLOR -> "Color detection"
                    SimpleMode.LIGHT -> "Light level meter"
                    SimpleMode.NONE -> ""
                }
                Text(
                    text = "$modeName active${if (continuousEnabled) " (continuous)" else ""}\nPress Back to stop.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 80.dp).background(Color(0x88000000)).padding(16.dp)
                )
            }
        }
    }
}

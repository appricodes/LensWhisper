package com.appricodes.lens_whisper.ui.models

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.offline.OfflineColorDetector
import com.appricodes.lens_whisper.offline.OfflineLightDetector
import com.appricodes.lens_whisper.offline.OfflineObjectDetector
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.VolumeShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CONTINUOUS_INTERVAL_MS = 3000L
private const val MAX_CAPTURE_DIMENSION = 2500

/**
 * MLKit's button set: reproduces today's Simple Camera 2x2 grid exactly (Identify Objects/Read
 * Text/Detect Colors/Light Level — see the deleted `SimpleCameraActivity.kt` for the original).
 * Unlike the prompt-based [OfflineVlmButtonProvider]/[OnlineButtonProvider], MLKit's buttons
 * capture-and-detect directly (tap = single shot, long-press = continuous polling) rather than
 * routing through [com.appricodes.lens_whisper.inference.runSelectedModelAnalysis] — so this
 * class owns its own small bit of session state (active mode, continuous flag, last-spoken text
 * per mode) instead of going through the shared
 * [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController]. No Explore support: MLKit
 * has no spatial-grid capability.
 *
 * [capturePhoto] is null when there's no live camera to capture from (e.g. if ever hosted on a
 * file-based screen) — in that case this provider renders an empty (all-null) grid rather than
 * offering buttons that can't do anything.
 */
class MlkitButtonProvider(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val capturePhoto: (suspend (maxDim: Int) -> Bitmap)?
) : ModelButtonProvider {

    private enum class Mode { NONE, OBJECTS, TEXT, COLOR, LIGHT }

    private var activeMode by mutableStateOf(Mode.NONE)
    private var continuousEnabled by mutableStateOf(false)
    private var lastObjectsText: String? = null
    private var lastTextText: String? = null
    private var lastColorText: String? = null
    private var lastSpokenText: String? = null

    /** True while Light Level is on — the host screen must bind the camera's analysis use case
     *  (via [onAnalysisFrame]) and force the flashlight off/disabled while this is true. */
    val isLightActive: Boolean get() = activeMode == Mode.LIGHT

    /** Non-null only while Light Level is active; pass to [com.appricodes.lens_whisper.ui.components.CameraController.bind]. */
    val onAnalysisFrame: ((Bitmap) -> Unit)?
        get() = if (activeMode == Mode.LIGHT) { frame -> OfflineLightDetector.detectAndPlayTone(frame) } else null

    /** Call from the host's back-press/stop handling to leave any active mode. Returns true if
     *  a mode was actually active (so the caller can decide whether Back should also exit). */
    fun stopActiveMode(): Boolean {
        if (activeMode == Mode.NONE) return false
        activeMode = Mode.NONE
        continuousEnabled = false
        return true
    }

    /** Call from the host's onPause handling — continuous polling must not keep running in the background. */
    fun stopContinuous() {
        continuousEnabled = false
    }

    /** Wires the volume-key/on-screen "Repeat" action for MLKit: repeats the last spoken result. */
    fun repeatLastSpoken() {
        val text = lastSpokenText
        if (text != null) AccessibilityUtils.announceToTalkBack(context, text)
        else AccessibilityUtils.announceToTalkBack(context, "No previous result to repeat.")
    }

    private fun speak(text: String) {
        AccessibilityUtils.announceToTalkBack(context, text)
        lastSpokenText = text
    }

    private fun speakIfChanged(previous: String?, text: String, updatePrevious: (String) -> Unit) {
        if (text != previous) {
            VolumeShortcuts.interruptTalkBackSpeech(context)
            speak(text)
        }
        updatePrevious(text)
    }

    private suspend fun captureAndRun(mode: Mode, prefs: PreferencesManager) {
        val capture = capturePhoto ?: return
        val bitmap = try {
            capture(MAX_CAPTURE_DIMENSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
            return
        }
        when (mode) {
            Mode.OBJECTS -> OfflineObjectDetector.detect(context, bitmap, prefs) { text ->
                if (continuousEnabled) speakIfChanged(lastObjectsText, text) { lastObjectsText = it }
                else { speak(text); lastObjectsText = text }
            }
            Mode.COLOR -> OfflineColorDetector.detect(context, bitmap, prefs) { text ->
                if (continuousEnabled) speakIfChanged(lastColorText, text) { lastColorText = it }
                else { speak(text); lastColorText = text }
            }
            Mode.TEXT -> OfflineTextRecognizer.recognize(bitmap, prefs.ocrLanguage) { text ->
                if (continuousEnabled) speakIfChanged(lastTextText, text) { lastTextText = it }
                else { speak(text); lastTextText = text }
            }
            else -> {}
        }
    }

    private fun selectSingleShot(mode: Mode, prefs: PreferencesManager) {
        activeMode = mode
        continuousEnabled = false
        coroutineScope.launch { captureAndRun(mode, prefs) }
    }

    private fun selectContinuous(mode: Mode) {
        activeMode = mode
        continuousEnabled = true
        AccessibilityUtils.announceToTalkBack(context, "Continuous mode activated. Press back to stop.")
    }

    private fun toggleLight() {
        activeMode = if (activeMode == Mode.LIGHT) Mode.NONE else Mode.LIGHT
        continuousEnabled = false
    }

    /** Must be called once, in composition, by the host screen — drives the continuous-mode polling loop. */
    @Composable
    fun ObserveContinuousLoop(prefs: PreferencesManager) {
        LaunchedEffect(activeMode, continuousEnabled) {
            if (continuousEnabled && (activeMode == Mode.OBJECTS || activeMode == Mode.TEXT || activeMode == Mode.COLOR)) {
                while (isActive) {
                    captureAndRun(activeMode, prefs)
                    delay(CONTINUOUS_INTERVAL_MS)
                }
            }
        }
    }

    override fun rows(ctx: ModelButtonContext): List<List<GridCell?>> {
        if (capturePhoto == null) return List(3) { listOf(null, null, null, null) }
        return listOf(
            listOf(
                GridCell(
                    label = "Identify Objects",
                    contentDescription = "Identify objects in view. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(Mode.OBJECTS, ctx.prefs) },
                    onLongClick = { selectContinuous(Mode.OBJECTS) }
                ),
                GridCell(
                    label = "Read Text",
                    contentDescription = "Read text in view. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(Mode.TEXT, ctx.prefs) },
                    onLongClick = { selectContinuous(Mode.TEXT) }
                ),
                null,
                null
            ),
            listOf(
                GridCell(
                    label = "Detect Colors",
                    contentDescription = "Detect the main object's colors. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(Mode.COLOR, ctx.prefs) },
                    onLongClick = { selectContinuous(Mode.COLOR) }
                ),
                GridCell(
                    label = "Light Level",
                    contentDescription = if (isLightActive) "Light level meter is on. Double tap to stop." else "Light level meter. Double tap to hear tones matching the brightness in front of the camera.",
                    onClick = { toggleLight() }
                ),
                null,
                null
            ),
            listOf(null, null, null, null)
        )
    }
}

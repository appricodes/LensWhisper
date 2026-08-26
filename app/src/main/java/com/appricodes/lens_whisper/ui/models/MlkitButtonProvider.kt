package com.appricodes.lens_whisper.ui.models

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appricodes.lens_whisper.ReadDocumentActivity
import com.appricodes.lens_whisper.SegmentationExploreActivity
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.offline.DetectedInstance
import com.appricodes.lens_whisper.offline.DetectionPhrasing
import com.appricodes.lens_whisper.offline.OfflineColorDetector
import com.appricodes.lens_whisper.offline.OfflineLightDetector
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.offline.SegmentationSpeedGate
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.components.SlowModelSwitchDialog
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.VolumeShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val CONTINUOUS_INTERVAL_MS = 3000L
private const val MAX_CAPTURE_DIMENSION = 2500

/**
 * "Basic Models"' button set (the on-screen label; [PreferencesManager.selectedModelId] is still
 * the literal string `"mlkit"` — see [ModelManagementActivity] for why the visible name changed):
 * Detect Objects/Object and Color Detection/Explore (all three backed by the bundled YOLOv8-seg
 * detector, see [OfflineSegmentationDetector]) plus Read Text and Light Level (true ML Kit).
 * Unlike the prompt-based [OfflineVlmButtonProvider]/[LocalServerButtonProvider], these buttons
 * capture-and-detect directly (tap = single shot, long-press = continuous polling, where
 * supported) rather than routing through
 * [com.appricodes.lens_whisper.inference.runSelectedModelAnalysis] — so this class owns its own
 * small bit of session state (active mode, continuous flag, last-spoken text per mode, [isBusy])
 * instead of going through the shared
 * [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController]. The plain ML Kit
 * object/color detectors this grid used to expose directly (`OfflineObjectDetector`, and
 * [OfflineColorDetector]'s own `detect`) were dropped once the segmentation-based Detect
 * Objects/Object and Color Detection buttons took over their slots — genuinely superseded, not
 * worth carrying forward unused (`OfflineObjectDetector.kt` was deleted outright;
 * [OfflineColorDetector]'s `detect` is simply unused now, kept only because
 * [OfflineColorDetector.namedDominantColors] in the same file is still very much alive).
 *
 * [capturePhoto] is null when there's no live camera to capture from (e.g. if ever hosted on a
 * file-based screen) — in that case this provider renders an empty (all-null) grid rather than
 * offering buttons that can't do anything. Its `onShutter` parameter fires synchronously at the
 * exact moment the shutter sound does (see `CameraController.capturePhotoInternal`) — modes whose
 * next step needs a "waiting for the model" tone start it from there, not after `capturePhoto`
 * itself returns (which also waits out image readout/decode/scale/file-write). Its `highRes`
 * parameter asks for a sharper capture (1080p vs. the usual 720p) — only Read Text and Read
 * Document pass true, since OCR needs enough resolution for small glyphs to stay legible, where
 * every other mode here only needs enough detail for object/scene description.
 */
class MlkitButtonProvider(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val capturePhoto: (suspend (maxDim: Int, highRes: Boolean, onShutter: () -> Unit) -> Bitmap)?
) : ModelButtonProvider {

    private enum class Mode { NONE, TEXT, LIGHT, SEGMENT, SEGMENT_COLOR }

    private var activeMode by mutableStateOf(Mode.NONE)
    private var continuousEnabled by mutableStateOf(false)
    // True from the moment any button's capture starts until its result has been spoken (or it
    // failed) — every entry point that can trigger a capture checks this first and refuses a
    // second one while it's still set, rather than letting two requests race each other.
    private var isBusy by mutableStateOf(false)
    // Drives SlowModelSwitchDialog — see ObserveSlowModelDialog and SegmentationSpeedGate.
    private var showSlowModelDialog by mutableStateOf(false)
    private var lastTextText: String? = null
    private var lastSegmentText: String? = null
    private var lastSegmentColorText: String? = null
    private var lastSpokenText: String? = null

    /** True while Light Level is on — the host screen must bind the camera's analysis use case
     *  (via [onAnalysisFrame]) and force the flashlight off/disabled while this is true. */
    val isLightActive: Boolean get() = activeMode == Mode.LIGHT

    /** Non-null only while Light Level is active; pass to
     *  [com.appricodes.lens_whisper.ui.components.CameraController.setAnalysisFrameSink]. */
    val onAnalysisFrame: ((Bitmap) -> Unit)?
        get() = if (activeMode == Mode.LIGHT) { frame -> OfflineLightDetector.detectAndPlayTone(context, frame) } else null

    /** True while a repeating Objects/Text/Colour/Segment mode is polling. The host holds one
     *  [com.appricodes.lens_whisper.ui.components.CameraController.withCaptureSession] open for as
     *  long as this is true — otherwise the loop would open and close the camera every couple of
     *  seconds, which is both slower and worse for the battery than simply keeping it open for the
     *  duration of a mode the user explicitly started. Explore is excluded — it leaves this screen
     *  for a new activity, and "keep re-opening a full-screen view every few seconds" isn't a mode
     *  that makes sense for it. */
    val isContinuousActive: Boolean
        get() = continuousEnabled &&
            (activeMode == Mode.TEXT || activeMode == Mode.SEGMENT || activeMode == Mode.SEGMENT_COLOR)

    /** Call from the host's back-press/stop handling to leave any active mode. Returns true if
     *  a mode was actually active (so the caller can decide whether Back should also exit). */
    fun stopActiveMode(): Boolean {
        if (activeMode == Mode.NONE) return false
        if (activeMode == Mode.LIGHT) OfflineLightDetector.stop()
        activeMode = Mode.NONE
        continuousEnabled = false
        return true
    }

    /** Call from the host's onPause handling — continuous polling must not keep running in the background. */
    fun stopContinuous() {
        continuousEnabled = false
        // Light Level isn't gated by continuousEnabled (see isContinuousActive's doc) and its
        // camera frames stop arriving on their own once the app backgrounds and the camera
        // releases — but a note that was already mid-playback would otherwise keep its
        // self-perpetuating completion chain going silently in the background. activeMode is left
        // as Mode.LIGHT so it resumes correctly (no re-tap needed) once frames start arriving again.
        if (activeMode == Mode.LIGHT) OfflineLightDetector.stop()
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
        // Refuse to start a second request while one is still in flight, rather than letting two
        // captures/detections race each other — continuous mode's own loop already can't overlap
        // with itself (see ObserveContinuousLoop), but a single-shot button press could otherwise
        // race a still-running continuous poll, or two rapid taps could race each other.
        if (isBusy) {
            announceBusy()
            return
        }
        isBusy = true
        var toneJob: Job? = null
        try {
            // Immediate feedback that the tap registered — binding a cold camera can itself take a
            // second or two before the shutter click fires. Skipped in continuous mode: the camera
            // stays bound for the whole mode there (see isContinuousActive), so there's no such gap
            // on any poll after the first, and a tick every few seconds would just be noise.
            if (!continuousEnabled) ProcessingSoundPlayer.playCaptureAcknowledge(context)
            val bitmap = try {
                capture(MAX_CAPTURE_DIMENSION, mode == Mode.TEXT) {
                    // Fires the instant the shutter sound plays (see
                    // CameraController.capturePhotoInternal), not after the whole capture — image
                    // readout/decode/scale/file-write shouldn't delay the "still working" cue.
                    // Only "Object Detection"/"Object and Color Detection" run the slow YOLOv8-seg
                    // model; the near-instant ML Kit Objects/Text/Colour modes never had this sound.
                    if (mode == Mode.SEGMENT || mode == Mode.SEGMENT_COLOR) {
                        toneJob = coroutineScope.launch(Dispatchers.IO) {
                            while (true) {
                                ProcessingSoundPlayer.playWaitingTone(context)
                                delay(1100)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                return
            }
            when (mode) {
                Mode.TEXT -> OfflineTextRecognizer.recognize(bitmap, prefs.ocrLanguage) { text ->
                    if (continuousEnabled) speakIfChanged(lastTextText, text) { lastTextText = it }
                    else { speak(text); lastTextText = text }
                }
                Mode.SEGMENT -> {
                    val text = summarizeInstances(context, bitmap, prefs, withColors = false)
                    // Stop the tone the instant there's something to say, not after — otherwise a
                    // tone dispatched a moment earlier can land right on top of the announcement.
                    toneJob?.cancel()
                    if (continuousEnabled) speakIfChanged(lastSegmentText, text) { lastSegmentText = it }
                    else { speak(text); lastSegmentText = text }
                }
                Mode.SEGMENT_COLOR -> {
                    val text = summarizeInstances(context, bitmap, prefs, withColors = true)
                    toneJob?.cancel()
                    if (continuousEnabled) speakIfChanged(lastSegmentColorText, text) { lastSegmentColorText = it }
                    else { speak(text); lastSegmentColorText = text }
                }
                else -> {}
            }
        } finally {
            toneJob?.cancel()
            isBusy = false
        }
    }

    private fun announceBusy() {
        AccessibilityUtils.announceToTalkBack(context, "Please wait for the current analysis to finish.")
    }

    /**
     * Runs [OfflineSegmentationDetector] on [bitmap] and builds the sentence spoken for "Detect
     * Objects"/"Object and Color Detection". [withColors] adds each instance's top-2 dominant
     * colors via [DetectedInstance.namedDominantColors] — the same Palette-based naming
     * [OfflineColorDetector] itself uses internally, just applied to this instance's own mask
     * instead of ML Kit's separate subject-segmentation step. Each instance's
     * own confidence is hedged into its phrasing by [DetectionPhrasing.describeDetection] rather
     * than spoken plainly regardless of how sure the model actually is.
     */
    private suspend fun summarizeInstances(context: Context, bitmap: Bitmap, prefs: PreferencesManager, withColors: Boolean): String {
        val instances = withContext(Dispatchers.Default) {
            SegmentationSpeedGate.detect(context, bitmap, prefs) { showSlowModelDialog = true }
        }
        if (instances.isEmpty()) return "No objects detected."
        // A plain for-loop, not joinToString { ... } — its transform lambda isn't a suspend
        // lambda, so a withContext call inside it doesn't compile.
        val parts = mutableListOf<String>()
        for (instance in instances) {
            val colorPhrase = if (!withColors) "" else {
                val colors = withContext(Dispatchers.Default) { instance.namedDominantColors(bitmap) }
                colors.joinToString(" and ")
            }
            parts.add(DetectionPhrasing.describeDetection(instance.confidence, instance.label, colorPhrase))
        }
        return parts.joinToString(", ")
    }

    /**
     * "Explore": captures a photo, saves it to the cache dir (mirroring
     * [com.appricodes.lens_whisper.ui.components.CameraController.capturePhotoToFile]'s own
     * pattern — nothing here needs anything beyond a Uri to hand to the next activity, but a raw
     * [Bitmap] can't safely cross an Intent extra), and opens [SegmentationExploreActivity], which
     * runs the actual detection itself once it's on screen — same "capture here, analyze there"
     * shape the existing VLM-based Explore ([com.appricodes.lens_whisper.ExploreActivity]) already
     * uses, so a user familiar with one Explore screen isn't met with a different loading pattern
     * on the other. [SegmentationExploreActivity] can also capture further photos of its own once
     * it's open (its "Capture new photo" list item), so this only ever needs to run once per visit.
     */
    private fun launchSegmentationExplore() {
        if (isBusy) {
            announceBusy()
            return
        }
        val capture = capturePhoto ?: return
        isBusy = true
        ProcessingSoundPlayer.playCaptureAcknowledge(context)
        coroutineScope.launch {
            try {
                val bitmap = try {
                    // No tone starts here — SegmentationExploreActivity owns its own "waiting for
                    // the model" cue, started once the captured image is actually received there.
                    // Not a text task, so the usual 720p (highRes = false).
                    capture(MAX_CAPTURE_DIMENSION, false) {}
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "explore_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    Uri.fromFile(file)
                }
                context.startActivity(
                    Intent(context, SegmentationExploreActivity::class.java)
                        .putExtra("IMAGE_URI", uri.toString())
                )
            } finally {
                isBusy = false
            }
        }
    }

    /** "Read Document": same "capture here, read there" shape as [launchSegmentationExplore] —
     *  see [ReadDocumentActivity]'s doc for what happens once it's open. */
    private fun launchReadDocument() {
        if (isBusy) {
            announceBusy()
            return
        }
        val capture = capturePhoto ?: return
        isBusy = true
        ProcessingSoundPlayer.playCaptureAcknowledge(context)
        coroutineScope.launch {
            try {
                val bitmap = try {
                    // No tone starts here — ReadDocumentActivity owns its own "waiting for the
                    // model" cue, started once the captured image is actually received there.
                    // A text task, so 1080p (highRes = true) — see MlkitButtonProvider's doc.
                    capture(MAX_CAPTURE_DIMENSION, true) {}
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "read_document_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    Uri.fromFile(file)
                }
                context.startActivity(
                    Intent(context, ReadDocumentActivity::class.java)
                        .putExtra("IMAGE_URI", uri.toString())
                )
            } finally {
                isBusy = false
            }
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
        if (activeMode == Mode.LIGHT) {
            OfflineLightDetector.stop()
            activeMode = Mode.NONE
        } else {
            activeMode = Mode.LIGHT
            OfflineLightDetector.start()
        }
        continuousEnabled = false
    }

    /** Must be called once, in composition, by the host screen — drives the continuous-mode polling loop. */
    @Composable
    fun ObserveContinuousLoop(prefs: PreferencesManager) {
        LaunchedEffect(activeMode, continuousEnabled) {
            if (isContinuousActive) {
                while (isActive) {
                    captureAndRun(activeMode, prefs)
                    delay(CONTINUOUS_INTERVAL_MS)
                }
            }
        }
    }

    /** Must be called once, in composition, by the host screen — renders the one-time slow-model
     *  prompt (see [SegmentationSpeedGate]) when a "Detect Objects"/"Object and Color Detection"
     *  run triggers it. */
    @Composable
    fun ObserveSlowModelDialog(prefs: PreferencesManager) {
        SlowModelSwitchDialog(context = context, prefs = prefs, show = showSlowModelDialog, onDone = { showSlowModelDialog = false })
    }

    override fun rows(ctx: ModelButtonContext): List<List<GridCell?>> {
        if (capturePhoto == null) return List(3) { listOf(null, null, null, null) }
        return listOf(
            listOf(
                GridCell(
                    label = "Detect Objects",
                    contentDescription = "Detect Objects. Names the objects in view, using a dedicated on-device detector. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(Mode.SEGMENT, ctx.prefs) },
                    onLongClick = { selectContinuous(Mode.SEGMENT) }
                ),
                GridCell(
                    label = "Identify Objects and Colors",
                    contentDescription = "Detect objects and each one's main colors. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(Mode.SEGMENT_COLOR, ctx.prefs) },
                    onLongClick = { selectContinuous(Mode.SEGMENT_COLOR) }
                ),
                GridCell(
                    label = "Explore Photo by Touch",
                    contentDescription = "Explore a photo by touch. Detects objects and their colors, then opens a full-screen view — move your finger over the picture to hear what's under it, or tap once for more options.",
                    onClick = { launchSegmentationExplore() }
                ),
                GridCell(
                    label = "Read Text",
                    contentDescription = "Read text in view. Tap for a single photo, hold to keep watching continuously.",
                    onClick = { selectSingleShot(Mode.TEXT, ctx.prefs) },
                    onLongClick = { selectContinuous(Mode.TEXT) }
                )
            ),
            listOf(
                GridCell(
                    label = "Read Document",
                    contentDescription = "Read a document by touch. Opens a full-screen view of a photo — move your finger over a paragraph to hear it read aloud, or tap once for more options.",
                    onClick = { launchReadDocument() }
                ),
                GridCell(
                    label = "Light Level Meter",
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

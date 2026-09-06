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
import com.appricodes.lens_whisper.BarcodeScanActivity
import com.appricodes.lens_whisper.ReadDocumentActivity
import com.appricodes.lens_whisper.SegmentationExploreActivity
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.offline.DetectedInstance
import com.appricodes.lens_whisper.offline.DetectionPhrasing
import com.appricodes.lens_whisper.offline.OfflineBarcodeScanner
import com.appricodes.lens_whisper.offline.OfflineColorDetector
import com.appricodes.lens_whisper.offline.OfflineLightDetector
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.offline.OfflineTranslator
import com.appricodes.lens_whisper.ui.components.GridCell
import com.appricodes.lens_whisper.ui.icons.GridIcon
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
// "Simple Color Detection" — a plain pixel square (not a fraction of the frame), per its own spec.
private const val SIMPLE_COLOR_CROP_SIZE = 50

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
 * [OfflineColorDetector]'s own `detect`, which used ML Kit Subject Segmentation + Image Labeling)
 * were dropped once the segmentation-based Detect Objects/Object and Color Detection buttons took
 * over their slots — genuinely superseded, not worth carrying forward unused
 * (`OfflineObjectDetector.kt` and `OfflineColorDetector.detect` were both deleted outright, 2026-08-27,
 * along with the play-services-mlkit-subject-segmentation and ML Kit image-labeling dependencies
 * that `detect` was their only consumer of; [OfflineColorDetector.namedDominantColors] in the same
 * file is still very much alive).
 *
 * [capturePhoto] is always a real capture function — CameraActivity's live-sensor one or
 * MediaDescriberScreen's file/frame one (see `MediaDescriberScreen.captureFileFrame`) — every host
 * has some image to hand back, so there's no "no source at all" case for this provider to render
 * around. Its `onShutter` parameter fires synchronously at the exact moment the shutter sound does (see
 * `CameraController.capturePhotoInternal`) — modes whose next step needs a "waiting for the model"
 * tone start it from there, not after `capturePhoto` itself returns (which also waits out image
 * readout/decode/scale/file-write). On a file-based host (MediaDescriberScreen) there is no
 * physical shutter, so `onShutter` there just fires as early as possible instead — see that
 * screen's own capture lambda. Its `highRes` parameter asks for a sharper capture (1080p vs. the
 * usual 720p) — only Read Text and Read Document pass true, since OCR needs enough resolution for
 * small glyphs to stay legible, where every other mode here only needs enough detail for
 * object/scene description; a file-based host has no separate higher-fidelity source to request,
 * so it's free to ignore this flag and just use the file/frame's own resolution throughout. Its
 * `allowFlash` parameter is how [captureAndRun] suppresses the flash after the first frame of a
 * continuous run (see [continuousFlashUsed]) — CameraActivity's live-sensor implementation ANDs it
 * with [PreferencesManager.flashEnabled] itself; a file-based host has no flash to fire at all and
 * just ignores it. Its `correctOrientation` parameter is CameraActivity's live-sensor cue to apply
 * (or skip) the physical-rotation fix described on
 * [com.appricodes.lens_whisper.ui.components.CameraController.withCaptureSession] — every mode here
 * passes true except [launchSegmentationExplore] and [launchReadDocument], whose captures are sent
 * to the model exactly as taken; a file-based host has no live sensor to correct from and just
 * ignores it.
 *
 * [hasLiveCamera] gates the two modes that only make sense against a live, continuously-exposed
 * camera feed rather than one already-fixed image: Light Level Meter (needs a live analysis frame
 * stream — see [onAnalysisFrame]) and Simple Color Detection (reads "the color in front of the
 * camera right now"; redundant with Identify Objects and Colors once the source is a fixed file).
 * Both render disabled, not omitted, when this is false — see [rows]. Scan Barcode is a third
 * case: it reads [hasLiveCamera] not to disable itself but to pick between two genuinely different
 * behaviours — a continuous live scan on a camera host, a one-shot detect against the current
 * file/frame otherwise; see [launchBarcodeScan]. Every other button here (Detect Objects, Identify
 * Objects and Colors, Explore Photo by Touch, Read Text, Read Document) needs nothing
 * camera-specific beyond [capturePhoto] itself, so they work unchanged against whichever bitmap a
 * file-based host's own `capturePhoto` hands back.
 */
class MlkitButtonProvider(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val capturePhoto: suspend (maxDim: Int, highRes: Boolean, allowFlash: Boolean, correctOrientation: Boolean, onShutter: () -> Unit) -> Bitmap,
    private val hasLiveCamera: Boolean = true
) : ModelButtonProvider {

    private enum class Mode { NONE, TEXT, LIGHT, SEGMENT, SEGMENT_COLOR, SIMPLE_COLOR }

    private var activeMode by mutableStateOf(Mode.NONE)
    private var continuousEnabled by mutableStateOf(false)
    // True once a continuous run (Read Text/Simple Color long-press) has fired the flash for its
    // first frame — every frame after that passes allowFlash = false regardless of
    // PreferencesManager.flashEnabled, per explicit product decision: flashing every couple of
    // seconds for as long as continuous mode runs is disruptive/dazzling in a way a single-shot
    // flash never is, and the first frame already gave the detector a properly lit image. Reset
    // by selectContinuous whenever a new run actually starts.
    private var continuousFlashUsed = false
    // True from the moment any button's capture starts until its result has been spoken (or it
    // failed) — every entry point that can trigger a capture checks this first and refuses a
    // second one while it's still set, rather than letting two requests race each other.
    private var isBusy by mutableStateOf(false)
    private var lastTextText: String? = null
    private var lastSimpleColorText: String? = null
    // The volume-key/on-screen "Repeat" action, for whichever single-shot mode last ran — set by
    // selectSingleShot right when the user triggers it, and re-invoked
    // (not just re-spoken) by repeatLastAction so a "repeat" genuinely captures a fresh photo, the
    // same as every other mode's own capture, rather than re-describing a stale one. Left untouched
    // by selectContinuous/toggleLight/launchSegmentationExplore/launchReadDocument — a continuous
    // poll, a toggle, and the two screens that navigate away aren't "repeat" candidates.
    private var lastRepeatableAction: (() -> Unit)? = null

    /** True while Light Level is on — the host screen must bind the camera's analysis use case
     *  (via [onAnalysisFrame]) and force the flashlight off/disabled while this is true. */
    val isLightActive: Boolean get() = activeMode == Mode.LIGHT

    /** Non-null only while Light Level is active; pass to
     *  [com.appricodes.lens_whisper.ui.components.CameraController.setAnalysisFrameSink]. */
    val onAnalysisFrame: ((Bitmap) -> Unit)?
        get() = if (activeMode == Mode.LIGHT) { frame -> OfflineLightDetector.detectAndPlayTone(context, frame) } else null

    /** True while a repeating Text/Simple-Color mode is polling. The host holds one
     *  [com.appricodes.lens_whisper.ui.components.CameraController.withCaptureSession] open for as
     *  long as this is true — otherwise the loop would open and close the camera every couple of
     *  seconds, which is both slower and worse for the battery than simply keeping it open for the
     *  duration of a mode the user explicitly started. Explore is excluded — it leaves this screen
     *  for a new activity, and "keep re-opening a full-screen view every few seconds" isn't a mode
     *  that makes sense for it. Detect Objects/Identify Objects and Colors are excluded too — the
     *  underlying YOLOv8-seg detector is heavy enough that polling it every few seconds isn't a good
     *  experience, so their long-press no longer starts continuous mode at all (see [rows]; only a
     *  single-shot capture remains for [Mode.SEGMENT]/[Mode.SEGMENT_COLOR]). */
    val isContinuousActive: Boolean
        get() = continuousEnabled && (activeMode == Mode.TEXT || activeMode == Mode.SIMPLE_COLOR)

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

    /** Wires the volume-key/on-screen "Repeat" action for MLKit: re-runs the last single-shot mode
     *  on a freshly captured photo — not just re-speaking the previous result against the old one,
     *  which is stale by the time the user asks to repeat it. */
    fun repeatLastAction() {
        val action = lastRepeatableAction
        if (action != null) action()
        else AccessibilityUtils.announceToTalkBack(context, "No previous action to repeat.")
    }

    /** Resets the repeat target to Detect Objects — the grid's first single-shot button — without
     *  actually running it. Call on a fresh app open or whenever the host switches to this model
     *  from a different one (see CameraActivity's `LaunchedEffect(selectedModelId)`), so Repeat/a
     *  headset shortcut always has a sensible default instead of "No previous action to repeat."
     *  until the user has tapped something. Mirrors [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController.seedDefaultPrompt]
     *  for the VLM catalog's own "last prompt" tracking. */
    fun resetRepeatToDefault(prefs: PreferencesManager) {
        lastRepeatableAction = { selectSingleShot(Mode.SEGMENT, prefs) }
    }

    private fun speak(text: String) {
        AccessibilityUtils.announceToTalkBack(context, text)
    }

    private fun speakIfChanged(previous: String?, text: String, updatePrevious: (String) -> Unit) {
        if (text != previous) {
            VolumeShortcuts.interruptTalkBackSpeech(context)
            speak(text)
        }
        updatePrevious(text)
    }

    /** [speakIfChanged], but for a mode whose text should go through [OfflineTranslator.translate]
     *  first — every mode here except [Mode.TEXT] (OCR output stays exactly what was read, in
     *  whatever script it's written in; see captureAndRun's Mode.TEXT branch, which stays on the
     *  plain [speakIfChanged] above). Text is translated *before* the interrupt, not after — not
     *  because order matters to the model, but because interrupting first and only then awaiting
     *  the translation would leave dead air between "cut off whatever was still being said" and
     *  "the new sentence is finally ready", instead of the two landing back to back. */
    private suspend fun speakIfChangedTranslated(
        previous: String?, text: String, prefs: PreferencesManager, updatePrevious: (String) -> Unit
    ) {
        if (text != previous) {
            val spoken = OfflineTranslator.translate(context, text, prefs)
            VolumeShortcuts.interruptTalkBackSpeech(context)
            speak(spoken)
        }
        updatePrevious(text)
    }

    private suspend fun captureAndRun(mode: Mode, prefs: PreferencesManager) {
        val capture = capturePhoto
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
            // OCR's model is downloaded on demand now (see OfflineTextRecognizer's own doc) —
            // check before capturing at all, not after, so a not-ready module doesn't cost the
            // user a capture (and its acknowledge sound) for an attempt that's going to fail
            // anyway. isReady() itself owns the "please wait" announcement and kicks off the
            // download in the background.
            if (mode == Mode.TEXT && !OfflineTextRecognizer.isReady(context, prefs.ocrLanguage)) return
            // Immediate feedback that the tap registered — binding a cold camera can itself take a
            // second or two before the shutter click fires. Skipped in continuous mode: the camera
            // stays bound for the whole mode there (see isContinuousActive), so there's no such gap
            // on any poll after the first, and a tick every few seconds would just be noise.
            if (!continuousEnabled) ProcessingSoundPlayer.playCaptureAcknowledge(context)
            // Single-shot: always respect the flash setting normally. Continuous: only the first
            // frame of the run is allowed to use it — see continuousFlashUsed's own doc.
            val allowFlash = if (continuousEnabled) !continuousFlashUsed else true
            if (continuousEnabled) continuousFlashUsed = true
            val bitmap = try {
                capture(MAX_CAPTURE_DIMENSION, mode == Mode.TEXT, allowFlash, true) {
                    // Fires the instant the shutter sound plays (see
                    // CameraController.capturePhotoInternal), not after the whole capture — image
                    // readout/decode/scale/file-write shouldn't delay the "still working" cue.
                    // Only "Object Detection"/"Object and Color Detection" run the slow YOLOv8-seg
                    // model; the near-instant ML Kit Objects/Text/Colour modes never had this sound.
                    if (mode == Mode.SEGMENT || mode == Mode.SEGMENT_COLOR) {
                        toneJob = coroutineScope.launch(Dispatchers.IO) {
                            ProcessingSoundPlayer.loopClockWaitingTone(context)
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
                // Single-shot only — SEGMENT/SEGMENT_COLOR's long-press no longer starts continuous
                // mode (see isContinuousActive's doc), so continuousEnabled is always false by the
                // time either of these runs; nothing here needs the speakIfChanged dedup path.
                //
                // Both translated (OfflineTranslator.translate, gated on the same "Translate
                // Offline Results" setting as everything else it covers) — unlike OCR, this text
                // is built entirely from a small, fixed vocabulary (DetectionPhrasing's hedge
                // words, COCO class names, Palette color names), the same kind of short, simple
                // sentence this app's on-device translator already handles for VLM responses.
                Mode.SEGMENT -> {
                    val text = summarizeInstances(context, bitmap, withColors = false)
                    // Stop the tone the instant there's something to say, not after — otherwise a
                    // tone dispatched a moment earlier can land right on top of the announcement.
                    toneJob?.cancel()
                    speak(OfflineTranslator.translate(context, text, prefs))
                }
                Mode.SEGMENT_COLOR -> {
                    val text = summarizeInstances(context, bitmap, withColors = true)
                    toneJob?.cancel()
                    speak(OfflineTranslator.translate(context, text, prefs))
                }
                Mode.SIMPLE_COLOR -> {
                    val colors = withContext(Dispatchers.Default) {
                        OfflineColorDetector.namedDominantColors(centerCrop(bitmap, SIMPLE_COLOR_CROP_SIZE), k = 2)
                    }
                    val text = if (colors.isEmpty()) "Could not detect a color." else colors.joinToString(" and ")
                    // Same translation reasoning as SEGMENT/SEGMENT_COLOR above. Deduping on the
                    // raw (untranslated) text, same as before — cheap, and avoids re-translating
                    // identical English on every poll tick when nothing's actually changed.
                    if (continuousEnabled) speakIfChangedTranslated(lastSimpleColorText, text, prefs) { lastSimpleColorText = it }
                    else { speak(OfflineTranslator.translate(context, text, prefs)); lastSimpleColorText = text }
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
    private suspend fun summarizeInstances(context: Context, bitmap: Bitmap, withColors: Boolean): String {
        val instances = withContext(Dispatchers.Default) {
            OfflineSegmentationDetector.detect(context, bitmap)
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
     * runs the actual detection itself once it's on screen. [SegmentationExploreActivity] can also
     * capture further photos of its own once it's open (its "Capture new photo" list item), so this
     * only ever needs to run once per visit — and it's the only Explore left in the app, reached
     * both from here and from the on-device VLM catalog's "Explore and Describe" (the older,
     * VLM-driven grid-JSON Explore was removed 2026-09-01).
     */
    private fun launchSegmentationExplore() {
        if (isBusy) {
            announceBusy()
            return
        }
        val capture = capturePhoto
        isBusy = true
        ProcessingSoundPlayer.playCaptureAcknowledge(context)
        coroutineScope.launch {
            try {
                val bitmap = try {
                    // No tone starts here — SegmentationExploreActivity owns its own "waiting for
                    // the model" cue, started once the captured image is actually received there.
                    // Not a text task, so the usual 720p (highRes = false). correctOrientation =
                    // false — Explore sends the image to the model exactly as taken, see
                    // capturePhoto's own doc.
                    capture(MAX_CAPTURE_DIMENSION, false, true, false) {}
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
     *  see [ReadDocumentActivity]'s doc for what happens once it's open. [prefs] is only needed
     *  for the OCR-module readiness check below (see [OfflineTextRecognizer.isReady]) —
     *  [ReadDocumentActivity] re-reads [PreferencesManager.ocrLanguage] itself once it's open, so
     *  nothing else here needs it. */
    private fun launchReadDocument(prefs: PreferencesManager) {
        if (isBusy) {
            announceBusy()
            return
        }
        val capture = capturePhoto
        isBusy = true
        coroutineScope.launch {
            try {
                // Same "check before capturing" reasoning as Mode.TEXT in captureAndRun — a
                // not-ready OCR module shouldn't cost the user a capture for nothing.
                if (!OfflineTextRecognizer.isReady(context, prefs.ocrLanguage)) return@launch
                ProcessingSoundPlayer.playCaptureAcknowledge(context)
                val bitmap = try {
                    // No tone starts here — ReadDocumentActivity owns its own "waiting for the
                    // model" cue, started once the captured image is actually received there.
                    // A text task, so 1080p (highRes = true) — see MlkitButtonProvider's doc.
                    // correctOrientation = false — Read Document is Explore's text counterpart and
                    // sends the image to the model exactly as taken, same reasoning as
                    // launchSegmentationExplore above.
                    capture(MAX_CAPTURE_DIMENSION, true, true, false) {}
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

    /**
     * "Scan Barcode": opens [BarcodeScanActivity], which does the whole job itself — it keeps a
     * live camera stream running until a barcode turns up, rather than taking a photo per attempt
     * (see that class's doc for why). So on a live-camera host this button is a plain launch with
     * nothing to capture first, unlike [launchSegmentationExplore]/[launchReadDocument] which hand
     * their screens an already-taken photo.
     *
     * A file-based host (Media Describer) has no live feed to stream, so a continuous scan is
     * impossible there — but the cell is not left dead either: it runs a one-shot detect against
     * the current file/frame here and opens the same result screen with the barcode already in
     * hand (the `BARCODE_*` extras), which is the whole feature minus the hunting-for-it part.
     * Nothing found is announced as such, without leaving this screen.
     */
    private fun launchBarcodeScan() {
        if (isBusy) {
            announceBusy()
            return
        }
        if (hasLiveCamera) {
            context.startActivity(Intent(context, BarcodeScanActivity::class.java))
            return
        }
        val capture = capturePhoto
        isBusy = true
        ProcessingSoundPlayer.playCaptureAcknowledge(context)
        coroutineScope.launch {
            try {
                if (!OfflineBarcodeScanner.isReady(context)) return@launch
                val bitmap = try {
                    capture(MAX_CAPTURE_DIMENSION, false, true, true) {}
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                    return@launch
                }
                val found = OfflineBarcodeScanner.pickBest(OfflineBarcodeScanner.scan(bitmap))
                if (found == null) {
                    AccessibilityUtils.announceToTalkBack(context, "No barcode found in this image.")
                    return@launch
                }
                context.startActivity(
                    Intent(context, BarcodeScanActivity::class.java)
                        .putExtra(BarcodeScanActivity.EXTRA_BARCODE_VALUE, found.value)
                        .putExtra(BarcodeScanActivity.EXTRA_BARCODE_FORMAT, found.format)
                        .putExtra(BarcodeScanActivity.EXTRA_BARCODE_IS_2D, found.isTwoDimensional)
                )
            } finally {
                isBusy = false
            }
        }
    }

    /** "Simple Color Detection"'s crop step: a plain, non-segmentation color reading — a fixed
     *  50x50-pixel square from the captured frame's exact center, named via
     *  [OfflineColorDetector.namedDominantColors] (the same Palette-based ranking "Identify Objects
     *  and Colors" uses, just over a plain rectangular center crop instead of a detected instance's
     *  own mask). No detector run at all, unlike every other color-aware button on this grid — this
     *  one is deliberately just "what color is the middle of the frame" — see [Mode.SIMPLE_COLOR]'s
     *  branch in [captureAndRun], which now owns the capture/busy/continuous handling this used to
     *  do on its own, the same as every other mode here. */
    private fun centerCrop(bitmap: Bitmap, size: Int): Bitmap {
        val w = size.coerceAtMost(bitmap.width)
        val h = size.coerceAtMost(bitmap.height)
        val left = (bitmap.width - w) / 2
        val top = (bitmap.height - h) / 2
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private fun selectSingleShot(mode: Mode, prefs: PreferencesManager) {
        activeMode = mode
        continuousEnabled = false
        lastRepeatableAction = { selectSingleShot(mode, prefs) }
        coroutineScope.launch { captureAndRun(mode, prefs) }
    }

    private fun selectContinuous(mode: Mode) {
        activeMode = mode
        continuousEnabled = true
        continuousFlashUsed = false
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

    override fun rows(ctx: ModelButtonContext): List<List<GridCell?>> {
        return listOf(
            listOf(
                GridCell(
                    label = "Detect Objects",
                    contentDescription = "Detect Objects. Names the objects in view, using a dedicated on-device detector.",
                    icon = GridIcon.DETECT_OBJECTS,
                    onClick = { selectSingleShot(Mode.SEGMENT, ctx.prefs) }
                ),
                GridCell(
                    label = "Identify Objects and Colors",
                    contentDescription = "Detect objects and each one's main colors.",
                    icon = GridIcon.IDENTIFY_OBJECTS_COLORS,
                    onClick = { selectSingleShot(Mode.SEGMENT_COLOR, ctx.prefs) }
                ),
                GridCell(
                    label = "Explore Photo by Touch",
                    contentDescription = "Explore by touch. Detects objects and their colors, then opens a full-screen view — move your finger over the picture to hear what's under it, or tap once for more options.",
                    icon = GridIcon.EXPLORE,
                    onClick = { launchSegmentationExplore() }
                ),
                GridCell(
                    label = "Read Text",
                    contentDescription = "Read text in view. Tap to analyze once, hold to keep watching continuously.",
                    icon = GridIcon.READ_TEXT,
                    onClick = { selectSingleShot(Mode.TEXT, ctx.prefs) },
                    onLongClick = { selectContinuous(Mode.TEXT) }
                )
            ),
            listOf(
                GridCell(
                    label = "Read Document",
                    contentDescription = "Read a document by touch. Opens a full-screen view — move your finger over a paragraph to hear it read aloud, or tap once for more options.",
                    icon = GridIcon.READ_DOCUMENT,
                    onClick = { launchReadDocument(ctx.prefs) }
                ),
                GridCell(
                    label = "Light Level Meter",
                    contentDescription = when {
                        !hasLiveCamera -> "Light level meter. Not available here — needs a live camera."
                        isLightActive -> "Light level meter is on. Double tap to stop."
                        else -> "Light level meter. Double tap to hear tones matching the brightness in front of the camera."
                    },
                    icon = GridIcon.LIGHT_METER,
                    enabled = hasLiveCamera,
                    onClick = { toggleLight() }
                ),
                GridCell(
                    label = "Simple Color Detection",
                    contentDescription = if (hasLiveCamera) {
                        "Simple color detection. Announces the two main colors at the center of the frame. Tap to analyze once, hold to keep watching continuously."
                    } else {
                        "Simple color detection. Not available here — needs a live camera."
                    },
                    icon = GridIcon.SIMPLE_COLOR,
                    enabled = hasLiveCamera,
                    onClick = { selectSingleShot(Mode.SIMPLE_COLOR, ctx.prefs) },
                    onLongClick = if (hasLiveCamera) { { selectContinuous(Mode.SIMPLE_COLOR) } } else null
                ),
                GridCell(
                    label = "Scan Barcode",
                    contentDescription = if (hasLiveCamera) {
                        "Scan barcode. Keeps looking until it finds a QR code or a product barcode, then " +
                            "reads out what it is and lets you save your own voice or text note about it."
                    } else {
                        "Scan barcode. Looks for a QR code or a product barcode in this image, then reads " +
                            "out what it is and lets you save your own voice or text note about it."
                    },
                    icon = GridIcon.SCAN_BARCODE,
                    onClick = { launchBarcodeScan() }
                )
            ),
            listOf(null, null, null, null)
        )
    }
}

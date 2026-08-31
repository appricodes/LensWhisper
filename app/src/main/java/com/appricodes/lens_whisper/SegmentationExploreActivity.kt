package com.appricodes.lens_whisper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.offline.DetectedInstance
import com.appricodes.lens_whisper.offline.DetectionPhrasing
import com.appricodes.lens_whisper.offline.OfflineColorDetector
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.offline.SegmentationIsolation
import com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController
import com.appricodes.lens_whisper.ui.analysis.rememberAnalysisSessionController
import com.appricodes.lens_whisper.ui.components.CameraController
import com.appricodes.lens_whisper.ui.components.ImmersiveBackButton
import com.appricodes.lens_whisper.ui.components.ImmersiveFullScreen
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.HoverFeedback
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.ShareUtils
import com.appricodes.lens_whisper.utils.VolumeShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val MAX_CAPTURE_DIMENSION = 2500

/**
 * The mask-based "Explore" screen — see [com.appricodes.lens_whisper.ui.models.MlkitButtonProvider],
 * the sole external caller. Shares its overall shape ("open on a captured photo, run inference,
 * then let the user explore the result") with the existing VLM-driven [ExploreActivity], but the
 * interaction itself is different: this one is grounded in [com.appricodes.lens_whisper.offline.OfflineSegmentationDetector]'s
 * exact per-pixel masks rather than a coarse VLM-described grid, so a finger genuinely has to be
 * *on* an object (not just inside its rough bounding area) to name it.
 *
 * Two ways to learn what's in the photo, both on [HoverExploreLayer] — hover works identically in
 * either of this Activity's two modes; only what a *tap* does differs:
 * - **Continuous hover**: move a finger over the photo to hear whatever is under it, with haptic +
 *   tone feedback on crossing into/out of an object (see [HoverFeedback]) so a blind user can feel
 *   an edge, not just hear about it after the fact. Every object also gets a visible outline (dark
 *   red normally, brightening while the finger is on it) — sighted-assistant/low-vision use, not
 *   needed for the audio path itself.
 * - **A single tap**, in the default mode reached from Basic Tasks' "Explore Photo by Touch",
 *   opens [ExploreMenu] — Color Report (a toggle, see [HoverExploreLayer]'s doc), Share Photo,
 *   Capture New Photo, then every detected object at once with a coarse spatial location
 *   (read-only — a fully TalkBack-navigable list needs no finger-precision at all, unlike a grid of
 *   small cells, but doesn't need to be actionable either).
 *
 * **Describe-mode** — reached only from the on-device VLM catalog's "Explore and Describe" (see
 * `OfflineVlmButtonProvider`), signaled by the `VLM_DESCRIBE_MODE` intent extra — replaces that tap
 * behavior entirely: a tap sends whatever object is currently under the finger, isolated onto a
 * white background at full sensor resolution (see [SegmentationIsolation], fed from the
 * `HIGH_RES_IMAGE_URI` extra this mode also requires), to the selected on-device model for a
 * detailed description, cancelling and replacing any still-in-flight analysis from a previous tap.
 * No [ExploreMenu] in this mode — see [analyzeTappedObject]'s doc for why "Capture New Photo" isn't
 * offered as a long-press instead.
 *
 * Full-screen ([ImmersiveFullScreen]): no status/nav bar, a small [ImmersiveBackButton] in the
 * bottom-right corner instead.
 */
class SegmentationExploreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageUri = intent?.getStringExtra("IMAGE_URI")?.let { Uri.parse(it) }
        val highResImageUri = intent?.getStringExtra("HIGH_RES_IMAGE_URI")?.let { Uri.parse(it) }
        val describeMode = intent?.getBooleanExtra("VLM_DESCRIBE_MODE", false) ?: false
        val prefs = PreferencesManager(this)
        val settings = SettingsRepository(this).loadSettingsFromAssets()

        setContent {
            LensWhisperTheme {
                SegmentationExploreScreen(
                    prefs = prefs,
                    settings = settings,
                    imageUri = imageUri,
                    highResImageUri = highResImageUri,
                    describeMode = describeMode,
                    onClose = { finish() }
                )
            }
        }
    }
}

/** Runs detection + per-instance color naming + confidence phrasing on [loadedBitmap], with the
 *  "waiting for the model" tone looping for the duration — shared by the initial load and by
 *  "Capture new photo" so the two paths can't drift apart. */
private suspend fun detectAndDescribe(
    context: Context,
    loadedBitmap: Bitmap,
    // Non-null when the caller (captureNewPhoto) already started the waiting tone itself, from
    // CameraController's onShutter callback, the instant the shutter fired — this function then
    // just adopts and eventually cancels that job instead of starting a second, later one. Null
    // for the initial-load path, where there's no shutter event and detectAndDescribe starting
    // its own tone right away already means "exactly when the image is received".
    externalToneJob: Job? = null
): Pair<List<DetectedInstance>, Map<DetectedInstance, String>> = coroutineScopeWithWaitingTone(context, externalToneJob) {
    val detected = withContext(Dispatchers.Default) {
        OfflineSegmentationDetector.detect(context, loadedBitmap)
    }
    val described = withContext(Dispatchers.Default) {
        detected.associateWith { instance ->
            val colors = instance.namedDominantColors(loadedBitmap)
            DetectionPhrasing.describeDetection(instance.confidence, instance.label, colors.joinToString(" and "))
        }
    }
    detected to described
}

private suspend fun <T> coroutineScopeWithWaitingTone(context: Context, externalToneJob: Job?, block: suspend CoroutineScope.() -> T): T =
    coroutineScope {
        val toneJob = externalToneJob ?: launch(Dispatchers.IO) {
            while (true) {
                ProcessingSoundPlayer.playWaitingTone(context)
                delay(1100)
            }
        }
        try {
            block()
        } finally {
            toneJob.cancel()
        }
    }

@Composable
private fun SegmentationExploreScreen(
    prefs: PreferencesManager,
    settings: AppSettings,
    imageUri: Uri?,
    highResImageUri: Uri?,
    describeMode: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraController = remember { CameraController(context) }
    // Only actually used in describeMode (see analyzeTappedObject) — created unconditionally
    // anyway, same as every other Compose remember in this function, rather than only sometimes
    // calling it depending on a runtime value.
    val controller = rememberAnalysisSessionController(context, prefs)

    var isLoading by remember { mutableStateOf(true) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // The current photo's own cache file, for "Share photo" — the initial one is already a cache
    // file (see MlkitButtonProvider.launchSegmentationExplore's Uri.fromFile), and "Capture new
    // photo" below writes a fresh one the same way.
    var currentPhotoFile by remember { mutableStateOf(imageUri?.path?.let { File(it) }) }
    // describeMode's high-res source file for SegmentationIsolation — see CameraActivity's
    // captureMaxResPair/DualResCapture doc for why this is a file, not a held bitmap. Never
    // replaced after launch: describeMode offers no "Capture New Photo" (see analyzeTappedObject's
    // doc), so there is no second high-res file to switch to mid-session.
    val highResFile = remember(highResImageUri) { highResImageUri?.path?.let { File(it) } }
    var instances by remember { mutableStateOf<List<DetectedInstance>>(emptyList()) }
    // Each instance's full spoken sentence, built via DetectionPhrasing.describeDetection and
    // computed once right after detection rather than on every hover/touch — Palette color
    // extraction isn't free, and the hover path in particular needs to stay cheap enough to run on
    // every pointer-move event.
    var descriptions by remember { mutableStateOf<Map<DetectedInstance, String>>(emptyMap()) }
    var showPopup by remember { mutableStateOf(false) }
    var colorReportEnabled by remember { mutableStateOf(false) }
    // describeMode's tap-serialization state — see startDescribe's doc for why this queues rather
    // The coroutine currently running one describeMode tap's isolate+analyze pipeline, if any —
    // cancelled and replaced the moment a new tap names a different object. activeDescribeInstance
    // is which object that is, so a repeat tap on the same one (still active) is a no-op instead of
    // restarting it. See analyzeTappedObject.
    var activeDescribeJob by remember { mutableStateOf<Job?>(null) }
    var activeDescribeInstance by remember { mutableStateOf<DetectedInstance?>(null) }

    BackHandler { onClose() }
    DisposableEffect(cameraController) { onDispose { cameraController.shutdown() } }
    ImmersiveFullScreen()

    /**
     * describeMode's tap handler: isolates [hit] from the full-resolution original (see
     * [SegmentationIsolation]) and asks the selected on-device model to describe it, cancelling
     * whatever the *previous* tap started first — a deliberate replace, not a busy-block, which is
     * why this calls [AnalysisSessionController.runAnalysisOnBitmap] directly rather than going
     * through [AnalysisSessionController.runGuarded]. Tapping the object already active is a no-op.
     *
     * **Waits for the previous job to fully finish unwinding ([Job.cancelAndJoin]) before starting
     * the new one — plain `cancel()` alone is not safe here**, and neither is `cancelAndJoin` on
     * its own: confirmed the hard way on a real device, twice, with an identical native SIGSEGV
     * inside the vendored LiteRT-LM/GPU-delegate library. The actual cause turned out to be one
     * step further down than the Kotlin cancellation itself — see
     * [com.appricodes.lens_whisper.inference.OfflineInferenceEngine.analyzeStreaming]'s own
     * `CancellationException` handler, which now calls `Conversation.cancelProcess()` before its
     * `use{}` block's implicit `close()` runs, exactly because closing a `Conversation` while it's
     * still generating (rather than properly halting it first) is what actually crashed. With that
     * fixed at the source, `cancelAndJoin()` here is correct again: it just needs to guarantee the
     * previous job's cancellation has *fully* unwound (cancelProcess + close both done) before this
     * function's own new `Conversation` opens, which is exactly what it does.
     *
     * The waiting tone is deliberately started/stopped as a *child* of this same job
     * ([describeScope], mirroring [captureNewPhoto]'s own `captureScope` pattern below) rather than
     * via [AnalysisSessionController.startProcessingChime]/`stopProcessingChime` — that pair lives
     * on the controller's own field, so cancelling one tap's job and immediately starting the next
     * one's `startProcessingChime()` call can reassign that field to the *new* tone before the
     * cancelled job's `finally` gets a chance to stop what it thinks is still "its" tone (cancellation
     * only requests a stop; it doesn't wait for one) — silently killing the new tap's tone instead
     * of the old one's. A tone job that dies with its own parent job can't have that race.
     *
     * No "Capture New Photo" affordance in this mode: the natural place to add one would be a
     * long-press, but this layer's whole interaction model is *resting/moving a finger on an
     * object* — a long-press and a deliberate dwell are the same physical gesture here, so the two
     * would conflict. Backing out and pressing "Explore and Describe" again re-captures instead.
     */
    fun analyzeTappedObject(hit: DetectedInstance) {
        if (hit == activeDescribeInstance) return
        val loadedBitmap = bitmap
        val file = highResFile
        if (loadedBitmap == null || file == null) {
            AccessibilityUtils.announceToTalkBack(context, "Could not analyze that object.")
            return
        }
        val previousJob = activeDescribeJob
        activeDescribeInstance = hit
        activeDescribeJob = coroutineScope.launch {
            // See this function's doc — must fully finish before a new native inference call
            // starts, not just be asked to stop.
            previousJob?.cancelAndJoin()
            val describeScope = this
            val toneJob = describeScope.launch(Dispatchers.IO) {
                while (true) {
                    ProcessingSoundPlayer.playWaitingTone(context)
                    delay(1100)
                }
            }
            try {
                val isolated = withContext(Dispatchers.Default) { SegmentationIsolation.isolate(loadedBitmap, hit, file) }
                if (isolated == null) {
                    AccessibilityUtils.announceToTalkBack(context, "Could not isolate that object.")
                    return@launch
                }
                val config = settings.prompts.find { it.id == "detailed_scene" } ?: return@launch
                controller.runAnalysisOnBitmap(config, isolated)
            } finally {
                toneJob.cancel()
                if (activeDescribeInstance == hit) activeDescribeInstance = null
            }
        }
    }

    suspend fun runDetection(loadedBitmap: Bitmap, externalToneJob: Job? = null) {
        isLoading = true
        bitmap = loadedBitmap
        AccessibilityUtils.announceToTalkBack(context, "Analyzing objects.")
        val (detected, described) = detectAndDescribe(context, loadedBitmap, externalToneJob)
        isLoading = false
        if (detected.isEmpty()) {
            instances = emptyList()
            descriptions = emptyMap()
            AccessibilityUtils.announceToTalkBack(context, "No objects detected.")
        } else {
            instances = detected
            descriptions = described
            ProcessingSoundPlayer.playScreenOpened(context)
            val hint = if (describeMode) {
                "Touch the screen to hear what's there. Tap an object for a detailed AI description."
            } else {
                "Touch the screen to hear what's there. Tap anywhere for more options."
            }
            AccessibilityUtils.announceToTalkBack(context, hint)
        }
    }

    fun captureNewPhoto() {
        showPopup = false
        // Hides the (now stale) hover layer immediately, rather than leaving it live and
        // interactive against the old photo for the moment the new capture takes.
        isLoading = true
        ProcessingSoundPlayer.playCaptureAcknowledge(context)
        coroutineScope.launch {
            // toneJob is launched as a child of THIS coroutine (captureScope), not of the outer
            // remembered coroutineScope — so if this coroutine is cancelled, or throws (including
            // from the file-write step below, which has no try of its own), toneJob is torn down
            // with it automatically, rather than needing every exit path to remember to cancel it.
            val captureScope = this
            var toneJob: Job? = null
            val newBitmap = try {
                cameraController.withCaptureSession(lifecycleOwner, prefs.flashEnabled) {
                    cameraController.capturePhoto(MAX_CAPTURE_DIMENSION) {
                        // Fires the instant the shutter sound plays (see
                        // CameraController.capturePhotoInternal), not after readout/decode/scale —
                        // runDetection adopts this job instead of starting its own, later one.
                        toneJob = captureScope.launch(Dispatchers.IO) {
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
                toneJob?.cancel()
                AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                isLoading = false
                return@launch
            }
            currentPhotoFile = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "explore_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { newBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                file
            }
            runDetection(newBitmap, toneJob)
        }
    }

    LaunchedEffect(imageUri) {
        if (imageUri == null) {
            AccessibilityUtils.announceToTalkBack(context, "No image to explore.")
            onClose()
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        if (loaded == null) {
            AccessibilityUtils.announceToTalkBack(context, "Could not load the photo.")
            onClose()
            return@LaunchedEffect
        }
        runDetection(loaded)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                // Fills the screen exactly, distorting the aspect ratio if needed, rather than
                // letterboxing — the whole photo stays touchable/visible edge to edge, which
                // matters more here than preserving its proportions.
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }

        val loadedBitmap = bitmap
        if (!isLoading && loadedBitmap != null) {
            HoverExploreLayer(
                sourceBitmap = loadedBitmap,
                instances = instances,
                descriptions = descriptions,
                colorReportEnabled = colorReportEnabled,
                describeMode = describeMode,
                onTap = { hit ->
                    if (describeMode) {
                        if (hit != null) analyzeTappedObject(hit)
                        else AccessibilityUtils.announceToTalkBack(context, "No object under your finger.")
                    } else {
                        showPopup = true
                    }
                }
            )
        }

        if (showPopup) {
            ExploreMenu(
                instances = instances,
                descriptions = descriptions,
                colorReportEnabled = colorReportEnabled,
                onColorReportToggled = { colorReportEnabled = it },
                onSharePhoto = {
                    showPopup = false
                    currentPhotoFile?.let { ShareUtils.shareImage(context, it) }
                },
                onCaptureNewPhoto = { captureNewPhoto() },
                onDismiss = { showPopup = false }
            )
        }

        // describeMode's per-tap analysis (analyzeTappedObject -> runAnalysisOnBitmap) can surface
        // the thermal warning — without these two calls that state would flip with nothing ever
        // rendering it, since this screen never otherwise touches AnalysisSessionController's own
        // dialogs (unlike CameraActivity/MediaDescriberScreen, which already call both).
        controller.ObserveDownloads()
        controller.ResultDialogs(onOpenModelManager = { context.startActivity(Intent(context, ModelManagementActivity::class.java)) })

        ImmersiveBackButton(onClick = onClose)
    }
}

/** Coarse 3x3-zone location for [instance]'s box center — "center, top, bottom, left, right, upper
 *  left, upper right, lower left, lower right" — used only by [ExploreMenu]'s list, where a spoken
 *  location stands in for the finger-precision [HoverExploreLayer] normally relies on. */
private fun locationDescriptor(instance: DetectedInstance): String {
    val cx = (instance.boxLeft + instance.boxRight) / 2f
    val cy = (instance.boxTop + instance.boxBottom) / 2f
    val vertical = when { cy < 0.33f -> "upper"; cy > 0.67f -> "lower"; else -> "" }
    val horizontal = when { cx < 0.33f -> "left"; cx > 0.67f -> "right"; else -> "" }
    return when {
        vertical.isEmpty() && horizontal.isEmpty() -> "center"
        vertical.isEmpty() -> horizontal
        horizontal.isEmpty() -> if (vertical == "upper") "top" else "bottom"
        else -> "$vertical $horizontal"
    }
}

/**
 * The tap-to-open menu — no title (the four items are self-explanatory without one). "Color
 * Report" is a toggle, not a one-shot action; the object list below it is read-only (browsable via
 * TalkBack, does nothing on selection) — it exists to let a user hear everything at once, not to
 * re-trigger anything.
 */
@Composable
private fun ExploreMenu(
    instances: List<DetectedInstance>,
    descriptions: Map<DetectedInstance, String>,
    colorReportEnabled: Boolean,
    onColorReportToggled: (Boolean) -> Unit,
    onSharePhoto: () -> Unit,
    onCaptureNewPhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.8f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onColorReportToggled(!colorReportEnabled) }
                        .padding(vertical = 14.dp)
                        .clearAndSetSemantics {
                            val state = if (colorReportEnabled) "on" else "off"
                            contentDescription = "Color Report is $state. Double tap to toggle. When on, hovering also announces the colors under your finger."
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Color Report", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    Checkbox(checked = colorReportEnabled, onCheckedChange = onColorReportToggled)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSharePhoto() }
                        .padding(vertical = 14.dp)
                        .clearAndSetSemantics { contentDescription = "Share photo. Double tap to share this photo." }
                ) {
                    Text(text = "Share Photo", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCaptureNewPhoto() }
                        .padding(vertical = 14.dp)
                        .clearAndSetSemantics { contentDescription = "Capture new photo. Double tap to take a new photo and re-analyze it." }
                ) {
                    Text(text = "Capture New Photo", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (instances.isEmpty()) {
                        item {
                            Text(
                                text = "No objects detected.",
                                fontSize = 16.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 14.dp)
                            )
                        }
                    }
                    items(instances) { instance ->
                        val text = "${descriptions[instance] ?: instance.label}, ${locationDescriptor(instance)}"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                                .clearAndSetSemantics { contentDescription = text }
                        ) {
                            Text(text = text, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** A small crop of [bitmap] centered on normalized point ([nx],[ny]), sized to [bitmap]'s own
 *  width / 50 on each side — "1/50 of the screen width" translates directly to this once the photo
 *  fills the screen edge to edge (see [ContentScale.FillBounds] above): screen-space and bitmap-space
 *  sizes are then just a constant scale factor apart, so the ratio is the same in either space. */
private fun cropAroundPoint(bitmap: Bitmap, nx: Float, ny: Float): Bitmap {
    val boxSize = (bitmap.width / 50f).toInt().coerceAtLeast(2)
    val cx = (nx * bitmap.width).toInt()
    val cy = (ny * bitmap.height).toInt()
    val left = (cx - boxSize / 2).coerceIn(0, bitmap.width - 1)
    val top = (cy - boxSize / 2).coerceIn(0, bitmap.height - 1)
    val right = (left + boxSize).coerceIn(left + 1, bitmap.width)
    val bottom = (top + boxSize).coerceIn(top + 1, bitmap.height)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

private val OUTLINE_COLOR_NORMAL = Color(0xFF8B0000) // dark red
private val OUTLINE_COLOR_ACTIVE = Color(0xFFFF4444) // bright red — the object currently under the finger

/**
 * The finger-hover exploration layer: draws every detected object's outline (bounding box —
 * precise enough to see, far simpler than tracing the mask's actual contour; hit-testing below
 * still uses the exact per-pixel mask, unaffected by this simplification) and handles hover/tap.
 * Deliberately built on raw [androidx.compose.ui.input.pointer.pointerInput] rather than a
 * `clickable`/semantics-node-per-region approach, because the region under the finger changes
 * continuously along an irregular mask boundary — there is no fixed set of nodes to pre-declare
 * the way a grid would have.
 *
 * [PointerEventType.Enter]/[PointerEventType.Move] are what TalkBack's touch-exploration delivers
 * for a moving finger while its "explore by touch" is active — the same channel a real stylus/mouse
 * hover uses, and the same one a plain touch-drag uses when TalkBack is off — so one handler covers
 * a screen-reader user, a sighted user without TalkBack, and a mouse/stylus tester alike.
 *
 * Announces via [AccessibilityUtils.announceToTalkBack] only on a genuine change: entering a new
 * (or different) object always interrupts and speaks the new name; leaving to empty space never
 * interrupts anything currently being spoken — it's just [ProcessingSoundPlayer.playEmptyAreaTone],
 * a plain sound effect, not TTS — so a name that was still being read out when the finger happened
 * to leave keeps playing to completion. Every boundary crossing also gets [HoverFeedback]'s short
 * hard vibration and a distinct tone per direction; staying inside the same object gets a soft,
 * throttled vibration on every subsequent move.
 *
 * [colorReportEnabled] adds, on top of all of the above and without changing any of it: while the
 * finger is inside an object, the 2 dominant colors of a small box around the exact finger position
 * (see [cropAroundPoint]) are announced alongside the object's name, re-announced (interrupting,
 * same as an object change) whenever that local color reading itself changes — even without
 * leaving the object, e.g. sliding from a red part of a shirt to a blue part of the same shirt.
 *
 * [onTap] fires for a real quick tap-without-drag (Press followed by Release with little movement
 * in between) — the raw-touch path for a sighted/mouse/stylus user. A TalkBack user reaches the
 * same thing through the ordinary double-tap-to-activate gesture, wired via `semantics { onClick }`
 * below — TalkBack consumes that gesture at the accessibility-service level before it ever becomes
 * a raw Press/Release pair here, so the two paths never double-fire for the same tap. Receives
 * whatever object was under the finger at that exact instant ([currentHitInstance] — null over
 * empty space) rather than the caller having to track hover state of its own: the default mode
 * ignores it and opens the options menu regardless, but describeMode (see
 * [SegmentationExploreActivity]'s doc) needs to know exactly which object to analyze.
 */
@Composable
private fun HoverExploreLayer(
    sourceBitmap: Bitmap,
    instances: List<DetectedInstance>,
    descriptions: Map<DetectedInstance, String>,
    colorReportEnabled: Boolean,
    describeMode: Boolean,
    onTap: (DetectedInstance?) -> Unit
) {
    val context = LocalContext.current
    var currentHitInstance by remember { mutableStateOf<DetectedInstance?>(null) }
    // The last object the finger was actually ON, for as long as this photo stays on screen —
    // updated only when a real object is found (hit != null), and deliberately never cleared by
    // anything else: not by moving off it to empty background, and not by lifting the finger
    // (TalkBack's "explore by touch" sends a hover Exit the instant that happens, right before the
    // double-tap gesture that's supposed to activate whatever was under it). If the tap handler
    // read currentHitInstance directly, either of those would already have reset it to null, so a
    // double-tap after finding an object and then moving away from it (on purpose or not) would
    // always land on "no object" instead of describing the one the user found. This field is what
    // onTap actually reads; it only stays null for a session where nothing has been touched yet.
    // Reset for free on each new photo: HoverExploreLayer leaves composition while isLoading is
    // true (see SegmentationExploreScreen), so this `remember` starts fresh every time.
    var lastTouchedInstance by remember { mutableStateOf<DetectedInstance?>(null) }
    var lastColorKey by remember { mutableStateOf<String?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmapWidth = sourceBitmap.width
    val bitmapHeight = sourceBitmap.height

    fun speak(text: String) {
        VolumeShortcuts.interruptTalkBackSpeech(context)
        AccessibilityUtils.announceToTalkBack(context, text)
    }

    fun baseTextFor(hit: DetectedInstance): String = descriptions[hit] ?: hit.label

    /** Color Report's own axis, independent of object detection: the 2 dominant colors of a small
     *  box around the exact finger point ([cropAroundPoint]), announced whenever that *local*
     *  reading changes — on top of/alongside whatever the object-hit logic in [updateHit] is doing,
     *  not instead of it, and not gated on [currentHitInstance] being non-null. Runs over detected
     *  objects (folding the object's own name in) exactly as much as over plain background (colors
     *  alone) — the point of this mode is a color reading anywhere on the photo, not just inside
     *  what the detector happened to recognize. */
    fun handleColorReport(position: Offset) {
        val crop = cropAroundPoint(sourceBitmap, position.x, position.y)
        val colorNames = OfflineColorDetector.namedDominantColors(crop, k = 2)
        val colorKey = colorNames.joinToString(" and ")
        if (colorKey.isEmpty() || colorKey == lastColorKey) return
        lastColorKey = colorKey
        val hit = currentHitInstance
        val text = if (hit != null) "${DetectionPhrasing.describeDetection(hit.confidence, hit.label)}, $colorKey" else colorKey
        speak(text)
    }

    /** [position] is the finger's normalized (0..1) location, or `null` for a true Exit (the
     *  finger left the touchable area entirely) — there's no valid position to sample color from
     *  in that case, so Color Report simply doesn't fire for that one event, unlike every other
     *  event where a position is always available. */
    fun updateHit(hit: DetectedInstance?, position: Offset?) {
        // Only a genuine object commits — moving off it to empty background, or lifting the finger
        // entirely, must not forget it. See lastTouchedInstance's own doc.
        if (hit != null) lastTouchedInstance = hit
        if (hit !== currentHitInstance) {
            if (currentHitInstance != null) {
                HoverFeedback.onBoundaryCrossed(context)
                HoverFeedback.playExitTone(context)
            }
            currentHitInstance = hit
            if (hit != null) {
                HoverFeedback.onBoundaryCrossed(context)
                HoverFeedback.playEnterTone(context)
                if (!colorReportEnabled) speak(baseTextFor(hit))
            } else {
                // Left to empty space — an ambient cue only, never an interrupting announcement,
                // so whatever name was still being read out keeps playing to completion.
                ProcessingSoundPlayer.playEmptyAreaTone(context)
            }
            // Forces the very next handleColorReport call below to announce unconditionally, even
            // if the local color at this new position happens to coincidentally match whatever was
            // last announced — a boundary crossing is itself worth a fresh announcement.
            lastColorKey = null
        } else if (hit != null) {
            HoverFeedback.onInsideObject(context)
        }
        if (colorReportEnabled && position != null) handleColorReport(position)
    }

    // The mask's exact boundary is expensive-ish to trace (a full pass over each instance's
    // 160x160 proto grid) but never changes for a given instance, so it's computed once per
    // detection (keyed on `instances`, which only ever changes when a new photo replaces the old
    // one) rather than on every recomposition/hover-move.
    val maskOutlines = remember(instances) { instances.associateWith { it.maskBoundaryEdgesNormalized() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .semantics {
                contentDescription = if (describeMode) {
                    "Photo. Move your finger over it to hear what's there, or double tap an object for a detailed AI description."
                } else {
                    "Photo. Move your finger over it to hear what's there, or double tap for more options."
                }
                onClick { onTap(lastTouchedInstance); true }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (instance in instances) {
                val isActive = instance === currentHitInstance
                val edges = maskOutlines[instance] ?: continue
                val path = Path()
                for ((start, end) in edges) {
                    path.moveTo(start.first * size.width, start.second * size.height)
                    path.lineTo(end.first * size.width, end.second * size.height)
                }
                drawPath(
                    path = path,
                    color = if (isActive) OUTLINE_COLOR_ACTIVE else OUTLINE_COLOR_NORMAL,
                    style = Stroke(width = if (isActive) 6f else 3f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(instances, descriptions, colorReportEnabled, containerSize) {
                    if (containerSize.width == 0 || containerSize.height == 0) return@pointerInput

                    var pressPosition: Offset? = null
                    var pressTimeMs = 0L

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    pressPosition = event.changes.first().position
                                    pressTimeMs = System.currentTimeMillis()
                                }
                                PointerEventType.Release -> {
                                    val downPos = pressPosition
                                    if (downPos != null) {
                                        val releasePos = event.changes.first().position
                                        val moved = (releasePos - downPos).getDistance()
                                        val elapsed = System.currentTimeMillis() - pressTimeMs
                                        if (moved < 24f && elapsed < 400) onTap(lastTouchedInstance)
                                    }
                                    pressPosition = null
                                }
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    // The image fills this Box exactly (ContentScale.FillBounds) —
                                    // no letterbox offset/scale to undo, just a direct fraction.
                                    val rawPosition = event.changes.first().position
                                    val nx = rawPosition.x / containerSize.width
                                    val ny = rawPosition.y / containerSize.height
                                    val outOfBounds = nx < 0f || nx > 1f || ny < 0f || ny > 1f
                                    val hit = if (outOfBounds) null else instances.firstOrNull { it.containsNormalizedPoint(nx, ny) }
                                    updateHit(hit, if (outOfBounds) null else Offset(nx, ny))
                                }
                                PointerEventType.Exit -> updateHit(null, null)
                                else -> {}
                            }
                        }
                    }
                }
        )
    }
}

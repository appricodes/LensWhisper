package com.appricodes.lens_whisper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.appricodes.lens_whisper.offline.DetectedInstance
import com.appricodes.lens_whisper.offline.DetectionPhrasing
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.ui.components.CameraController
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.HoverFeedback
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.VolumeShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_CAPTURE_DIMENSION = 2500

/**
 * The mask-based "Explore" screen — see [com.appricodes.lens_whisper.ui.models.MlkitButtonProvider],
 * the sole external caller. Shares its overall shape ("open on a captured photo, run inference,
 * then let the user explore the result") with the existing VLM-driven [ExploreActivity], but the
 * interaction itself is different: this one is grounded in [OfflineSegmentationDetector]'s exact
 * per-pixel masks rather than a coarse VLM-described grid, so a finger genuinely has to be *on* an
 * object (not just inside its rough bounding area) to name it.
 *
 * Two ways to learn what's in the photo, both on [HoverExploreLayer]:
 * - **Continuous hover**: move a finger over the photo to hear whatever is under it, with haptic +
 *   tone feedback on crossing into/out of an object (see [HoverFeedback]) so a blind user can feel
 *   an edge, not just hear about it after the fact.
 * - **A single tap** opens [DetectedItemsPopup] — every detected object at once, each with a coarse
 *   spatial location, plus "Capture new photo" as the first entry so a bad shot can be retaken
 *   without leaving this screen. This replaces the former 6x6 grid variant ("Explore 2"): a
 *   TalkBack-navigable list needs no finger-precision at all, unlike a grid of small cells.
 */
class SegmentationExploreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageUri = intent?.getStringExtra("IMAGE_URI")?.let { Uri.parse(it) }
        val prefs = PreferencesManager(this)

        setContent {
            LensWhisperTheme {
                SegmentationExploreScreen(prefs = prefs, imageUri = imageUri, onClose = { finish() })
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
    prefs: PreferencesManager
): Pair<List<DetectedInstance>, Map<DetectedInstance, String>> = coroutineScopeWithWaitingTone(context) {
    val detected = withContext(Dispatchers.Default) { OfflineSegmentationDetector.detect(context, loadedBitmap, prefs) }
    val described = withContext(Dispatchers.Default) {
        detected.associateWith { instance ->
            val colors = instance.namedDominantColors(loadedBitmap)
            DetectionPhrasing.describeDetection(instance.confidence, instance.label, colors.joinToString(" and "))
        }
    }
    detected to described
}

private suspend fun <T> coroutineScopeWithWaitingTone(context: Context, block: suspend CoroutineScope.() -> T): T =
    coroutineScope {
        val toneJob = launch(Dispatchers.IO) {
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
private fun SegmentationExploreScreen(prefs: PreferencesManager, imageUri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraController = remember { CameraController(context) }

    var isLoading by remember { mutableStateOf(true) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var instances by remember { mutableStateOf<List<DetectedInstance>>(emptyList()) }
    // Each instance's full spoken sentence, built via DetectionPhrasing.describeDetection and
    // computed once right after detection rather than on every hover/touch — Palette color
    // extraction isn't free, and the hover path in particular needs to stay cheap enough to run on
    // every pointer-move event.
    var descriptions by remember { mutableStateOf<Map<DetectedInstance, String>>(emptyMap()) }
    var showPopup by remember { mutableStateOf(false) }

    BackHandler { onClose() }
    DisposableEffect(cameraController) { onDispose { cameraController.shutdown() } }

    suspend fun runDetection(loadedBitmap: Bitmap) {
        isLoading = true
        bitmap = loadedBitmap
        AccessibilityUtils.announceToTalkBack(context, "Analyzing objects.")
        val (detected, described) = detectAndDescribe(context, loadedBitmap, prefs)
        isLoading = false
        if (detected.isEmpty()) {
            instances = emptyList()
            descriptions = emptyMap()
            AccessibilityUtils.announceToTalkBack(context, "No objects detected.")
        } else {
            instances = detected
            descriptions = described
            AccessibilityUtils.announceToTalkBack(context, "Ready. Move your finger over the photo to hear what's there, or tap once for a list.")
        }
    }

    fun captureNewPhoto() {
        showPopup = false
        // Hides the (now stale) hover layer immediately, rather than leaving it live and
        // interactive against the old photo for the moment the new capture takes.
        isLoading = true
        ProcessingSoundPlayer.playCaptureAcknowledge(context)
        coroutineScope.launch {
            val newBitmap = try {
                cameraController.withCaptureSession(lifecycleOwner, prefs.flashEnabled) {
                    cameraController.capturePhoto(MAX_CAPTURE_DIMENSION)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                isLoading = false
                return@launch
            }
            runDetection(newBitmap)
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
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        val loadedBitmap = bitmap
        if (!isLoading && loadedBitmap != null) {
            HoverExploreLayer(
                bitmapSize = loadedBitmap.width to loadedBitmap.height,
                instances = instances,
                descriptions = descriptions,
                onTap = { showPopup = true }
            )
        }

        if (showPopup) {
            DetectedItemsPopup(
                instances = instances,
                descriptions = descriptions,
                onCaptureNewPhoto = { captureNewPhoto() },
                onItemSelected = { text ->
                    showPopup = false
                    AccessibilityUtils.announceToTalkBack(context, text)
                },
                onDismiss = { showPopup = false }
            )
        }
    }
}

/** Coarse 3x3-zone location for [instance]'s box center — "center, top, bottom, left, right, upper
 *  left, upper right, lower left, lower right" — used only by [DetectedItemsPopup]'s list, where a
 *  spoken location stands in for the finger-precision [HoverExploreLayer] normally relies on. */
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
 * The list that replaces the old 6x6 grid: "Capture new photo" first, then every detected
 * instance as "<description>, <location>" — a fully TalkBack-navigable list needs no finger
 * precision at all, unlike touching a specific grid cell or mask edge.
 */
@Composable
private fun DetectedItemsPopup(
    instances: List<DetectedInstance>,
    descriptions: Map<DetectedInstance, String>,
    onCaptureNewPhoto: () -> Unit,
    onItemSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.8f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(text = "Detected Objects", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCaptureNewPhoto() }
                                .padding(vertical = 14.dp)
                                .clearAndSetSemantics {
                                    contentDescription = "Capture new photo. Double tap to take a new photo and re-analyze it."
                                }
                        ) {
                            Text(text = "Capture new photo", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
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
                                .clickable { onItemSelected(text) }
                                .padding(vertical = 14.dp)
                                .clearAndSetSemantics { contentDescription = "$text. Double tap to hear again." }
                        ) {
                            Text(text = text, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The finger-hover exploration layer. Deliberately built on raw
 * [androidx.compose.ui.input.pointer.pointerInput] rather than a `clickable`/semantics-node-per-
 * region approach, because the region under the finger changes continuously along an irregular
 * mask boundary — there is no fixed set of nodes to pre-declare the way a grid would have.
 *
 * [PointerEventType.Enter]/[PointerEventType.Move] are what TalkBack's touch-exploration delivers
 * for a moving finger while its "explore by touch" is active — the same channel a real stylus/mouse
 * hover uses, and the same one a plain touch-drag uses when TalkBack is off — so one handler covers
 * a screen-reader user, a sighted user without TalkBack, and a mouse/stylus tester alike.
 *
 * Announces via [AccessibilityUtils.announceToTalkBack], only when the hovered instance actually
 * changes — the same dedupe/interrupt pattern
 * [com.appricodes.lens_whisper.ui.models.MlkitButtonProvider]'s continuous modes already use, kept
 * consistent rather than inventing a second announcement style for this one screen. Every boundary
 * crossing (entering/leaving an object, including moving directly from one object to another) also
 * gets [HoverFeedback]'s short hard vibration and a distinct tone per direction; staying inside the
 * same object gets a soft, throttled vibration on every subsequent move.
 *
 * [onTap] fires for a real quick tap-without-drag (Press followed by Release with little movement
 * in between) — the raw-touch path for a sighted/mouse/stylus user. A TalkBack user reaches the
 * same thing through the ordinary double-tap-to-activate gesture, wired via `semantics { onClick }`
 * below — TalkBack consumes that gesture at the accessibility-service level before it ever becomes
 * a raw Press/Release pair here, so the two paths never double-fire for the same tap.
 */
@Composable
private fun HoverExploreLayer(
    bitmapSize: Pair<Int, Int>,
    instances: List<DetectedInstance>,
    descriptions: Map<DetectedInstance, String>,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var lastAnnounced by remember { mutableStateOf<String?>(null) }
    var currentHitInstance by remember { mutableStateOf<DetectedInstance?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val (bitmapWidth, bitmapHeight) = bitmapSize

    fun announce(text: String) {
        if (text == lastAnnounced) return
        lastAnnounced = text
        VolumeShortcuts.interruptTalkBackSpeech(context)
        AccessibilityUtils.announceToTalkBack(context, text)
    }

    fun updateHit(hit: DetectedInstance?) {
        if (hit !== currentHitInstance) {
            if (currentHitInstance != null) {
                HoverFeedback.onBoundaryCrossed(context)
                HoverFeedback.playExitTone(context)
            }
            if (hit != null) {
                HoverFeedback.onBoundaryCrossed(context)
                HoverFeedback.playEnterTone(context)
            }
            currentHitInstance = hit
        } else if (hit != null) {
            HoverFeedback.onInsideObject(context)
        }
        announce(hit?.let { descriptions[it] ?: it.label } ?: "Nothing here.")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .semantics {
                contentDescription = "Photo. Move your finger over it to hear what's there, or double tap for a list of everything found."
                onClick { onTap(); true }
            }
            .pointerInput(instances, descriptions, containerSize) {
                if (containerSize.width == 0 || containerSize.height == 0 || bitmapWidth == 0 || bitmapHeight == 0) return@pointerInput

                // Mirrors ContentScale.Fit's own centering math for the Image above — the photo
                // is letterboxed inside this full-screen Box exactly the way ContentScale.Fit
                // renders it, so hit-testing has to undo the same fit before it can ask a
                // DetectedInstance (which thinks in the *photo's* own normalized coordinates)
                // whether a point falls inside it.
                val fitScale = minOf(
                    containerSize.width.toFloat() / bitmapWidth,
                    containerSize.height.toFloat() / bitmapHeight
                )
                val renderedWidth = bitmapWidth * fitScale
                val renderedHeight = bitmapHeight * fitScale
                val offsetX = (containerSize.width - renderedWidth) / 2f
                val offsetY = (containerSize.height - renderedHeight) / 2f

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
                                    if (moved < 24f && elapsed < 400) onTap()
                                }
                                pressPosition = null
                            }
                            PointerEventType.Enter, PointerEventType.Move -> {
                                val position = event.changes.first().position
                                val nx = (position.x - offsetX) / renderedWidth
                                val ny = (position.y - offsetY) / renderedHeight
                                val hit = if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) null
                                    else instances.firstOrNull { it.containsNormalizedPoint(nx, ny) }
                                updateHit(hit)
                            }
                            PointerEventType.Exit -> updateHit(null)
                            else -> {}
                        }
                    }
                }
            }
    )
}

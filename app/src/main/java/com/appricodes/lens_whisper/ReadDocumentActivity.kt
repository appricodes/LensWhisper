package com.appricodes.lens_whisper

import com.appricodes.lens_whisper.ui.components.CameraPermissionDeniedException
import com.appricodes.lens_whisper.ui.components.CAMERA_DENIED_MESSAGE
import com.appricodes.lens_whisper.ui.components.rememberPermissionRequester
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.offline.TextBlockResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val MAX_CAPTURE_DIMENSION = 2500

/**
 * "Read Document": captures a photo, then lets the user explore it exactly like
 * [SegmentationExploreActivity] does with objects, except each "object" is a paragraph of
 * recognized text ([OfflineTextRecognizer.recognizeBlocks], ML Kit's `TextBlock` grouping — the
 * closest real equivalent to "paragraph" this OCR has) — moving a finger onto one reads it aloud.
 *
 * Same hover feedback as Explore: [HoverFeedback]'s short hard vibration + a distinct tone on
 * crossing into/out of a paragraph, a soft continuous vibration while staying inside one, and
 * [ProcessingSoundPlayer.playEmptyAreaTone] (never an interrupting announcement) over blank space
 * instead of a spoken "Nothing here." A tap opens [ReadDocumentMenu]: capture a new photo, copy
 * the whole recognized text, share the text or the photo, or pick one paragraph from a list to
 * open a small Copy/Share menu for just that one.
 *
 * Full-screen ([ImmersiveFullScreen]): no status/nav bar, a small [ImmersiveBackButton] in the
 * bottom-right corner instead.
 */
class ReadDocumentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageUri = intent?.getStringExtra("IMAGE_URI")?.let { Uri.parse(it) }
        val prefs = PreferencesManager(this)

        setContent {
            LensWhisperTheme {
                ReadDocumentScreen(prefs = prefs, imageUri = imageUri, onClose = { finish() })
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Document text", text))
}

@Composable
private fun ReadDocumentScreen(prefs: PreferencesManager, imageUri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val permissions = rememberPermissionRequester()
    val cameraController = remember {
        CameraController(context) { permissions.request(Manifest.permission.CAMERA, CAMERA_DENIED_MESSAGE) }
    }

    var isLoading by remember { mutableStateOf(true) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentPhotoFile by remember { mutableStateOf(imageUri?.path?.let { File(it) }) }
    var blocks by remember { mutableStateOf<List<TextBlockResult>>(emptyList()) }
    var showPopup by remember { mutableStateOf(false) }
    // Non-null while the small Copy/Share dialog for one specific paragraph (picked from the
    // list in ReadDocumentMenu) is open.
    var selectedParagraphText by remember { mutableStateOf<String?>(null) }

    BackHandler { onClose() }
    DisposableEffect(cameraController) { onDispose { cameraController.shutdown() } }
    ImmersiveFullScreen()

    suspend fun runRecognition(loadedBitmap: Bitmap, externalToneJob: Job? = null) {
        isLoading = true
        bitmap = loadedBitmap
        AccessibilityUtils.announceToTalkBack(context, "Reading document.")
        // Non-null when captureNewPhoto already started the tone itself, from onShutter, the
        // instant the shutter fired — adopt and cancel that job instead of starting a later one.
        // Null for the initial-load path, where starting fresh here already means "exactly when
        // the image is received", since there's no shutter event to anchor to.
        val toneJob = externalToneJob ?: coroutineScope.launch(Dispatchers.IO) {
            ProcessingSoundPlayer.loopClockWaitingTone(context)
        }
        val recognized = try {
            withContext(Dispatchers.Default) { OfflineTextRecognizer.recognizeBlocks(loadedBitmap, prefs.ocrLanguage) }
        } finally {
            toneJob.cancel()
        }
        isLoading = false
        if (recognized.isEmpty()) {
            blocks = emptyList()
            AccessibilityUtils.announceToTalkBack(context, "No text found.")
        } else {
            blocks = recognized
            ProcessingSoundPlayer.playScreenOpened(context)
            AccessibilityUtils.announceToTalkBack(context, "Touch the screen to hear the text. Tap anywhere for more options.")
        }
    }

    fun captureNewPhoto() {
        showPopup = false
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
                // highResolution = true — this screen reads text, so it needs enough resolution
                // for small glyphs to stay legible, same as the initial capture that opened it.
                // correctOrientation = false — Read Document is Explore's text counterpart and
                // sends the image to the model exactly as taken, same as MlkitButtonProvider's
                // launchReadDocument (the capture that opened this screen in the first place).
                cameraController.withCaptureSession(lifecycleOwner, prefs.flashEnabled, highResolution = true, correctOrientation = false) {
                    cameraController.capturePhoto(MAX_CAPTURE_DIMENSION) {
                        // Fires the instant the shutter sound plays (see
                        // CameraController.capturePhotoInternal), not after readout/decode/scale —
                        // runRecognition adopts this job instead of starting its own, later one.
                        toneJob = captureScope.launch(Dispatchers.IO) {
                            ProcessingSoundPlayer.loopClockWaitingTone(context)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toneJob?.cancel()
                if (e !is CameraPermissionDeniedException) AccessibilityUtils.announceToTalkBack(context, "Could not capture photo.")
                isLoading = false
                return@launch
            }
            currentPhotoFile = withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "read_document_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { newBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                file
            }
            runRecognition(newBitmap, toneJob)
        }
    }

    LaunchedEffect(imageUri) {
        if (imageUri == null) {
            AccessibilityUtils.announceToTalkBack(context, "No image to read.")
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
        runRecognition(loaded)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }

        if (!isLoading) {
            HoverReadLayer(blocks = blocks, onTap = { showPopup = true })
        }

        if (showPopup) {
            ReadDocumentMenu(
                blocks = blocks,
                onCaptureNewPhoto = { captureNewPhoto() },
                onCopyFullText = {
                    showPopup = false
                    copyToClipboard(context, blocks.joinToString("\n\n") { it.text })
                    AccessibilityUtils.announceToTalkBack(context, "Copied.")
                },
                onShareText = {
                    showPopup = false
                    ShareUtils.shareText(context, blocks.joinToString("\n\n") { it.text })
                },
                onSharePhoto = {
                    showPopup = false
                    currentPhotoFile?.let { ShareUtils.shareImage(context, it) }
                },
                onParagraphSelected = { text ->
                    showPopup = false
                    selectedParagraphText = text
                },
                onDismiss = { showPopup = false }
            )
        }

        selectedParagraphText?.let { text ->
            ParagraphActionDialog(
                onCopy = {
                    copyToClipboard(context, text)
                    AccessibilityUtils.announceToTalkBack(context, "Copied.")
                    selectedParagraphText = null
                },
                onShare = {
                    selectedParagraphText = null
                    ShareUtils.shareText(context, text)
                },
                onDismiss = { selectedParagraphText = null }
            )
        }

        ImmersiveBackButton(onClick = onClose)
    }
}

/**
 * No title, same reasoning as [SegmentationExploreActivity]'s object-list menu. Unlike that menu's
 * read-only object list, each paragraph here *is* actionable — selecting one opens
 * [ParagraphActionDialog], a small Copy/Share menu for just that paragraph, since acting on a
 * single paragraph was an explicit, separate item from copying/sharing the whole text.
 */
@Composable
private fun ReadDocumentMenu(
    blocks: List<TextBlockResult>,
    onCaptureNewPhoto: () -> Unit,
    onCopyFullText: () -> Unit,
    onShareText: () -> Unit,
    onSharePhoto: () -> Unit,
    onParagraphSelected: (String) -> Unit,
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
                        .clickable { onCaptureNewPhoto() }
                        .padding(vertical = 14.dp)
                        .clearAndSetSemantics { contentDescription = "Capture new photo. Double tap to take a new photo and re-read it." }
                ) {
                    Text(text = "Capture New Photo", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyFullText() }
                        .padding(vertical = 14.dp)
                        .clearAndSetSemantics { contentDescription = "Copy full text. Double tap to copy all recognized text." }
                ) {
                    Text(text = "Copy Full Text", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShareText() }
                        .padding(vertical = 14.dp)
                        .clearAndSetSemantics { contentDescription = "Share text. Double tap to share the recognized text." }
                ) {
                    Text(text = "Share Text", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
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
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (blocks.isEmpty()) {
                        item {
                            Text(
                                text = "No text found.",
                                fontSize = 16.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 14.dp)
                            )
                        }
                    }
                    items(blocks) { block ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onParagraphSelected(block.text) }
                                .padding(vertical = 14.dp)
                                .clearAndSetSemantics { contentDescription = "${block.text}. Double tap for copy or share options." }
                        ) {
                            Text(text = block.text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** The small Copy/Share choice for one paragraph selected from [ReadDocumentMenu]'s list. */
@Composable
private fun ParagraphActionDialog(onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paragraph") },
        text = { Text("Copy or share just this paragraph?") },
        confirmButton = {
            Button(onClick = onCopy) { Text("Copy") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/**
 * Paragraph-hover layer — the same shape as [SegmentationExploreActivity]'s `HoverExploreLayer`,
 * minus the outlines and Color Report (paragraphs aren't drawn, and there's no color-report
 * equivalent for text) — [HoverFeedback]'s haptics/tones work exactly the same way here. Entering
 * a new paragraph always interrupts and speaks it; leaving to empty space never interrupts
 * anything currently being read — just [ProcessingSoundPlayer.playEmptyAreaTone] — so a paragraph
 * that was still being read out when the finger happened to leave keeps playing to completion,
 * exactly like Explore.
 */
@Composable
private fun HoverReadLayer(blocks: List<TextBlockResult>, onTap: () -> Unit) {
    val context = LocalContext.current
    var currentBlock by remember { mutableStateOf<TextBlockResult?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun updateHit(hit: TextBlockResult?) {
        if (hit !== currentBlock) {
            if (currentBlock != null) {
                HoverFeedback.onBoundaryCrossed(context)
                HoverFeedback.playExitTone(context)
            }
            currentBlock = hit
            if (hit != null) {
                HoverFeedback.onBoundaryCrossed(context)
                HoverFeedback.playEnterTone(context)
                VolumeShortcuts.interruptTalkBackSpeech(context)
                AccessibilityUtils.announceToTalkBack(context, hit.text)
            } else {
                // Left to empty space — an ambient cue only, never an interrupting announcement,
                // so a paragraph still being read out when the finger happened to leave keeps
                // playing to completion.
                ProcessingSoundPlayer.playEmptyAreaTone(context)
            }
        } else if (hit != null) {
            HoverFeedback.onInsideObject(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .semantics {
                contentDescription = "Photo of a document. Move your finger over it to hear the text, or double tap for more options."
                onClick { onTap(); true }
            }
            .pointerInput(blocks, containerSize) {
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
                                    if (moved < 24f && elapsed < 400) onTap()
                                }
                                pressPosition = null
                            }
                            PointerEventType.Enter, PointerEventType.Move -> {
                                // The image fills this Box exactly (ContentScale.FillBounds) — no
                                // letterbox offset/scale to undo, just a direct fraction.
                                val position = event.changes.first().position
                                val nx = position.x / containerSize.width
                                val ny = position.y / containerSize.height
                                val hit = if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) null
                                    else blocks.firstOrNull { it.containsNormalizedPoint(nx, ny) }
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

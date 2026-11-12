package com.appricodes.lens_whisper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.appricodes.lens_whisper.offline.LocalRelief
import com.appricodes.lens_whisper.offline.OfflineDepthEstimator
import com.appricodes.lens_whisper.offline.ReliefMap
import com.appricodes.lens_whisper.ui.components.ImmersiveBackButton
import com.appricodes.lens_whisper.ui.components.ImmersiveFullScreen
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.ReliefHaptics
import com.appricodes.lens_whisper.utils.VolumeShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ReliefExplore"

/**
 * "Feel Image Relief" — launched by Basic Tasks
 * ([com.appricodes.lens_whisper.ui.models.BasicTasksButtonProvider]) with an already-captured photo
 * or video frame in the `IMAGE_URI` extra. Estimates the photo's depth on-device
 * ([OfflineDepthEstimator], MiDaS v2.1 small), turns it into *local* relief ([LocalRelief] — each
 * point compared only with its own surroundings, so a nose stands out from the face around it, not
 * from the far wall), and then plays that relief as vibration under a moving finger
 * ([ReliefHaptics]): stronger = raised, weaker = recessed.
 *
 * Touch handling is the same raw-pointer approach as [SegmentationExploreActivity]'s
 * HoverExploreLayer: TalkBack's explore-by-touch delivers a moving finger as hover Enter/Move/Exit,
 * a plain touch delivers Press/Move/Release, and one handler covers both. A TalkBack double-tap
 * (`semantics { onClick }`) speaks the relief level at the last point touched.
 *
 * Fully offline; the photo is displayed full-screen ([ImmersiveFullScreen]), rotated a quarter turn
 * when that fits the screen better (the relief map is rotated with it).
 */
class ReliefExploreActivity : ComponentActivity() {

    private lateinit var haptics: ReliefHaptics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = ReliefHaptics(this)
        val imageUri = intent?.getStringExtra("IMAGE_URI")?.let { Uri.parse(it) }
        setContent {
            LensWhisperTheme {
                ReliefExploreScreen(imageUri = imageUri, haptics = haptics, onClose = { finish() })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        haptics.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        haptics.stop()
        if (isFinishing) OfflineDepthEstimator.release()
    }
}

private fun reliefWord(r: Float): String = when {
    r > 0.5f -> "Raised"
    r > 0.18f -> "Slightly raised"
    r < -0.5f -> "Recessed"
    r < -0.18f -> "Slightly recessed"
    else -> "Level"
}

@Composable
private fun ReliefExploreScreen(imageUri: Uri?, haptics: ReliefHaptics, onClose: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var relief by remember { mutableStateOf<ReliefMap?>(null) }

    BackHandler { onClose() }
    DisposableEffect(Unit) { onDispose { haptics.stop() } }
    ImmersiveFullScreen()

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
        bitmap = loaded
        AccessibilityUtils.announceToTalkBack(context, "Analyzing the image's relief.")
        val toneJob = launch(Dispatchers.IO) { ProcessingSoundPlayer.loopClockWaitingTone(context) }
        val result = try {
            withContext(Dispatchers.Default) {
                val start = System.currentTimeMillis()
                // Depth runs on the photo as captured (upright) — MiDaS does worse on sideways scenes.
                val depth = OfflineDepthEstimator.estimate(context, loaded)
                val map = LocalRelief.compute(depth)
                Log.d(TAG, "depth+relief ${System.currentTimeMillis() - start} ms, grid ${depth.size}")
                // Only then turn picture and map together to fit the screen.
                val screenLandscape = context.resources.displayMetrics.let { it.widthPixels >= it.heightPixels }
                val fitted = if ((loaded.width >= loaded.height) != screenLandscape) {
                    val rotated = Bitmap.createBitmap(loaded, 0, 0, loaded.width, loaded.height, Matrix().apply { postRotate(90f) }, true)
                    rotated to map.rotatedClockwise()
                } else {
                    loaded to map
                }
                // A grayscale copy of the map in the cache (overwritten each time) — lets the relief
                // be checked by eye (`adb pull`), never shown or shared by the app itself.
                runCatching {
                    FileOutputStream(File(context.cacheDir, "relief_debug.png")).use {
                        fitted.second.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                fitted
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "relief analysis failed", e)
            null
        } finally {
            toneJob.cancel()
        }
        if (result == null) {
            AccessibilityUtils.announceToTalkBack(context, "Could not analyze the image's relief.")
            onClose()
            return@LaunchedEffect
        }
        if (result.first !== loaded) {
            bitmap = result.first
            loaded.recycle()
        }
        relief = result.second
        ProcessingSoundPlayer.playScreenOpened(context)
        val ready = "Ready. Move your finger over the picture. Stronger vibration means raised, weaker means recessed."
        val warning = when {
            !haptics.hasVibrator -> " This phone has no vibration motor, so double tap to hear the relief under your finger instead."
            !haptics.enabledInSettings -> " Vibration is turned off in Settings. Turn it on to feel the image. Until then, double tap to hear the relief under your finger."
            !haptics.hasAmplitudeControl -> " On this phone, faster pulses mean raised and slower pulses mean recessed."
            else -> ""
        }
        AccessibilityUtils.announceToTalkBack(context, ready + warning)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Edge to edge, like Explore Photo by Touch: the whole photo is touchable and the
                // relief map (normalized coordinates) lines up with it exactly.
                contentScale = ContentScale.FillBounds
            )
        }
        relief?.let { map -> ReliefTouchLayer(map, haptics) }
        ImmersiveBackButton(onClick = onClose)
    }
}

@Composable
private fun ReliefTouchLayer(map: ReliefMap, haptics: ReliefHaptics) {
    val context = LocalContext.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var touching by remember { mutableStateOf(false) }
    // Kept after the finger lifts — TalkBack sends hover Exit right before its double-tap, which
    // must still describe the spot the user was feeling.
    var lastPoint by remember { mutableStateOf<Offset?>(null) }

    // Pushes a level the haptics rate-limiter held back once the finger slows or stops.
    LaunchedEffect(touching) {
        while (touching && isActive) {
            haptics.flush()
            delay(35)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .semantics {
                contentDescription = "Picture relief. Move your finger over it and feel the vibration: stronger means raised, weaker means recessed. Double tap to hear the relief at the last point."
                onClick {
                    val p = lastPoint
                    VolumeShortcuts.interruptTalkBackSpeech(context)
                    AccessibilityUtils.announceToTalkBack(
                        context,
                        if (p == null) "Touch the picture first." else reliefWord(map.sample(p.x, p.y))
                    )
                    true
                }
            }
            .pointerInput(map, containerSize) {
                if (containerSize.width == 0 || containerSize.height == 0) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press, PointerEventType.Enter, PointerEventType.Move -> {
                                val pos = event.changes.first().position
                                val nx = pos.x / containerSize.width
                                val ny = pos.y / containerSize.height
                                if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) {
                                    touching = false
                                    haptics.stop()
                                } else {
                                    touching = true
                                    lastPoint = Offset(nx, ny)
                                    haptics.update(map.sample(nx, ny))
                                }
                            }
                            PointerEventType.Release, PointerEventType.Exit -> {
                                touching = false
                                haptics.stop()
                            }
                            else -> {}
                        }
                    }
                }
            }
    )
}

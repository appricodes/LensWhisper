package com.appricodes.lens_whisper.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.appricodes.lens_whisper.utils.ImageScaling
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A captured still photo: the decoded/scaled bitmap plus a cache-file Uri for upload paths. */
data class CapturedFrame(val bitmap: Bitmap, val uri: Uri)

/**
 * Binds CameraX without ever showing a preview surface, and — since this rework — **only while the
 * camera is actually needed**.
 *
 * Previously the camera stayed bound for the entire lifetime of the screen, which on a screen with
 * no preview meant the sensor was powered and streaming for the whole session purely so that a
 * button press could be answered a few hundred milliseconds sooner. That drains the battery, keeps
 * the camera-in-use indicator lit, holds the camera against other apps, and is hard to justify to a
 * user who cannot see that nothing is being shown. Now:
 *
 * - **Capture** binds `ImageCapture`, takes the photo, and unbinds again — see [withCaptureSession],
 *   which brackets a whole burst (Story mode's several frames) in one bind rather than one per
 *   frame.
 * - **The flash** is not a torch any more. [PreferencesManager.flashEnabled] is an intent, applied
 *   as `ImageCapture.FLASH_MODE_ON` for the moment of capture, so the light exists only while the
 *   shutter does. There is consequently no auto-off timer to get wrong and no way to leave a torch
 *   burning.
 * - **A continuous analysis feed** ([setAnalysisFrameSink]) is the one thing that does hold the
 *   camera open, because MLKit's light-level and continuous modes genuinely need a live stream.
 *   It is bound only while such a mode is running and dropped the moment it stops.
 *
 * [rebind] is the single place that decides what is bound: whatever the current combination of
 * "someone is capturing" and "someone wants frames" calls for, or nothing at all.
 */
class CameraController(private val context: Context) {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null

    private var analysisFrameSink: ((Bitmap) -> Unit)? = null
    private var captureSessionDepth = 0
    // Held on the controller, not set once on the use case, because [rebind] can replace the
    // ImageCapture mid-session (e.g. MLKit's light mode starting while a Story burst runs) and a
    // fresh use case would silently come back with the flash off.
    private var captureFlashEnabled = false
    private var boundLifecycleOwner: LifecycleOwner? = null
    // What the current binding was built for. rebind() is a no-op when nothing in it changed,
    // which matters because a capture taken *inside* a continuous session bumps the session depth
    // without changing what needs to be bound - rebuilding the use cases there would tear down and
    // re-create the live ImageCapture every few seconds for no reason.
    private var boundSignature: String? = null

    // Now that a binding can outlive the exact call that made it (see the keep-warm window on
    // withCaptureSession), boundSignature alone isn't enough to know whether it's still actually
    // valid: CameraX itself silently tears down/suspends whatever's bound the moment its
    // LifecycleOwner drops below STARTED (e.g. a keep-warm window spans a navigation away from
    // this screen and back), with no signal to this class's own bookkeeping. This observer catches
    // that the moment it happens rather than at the next rebind() call, which by then would find
    // the LifecycleOwner already RESUMED again and wrongly conclude nothing changed. Attached to
    // whichever LifecycleOwner is currently bound (see the top of rebind()), moved whenever that
    // changes, removed entirely in release().
    private var observedLifecycleOwner: LifecycleOwner? = null
    private val onStopObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) boundSignature = null
    }

    // Backs the brief "keep-warm" window after a capture session ends — see withCaptureSession.
    // Cancelled in shutdown()/release() so nothing outlives the controller itself. Main, not
    // Default: rebind() ends up calling ProcessCameraProvider.bindToLifecycle/unbindAll, which
    // CameraX hard-asserts must run on the main thread (crashed with exactly that
    // IllegalStateException — "Not in application's main thread" — in scheduleDelayedUnbind before
    // this was caught: every other rebind() caller reaches it via a coroutine launched from
    // Compose, which already runs on Main, so this was the one path that didn't).
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingUnbindJob: Job? = null

    // Serializes both binding changes and takePicture() calls. Rapidly switching between buttons
    // could otherwise fire two concurrent captures on the same use case, which raced against
    // CameraX's internal capture-session state and intermittently failed — and now it could also
    // interleave a bind with an unbind.
    private val cameraMutex = Mutex()

    var activeCamera: Camera? = null
        private set

    // mutableStateOf (not a plain var) so screens can read it reactively to announce which
    // camera is current/was just switched to, without threading a separate tracking var.
    var lensFacing by mutableStateOf(CameraSelector.LENS_FACING_BACK)
        private set

    /**
     * Starts or stops the continuous frame feed. Passing null unbinds it, which — with no capture
     * in flight — releases the camera entirely.
     */
    suspend fun setAnalysisFrameSink(lifecycleOwner: LifecycleOwner, sink: ((Bitmap) -> Unit)?) {
        cameraMutex.withLock {
            analysisFrameSink = sink
            boundLifecycleOwner = lifecycleOwner
            rebind()
        }
    }

    /**
     * Binds the camera for the duration of [block] and releases it afterwards, whatever happens.
     * Nest-safe and burst-friendly: Story mode's several `capturePhotoToFile` calls run inside one
     * session, so the camera is opened once rather than once per frame.
     *
     * Doesn't unbind the instant [block] finishes, though — it schedules that for
     * [KEEP_WARM_MS] later instead (see [scheduleDelayedUnbind]), cancelled if another session
     * starts before then. Binding a cold camera from nothing measurably takes a second or two on
     * some devices (sensor open, AE/AF convergence) — noticeable as a delay between a capture
     * button and [ProcessingSoundPlayer.playShutter] actually firing (partly masked by
     * [ProcessingSoundPlayer.playCaptureAcknowledge] firing immediately instead of waiting for
     * it). [KEEP_WARM_MS] (30s, raised from an initial 4s once the trade-off below was accepted)
     * of the camera staying bound right after real use isn't "continuously active" in the sense
     * that requirement was about — it's still closed the rest of the time — but it does mean the
     * system's camera-in-use indicator can stay lit up to that long past the actual photo, which
     * is the real trade-off here.
     */
    suspend fun <T> withCaptureSession(lifecycleOwner: LifecycleOwner, flashEnabled: Boolean, block: suspend () -> T): T {
        cameraMutex.withLock {
            pendingUnbindJob?.cancel()
            pendingUnbindJob = null
            captureSessionDepth++
            boundLifecycleOwner = lifecycleOwner
            captureFlashEnabled = flashEnabled
            rebind()
        }
        try {
            return block()
        } finally {
            cameraMutex.withLock {
                captureSessionDepth--
                if (captureSessionDepth == 0) {
                    captureFlashEnabled = false
                    scheduleDelayedUnbind()
                }
            }
        }
    }

    /** Actually tears the binding down [KEEP_WARM_MS] after the last capture session ended,
     *  unless a new one started (and cancelled this) first. Runs on [controllerScope], not the
     *  caller's coroutine — [withCaptureSession]'s `finally` has already returned to its caller by
     *  the time this fires. */
    private fun scheduleDelayedUnbind() {
        pendingUnbindJob = controllerScope.launch {
            delay(KEEP_WARM_MS)
            cameraMutex.withLock {
                // analysisFrameSink != null means a continuous mode (Light Level, MLKit's
                // continuous polling) is holding the camera open on purpose — this timer exists
                // only to expire an idle *capture* session, and must never tear down a live feed
                // out from under one of those, keep-warm window elapsed or not.
                if (captureSessionDepth == 0 && analysisFrameSink == null) rebind()
            }
        }
    }

    /**
     * Rebuilds the binding from scratch to match what is currently needed. Called under
     * [cameraMutex] only.
     */
    private suspend fun rebind() {
        val lifecycleOwner = boundLifecycleOwner ?: return
        val cameraProvider = provider ?: awaitCameraProvider(context).also { provider = it }

        // Keep onStopObserver pointed at whichever LifecycleOwner is actually bound right now —
        // see its declaration for why this exists at all.
        if (observedLifecycleOwner !== lifecycleOwner) {
            observedLifecycleOwner?.lifecycle?.removeObserver(onStopObserver)
            lifecycleOwner.lifecycle.addObserver(onStopObserver)
            observedLifecycleOwner = lifecycleOwner
        }

        val wantCapture = captureSessionDepth > 0
        val sink = analysisFrameSink
        val signature = "$wantCapture|${sink != null}|$lensFacing|$captureFlashEnabled"
        // Belt-and-braces alongside onStopObserver: if this is somehow being asked to reuse a
        // binding for a LifecycleOwner that isn't even at STARTED right now, don't trust the
        // signature match either way.
        val lifecycleStillActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (signature == boundSignature && lifecycleStillActive) return
        boundSignature = signature

        cameraProvider.unbindAll()

        if (!wantCapture && sink == null) {
            analysis?.clearAnalyzer()
            analysis = null
            imageCapture = null
            activeCamera = null
            return
        }

        val useCases = mutableListOf<UseCase>()

        if (wantCapture) {
            val captureUseCase = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            captureUseCase.flashMode =
                if (captureFlashEnabled && lensHasFlash(cameraProvider)) ImageCapture.FLASH_MODE_ON
                else ImageCapture.FLASH_MODE_OFF
            imageCapture = captureUseCase
            useCases.add(captureUseCase)
        } else {
            imageCapture = null
        }

        if (sink != null) {
            // This analysis stream is only ever bound for Light Level (see onAnalysisFrame in
            // MlkitButtonProvider — the only caller of setAnalysisFrameSink with a non-null sink),
            // which only needs a coarse average brightness, not detail — requesting the smallest
            // resolution the camera supports here means less data to convert to a Bitmap per frame,
            // on top of OfflineLightDetector's own further downscale before it does any math.
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(32, 32), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .build()
            analysisUseCase.setAnalyzer(cameraExecutor) { proxy ->
                try {
                    sink(yPlaneToGrayscaleBitmap(proxy))
                } finally {
                    proxy.close()
                }
            }
            analysis?.clearAnalyzer()
            analysis = analysisUseCase
            useCases.add(analysisUseCase)
        } else {
            analysis?.clearAnalyzer()
            analysis = null
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        activeCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
    }

    /** Unbinds everything and forces the torch off. Safe to call when nothing is bound. Also
     *  cancels any pending [scheduleDelayedUnbind] keep-warm job — this is an immediate, unconditional
     *  teardown, not something a fresh capture session should be able to interrupt. */
    fun release() {
        pendingUnbindJob?.cancel()
        pendingUnbindJob = null
        observedLifecycleOwner?.lifecycle?.removeObserver(onStopObserver)
        observedLifecycleOwner = null
        analysisFrameSink = null
        captureSessionDepth = 0
        boundSignature = null
        activeCamera?.cameraControl?.enableTorch(false)
        analysis?.clearAnalyzer()
        analysis = null
        imageCapture = null
        provider?.unbindAll()
        activeCamera = null
    }

    /** Call when the owning screen is destroyed — [release] alone leaves the analyzer executor
     *  thread and [controllerScope] alive. */
    fun shutdown() {
        release()
        controllerScope.cancel()
        cameraExecutor.shutdown()
    }

    /** Returns the new lens facing. Takes effect on the next bind — with the camera only open
     *  during a capture, there is usually nothing bound at the moment this is called. */
    suspend fun toggleLensFacing(): Int {
        cameraMutex.withLock {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            rebind()
        }
        return lensFacing
    }

    /**
     * Whether the current lens has flash hardware, answerable without binding anything — the flash
     * toggle has to be describable to TalkBack while the camera is closed, which is now almost
     * always. Falls back to "the back lens usually has one, the front usually doesn't" if CameraX
     * can't be queried yet.
     */
    fun hasFlashUnitForLens(): Boolean {
        val cameraProvider = provider ?: return lensFacing == CameraSelector.LENS_FACING_BACK
        return lensHasFlash(cameraProvider)
    }

    private fun lensHasFlash(cameraProvider: ProcessCameraProvider): Boolean = try {
        CameraSelector.Builder().requireLensFacing(lensFacing).build()
            .filter(cameraProvider.availableCameraInfos)
            .any { it.hasFlashUnit() }
    } catch (e: Exception) {
        lensFacing == CameraSelector.LENS_FACING_BACK
    }

    /** Takes a photo and returns just the bitmap. Must be called inside [withCaptureSession]. */
    suspend fun capturePhoto(maxDim: Int = 2500): Bitmap = capturePhotoInternal(maxDim).bitmap

    /** Same as [capturePhoto] but also writes a JPEG to the cache dir, for the upload paths. */
    suspend fun capturePhotoToFile(maxDim: Int = 2500): CapturedFrame = capturePhotoInternal(maxDim)

    private suspend fun capturePhotoInternal(maxDim: Int): CapturedFrame = cameraMutex.withLock {
        suspendCancellableCoroutine { cont ->
            val capture = imageCapture
            if (capture == null) {
                cont.resumeWithException(IllegalStateException("Camera not bound — capture must run inside withCaptureSession"))
                return@suspendCancellableCoroutine
            }
            capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try { imageProxyToBitmap(image) } finally { image.close() }
                    if (bitmap == null) {
                        cont.resumeWithException(IllegalStateException("Failed to decode captured photo"))
                        return
                    }
                    // The single choke point every capture path in the app goes through (single
                    // shot, Story frames, every MLKit/segmentation mode, Explore) — playing the
                    // shutter click here, right as the photo is actually taken, means no call site
                    // has to remember to trigger it itself.
                    ProcessingSoundPlayer.playShutter(context)
                    val scaled = ImageScaling.scaleToMax(bitmap, maxDim)
                    val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    cont.resume(CapturedFrame(scaled, Uri.fromFile(file)))
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    companion object {
        /** How long the camera stays bound after a capture session ends before actually
         *  unbinding — see [withCaptureSession]/[scheduleDelayedUnbind]. */
        private const val KEEP_WARM_MS = 30000L

        /**
         * Process-wide belt-and-braces release: unbinds every CameraX use case and turns any torch
         * off, without needing a [CameraController] instance.
         *
         * `ProcessCameraProvider` is a process singleton, so a screen that owns no camera at all
         * (the Video/Image describer) can still inherit a binding left by the camera screen if that
         * screen was only paused rather than destroyed. Calling this on entry makes "no camera, no
         * flash" true by construction rather than by assumption.
         */
        fun releaseAllCameras(context: Context) {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (e: Exception) {
                // Nothing was bound, or CameraX isn't initialised on this device — either way
                // there is no camera left running, which is the whole point of the call.
            }
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Reads the Y (luma) plane straight from a YUV_420_888 frame into a small grayscale bitmap. */
private fun yPlaneToGrayscaleBitmap(image: ImageProxy, sampleSize: Int = 64): Bitmap {
    val yPlane = image.planes[0]
    val buffer = yPlane.buffer
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val width = image.width
    val height = image.height
    val stepX = (width / sampleSize).coerceAtLeast(1)
    val stepY = (height / sampleSize).coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    for (sy in 0 until sampleSize) {
        val srcY = (sy * stepY).coerceIn(0, height - 1)
        for (sx in 0 until sampleSize) {
            val srcX = (sx * stepX).coerceIn(0, width - 1)
            val index = srcY * rowStride + srcX * pixelStride
            val luma = buffer.get(index).toInt() and 0xFF
            bitmap.setPixel(sx, sy, Color.rgb(luma, luma, luma))
        }
    }
    return bitmap
}

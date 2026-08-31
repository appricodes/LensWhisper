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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * - **Capture** binds `ImageCapture`, takes the photo, and unbinds again immediately once the
 *   whole session ends — see [withCaptureSession], which brackets a whole burst (Story mode's
 *   several frames) in one bind rather than one per frame, but still releases the instant that
 *   burst is done rather than lingering.
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
    // Same reasoning as captureFlashEnabled — which resolution the current capture session wants,
    // see withCaptureSession's highResolution parameter.
    private var captureTargetResolution: Size = RESOLUTION_720P
    private var boundLifecycleOwner: LifecycleOwner? = null
    // What the current binding was built for. rebind() is a no-op when nothing in it changed,
    // which matters because a capture taken *inside* a continuous session bumps the session depth
    // without changing what needs to be bound - rebuilding the use cases there would tear down and
    // re-create the live ImageCapture every few seconds for no reason.
    private var boundSignature: String? = null

    // boundSignature alone isn't enough to know a binding is still actually valid: CameraX itself
    // silently tears down/suspends whatever's bound the moment its LifecycleOwner drops below
    // STARTED (e.g. a continuous mode's long-lived binding spans a navigation away from this
    // screen and back), with no signal to this class's own bookkeeping. This observer catches that
    // the moment it happens rather than at the next rebind() call, which by then would find the
    // LifecycleOwner already RESUMED again and wrongly conclude nothing changed. Attached to
    // whichever LifecycleOwner is currently bound (see the top of rebind()), moved whenever that
    // changes, removed entirely in release().
    private var observedLifecycleOwner: LifecycleOwner? = null
    private val onStopObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) boundSignature = null
    }

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
     * Binds the camera for the duration of [block] and releases it again immediately afterwards,
     * whatever happens — no keep-warm window. Nest-safe and burst-friendly: Story mode's several
     * `capturePhotoToFile` calls run inside one session, so the camera is opened once rather than
     * once per frame, and only unbinds once the whole burst is done.
     *
     * Binding a cold camera from nothing measurably takes a second or two on some devices (sensor
     * open, AE/AF convergence) — noticeable as a delay between a capture button and
     * [ProcessingSoundPlayer.playShutter] actually firing (partly masked by
     * [ProcessingSoundPlayer.playCaptureAcknowledge] firing immediately instead of waiting for
     * it). An earlier version of this method kept the binding alive for 30s past the end of
     * [block] specifically to absorb that cost on a quick follow-up capture; that traded the
     * system's camera-in-use indicator staying lit for up to 30s past the actual photo, which
     * wasn't worth it — every use now releases the camera the moment it's done with it instead.
     */
    suspend fun <T> withCaptureSession(
        lifecycleOwner: LifecycleOwner,
        flashEnabled: Boolean,
        highResolution: Boolean = false,
        // "Describe TV Image"/"Explore and Describe" pass true — they isolate one object out of
        // the rest of the photo at full detail (see SegmentationIsolation), which needs whatever
        // resolution the sensor actually has, not just 1080p. Takes priority over [highResolution]
        // if both are somehow true. Left false by every other caller.
        maxResolution: Boolean = false,
        block: suspend () -> T
    ): T {
        cameraMutex.withLock {
            captureSessionDepth++
            boundLifecycleOwner = lifecycleOwner
            captureFlashEnabled = flashEnabled
            // Read Text/Read Document pass highResolution; Describe TV Image/Explore and Describe
            // pass maxResolution; every other capture leaves both false for the 720p default — see
            // RESOLUTION_MAX/RESOLUTION_1080P/RESOLUTION_720P's doc.
            captureTargetResolution = if (maxResolution) RESOLUTION_MAX else if (highResolution) RESOLUTION_1080P else RESOLUTION_720P
            rebind()
        }
        try {
            return block()
        } finally {
            // NonCancellable: [block] can be cancelled mid-capture now that
            // AnalysisSessionController.launchAnalysis cancels a previous analysis job (which may
            // be sitting inside capturePhotoInternal, itself holding cameraMutex for the whole
            // takePicture() call) to start a new one. Mutex.lock() on an already-cancelled
            // coroutine throws immediately instead of acquiring — without this wrapper,
            // captureSessionDepth-- below would never run, permanently leaking the count above 0
            // and leaving the camera bound forever (silently defeating the "release right after
            // every use" contract this method exists for).
            withContext(NonCancellable) {
                cameraMutex.withLock {
                    captureSessionDepth--
                    if (captureSessionDepth == 0) {
                        captureFlashEnabled = false
                        captureTargetResolution = RESOLUTION_720P
                        // Releases right away — rebind() recomputes from captureSessionDepth/
                        // analysisFrameSink, so this naturally leaves a continuous mode's live feed
                        // (Light Level, MLKit's continuous polling) bound if one is holding the
                        // camera open on purpose, and only tears down the just-finished capture use
                        // case.
                        rebind()
                    }
                }
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
        val signature = "$wantCapture|${sink != null}|$lensFacing|$captureFlashEnabled|$captureTargetResolution"
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
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        // ResolutionSelector applies its AspectRatioStrategy before the
                        // ResolutionStrategy below, and the builder's default is 4:3 — left alone,
                        // that would filter the candidate list against 4:3 before ever looking at
                        // captureTargetResolution's 16:9 target (1920x1080/1280x720), and could
                        // land on some other resolution entirely. Pin it to 16:9 explicitly so the
                        // resolution strategy is actually choosing among sizes near the target.
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(captureTargetResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
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

    /** Unbinds everything and forces the torch off. Safe to call when nothing is bound. */
    fun release() {
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
     *  thread alive. */
    fun shutdown() {
        release()
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

    /** Takes a photo and returns just the bitmap. Must be called inside [withCaptureSession].
     *  [onShutter] fires synchronously at the exact moment the shutter sound does — see
     *  [capturePhotoInternal]'s doc — so a caller that wants its "waiting for the model" tone to
     *  start right after the shutter, not after this whole suspend function returns (which also
     *  waits out image readout/decode/scale/file-write), starts it from there instead of after
     *  `await`ing this call. */
    suspend fun capturePhoto(maxDim: Int = 2500, onShutter: () -> Unit = {}): Bitmap = capturePhotoInternal(maxDim, onShutter).bitmap

    /** Same as [capturePhoto] but also writes a JPEG to the cache dir, for the upload paths. */
    suspend fun capturePhotoToFile(maxDim: Int = 2500, onShutter: () -> Unit = {}): CapturedFrame = capturePhotoInternal(maxDim, onShutter)

    private suspend fun capturePhotoInternal(maxDim: Int, onShutter: () -> Unit): CapturedFrame = cameraMutex.withLock {
        suspendCancellableCoroutine { cont ->
            val capture = imageCapture
            if (capture == null) {
                cont.resumeWithException(IllegalStateException("Camera not bound — capture must run inside withCaptureSession"))
                return@suspendCancellableCoroutine
            }
            // The single choke point every capture path in the app goes through (single shot,
            // Story frames, every MLKit/segmentation mode, Explore) — playing the shutter click
            // here, right before the capture command itself is issued, means no call site has to
            // remember to trigger it itself. Deliberately *before* takePicture(), not in
            // onCaptureSuccess below: a real shutter sound marks the moment of exposure, not the
            // moment the resulting image finishes being read out and decoded — which can trail the
            // former by a further processing delay. onShutter() fires in the same breath, for
            // callers that need to react at this exact point rather than after the whole capture.
            ProcessingSoundPlayer.playShutter(context)
            onShutter()
            capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try { imageProxyToBitmap(image) } finally { image.close() }
                    if (bitmap == null) {
                        cont.resumeWithException(IllegalStateException("Failed to decode captured photo"))
                        return
                    }
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
        // Capture targets for withCaptureSession's highResolution flag — Read Text/Read Document
        // need enough resolution for small glyphs to stay legible to OCR; every other capture
        // (custom VLM prompts, Story, Detect Objects/Object and Color Detection, Explore) only
        // needs enough detail for object/scene description, where 720p is plenty and faster to
        // capture, scale, and run inference on. Landscape (width x height) regardless of the
        // device's own orientation — CameraX resolves these against the sensor's native reported
        // sizes, same convention as the 32x32 analysis-resolution selector below.
        private val RESOLUTION_1080P = Size(1920, 1080)
        private val RESOLUTION_720P = Size(1280, 720)

        // Describe TV Image/Explore and Describe's tier — deliberately larger than any real sensor
        // reports. FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER then has nothing "higher" to pick, so it
        // falls back to the closest lower size, i.e. the largest 16:9 resolution the camera actually
        // offers. Still pinned to 16:9 (see the AspectRatioStrategy below) rather than letting these
        // two buttons alone see a wider field of view than every other capture in the app.
        private val RESOLUTION_MAX = Size(9999, 5625)

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

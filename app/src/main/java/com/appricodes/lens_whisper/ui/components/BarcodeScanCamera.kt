package com.appricodes.lens_whisper.ui.components

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appricodes.lens_whisper.offline.OfflineBarcodeScanner
import com.appricodes.lens_whisper.offline.ScannedBarcode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BarcodeScanCamera"

/** 720p, per the feature's spec — plenty for a barcode that fills a good part of the frame, and
 *  markedly cheaper per frame than 1080p when every frame is being analyzed. */
private val SCAN_RESOLUTION = Size(1280, 720)

/** Floor on how often a frame is actually handed to ML Kit. `STRATEGY_KEEP_ONLY_LATEST` plus the
 *  detector's own latency already throttles this most of the way; this only stops a fast device
 *  from spending every drop of CPU (and battery, and heat — see [com.appricodes.lens_whisper.utils.ThermalGuard])
 *  on a scan that will not find anything new 8 milliseconds later. Well under the 1-second poll
 *  the feature was sketched with: a blind user sweeping a package looking for the label needs the
 *  hit the moment the barcode crosses the frame, not up to a second afterwards. */
private const val MIN_SCAN_INTERVAL_MS = 200L

/**
 * A small, self-contained CameraX binding that does one thing: stream frames to
 * [OfflineBarcodeScanner] until a barcode turns up.
 *
 * Deliberately **not** built on [CameraController], even though that class already binds an
 * `ImageAnalysis`. Two concrete incompatibilities, not just tidiness:
 * - Its analyzer closes the `ImageProxy` in a `finally` immediately after calling the sink. That is
 *   correct for the light meter (whose sink synchronously copies the Y plane out) and wrong here:
 *   ML Kit's `process()` is asynchronous and needs the underlying `Image` to stay valid until it
 *   completes.
 * - Its `rebind()` keys on `boundSignature = "$wantCapture|${sink != null}|..."`, which encodes
 *   neither *which kind* of sink is attached nor the analysis resolution (hardcoded to 32x32 for
 *   the light meter). A second sink kind would collapse into the same signature and silently
 *   no-op the rebind, leaving a barcode scan reading 32x32 grayscale thumbnails.
 *
 * Barcode scanning also never coexists with anything else — it owns its own full-screen activity,
 * takes no photos, corrects no orientation — so none of [CameraController]'s capture-session,
 * flash-intent or rotation machinery would be doing anything for it anyway.
 *
 * [start] and [stop] are safe to call repeatedly and in any order; [shutdown] additionally tears
 * down the analyzer thread and must be called from the owning screen's `onDispose`.
 */
class BarcodeScanCamera(private val context: Context) {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    // Frames are dispatched here rather than on the caller's scope so that stopping the scan
    // cancels every in-flight detection at once, and so an outer scope's cancellation can never
    // strand a frame mid-analysis.
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    @Volatile
    private var lastScanAtMs = 0L

    /**
     * Binds the back camera's analysis stream and calls [onBarcodes] on the main thread with every
     * non-empty detection. The caller decides what "found" means (it stops the scan itself by
     * calling [stop]) — this class keeps streaming until told otherwise.
     *
     * [torch] turns the flashlight on for the duration of the scan, honouring the app's existing
     * "use the flash" preference. A stream has no shutter to fire a flash at, so the torch is the
     * only equivalent; it is switched off again by [stop].
     */
    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        torch: Boolean,
        onBarcodes: (List<ScannedBarcode>) -> Unit
    ) {
        // Drop whatever a previous start() left behind before building a new use case — this can
        // legitimately be called more than once (the screen re-runs it when the camera permission
        // is granted, or when the user scans again), and an ImageAnalysis whose analyzer was never
        // cleared keeps its callback — and this object — alive for nothing.
        stop()
        val cameraProvider = provider ?: awaitProvider().also { provider = it }
        // Take the camera cleanly: ProcessCameraProvider is a process singleton, so a paused
        // screen elsewhere in the app may still have use cases bound to it.
        cameraProvider.unbindAll()

        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(SCAN_RESOLUTION, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .build()

        analysisUseCase.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy, onBarcodes) }
        analysis = analysisUseCase

        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analysisUseCase
        )
        if (torch && camera.cameraInfo.hasFlashUnit()) {
            runCatching { camera.cameraControl.enableTorch(true) }
        }
    }

    /**
     * One analyzed frame. Split out of the `setAnalyzer` lambda rather than written inline so the
     * opt-in for `ImageProxy.getImage` sits on a named declaration — Android Lint's
     * `UnsafeOptInUsageError` does not follow a Kotlin `@OptIn` into a lambda body.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy, onBarcodes: (List<ScannedBarcode>) -> Unit) {
        val now = System.currentTimeMillis()
        val mediaImage = proxy.image
        if (mediaImage == null || now - lastScanAtMs < MIN_SCAN_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastScanAtMs = now
        val rotation = proxy.imageInfo.rotationDegrees
        val job = scanScope.launch {
            val found = OfflineBarcodeScanner.scan(mediaImage, rotation)
            if (found.isNotEmpty()) withContext(Dispatchers.Main) { onBarcodes(found) }
        }
        // invokeOnCompletion, not a `finally` inside the coroutine: if scanScope was already
        // cancelled when this frame arrived, launch returns an already-cancelled job whose body
        // never runs at all — and an ImageProxy that is never closed stalls the whole analysis
        // stream (STRATEGY_KEEP_ONLY_LATEST will not deliver another frame until this one is
        // released). This callback fires either way.
        job.invokeOnCompletion { runCatching { proxy.close() } }
    }

    /** Unbinds the camera and cancels any detection still in flight. The torch goes out with the
     *  binding. Safe to call when nothing is bound. */
    fun stop() {
        analysis?.clearAnalyzer()
        analysis = null
        runCatching { provider?.unbindAll() }
    }

    /** [stop] plus the analyzer thread and the scan scope — the screen is going away for good. */
    fun shutdown() {
        stop()
        scanScope.cancel()
        analysisExecutor.shutdown()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    Log.w(TAG, "Camera provider unavailable", e)
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
}

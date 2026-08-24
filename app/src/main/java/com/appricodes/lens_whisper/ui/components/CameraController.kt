package com.appricodes.lens_whisper.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appricodes.lens_whisper.utils.ImageScaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

private const val TORCH_AUTO_OFF_MS = 60_000L

/** A captured still photo: the decoded/scaled bitmap plus a cache-file Uri for network upload. */
data class CapturedFrame(val bitmap: Bitmap, val uri: Uri)

/**
 * Binds CameraX without ever showing a preview surface. Screens that only need to "take a
 * photo" (Simple/Advanced Camera describers) never attach a Preview use case, so there is
 * nothing for the user to see except the button grid.
 */
class CameraController(private val context: Context) {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    // Serializes takePicture() calls. Rapidly switching between buttons (e.g. Objects -> Text)
    // could otherwise fire two concurrent captures on the same use case, which raced against
    // CameraX's internal capture-session state and intermittently failed.
    private val captureMutex = Mutex()

    var activeCamera: Camera? = null
        private set

    // mutableStateOf (not a plain var) so screens can read it reactively to announce which
    // camera is current/was just switched to, without threading a separate tracking var.
    var lensFacing by mutableStateOf(CameraSelector.LENS_FACING_BACK)
        private set

    var torchOn by mutableStateOf(false)
        private set
    private var torchAutoOffJob: Job? = null

    /** Toggles the flashlight, auto-turning it off after [TORCH_AUTO_OFF_MS] if left on.
     *  [onAutoOff] is invoked (on the main thread) only when the auto-off timer — not a manual
     *  toggle — is what turned it off, so the caller can announce it via TalkBack. */
    fun toggleTorch(scope: CoroutineScope, onAutoOff: () -> Unit = {}) {
        val camera = activeCamera ?: return
        torchAutoOffJob?.cancel()
        val turningOn = !torchOn
        torchOn = turningOn
        camera.cameraControl.enableTorch(turningOn)
        if (turningOn) {
            torchAutoOffJob = scope.launch {
                delay(TORCH_AUTO_OFF_MS)
                torchOn = false
                activeCamera?.cameraControl?.enableTorch(false)
                onAutoOff()
            }
        }
    }

    /** Forces the torch off with no auto-off announcement — used before lens switches, when
     *  Light Level activates, and on lifecycle pause, where a stray timer must never survive. */
    fun forceTorchOff() {
        torchAutoOffJob?.cancel()
        torchAutoOffJob = null
        torchOn = false
        activeCamera?.cameraControl?.enableTorch(false)
    }

    /** Binds ImageCapture (always) and, if [onAnalysisFrame] is provided, a continuous ImageAnalysis feed. */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        onAnalysisFrame: ((Bitmap) -> Unit)? = null
    ) {
        val cameraProvider = provider ?: awaitCameraProvider(context).also { provider = it }

        val captureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = captureUseCase

        val useCases = mutableListOf<UseCase>(captureUseCase)

        if (onAnalysisFrame != null) {
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                try {
                    onAnalysisFrame(yPlaneToGrayscaleBitmap(proxy))
                } finally {
                    proxy.close()
                }
            }
            useCases.add(analysis)
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        cameraProvider.unbindAll()
        activeCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
    }

    fun unbind() {
        forceTorchOff()
        provider?.unbindAll()
        activeCamera = null
    }

    /** Returns the new lens facing; caller should re-[bind] to apply it. Forces the torch off
     *  first — the front lens usually has no torch hardware, and a stray auto-off timer must
     *  never outlive the camera it was bound to. */
    fun toggleLensFacing(): Int {
        forceTorchOff()
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        return lensFacing
    }

    /** Whether the currently bound camera has flash/torch hardware at all (usually false for the front lens). */
    fun hasFlashUnit(): Boolean = activeCamera?.cameraInfo?.hasFlashUnit() == true

    /** Takes a photo, scales it to [maxDim] (never upscaling), and returns the bitmap. */
    suspend fun capturePhoto(maxDim: Int = 2500): Bitmap = capturePhotoInternal(maxDim).bitmap

    /** Same as [capturePhoto] but also writes a JPEG to the cache dir, for network upload. */
    suspend fun capturePhotoToFile(maxDim: Int = 2500): CapturedFrame = capturePhotoInternal(maxDim)

    private suspend fun capturePhotoInternal(maxDim: Int): CapturedFrame = captureMutex.withLock {
        suspendCancellableCoroutine { cont ->
            val capture = imageCapture
            if (capture == null) {
                cont.resumeWithException(IllegalStateException("Camera not bound"))
                return@suspendCancellableCoroutine
            }
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

    fun release() {
        unbind()
        cameraExecutor.shutdown()
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

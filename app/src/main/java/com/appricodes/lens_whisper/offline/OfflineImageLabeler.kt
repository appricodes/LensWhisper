package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Detect Objects": ML Kit Image Labeling's base model (400+ everyday labels — objects, animals,
 * food, places, activities) via the unbundled Play Services module, so nothing ships in the APK.
 * Unlike [OfflineSegmentationDetector] it says *what* is in the photo but not *where*, which is all
 * this button needs — and it knows far more kinds of thing than COCO's 80 classes.
 */
object OfflineImageLabeler {

    private const val TAG = "OfflineImageLabeler"

    /** ML Kit's own default floor; below it labels are mostly guesses. */
    private const val MIN_CONFIDENCE = 0.5f

    /** At most this many labels are spoken, strongest first — a list longer than this stops being
     *  useful to listen to. */
    private const val MAX_LABELS = 10

    @Volatile
    private var client: ImageLabeler? = null

    private var warnedNotReady = false

    private fun labeler(): ImageLabeler {
        client?.let { return it }
        return synchronized(this) {
            client ?: ImageLabeling.getClient(
                ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build()
            ).also { client = it }
        }
    }

    /** Same contract as [OfflineTextRecognizer.isReady]: true if the module is installed; otherwise
     *  starts the download, announces "not ready" once per process, and returns false. */
    suspend fun isReady(context: Context): Boolean {
        val available = try {
            suspendCancellableCoroutine { cont ->
                ModuleInstall.getClient(context).areModulesAvailable(labeler())
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it.areModulesAvailable()) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(false) }
            }
        } catch (e: Exception) {
            false
        }
        if (!available) {
            try {
                ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(labeler()).build())
                    .addOnFailureListener { e -> Log.e(TAG, "Image labeling module install failed", e) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not request image labeling module install", e)
            }
            if (!warnedNotReady) {
                warnedNotReady = true
                AccessibilityUtils.announceToTalkBack(context, "The object detector isn't ready yet. Please wait for it to finish downloading, then try again.")
            }
        }
        return available
    }

    /** Labels for [bitmap], strongest first, at most [MAX_LABELS]. Throws if ML Kit fails. */
    suspend fun label(bitmap: Bitmap): List<ImageLabel> {
        val labels = suspendCancellableCoroutine { cont ->
            labeler().process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
        return labels.sortedByDescending { it.confidence }.take(MAX_LABELS)
    }
}

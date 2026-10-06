package com.appricodes.lens_whisper.offline

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.appricodes.lens_whisper.BuildConfig
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.segmenter.client.SegmenterClient
import com.appricodes.lens_whisper.segmenter.client.SegmenterContract
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import java.io.File

/**
 * The optional companion app, "LensWhisper Object Explorer" (package [SegmenterContract.PACKAGE],
 * a separate AGPL-3.0 app bundling YOLOv8m-seg). When it is installed *and* signed with one of
 * [trustedCerts], [detect] has it segment the photo — more accurate than the bundled YOLACT — and
 * returns null otherwise, so [OfflineSegmentationDetector] falls back to YOLACT.
 *
 * Photos only ever go to the genuine app: [SegmenterClient] checks the installed package's signing
 * certificate before binding and again once connected, binds by explicit package, and passes the
 * photo as a read-only file descriptor over that one Binder connection — no shared file, no URI any
 * other app could open. An empty certificate list fails closed.
 *
 * The two apps only exchange an image and a list of labelled masks through the companion's
 * public API (see [API_GUIDE_URL]), the same API any other app may use.
 */
object CompanionSegmenter {

    private const val TAG = "CompanionSegmenter"

    const val API_GUIDE_URL = "https://github.com/appricodes/LensWhisper-Segmenter/blob/main/api/API.md"
    private const val PLAY_URL = "https://play.google.com/store/apps/details?id=${SegmenterContract.PACKAGE}"

    /** The Android debug keystore on the development machine, so debug builds of the two apps
     *  recognise each other. Never trusted by a release build. */
    private const val DEBUG_CERT_SHA256 = "8AA3AD16A80E33DA89A27AD6BD4A31826A680EDA913CE1CC5DAF04B035266273"

    private val trustedCerts: Set<String> =
        if (BuildConfig.DEBUG) SegmenterContract.RELEASE_CERT_SHA256 + DEBUG_CERT_SHA256 else SegmenterContract.RELEASE_CERT_SHA256

    private fun client(context: Context) = SegmenterClient(context, trustedCerts)

    /** Installed and genuinely ours. Cheap enough to check before every use. */
    fun isAvailable(context: Context): Boolean = client(context).isAvailable()

    private fun isInstalledAtAll(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SegmenterContract.PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Segments [bitmap] with the companion, or returns null (not installed, untrusted, busy,
     * failed, timed out) so the caller can use YOLACT instead. Blocking; call off the main thread.
     */
    fun detect(context: Context, bitmap: Bitmap): List<DetectedInstance>? {
        if (!isAvailable(context)) return null
        val file = File(context.cacheDir, "companion_${System.nanoTime()}.jpg")
        return try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val options = Bundle().apply {
                putFloat(SegmenterContract.OPTION_MIN_CONFIDENCE, DetectionPhrasing.MINIMUM_CONFIDENCE)
                putInt(SegmenterContract.OPTION_MAX_INSTANCES, OfflineSegmentationDetector.MAX_DETECTIONS)
            }
            val (status, objects) = client(context).segment(file, options) ?: return null
            if (status != SegmenterContract.STATUS_OK) {
                Log.w(TAG, "Companion returned status $status")
                return null
            }
            objects.map {
                DetectedInstance(
                    label = it.label,
                    confidence = it.confidence,
                    boxLeft = it.box[0], boxTop = it.box[1], boxRight = it.box[2], boxBottom = it.box[3],
                    mask = it.mask, maskWidth = it.maskWidth, maskHeight = it.maskHeight
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Companion segmentation failed, using the built-in detector", e)
            null
        } finally {
            file.delete()
        }
    }

    /**
     * Before the first segmentation task: if the companion isn't installed and this has never been
     * shown, shows [showDialog] and returns true — the caller then skips this run, so the result
     * isn't spoken over the dialog. Returns false (carry on) every other time.
     */
    fun suggestOnce(context: Context): Boolean {
        val prefs = PreferencesManager(context)
        if (prefs.companionSuggestionShown || isAvailable(context)) return false
        val activity = context.findActivity() ?: return false
        prefs.companionSuggestionShown = true
        showDialog(activity, firstTime = true)
        return true
    }

    /** "Enhance Object Detection": what the companion is, whether it's in use, and how to get it.
     *  Shown from the More menu, and once by [suggestOnce]. */
    fun showDialog(activity: Activity, firstTime: Boolean = false) {
        val builder = AlertDialog.Builder(activity).setTitle("Enhance Object Detection")
        when {
            isAvailable(activity) -> builder
                .setMessage(
                    "LensWhisper Object Explorer is installed, and LensWhisper is using it for Identify Objects " +
                        "and Colors, Explore Photo by Touch, Describe TV Image and Explore and Describe.\n\n" +
                        "It runs on your phone and has no internet access."
                )
                .setPositiveButton("OK", null)
            isInstalledAtAll(activity) -> builder
                .setMessage(
                    "An app called LensWhisper Object Explorer is installed, but it is not signed by " +
                        "LensWhisper's developer, so LensWhisper does not send it any photos and uses its own " +
                        "detector instead. To use the real one, uninstall this copy and install it from Google Play."
                )
                .setPositiveButton("OK", null)
            else -> builder
                .setMessage(
                    (if (firstTime) "Before this first object search: " else "") +
                        "LensWhisper Object Explorer is a free companion app that recognises objects and their " +
                        "exact outlines more accurately than the detector built into LensWhisper. Once it is " +
                        "installed, LensWhisper uses it automatically for Identify Objects and Colors, Explore " +
                        "Photo by Touch, Describe TV Image and Explore and Describe.\n\n" +
                        "It runs entirely on your phone and has no internet access. You can install it later " +
                        "from Enhance Object Detection in the More menu."
                )
                .setPositiveButton("Install") { _, _ -> openPlayListing(activity) }
                .setNegativeButton(if (firstTime) "Not now" else "Close", null)
        }
        builder.setNeutralButton("Developer API guide") { _, _ -> openUrl(activity, API_GUIDE_URL) }
        builder.show()
    }

    private fun openPlayListing(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${SegmenterContract.PACKAGE}")))
        } catch (e: ActivityNotFoundException) {
            openUrl(context, PLAY_URL)
        }
    }

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            AccessibilityUtils.announceToTalkBack(context, "No browser found. The address is $url")
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

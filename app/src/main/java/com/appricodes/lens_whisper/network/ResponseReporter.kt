package com.appricodes.lens_whisper.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.appricodes.lens_whisper.BuildConfig
import com.appricodes.lens_whisper.ui.analysis.ReportableResponse
import com.appricodes.lens_whisper.utils.ImageScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Sends a user's report about an on-device model's answer to the developer — Google Play requires
 * a way to flag offensive AI-generated content from inside the app. The server script
 * (`server/lens_whisper/report.php`, not in the repository) forwards it by email.
 *
 * Only ever called from the Report dialog, after the user pressed Send: what goes is exactly what
 * that dialog says — the question the model was asked, the model's name, its answer, the user's
 * message, the app version, and the photo only if they ticked the box.
 */
object ResponseReporter {

    private const val TAG = "ResponseReporter"
    private const val ENDPOINT = "https://wikiponder.com/lens_whisper/report.php"

    /** Shared hosts commonly cap uploads at a few MB; this keeps a photo well under that while
     *  still showing what the model saw. */
    private const val PHOTO_MAX_DIM = 1280
    private const val PHOTO_JPEG_QUALITY = 80

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Whether [report]'s photo can still be sent — a caller may have recycled it meanwhile. */
    fun hasPhoto(report: ReportableResponse): Boolean = report.image?.isRecycled == false

    /** True only when the server confirmed it passed the report on. */
    suspend fun send(context: Context, report: ReportableResponse, message: String, includePhoto: Boolean): Boolean {
        val photo = if (includePhoto && hasPhoto(report)) {
            withContext(Dispatchers.Default) { runCatching { jpeg(report.image!!) }.getOrNull() }
        } else null

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
            .addFormDataPart("model_id", report.modelId)
            .addFormDataPart("model_name", report.modelName)
            .addFormDataPart("prompt", report.userPrompt)
            .addFormDataPart("system_instruction", report.systemInstruction)
            .addFormDataPart("response", report.response)
            .addFormDataPart("message", message)
            .apply {
                if (photo != null) addFormDataPart("photo", "photo.jpg", photo.toRequestBody("image/jpeg".toMediaType()))
            }
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(ENDPOINT).post(body).build()).execute().use { response ->
                    val text = response.body.string()
                    response.isSuccessful && runCatching { JSONObject(text).optBoolean("ok") }.getOrDefault(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Report not sent", e)
                false
            }
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val scaled = ImageScaling.scaleToMax(bitmap, PHOTO_MAX_DIM)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray()
        }
    }
}

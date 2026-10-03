package com.appricodes.lens_whisper.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.PromptConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for a user's own LensWhisper Desktop companion app on the local network (see
 * `desktop/`), which serves the `/process.php` contract this class speaks.
 *
 * This used to double as the client for a hosted cloud backend, with [baseUrl] defaulting to it.
 * That backend is gone — no image this app handles leaves the device any more — so [baseUrl] is
 * now mandatory and always a LAN address, every request/response body is always encrypted (see
 * [LocalServerAuth]), and the credit fields the cloud responses used to carry are ignored.
 *
 * Currently unreachable: [LocalServerFeature] gates every entry point and is switched off.
 *
 * @param baseUrl e.g. "http://192.168.1.42:8765", from [LocalServerDiscovery].
 */
class LocalServerEngine(private val baseUrl: String) {

	private val client = OkHttpClient.Builder()
		.connectTimeout(60, TimeUnit.SECONDS)
		// Local models on modest CPU hardware are slow; this is not a network-latency budget.
		.readTimeout(180, TimeUnit.SECONDS)
		.writeTimeout(60, TimeUnit.SECONDS)
		.build()

	/** Local Server mode requires a password (matching the desktop server's own refusal to start
	 *  without one) — every request/response is encrypted with the token derived from it, so
	 *  without one there's no key to encrypt with in the first place. Returns a user-facing
	 *  "ERROR: ..." message when blocked, or null when it's fine to proceed. */
	private fun blockedByMissingLocalServerPassword(prefs: PreferencesManager): String? =
		if (prefs.localServerPasswordToken.isBlank())
			"ERROR: Set a Local Server password in Settings before using Local Server mode."
		else null

	/** Builds the outgoing `/process.php` body: always AES-256-GCM encrypted raw bytes (see
	 *  [LocalServerAuth]). The plain-JSON alternative existed only for the cloud backend. */
	private fun buildRequestBody(payload: JSONObject, prefs: PreferencesManager): RequestBody {
		val key = LocalServerAuth.keyFor(prefs.localServerPasswordToken)
		return LocalServerAuth.encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
			.toRequestBody("application/octet-stream".toMediaType())
	}

	private fun requestContentType(): String = "application/octet-stream"

	/** Decrypts [rawBytes] with the stored Local Server token. Returns null only when decryption
	 *  itself fails - a wrong/mismatched password - never merely because the body happens to be
	 *  empty. */
	private fun decryptResponseBody(rawBytes: ByteArray, prefs: PreferencesManager): String? {
		val key = LocalServerAuth.keyFor(prefs.localServerPasswordToken)
		return LocalServerAuth.decrypt(key, rawBytes)?.toString(Charsets.UTF_8)
	}

	/** Shared by [callBackendApi] and [callBackendApiStreaming] so the two request payloads can
	 *  never drift apart on field names/values — the only difference between them is [streaming]. */
	private fun buildPayload(base64Images: List<String>, config: PromptConfig, prefs: PreferencesManager, streaming: Boolean): JSONObject =
		JSONObject().apply {
			// No identity of any kind is sent. The account/install fields this payload used to
			// carry existed only for the cloud backend's credit metering, which no longer exists.
			put("prompt_id", config.id)
			put("language", prefs.selectedLanguage)
			put("persona", prefs.selectedMode)
			put("quality", prefs.analysisQuality.lowercase())
			put("streaming", streaming)
			if (config.id == "custom") put("custom_question", config.prompt)
			val framesArray = JSONArray()
			base64Images.forEach { framesArray.put(it) }
			put("frames", framesArray)
		}

	private fun scaleToMax448(bitmap: Bitmap): Bitmap {
		val maxDim = 448f
		val scale = minOf(maxDim / bitmap.width, maxDim / bitmap.height)
		if (scale >= 1f) return bitmap
		return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
	}

	suspend fun analyzeStaticImageFile(
		context: Context, uri: Uri, config: PromptConfig, prefs: PreferencesManager
	): String = withContext(Dispatchers.IO) {
		Log.i("LocalServerEngine", "--- Starting Local Server Request [Type: Static Image] ---")
		try {
			val baseBitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }

			if (baseBitmap == null) return@withContext "ERROR: Error reading image data from device."

			val scaledBitmap = scaleToMax448(baseBitmap)
			val base64Image = encodeBitmapToBase64(scaledBitmap)

			callBackendApi(listOf(base64Image), config, prefs)
		} catch (e: Exception) {
			Log.e("LensWhisperStatic", "Image pipeline error", e)
			"ERROR: Error analyzing image. ${e.localizedMessage}"
		}
	}

	suspend fun analyzeVideoFrame(
		context: Context, uri: Uri, positionMs: Long, config: PromptConfig, prefs: PreferencesManager
	): String = withContext(Dispatchers.IO) {
		Log.i("LocalServerEngine", "--- Starting Local Server Request [Type: Video Frames] ---")
		try {
			val retriever = MediaMetadataRetriever()
			val base64Images = mutableListOf<String>()
			try {
				retriever.setDataSource(context, uri)
				val intervalMs = (config.framesInterval * 1000).toLong()

				for (i in (config.framesCount - 1) downTo 0) {
					val targetTimeMs = positionMs - (i * intervalMs)
					if (targetTimeMs >= 0) {
						val frame = retriever.getFrameAtTime(targetTimeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
						if (frame != null) {
							val scaled = scaleToMax448(frame)
							base64Images.add(encodeBitmapToBase64(scaled))
						}
					}
				}
			} finally {
				// finally, not after the loop: anything thrown while reading the file would
				// otherwise skip release() and leak the retriever's native resources.
				runCatching { retriever.release() }
			}

			if (base64Images.isEmpty()) return@withContext "ERROR: Error capturing video frames."

			callBackendApi(base64Images, config, prefs)
		} catch (e: Exception) {
			Log.e("LensWhisper", "API Execution Error", e)
			"ERROR: Error processing video frame. ${e.localizedMessage}"
		}
	}

	suspend fun analyzeMultipleStaticImages(
		context: Context, uris: List<Uri>, config: PromptConfig, prefs: PreferencesManager
	): String = withContext(Dispatchers.IO) {
		Log.i("LocalServerEngine", "--- Starting Local Server Request [Type: Camera Story Frames] ---")
		try {
			val base64Images = mutableListOf<String>()
			for (uri in uris) {
				val baseBitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
				if (baseBitmap != null) {
					val scaledBitmap = scaleToMax448(baseBitmap)
					base64Images.add(encodeBitmapToBase64(scaledBitmap))
				}
			}

			if (base64Images.isEmpty()) return@withContext "ERROR: Error capturing timeline frames."

			callBackendApi(base64Images, config, prefs)
		} catch (e: Exception) {
			Log.e("LensWhisper", "API Execution Error", e)
			"ERROR: Error processing images. ${e.localizedMessage}"
		}
	}

	/** Streaming counterparts to [analyzeStaticImageFile]/[analyzeVideoFrame]/[analyzeMultipleStaticImages]
	 *  — emit the response sentence-fragment by sentence-fragment as the server relays it (see
	 *  desktop/README.md's curl examples for the NDJSON wire format — one `{"delta":...}` line per
	 *  fragment, then `{"done":...}` — that /process.php speaks when `streaming: true` is sent). Used whenever a streaming call is offered at all now — the
	 *  Settings toggle this used to be gated behind (`PreferencesManager.streamingEnabled`) is
	 *  gone; streaming is unconditional wherever it's available. */
	fun analyzeStaticImageFileStreaming(
		context: Context, uri: Uri, config: PromptConfig, prefs: PreferencesManager
	): Flow<String> = flow {
		val baseBitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
		if (baseBitmap == null) throw IllegalStateException("Error reading image data from device.")
		val base64Image = encodeBitmapToBase64(scaleToMax448(baseBitmap))
		emitAll(callBackendApiStreaming(listOf(base64Image), config, prefs))
	}.flowOn(Dispatchers.IO)

	fun analyzeVideoFrameStreaming(
		context: Context, uri: Uri, positionMs: Long, config: PromptConfig, prefs: PreferencesManager
	): Flow<String> = flow {
		val retriever = MediaMetadataRetriever()
		val base64Images = mutableListOf<String>()
		try {
			retriever.setDataSource(context, uri)
			val intervalMs = (config.framesInterval * 1000).toLong()
			for (i in (config.framesCount - 1) downTo 0) {
				val targetTimeMs = positionMs - (i * intervalMs)
				if (targetTimeMs >= 0) {
					val frame = retriever.getFrameAtTime(targetTimeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
					if (frame != null) base64Images.add(encodeBitmapToBase64(scaleToMax448(frame)))
				}
			}
		} finally {
			// Same reasoning as analyzeVideoFrame above — release on every path, not just the
			// successful one.
			runCatching { retriever.release() }
		}
		if (base64Images.isEmpty()) throw IllegalStateException("Error capturing video frames.")
		emitAll(callBackendApiStreaming(base64Images, config, prefs))
	}.flowOn(Dispatchers.IO)

	fun analyzeMultipleStaticImagesStreaming(
		context: Context, uris: List<Uri>, config: PromptConfig, prefs: PreferencesManager
	): Flow<String> = flow {
		val base64Images = mutableListOf<String>()
		for (uri in uris) {
			val baseBitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
			if (baseBitmap != null) base64Images.add(encodeBitmapToBase64(scaleToMax448(baseBitmap)))
		}
		if (base64Images.isEmpty()) throw IllegalStateException("Error capturing timeline frames.")
		emitAll(callBackendApiStreaming(base64Images, config, prefs))
	}.flowOn(Dispatchers.IO)

	/**
	 * Opens the streaming request (`streaming: true` in the payload) and reads process.php's
	 * NDJSON response line by line, emitting each `{"delta": "..."}` line's text as it arrives.
	 * `{"done": ...}` updates [prefs]' credit balance (mirroring [callBackendApi]'s non-streaming
	 * update) and ends the flow normally; `{"error": ...}` or the connection closing before either
	 * of those arrives throws — the caller (`AnalysisSessionController`) treats that the same way
	 * as any other analysis failure, whether or not some deltas already went out.
	 */
	private fun callBackendApiStreaming(
		base64Images: List<String>, config: PromptConfig, prefs: PreferencesManager
	): Flow<String> = flow {
		blockedByMissingLocalServerPassword(prefs)?.let { throw IllegalStateException(it.removePrefix("ERROR:").trim()) }

		val payload = buildPayload(base64Images, config, prefs, streaming = true)

		val request = Request.Builder()
			.url("$baseUrl/process.php")
			.addHeader("Content-Type", requestContentType())
			.post(buildRequestBody(payload, prefs))
			.build()

		try {
			client.newCall(request).execute().use { response ->
				val body = response.body
				if (!response.isSuccessful) {
					// Same shape as callBackendApi's non-streaming error responses (400/401/403/405/502) —
					// those never switch into NDJSON mode server-side, so this is still a single JSON object.
					val errText = body?.bytes()?.let { decryptResponseBody(it, prefs) }
					val errJson = errText?.let { runCatching { JSONObject(it) }.getOrNull() }
					val errMsg = errJson?.optString("model_response")?.takeIf { it.isNotBlank() }
						?: errJson?.optString("message")?.takeIf { it.isNotBlank() }
						?: if (errText == null) "Could not securely read the local server's response. Check that its password matches yours."
						else "Server error code ${response.code}"
					throw IllegalStateException(errMsg)
				}

				val source = body.source()
				var finished = false
				while (!source.exhausted()) {
					val line = source.readUtf8Line() ?: break
					if (line.isBlank()) continue
					// Each line is base64(AES-GCM(IV || ciphertext)) — decode+decrypt before it can
					// be parsed as JSON. A decrypt failure here means the phone's and the server's
					// passwords have drifted apart (e.g. changed on one side only) — surfaced the
					// same way as any other streaming failure, not silently skipped like a
					// blank/unparseable line.
					val decryptedLine = run {
						val key = LocalServerAuth.keyFor(prefs.localServerPasswordToken)
						val raw = runCatching { Base64.decode(line, Base64.NO_WRAP) }.getOrNull()
						val plaintext = raw?.let { LocalServerAuth.decrypt(key, it) }
							?: throw IllegalStateException("Could not securely read the local server's response. Check that its password matches yours.")
						plaintext.toString(Charsets.UTF_8)
					}
					val json = runCatching { JSONObject(decryptedLine) }.getOrNull() ?: continue
					when {
						json.has("error") -> throw IllegalStateException(json.optString("model_response", "Streaming request failed."))
						// Any credit fields the server sends are ignored — the desktop server emits
						// dummy values for contract compatibility and nothing meters usage any more.
						json.has("done") -> finished = true
						json.has("delta") -> {
							val delta = json.optString("delta")
							if (delta.isNotEmpty()) {
								// Verifies this is genuinely streaming, not one lump at the end —
								// compare consecutive timestamps of this exact line in logcat.
								Log.d("LocalServerEngine", "STREAM[local/${config.id}] chunk (${delta.length} chars): ${delta.take(60).replace("\n", "\\n")}")
								emit(delta)
							}
						}
					}
				}
				if (!finished) throw IllegalStateException("Connection closed before the response finished.")
			}
		} catch (e: IOException) {
			throw IllegalStateException("Network connection failed. Please check internet access or your signal strength. (${e.localizedMessage})", e)
		}
	}.flowOn(Dispatchers.IO)

	private fun encodeBitmapToBase64(bitmap: Bitmap): String {
		val outputStream = ByteArrayOutputStream()
		bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
		return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
	}

	private fun callBackendApi(
		base64Images: List<String>, config: PromptConfig, prefs: PreferencesManager
	): String {
		blockedByMissingLocalServerPassword(prefs)?.let { return it }
		try {
			val payload = buildPayload(base64Images, config, prefs, streaming = false)

			val request = Request.Builder()
				.url("$baseUrl/process.php")
				.addHeader("Content-Type", requestContentType())
				.post(buildRequestBody(payload, prefs))
				.build()

			client.newCall(request).execute().use { response ->
				val rawBytes = response.body?.bytes()
				val responseBodyString = rawBytes?.let { decryptResponseBody(it, prefs) }

				if (!response.isSuccessful || responseBodyString.isNullOrBlank()) {
					val errJson = responseBodyString?.let { runCatching { JSONObject(it) }.getOrNull() }
					val errMsg = errJson?.optString("model_response")
						?: errJson?.optString("message")
						?: if (rawBytes != null && responseBodyString == null)
							"Could not securely communicate with the local server. Check that its password matches yours."
						else "Server error code ${response.code}"
					return "ERROR: $errMsg"
				}

				val jsonResponse = JSONObject(responseBodyString)

				return jsonResponse.optString("model_response", "ERROR: Response mapped incorrectly.")
			}
		} catch (e: Exception) {
			return "ERROR: Network connection failed. Please check internet access or your signal strength. (${e.localizedMessage})"
		}
	}
}
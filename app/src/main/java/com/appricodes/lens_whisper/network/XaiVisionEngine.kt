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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class XaiVisionEngine {
	private val client = OkHttpClient.Builder()
		.connectTimeout(60, TimeUnit.SECONDS)
		.readTimeout(60, TimeUnit.SECONDS)
		.writeTimeout(60, TimeUnit.SECONDS)
		.build()

	private fun scaleToMax448(bitmap: Bitmap): Bitmap {
		val maxDim = 448f
		val scale = minOf(maxDim / bitmap.width, maxDim / bitmap.height)
		if (scale >= 1f) return bitmap
		return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
	}

	suspend fun analyzeStaticImageFile(
		context: Context, uri: Uri, config: PromptConfig, prefs: PreferencesManager
	): String = withContext(Dispatchers.IO) {
		Log.i("XaiVisionEngine", "--- Starting Backend AI Request [Type: Static Image] ---")
		try {
			val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
			val baseBitmap = BitmapFactory.decodeStream(inputStream)
			inputStream?.close()

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
		Log.i("XaiVisionEngine", "--- Starting Backend AI Request [Type: Video Frames] ---")
		try {
			val retriever = MediaMetadataRetriever()
			retriever.setDataSource(context, uri)
			val base64Images = mutableListOf<String>()
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
			retriever.release()

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
		Log.i("XaiVisionEngine", "--- Starting Backend AI Request [Type: Camera Story Frames] ---")
		try {
			val base64Images = mutableListOf<String>()
			for (uri in uris) {
				val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
				val baseBitmap = BitmapFactory.decodeStream(inputStream)
				inputStream?.close()
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
	 *  backend/readme.md's "Streaming" section for the NDJSON wire format process.php speaks when
	 *  `streaming: true` is sent). Only used when [PreferencesManager.streamingEnabled] is on; there's
	 *  deliberately no streaming variant of [analyzeFrameSpatialGrid] — Explore's structured grid JSON
	 *  isn't sentence-shaped, same reasoning as the on-device catalog never offering Explore at all. */
	fun analyzeStaticImageFileStreaming(
		context: Context, uri: Uri, config: PromptConfig, prefs: PreferencesManager
	): Flow<String> = flow {
		val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
		val baseBitmap = BitmapFactory.decodeStream(inputStream)
		inputStream?.close()
		if (baseBitmap == null) throw IllegalStateException("Error reading image data from device.")
		val base64Image = encodeBitmapToBase64(scaleToMax448(baseBitmap))
		emitAll(callBackendApiStreaming(listOf(base64Image), config, prefs))
	}.flowOn(Dispatchers.IO)

	fun analyzeVideoFrameStreaming(
		context: Context, uri: Uri, positionMs: Long, config: PromptConfig, prefs: PreferencesManager
	): Flow<String> = flow {
		val retriever = MediaMetadataRetriever()
		retriever.setDataSource(context, uri)
		val base64Images = mutableListOf<String>()
		val intervalMs = (config.framesInterval * 1000).toLong()
		for (i in (config.framesCount - 1) downTo 0) {
			val targetTimeMs = positionMs - (i * intervalMs)
			if (targetTimeMs >= 0) {
				val frame = retriever.getFrameAtTime(targetTimeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
				if (frame != null) base64Images.add(encodeBitmapToBase64(scaleToMax448(frame)))
			}
		}
		retriever.release()
		if (base64Images.isEmpty()) throw IllegalStateException("Error capturing video frames.")
		emitAll(callBackendApiStreaming(base64Images, config, prefs))
	}.flowOn(Dispatchers.IO)

	fun analyzeMultipleStaticImagesStreaming(
		context: Context, uris: List<Uri>, config: PromptConfig, prefs: PreferencesManager
	): Flow<String> = flow {
		val base64Images = mutableListOf<String>()
		for (uri in uris) {
			val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
			val baseBitmap = BitmapFactory.decodeStream(inputStream)
			inputStream?.close()
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
		val payload = JSONObject().apply {
			put("email", prefs.userEmail ?: "")
			put("google_id", prefs.googleId ?: "")
			put("install_id", prefs.installId)
			put("prompt_id", config.id)
			put("language", prefs.selectedLanguage)
			put("persona", prefs.selectedMode)
			put("quality", prefs.analysisQuality.lowercase())
			put("streaming", true)
			if (config.id == "custom") put("custom_question", config.prompt)
			val framesArray = JSONArray()
			base64Images.forEach { framesArray.put(it) }
			put("frames", framesArray)
		}

		val request = Request.Builder()
			.url("https://lw.wikiponder.com/process.php")
			.addHeader("Content-Type", "application/json")
			.post(payload.toString().toRequestBody("application/json".toMediaType()))
			.build()

		try {
			client.newCall(request).execute().use { response ->
				val body = response.body
				if (!response.isSuccessful) {
					// Same shape as callBackendApi's non-streaming error responses (400/401/403/405/502) —
					// those never switch into NDJSON mode server-side, so this is still a single JSON object.
					val errJson = runCatching { JSONObject(body.string()) }.getOrNull()
					val errMsg = errJson?.optString("model_response")?.takeIf { it.isNotBlank() }
						?: errJson?.optString("message")?.takeIf { it.isNotBlank() }
						?: "Server error code ${response.code}"
					throw IllegalStateException(errMsg)
				}

				val source = body.source()
				var finished = false
				while (!source.exhausted()) {
					val line = source.readUtf8Line() ?: break
					if (line.isBlank()) continue
					val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
					when {
						json.has("error") -> throw IllegalStateException(json.optString("model_response", "Streaming request failed."))
						json.has("done") -> {
							if (json.has("free_credits") && json.has("purchased_credits")) {
								prefs.freeCredits = json.getInt("free_credits")
								prefs.purchasedCredits = json.getInt("purchased_credits")
							}
							finished = true
						}
						json.has("delta") -> {
							val delta = json.optString("delta")
							if (delta.isNotEmpty()) {
								// Verifies this is genuinely streaming, not one lump at the end —
								// compare consecutive timestamps of this exact line in logcat.
								Log.d("XaiVisionEngine", "STREAM[online/${config.id}] chunk (${delta.length} chars): ${delta.take(60).replace("\n", "\\n")}")
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

	suspend fun analyzeFrameSpatialGrid(
		context: Context, uri: Uri, positionMs: Long, gridSize: Int, isStaticImage: Boolean, prefs: PreferencesManager
	): String = withContext(Dispatchers.IO) {
		Log.i("XaiVisionEngine", "--- Starting Backend AI Request [Type: Spatial Grid] ---")
		try {
			val bitmap = if (isStaticImage) {
				val inputStream = context.contentResolver.openInputStream(uri)
				val bmp = BitmapFactory.decodeStream(inputStream)
				inputStream?.close()
				bmp
			} else {
				val retriever = MediaMetadataRetriever()
				retriever.setDataSource(context, uri)
				val bmp = retriever.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
				retriever.release()
				bmp
			} ?: return@withContext "ERROR: Could not extract frame for grid mapping."

			val scaledBitmap = scaleToMax448(bitmap)
			val base64Image = encodeBitmapToBase64(scaledBitmap)

			val dummyConfig = PromptConfig("explore", "Explore", "", 1, 0.5)
			callBackendApi(listOf(base64Image), dummyConfig, prefs)
		} catch (e: Exception) {
			Log.e("LensWhisperExplore", "Spatial Extraction Error", e)
			"ERROR: Spatial map extraction failed. ${e.localizedMessage}"
		}
	}

	fun parseSpatialJson(jsonResponse: String, gridSize: Int): Map<String, String> {
		return runCatching {
			val sanitized = jsonResponse.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
			val rootJson = JSONObject(sanitized)
			val metricMap = mutableMapOf<String, String>()

			for (row in 1..gridSize) {
				for (col in 1..gridSize) {
					val coordinateToken = "row${row}_col${col}"
					if (rootJson.has(coordinateToken)) {
						metricMap[coordinateToken] = rootJson.getString(coordinateToken)
					}
				}
			}
			metricMap
		}.getOrElse { emptyMap() }
	}

	private fun encodeBitmapToBase64(bitmap: Bitmap): String {
		val outputStream = ByteArrayOutputStream()
		bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
		return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
	}

	private fun callBackendApi(
		base64Images: List<String>, config: PromptConfig, prefs: PreferencesManager
	): String {
		try {
			val payload = JSONObject().apply {
				put("email", prefs.userEmail ?: "")
				put("google_id", prefs.googleId ?: "")
				// Anonymous identity — the only one currently reachable, since login is
				// disconnected (see LOGIN_AND_PURCHASES_REMOVED.md). process.php falls back to
				// this whenever email/google_id above are blank.
				put("install_id", prefs.installId)
				put("prompt_id", config.id)
				put("language", prefs.selectedLanguage)
				put("persona", prefs.selectedMode)

				// Added quality preference to determine model cost and routing
				put("quality", prefs.analysisQuality.lowercase())

				if (config.id == "custom") {
					put("custom_question", config.prompt)
				}

				val framesArray = JSONArray()
				base64Images.forEach { framesArray.put(it) }
				put("frames", framesArray)
			}

			val request = Request.Builder()
				.url("https://lw.wikiponder.com/process.php")
				.addHeader("Content-Type", "application/json")
				.post(payload.toString().toRequestBody("application/json".toMediaType()))
				.build()

			client.newCall(request).execute().use { response ->
				val responseBodyString = response.body?.string()

				if (!response.isSuccessful || responseBodyString.isNullOrBlank()) {
					val errJson = responseBodyString?.let { runCatching { JSONObject(it) }.getOrNull() }
					val errMsg = errJson?.optString("model_response")
						?: errJson?.optString("message")
						?: "Server error code ${response.code}"
					return "ERROR: $errMsg"
				}

				val jsonResponse = JSONObject(responseBodyString)

				// Update local credits
				if (jsonResponse.has("free_credits") && jsonResponse.has("purchased_credits")) {
					prefs.freeCredits = jsonResponse.getInt("free_credits")
					prefs.purchasedCredits = jsonResponse.getInt("purchased_credits")
				}

				return jsonResponse.optString("model_response", "ERROR: Response mapped incorrectly.")
			}
		} catch (e: Exception) {
			return "ERROR: Network connection failed. Please check internet access or your signal strength. (${e.localizedMessage})"
		}
	}
}
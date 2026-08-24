package com.appricodes.lenswhisper.desktop.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Calls a llama-server instance's OpenAI-compatible `/v1/chat/completions` with an image+text
 * prompt, matching the request shape backend/process.php uses: a single user message with the
 * text part first, then one image_url part per frame (each tagged `detail: "low"` when there's
 * more than one frame). */
class AnalysisService(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** [frameJpegBase64] are raw base64-encoded JPEG bytes (no data: prefix). */
    fun analyze(
        systemInstruction: String,
        userPrompt: String,
        frameJpegBase64: List<String>,
        maxTokens: Int = 400,
        temperature: Double = 0.7,
        expectJson: Boolean = false,
    ): String {
        val requestBody = buildRequestJson(systemInstruction, userPrompt, frameJpegBase64, maxTokens, temperature, stream = false, expectJson = expectJson)
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("llama-server returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }
        val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), response.body())
        return parsed.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("llama-server response had no message content: ${response.body().take(500)}")
    }

    /** Streams response deltas as they arrive over SSE. Emits raw text fragments; the caller
     * concatenates them for the full response. */
    fun analyzeStreaming(
        systemInstruction: String,
        userPrompt: String,
        frameJpegBase64: List<String>,
        maxTokens: Int = 400,
        temperature: Double = 0.7,
    ): Flow<String> = flow {
        val requestBody = buildRequestJson(systemInstruction, userPrompt, frameJpegBase64, maxTokens, temperature, stream = true, expectJson = false)
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().bufferedReader().use(BufferedReader::readText)
            throw RuntimeException("llama-server returned HTTP ${response.statusCode()}: ${errorBody.take(500)}")
        }
        response.body().bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                val chunk = json.decodeFromString(ChatCompletionResponse.serializer(), payload)
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestJson(
        systemInstruction: String,
        userPrompt: String,
        frameJpegBase64: List<String>,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean,
        expectJson: Boolean,
    ): String {
        val isMultiFrame = frameJpegBase64.size > 1
        // Text part first, then one image_url part per frame — same order as backend/process.php.
        val userContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", userPrompt)
            })
            for (frame in frameJpegBase64) {
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64,$frame")
                        if (isMultiFrame) put("detail", "low")
                    })
                })
            }
        }
        val requestObj = buildJsonObject {
            put("model", "local")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", JsonPrimitive(systemInstruction))
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", stream)
            if (expectJson) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        return requestObj.toString()
    }
}

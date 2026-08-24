package com.appricodes.lenswhisper.desktop.server

import com.appricodes.lenswhisper.desktop.analysis.AnalysisService
import com.appricodes.lenswhisper.desktop.prompts.PromptBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/** Everything a request handler needs: the running llama-server's chat endpoint and which model
 * is loaded, so /health can report it. */
class ServerContext(
    val analysisService: AnalysisService,
    val modelId: String,
    val isEngineReady: () -> Boolean,
)

// Mirrors backend/process.php's persona allowlist exactly (case-sensitive).
private val ALLOWED_PERSONAS = setOf(
    "Neutral", "Family", "Childish", "Technical", "explicit", "explicit and naughty", "Humorous", "poetry"
)

private const val ERR_COMMUNICATING = "Error communicating with AI server. Please try again."

// encodeDefaults=true here too — StreamDoneLine/StreamErrorLine are built with all-default field
// values in the common case, which would otherwise serialize to "{}".
private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

fun Application.configureServer(context: ServerContext) {
    install(ContentNegotiation) {
        // encodeDefaults=true: PHP's json_response always includes error/cost/credit fields even
        // when they hold their "default" value, and the app's parsing may rely on their presence.
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ProcessErrorResponse(model_response = "Method not allowed. Please use POST."))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ProcessErrorResponse(model_response = "Not found."))
        }
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = if (context.isEngineReady()) "ok" else "starting",
                    model = context.modelId,
                    engine_ready = context.isEngineReady(),
                )
            )
        }

        post("/process.php") {
            val rawBody = call.receiveText()
            val request = try {
                json.decodeFromString(ProcessRequest.serializer(), rawBody)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Invalid JSON payload received."))
                return@post
            }

            val persona = request.persona.ifBlank { "Neutral" }
            if (persona !in ALLOWED_PERSONAS) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Invalid persona mode selected."))
                return@post
            }

            val frames = request.frames
            if (frames.isEmpty() || frames.size > 5) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Invalid frame count. You must send between 1 and 5 frames."))
                return@post
            }
            if (frames.any { it.length > 135_000 }) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Media resolution too high. Please try again."))
                return@post
            }

            val promptId = (request.prompt_id ?: "").filter { it.isLetterOrDigit() || it == '_' }
            val language = request.language.ifBlank { "en-US" }
            val isMultiFrame = frames.size > 1
            val customQuestion = (request.custom_question ?: "").take(200)

            val spec = PromptBuilder.buildPromptSpec(promptId, language, persona, customQuestion, isMultiFrame)
            val systemInstruction = spec.systemInstruction
            val userPrompt = spec.userPrompt
            val expectJson = spec.expectJson
            val streaming = request.streaming && !spec.forceNonStreaming

            if (streaming) {
                call.respondTextWriter(ContentType.parse("application/x-ndjson; charset=utf-8")) {
                    try {
                        context.analysisService.analyzeStreaming(systemInstruction, userPrompt, frames)
                            .collect { delta ->
                                write(json.encodeToString(StreamDeltaLine.serializer(), StreamDeltaLine(delta = delta)))
                                write("\n")
                                flush()
                            }
                        write(json.encodeToString(StreamDoneLine.serializer(), StreamDoneLine()))
                        write("\n")
                        flush()
                    } catch (e: Exception) {
                        // Headers are already committed as 200 for an ndjson stream — same as
                        // process.php, any failure (before or mid-stream) becomes a single
                        // {"error":...} line instead of an HTTP error status, and we stop there
                        // without writing a "done" line.
                        write(json.encodeToString(StreamErrorLine.serializer(), StreamErrorLine(model_response = ERR_COMMUNICATING)))
                        write("\n")
                        flush()
                    }
                }
            } else {
                try {
                    val result = context.analysisService.analyze(systemInstruction, userPrompt, frames, expectJson = expectJson)
                    call.respond(ProcessResponse(model_response = result))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ProcessErrorResponse(model_response = ERR_COMMUNICATING))
                }
            }
        }
    }
}

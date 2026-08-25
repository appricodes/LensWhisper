package com.appricodes.lenswhisper.desktop.server

import com.appricodes.lenswhisper.desktop.analysis.AnalysisService
import com.appricodes.lenswhisper.desktop.config.AppConfig
import com.appricodes.lenswhisper.desktop.config.AuthConfig
import com.appricodes.lenswhisper.desktop.config.OsFamily
import com.appricodes.lenswhisper.desktop.gui.AppController
import com.appricodes.lenswhisper.desktop.gui.DownloadCoordinator
import com.appricodes.lenswhisper.desktop.llama.Backend
import com.appricodes.lenswhisper.desktop.llama.LlamaBinaries
import com.appricodes.lenswhisper.desktop.models.ModelCatalog
import com.appricodes.lenswhisper.desktop.models.ModelDownloader
import com.appricodes.lenswhisper.desktop.prompts.PromptBuilder
import com.appricodes.lenswhisper.desktop.requireArtifacts
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Everything a request handler needs: the running llama-server's chat endpoint and which model
 * is loaded, so /health can report it. Used by the CLI's `serve` command, which starts exactly
 * one model before Ktor even starts and never changes it. */
class ServerContext(
    val analysisService: AnalysisService,
    val modelId: String,
    val isEngineReady: () -> Boolean,
    val backend: String,
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
    installCommonStatusPages()

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = if (context.isEngineReady()) "ok" else "starting",
                    model = context.modelId,
                    engine_ready = context.isEngineReady(),
                    backend = context.backend,
                )
            )
        }

        post("/process.php") {
            handleProcessRequest(call, context.analysisService)
        }
    }
}

/** internal, not private: shared with `Main.kt`'s `cmdServe` so both entry points refuse the same
 * way when no password is configured yet — a Local Server has required one since the encrypted
 * transport landed (see [AuthConfig]'s doc comment). */
internal const val PASSWORD_REQUIRED_MESSAGE =
    "A password is required before this server can start (analysis requests are encrypted with it). Set one first."

/** GUI variant: Ktor starts immediately (so the control panel is reachable) — no model is
 * guaranteed running yet, and which model/backend is "current" can change over the process's
 * lifetime as the user starts/stops servers from the page. Every request reads live state from
 * [controller] instead of closing over one fixed AnalysisService. */
fun Application.configureControlPanelServer(controller: AppController, coordinator: DownloadCoordinator, dataDir: Path) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    installCommonStatusPages()

    routing {
        staticResources("/", "static", index = "index.html")

        get("/health") {
            val s = controller.status()
            call.respond(
                HealthResponse(
                    status = if (s.running) "ok" else "starting",
                    model = s.modelId ?: "",
                    engine_ready = s.running,
                    backend = s.backend ?: "",
                )
            )
        }

        post("/process.php") {
            handleProcessRequest(call, controller.analysisServiceOrNull()) { success, durationMs, promptTokens, completionTokens ->
                if (success) controller.stats.recordSuccess(durationMs, promptTokens, completionTokens)
                else controller.stats.recordFailure()
            }
        }

        get("/api/models") {
            val modelsDir = AppConfig.modelsDir(dataDir)
            call.respond(ModelCatalog.all().map {
                ModelListItem(
                    id = it.id,
                    displayName = it.displayName,
                    description = it.description,
                    modelSizeBytes = it.modelSizeBytes,
                    mmprojSizeBytes = it.mmprojSizeBytes,
                    downloaded = ModelDownloader.isDownloaded(it, modelsDir),
                )
            })
        }

        post("/api/models/{id}/download") {
            val id = call.parameters["id"] ?: ""
            val spec = ModelCatalog.byId(id)
            if (spec == null) {
                call.respond(HttpStatusCode.NotFound, ProcessErrorResponse(model_response = "Unknown model id '$id'."))
                return@post
            }
            val req = try {
                json.decodeFromString(DownloadRequest.serializer(), call.receiveText().ifBlank { "{}" })
            } catch (e: Exception) {
                DownloadRequest()
            }
            val backend = if (req.backend == "gpu") Backend.GPU else Backend.CPU
            val started = coordinator.start(spec, backend)
            if (!started) {
                call.respond(HttpStatusCode.Conflict, ProcessErrorResponse(model_response = "A download for this model is already in progress."))
                return@post
            }
            call.respond(HttpStatusCode.Accepted, DownloadStatusResponse(state = "downloading"))
        }

        get("/api/models/{id}/download/status") {
            val id = call.parameters["id"] ?: ""
            val s = coordinator.statusOf(id)
            call.respond(DownloadStatusResponse(s.state, s.currentFile, s.bytesDownloaded, s.totalBytes, s.error))
        }

        get("/api/gpu/probe") {
            call.respond(probeGpu(dataDir))
        }

        get("/api/auth/status") {
            call.respond(AuthStatusResponse(enabled = AuthConfig.isEnabled()))
        }

        post("/api/auth/set") {
            val req = try {
                json.decodeFromString(SetPasswordRequest.serializer(), call.receiveText())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Invalid request."))
                return@post
            }
            if (req.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Password can't be empty."))
                return@post
            }
            AuthConfig.setPassword(req.password)
            call.respond(AuthStatusResponse(enabled = true))
        }

        post("/api/auth/clear") {
            if (controller.status().running) {
                call.respond(HttpStatusCode.Conflict, ProcessErrorResponse(model_response = "Stop the server before removing its password — a running server always requires one."))
                return@post
            }
            AuthConfig.clearPassword()
            call.respond(AuthStatusResponse(enabled = false))
        }

        post("/api/server/start") {
            if (!AuthConfig.isEnabled()) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = PASSWORD_REQUIRED_MESSAGE))
                return@post
            }
            val req = try {
                json.decodeFromString(StartServerRequest.serializer(), call.receiveText())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Invalid request."))
                return@post
            }
            val spec = ModelCatalog.byId(req.modelId)
            if (spec == null) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = "Unknown model id '${req.modelId}'."))
                return@post
            }
            val backend = if (req.backend == "gpu") Backend.GPU else Backend.CPU

            try {
                requireArtifacts(dataDir, spec, gpuRequested = backend == Backend.GPU)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ProcessErrorResponse(model_response = e.message ?: "Required files are missing."))
                return@post
            }

            val logFile = AppConfig.logsDir(dataDir).resolve("llama-server.log")
            try {
                withContext(Dispatchers.IO) { controller.start(spec, backend, logFile, reasoningBudget = req.reasoningBudget) }
                val s = controller.status()
                call.respond(StartServerResponse(modelId = s.modelId ?: spec.id, backend = s.backend ?: "cpu", port = s.port ?: -1))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ProcessErrorResponse(model_response = e.message ?: "A server is already running."))
            } catch (e: Exception) {
                // The real failure reason (e.g. no compatible GPU driver) — never silently
                // falls back to CPU here, unlike the CLI's launchServer.
                call.respond(HttpStatusCode.BadGateway, ProcessErrorResponse(model_response = e.message ?: "Failed to start server."))
            }
        }

        post("/api/server/stop") {
            val wasRunning = controller.status().running
            withContext(Dispatchers.IO) { controller.stop() }
            call.respond(StopServerResponse(stopped = wasRunning))
        }

        get("/api/status") {
            val s = controller.status()
            val stats = controller.stats.snapshot()
            call.respond(
                StatusResponse(
                    running = s.running,
                    modelId = s.modelId,
                    modelDisplayName = s.modelDisplayName,
                    backend = s.backend,
                    reasoningBudget = s.reasoningBudget,
                    uptimeMs = s.uptimeMs,
                    enginePort = s.port,
                    stats = StatsDto(
                        requestsSucceeded = stats.requestsSucceeded,
                        requestsFailed = stats.requestsFailed,
                        totalGenerationMs = stats.totalGenerationMs,
                        totalPromptTokens = stats.totalPromptTokens,
                        totalCompletionTokens = stats.totalCompletionTokens,
                        tokensPerSecond = stats.tokensPerSecond,
                    ),
                )
            )
        }

        post("/api/quit") {
            call.respond(StopServerResponse(stopped = controller.status().running))
            Thread {
                Thread.sleep(300)
                controller.stop()
                exitProcess(0)
            }.apply { isDaemon = true; start() }
        }
    }
}

private fun Application.installCommonStatusPages() {
    install(StatusPages) {
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ProcessErrorResponse(model_response = "Method not allowed. Please use POST."))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ProcessErrorResponse(model_response = "Not found."))
        }
    }
}

/** Shared /process.php handler — identical validation/streaming logic for both the CLI's `serve`
 * (via [ServerContext]) and the GUI's control panel (via [AppController], where [service] can be
 * null if nothing's running yet). [onResult] lets the GUI record stats; the CLI path ignores it
 * (default no-op).
 *
 * Every request body and response body here is AES-256-GCM encrypted with [AuthConfig]'s current
 * key (see its doc comment) — there's no separate "check a header" step any more; a body that
 * fails to decrypt *is* the unauthorized case, indistinguishable from a wrong password. This is
 * unconditional (not "only if a password is set") because a Local Server can no longer run
 * without one — see [PASSWORD_REQUIRED_MESSAGE] and its call sites.
 */
private suspend fun handleProcessRequest(
    call: ApplicationCall,
    service: AnalysisService?,
    onResult: (success: Boolean, durationMs: Long, promptTokens: Int, completionTokens: Int) -> Unit = { _, _, _, _ -> },
) {
    val key = AuthConfig.currentKey()
    if (key == null) {
        // Shouldn't normally be reachable — both entry points refuse to start without a password
        // — but a defensive, unencrypted fallback beats a confusing 500 if it ever is.
        call.respond(HttpStatusCode.ServiceUnavailable, ProcessErrorResponse(model_response = PASSWORD_REQUIRED_MESSAGE))
        return
    }

    suspend fun respondEncrypted(status: HttpStatusCode, bodyJson: String) {
        call.respondBytes(AuthConfig.encrypt(key, bodyJson.toByteArray(Charsets.UTF_8)), ContentType.Application.OctetStream, status)
    }
    suspend fun respondEncryptedError(status: HttpStatusCode, message: String) =
        respondEncrypted(status, json.encodeToString(ProcessErrorResponse.serializer(), ProcessErrorResponse(model_response = message)))

    val rawBody = call.receive<ByteArray>()
    val decryptedBody = AuthConfig.decrypt(key, rawBody)
    if (decryptedBody == null) {
        respondEncryptedError(HttpStatusCode.Unauthorized, "Incorrect or missing password.")
        return
    }

    val request = try {
        json.decodeFromString(ProcessRequest.serializer(), decryptedBody.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        respondEncryptedError(HttpStatusCode.BadRequest, "Invalid JSON payload received.")
        return
    }

    if (service == null) {
        respondEncryptedError(HttpStatusCode.ServiceUnavailable, "No model is currently running. Start one from the control panel.")
        return
    }

    val persona = request.persona.ifBlank { "Neutral" }
    if (persona !in ALLOWED_PERSONAS) {
        respondEncryptedError(HttpStatusCode.BadRequest, "Invalid persona mode selected.")
        return
    }

    val frames = request.frames
    if (frames.isEmpty() || frames.size > 5) {
        respondEncryptedError(HttpStatusCode.BadRequest, "Invalid frame count. You must send between 1 and 5 frames.")
        return
    }
    if (frames.any { it.length > 135_000 }) {
        respondEncryptedError(HttpStatusCode.BadRequest, "Media resolution too high. Please try again.")
        return
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
        var capturedPromptTokens = 0
        var capturedCompletionTokens = 0
        val startedAt = System.currentTimeMillis()
        call.respondTextWriter(ContentType.parse("application/x-ndjson; charset=utf-8")) {
            // Each NDJSON line is independently AES-GCM encrypted (its own random IV) and then
            // base64'd so the line-based framing survives — the plaintext newline-delimited
            // protocol underneath is otherwise unchanged from before encryption was added.
            fun writeEncryptedLine(bodyJson: String) {
                write(Base64.getEncoder().encodeToString(AuthConfig.encrypt(key, bodyJson.toByteArray(Charsets.UTF_8))))
                write("\n")
                flush()
            }
            try {
                service.analyzeStreaming(systemInstruction, userPrompt, frames, onUsage = { p, c ->
                    capturedPromptTokens = p
                    capturedCompletionTokens = c
                }).collect { delta ->
                    writeEncryptedLine(json.encodeToString(StreamDeltaLine.serializer(), StreamDeltaLine(delta = delta)))
                }
                writeEncryptedLine(json.encodeToString(StreamDoneLine.serializer(), StreamDoneLine()))
                onResult(true, System.currentTimeMillis() - startedAt, capturedPromptTokens, capturedCompletionTokens)
            } catch (e: Exception) {
                // Headers are already committed as 200 for an ndjson stream — same as
                // process.php, any failure (before or mid-stream) becomes a single
                // {"error":...} line instead of an HTTP error status, and we stop there
                // without writing a "done" line.
                writeEncryptedLine(json.encodeToString(StreamErrorLine.serializer(), StreamErrorLine(model_response = ERR_COMMUNICATING)))
                onResult(false, System.currentTimeMillis() - startedAt, 0, 0)
            }
        }
    } else {
        val startedAt = System.currentTimeMillis()
        try {
            var promptTokens = 0
            var completionTokens = 0
            val result = service.analyze(systemInstruction, userPrompt, frames, expectJson = expectJson, onUsage = { p, c ->
                promptTokens = p
                completionTokens = c
            })
            respondEncrypted(HttpStatusCode.OK, json.encodeToString(ProcessResponse.serializer(), ProcessResponse(model_response = result)))
            onResult(true, System.currentTimeMillis() - startedAt, promptTokens, completionTokens)
        } catch (e: Exception) {
            respondEncryptedError(HttpStatusCode.BadGateway, ERR_COMMUNICATING)
            onResult(false, System.currentTimeMillis() - startedAt, 0, 0)
        }
    }
}

/** Best-effort GPU hint for the UI — runs `llama-server --list-devices` on the backend-appropriate
 * binary (the Vulkan build on Windows/Linux, the standard build on macOS where Metal lives) with a
 * short timeout. Never gates the Start button — the authoritative check is the real start attempt. */
private suspend fun probeGpu(dataDir: Path): GpuProbeResponse = withContext(Dispatchers.IO) {
    val os = AppConfig.detectOs()
    val binDir = if (os == OsFamily.MACOS) AppConfig.binDir(dataDir) else AppConfig.gpuBinDir(dataDir)
    val exe = LlamaBinaries.findServerExecutable(binDir)
        ?: return@withContext GpuProbeResponse(available = false, raw = "GPU build is not installed yet.")
    try {
        val process = ProcessBuilder(exe.toAbsolutePath().toString(), "--list-devices")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@withContext GpuProbeResponse(available = false, raw = "Timed out probing for a GPU device.")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        val available = process.exitValue() == 0 && output.contains(":")
        GpuProbeResponse(available = available, raw = output.ifBlank { "No devices reported." })
    } catch (e: Exception) {
        GpuProbeResponse(available = false, raw = e.message ?: "Could not probe for a GPU device.")
    }
}

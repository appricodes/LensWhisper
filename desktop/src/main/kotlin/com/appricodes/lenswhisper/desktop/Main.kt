package com.appricodes.lenswhisper.desktop

import com.appricodes.lenswhisper.desktop.analysis.AnalysisService
import com.appricodes.lenswhisper.desktop.config.AppConfig
import com.appricodes.lenswhisper.desktop.config.AuthConfig
import com.appricodes.lenswhisper.desktop.config.BrowserLauncher
import com.appricodes.lenswhisper.desktop.config.OsFamily
import com.appricodes.lenswhisper.desktop.gui.AppController
import com.appricodes.lenswhisper.desktop.gui.DownloadCoordinator
import com.appricodes.lenswhisper.desktop.llama.Backend
import com.appricodes.lenswhisper.desktop.llama.LlamaBinaries
import com.appricodes.lenswhisper.desktop.llama.LlamaDownloader
import com.appricodes.lenswhisper.desktop.llama.ServerLaunch
import com.appricodes.lenswhisper.desktop.llama.launchServerSingleAttempt
import com.appricodes.lenswhisper.desktop.models.ModelCatalog
import com.appricodes.lenswhisper.desktop.models.ModelDownloader
import com.appricodes.lenswhisper.desktop.models.ModelSpec
import com.appricodes.lenswhisper.desktop.prompts.PromptBuilder
import com.appricodes.lenswhisper.desktop.server.PASSWORD_REQUIRED_MESSAGE
import com.appricodes.lenswhisper.desktop.server.ServerContext
import com.appricodes.lenswhisper.desktop.server.configureControlPanelServer
import com.appricodes.lenswhisper.desktop.server.configureServer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import javax.imageio.ImageIO

private const val PREPROCESS_MAX_DIM = 448
private const val PREPROCESS_JPEG_QUALITY = 0.8f

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        launchGui()
        return
    }
    val command = args[0]
    val rest = args.drop(1)
    try {
        when (command) {
            "setup" -> cmdSetup(rest)
            "analyze" -> cmdAnalyze(rest)
            "serve" -> cmdServe(rest)
            "-h", "--help", "help" -> printUsage()
            else -> {
                System.err.println("Unknown command: $command\n")
                printUsage()
                kotlin.system.exitProcess(1)
            }
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}

private fun printUsage() {
    println(
        """
        LensWhisper Desktop — local image analysis via llama.cpp

        Run with no arguments to open the graphical control panel in your browser.

        Usage:
          setup [--model <id>] [--gpu] [--data-dir <path>]
              Downloads llama.cpp and the chosen GGUF model. Run this first.
              --gpu also downloads the Vulkan GPU build (Windows/Linux only — macOS already
              has GPU/Metal support in its standard build).
              Available models: ${ModelCatalog.all().joinToString { it.id }}

          analyze --image <path> [--prompt-id <id>] [--language <lang>] [--persona <persona>]
                  [--custom <question>] [--stream] [--gpu] [--model <id>] [--data-dir <path>]
              Analyzes one image and prints the result.

          serve [--port <port>] [--gpu] [--model <id>] [--data-dir <path>] [--password <value>]
              Starts an HTTP server compatible with backend/process.php on the given port
              (default ${AppConfig.DEFAULT_SERVER_PORT}). Requires a password — every request and
              response is AES-256-GCM encrypted with it, and the app won't use Local Server mode
              without a matching one entered on the phone. Pass --password to set/change it and
              start in one step, or omit it to reuse whatever password was last set (here or via
              the GUI, since both share the same data directory) — the command refuses to start
              with neither.

          --gpu tries the GPU build first and automatically falls back to CPU if it fails to
          start (e.g. no compatible GPU driver). Requires having run `setup --gpu` first.
        """.trimIndent()
    )
}

/** No-args entry point: starts the control-panel Ktor server immediately (before any model is
 * running — the user picks/downloads/starts one from the page) and opens it via
 * [BrowserLauncher] — an app-mode Chromium window when one's available, the OS default browser
 * otherwise. This is the accessibility-first GUI — see desktop/README.md for why it's a web page
 * rather than a native toolkit window. */
private fun launchGui() {
    val dataDir = AppConfig.dataDir(null)
    AuthConfig.init(dataDir)
    val port = AppConfig.DEFAULT_SERVER_PORT
    val controller = AppController(dataDir)
    val coordinator = DownloadCoordinator(dataDir)
    val url = "http://localhost:$port"

    println("Starting LensWhisper Desktop control panel at $url")
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        configureControlPanelServer(controller, coordinator, dataDir)
    }.start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread { controller.stop() })

    BrowserLauncher.openControlPanel(url)

    // Keep the JVM alive until Ctrl+C (shutdown hook above) or /api/quit calls exitProcess.
    Thread.currentThread().join()
}

private fun parseFlags(args: List<String>, booleanFlags: Set<String>): Pair<Map<String, String>, Set<String>> {
    val values = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            if (key in booleanFlags) {
                flags.add(key)
                i += 1
            } else if (i + 1 < args.size) {
                values[key] = args[i + 1]
                i += 2
            } else {
                throw IllegalArgumentException("Missing value for --$key")
            }
        } else {
            i += 1
        }
    }
    return values to flags
}

private fun resolveModel(id: String) =
    ModelCatalog.byId(id) ?: throw IllegalArgumentException(
        "Unknown model id '$id'. Available: ${ModelCatalog.all().joinToString { it.id }}"
    )

private fun cmdSetup(args: List<String>) {
    val (values, flags) = parseFlags(args, setOf("gpu"))
    val modelId = values["model"] ?: ModelCatalog.DEFAULT_MODEL_ID
    val spec = resolveModel(modelId)
    val dataDir = AppConfig.dataDir(values["data-dir"])
    println("Data directory: $dataDir")

    println("Installing llama.cpp (release ${AppConfig.LLAMA_RELEASE_TAG})...")
    LlamaDownloader.ensureInstalled(AppConfig.binDir(dataDir))

    if (flags.contains("gpu")) {
        if (AppConfig.detectOs() == OsFamily.MACOS) {
            println("  GPU: nothing extra to download — the standard macOS build already includes Metal support.")
        } else {
            println("Installing llama.cpp Vulkan (GPU) build...")
            LlamaDownloader.ensureVulkanInstalled(AppConfig.gpuBinDir(dataDir))
        }
    }

    println("Downloading model: ${spec.displayName} (${spec.id})")
    ModelDownloader.ensureDownloaded(spec, AppConfig.modelsDir(dataDir))

    println("Setup complete. Try:")
    println("  analyze --image test_images/sample.jpg" + (if (flags.contains("gpu")) " --gpu" else ""))
}

/** internal, not private: reused by the GUI's /api/server/start route (server/HttpApi.kt) to
 * produce the same "download first" message instead of a raw exception. */
internal fun requireArtifacts(dataDir: Path, spec: ModelSpec, gpuRequested: Boolean = false): Path {
    val binDir = AppConfig.binDir(dataDir)
    val modelsDir = AppConfig.modelsDir(dataDir)
    // Only mention --data-dir in the hint when it isn't the OS default, so users who never
    // passed the flag aren't told to pass it back to themselves.
    val dataDirHint = if (dataDir != AppConfig.dataDir(null)) " --data-dir \"$dataDir\"" else ""
    if (!LlamaBinaries.isInstalled(binDir) || !ModelDownloader.isDownloaded(spec, modelsDir)) {
        throw IllegalStateException("Required files are missing. Run: setup --model ${spec.id}$dataDirHint")
    }
    if (gpuRequested && AppConfig.detectOs() != OsFamily.MACOS && !LlamaBinaries.isInstalled(AppConfig.gpuBinDir(dataDir))) {
        throw IllegalStateException("GPU (Vulkan) build is missing. Run: setup --model ${spec.id} --gpu$dataDirHint")
    }
    return binDir
}

/** CLI fallback wrapper: when [gpuRequested], tries the GPU build first and falls back to the
 * plain CPU build on any startup failure — same shape as the Android app's own GPU→CPU fallback
 * in OfflineInferenceEngine. Built on the shared [launchServerSingleAttempt] primitive, which
 * already cleans up a failed GPU process before this catches and retries on CPU. Assumes
 * [requireArtifacts] already validated that the necessary files are present. */
private fun launchServer(spec: ModelSpec, dataDir: Path, gpuRequested: Boolean, logFile: Path): ServerLaunch {
    if (!gpuRequested) return launchServerSingleAttempt(spec, dataDir, Backend.CPU, logFile)
    return try {
        launchServerSingleAttempt(spec, dataDir, Backend.GPU, logFile)
    } catch (e: Exception) {
        println("  GPU (Vulkan) backend failed to start (${e.message}); falling back to CPU")
        val fallback = launchServerSingleAttempt(spec, dataDir, Backend.CPU, logFile)
        ServerLaunch(fallback.manager, "cpu (gpu fallback)")
    }
}

private fun cmdAnalyze(args: List<String>) {
    val (values, flags) = parseFlags(args, setOf("stream", "gpu"))
    val imagePathStr = values["image"] ?: throw IllegalArgumentException("--image <path> is required")
    val promptId = values["prompt-id"] ?: "brief_narration"
    val language = values["language"] ?: "en-US"
    val persona = values["persona"] ?: "Neutral"
    val custom = values["custom"] ?: ""
    val stream = flags.contains("stream")
    val gpu = flags.contains("gpu")
    val modelId = values["model"] ?: ModelCatalog.DEFAULT_MODEL_ID

    if (promptId == "custom" && custom.isBlank()) {
        throw IllegalArgumentException("--custom \"<question>\" is required when --prompt-id custom")
    }

    val spec = resolveModel(modelId)
    val dataDir = AppConfig.dataDir(values["data-dir"])
    requireArtifacts(dataDir, spec, gpu)

    val imagePath = Paths.get(imagePathStr)
    if (!Files.exists(imagePath)) throw IllegalArgumentException("Image not found: $imagePath")
    println("Preprocessing image: $imagePath")
    val frameBase64 = encodeImageForAnalysis(imagePath)

    val logFile = AppConfig.logsDir(dataDir).resolve("llama-server.log")
    val launch = launchServer(spec, dataDir, gpu, logFile)
    val manager = launch.manager
    println("  backend: ${launch.backend}")
    try {
        val service = AnalysisService(manager.baseUrl)
        val promptSpec = PromptBuilder.buildPromptSpec(promptId, language, persona, custom, isMultiFrame = false)

        println("--- Result ---")
        if (stream) {
            runBlocking {
                service.analyzeStreaming(promptSpec.systemInstruction, promptSpec.userPrompt, listOf(frameBase64))
                    .collect { delta ->
                        print(delta)
                        System.out.flush()
                    }
            }
            println()
        } else {
            val result = service.analyze(
                promptSpec.systemInstruction, promptSpec.userPrompt, listOf(frameBase64),
                expectJson = promptSpec.expectJson,
            )
            println(result)
        }
    } finally {
        manager.stop()
    }
}

private fun cmdServe(args: List<String>) {
    val (values, flags) = parseFlags(args, setOf("gpu"))
    val port = (values["port"] ?: AppConfig.DEFAULT_SERVER_PORT.toString()).toInt()
    val gpu = flags.contains("gpu")
    val modelId = values["model"] ?: ModelCatalog.DEFAULT_MODEL_ID

    val spec = resolveModel(modelId)
    val dataDir = AppConfig.dataDir(values["data-dir"])
    AuthConfig.init(dataDir)
    values["password"]?.let { AuthConfig.setPassword(it) }
    if (!AuthConfig.isEnabled()) {
        System.err.println("$PASSWORD_REQUIRED_MESSAGE Pass --password <value>, or set one from the GUI first.")
        kotlin.system.exitProcess(1)
    }
    requireArtifacts(dataDir, spec, gpu)

    val logFile = AppConfig.logsDir(dataDir).resolve("llama-server.log")
    val launch = launchServer(spec, dataDir, gpu, logFile)
    val manager = launch.manager
    Runtime.getRuntime().addShutdownHook(Thread { manager.stop() })

    val analysisService = AnalysisService(manager.baseUrl)
    val context = ServerContext(
        analysisService = analysisService,
        modelId = spec.id,
        isEngineReady = { manager.isAlive() },
        backend = launch.backend,
    )

    println("Serving on http://0.0.0.0:$port  (model: ${spec.displayName}, backend: ${launch.backend})")
    println("Try: curl http://localhost:$port/health")
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        configureServer(context)
    }.start(wait = true)
}

/** Re-encodes an arbitrary input image to JPEG at quality 0.8, scaled so its longer side is at
 * most 448px — the same preprocessing the Android app applies before sending frames to
 * backend/process.php, so desktop results are comparable to online/offline app results. */
private fun encodeImageForAnalysis(path: Path): String {
    val original = ImageIO.read(path.toFile())
        ?: throw IllegalArgumentException("Could not decode image: $path")

    val scale = PREPROCESS_MAX_DIM.toDouble() / maxOf(original.width, original.height)
    val (targetW, targetH) = if (scale < 1.0) {
        (original.width * scale).toInt().coerceAtLeast(1) to (original.height * scale).toInt().coerceAtLeast(1)
    } else {
        original.width to original.height
    }

    val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
    val g = scaled.createGraphics()
    g.drawImage(original.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH), 0, 0, null)
    g.dispose()

    val writers = ImageIO.getImageWritersByFormatName("jpg")
    val writer = writers.next()
    val writeParam = writer.defaultWriteParam
    writeParam.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
    writeParam.compressionQuality = PREPROCESS_JPEG_QUALITY

    val output = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(output).use { ios ->
        writer.output = ios
        writer.write(null, javax.imageio.IIOImage(scaled, null, null), writeParam)
    }
    writer.dispose()

    return Base64.getEncoder().encodeToString(output.toByteArray())
}

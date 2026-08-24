package com.appricodes.lenswhisper.desktop

import com.appricodes.lenswhisper.desktop.analysis.AnalysisService
import com.appricodes.lenswhisper.desktop.config.AppConfig
import com.appricodes.lenswhisper.desktop.llama.LlamaBinaries
import com.appricodes.lenswhisper.desktop.llama.LlamaDownloader
import com.appricodes.lenswhisper.desktop.llama.LlamaServerManager
import com.appricodes.lenswhisper.desktop.models.ModelCatalog
import com.appricodes.lenswhisper.desktop.models.ModelDownloader
import com.appricodes.lenswhisper.desktop.models.ModelSpec
import com.appricodes.lenswhisper.desktop.prompts.PromptBuilder
import com.appricodes.lenswhisper.desktop.server.ServerContext
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
        printUsage()
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

        Usage:
          setup [--model <id>] [--data-dir <path>]
              Downloads llama.cpp and the chosen GGUF model. Run this first.
              Available models: ${ModelCatalog.all().joinToString { it.id }}

          analyze --image <path> [--prompt-id <id>] [--language <lang>] [--persona <persona>]
                  [--custom <question>] [--stream] [--model <id>] [--data-dir <path>]
              Analyzes one image and prints the result.

          serve [--port <port>] [--model <id>] [--data-dir <path>]
              Starts an HTTP server compatible with backend/process.php on the given port
              (default ${AppConfig.DEFAULT_SERVER_PORT}).
        """.trimIndent()
    )
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
    val (values, _) = parseFlags(args, emptySet())
    val modelId = values["model"] ?: ModelCatalog.DEFAULT_MODEL_ID
    val spec = resolveModel(modelId)
    val dataDir = AppConfig.dataDir(values["data-dir"])
    println("Data directory: $dataDir")

    println("Installing llama.cpp (release ${AppConfig.LLAMA_RELEASE_TAG})...")
    LlamaDownloader.ensureInstalled(AppConfig.binDir(dataDir))

    println("Downloading model: ${spec.displayName} (${spec.id})")
    ModelDownloader.ensureDownloaded(spec, AppConfig.modelsDir(dataDir))

    println("Setup complete. Try:")
    println("  analyze --image test_images/sample.jpg")
}

private fun requireArtifacts(dataDir: Path, spec: ModelSpec): Path {
    val binDir = AppConfig.binDir(dataDir)
    val modelsDir = AppConfig.modelsDir(dataDir)
    if (!LlamaBinaries.isInstalled(binDir) || !ModelDownloader.isDownloaded(spec, modelsDir)) {
        // Only mention --data-dir in the hint when it isn't the OS default, so users who never
        // passed the flag aren't told to pass it back to themselves.
        val dataDirHint = if (dataDir != AppConfig.dataDir(null)) " --data-dir \"$dataDir\"" else ""
        throw IllegalStateException("Required files are missing. Run: setup --model ${spec.id}$dataDirHint")
    }
    return binDir
}

private fun cmdAnalyze(args: List<String>) {
    val (values, flags) = parseFlags(args, setOf("stream"))
    val imagePathStr = values["image"] ?: throw IllegalArgumentException("--image <path> is required")
    val promptId = values["prompt-id"] ?: "brief_narration"
    val language = values["language"] ?: "en-US"
    val persona = values["persona"] ?: "Neutral"
    val custom = values["custom"] ?: ""
    val stream = flags.contains("stream")
    val modelId = values["model"] ?: ModelCatalog.DEFAULT_MODEL_ID

    if (promptId == "custom" && custom.isBlank()) {
        throw IllegalArgumentException("--custom \"<question>\" is required when --prompt-id custom")
    }

    val spec = resolveModel(modelId)
    val dataDir = AppConfig.dataDir(values["data-dir"])
    val binDir = requireArtifacts(dataDir, spec)
    val modelsDir = AppConfig.modelsDir(dataDir)

    val imagePath = Paths.get(imagePathStr)
    if (!Files.exists(imagePath)) throw IllegalArgumentException("Image not found: $imagePath")
    println("Preprocessing image: $imagePath")
    val frameBase64 = encodeImageForAnalysis(imagePath)

    val serverExe = LlamaBinaries.findServerExecutable(binDir)!!
    val modelPath = modelsDir.resolve(spec.modelFile)
    val mmprojPath = modelsDir.resolve(spec.mmprojFile)
    val logFile = AppConfig.logsDir(dataDir).resolve("llama-server.log")

    val manager = LlamaServerManager(serverExe, modelPath, mmprojPath, logFile)
    manager.start()
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
    val (values, _) = parseFlags(args, emptySet())
    val port = (values["port"] ?: AppConfig.DEFAULT_SERVER_PORT.toString()).toInt()
    val modelId = values["model"] ?: ModelCatalog.DEFAULT_MODEL_ID

    val spec = resolveModel(modelId)
    val dataDir = AppConfig.dataDir(values["data-dir"])
    val binDir = requireArtifacts(dataDir, spec)
    val modelsDir = AppConfig.modelsDir(dataDir)

    val serverExe = LlamaBinaries.findServerExecutable(binDir)!!
    val modelPath = modelsDir.resolve(spec.modelFile)
    val mmprojPath = modelsDir.resolve(spec.mmprojFile)
    val logFile = AppConfig.logsDir(dataDir).resolve("llama-server.log")

    val manager = LlamaServerManager(serverExe, modelPath, mmprojPath, logFile)
    manager.start()
    Runtime.getRuntime().addShutdownHook(Thread { manager.stop() })

    val analysisService = AnalysisService(manager.baseUrl)
    val context = ServerContext(
        analysisService = analysisService,
        modelId = spec.id,
        isEngineReady = { manager.isAlive() },
    )

    println("Serving on http://0.0.0.0:$port  (model: ${spec.displayName})")
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

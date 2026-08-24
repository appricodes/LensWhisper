package com.appricodes.lenswhisper.desktop.llama

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** Spawns and supervises a single `llama-server` child process serving one GGUF vision model. */
class LlamaServerManager(
    private val serverExecutable: Path,
    private val modelPath: Path,
    private val mmprojPath: Path,
    private val logFile: Path,
) : AutoCloseable {

    private var process: Process? = null
    var port: Int = -1
        private set

    private val healthClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** Starts llama-server on a free local port and blocks until /health reports ready. */
    fun start(healthTimeout: Duration = Duration.ofSeconds(180)) {
        check(process == null) { "LlamaServerManager already started" }
        port = findFreePort()
        java.nio.file.Files.createDirectories(logFile.parent)

        val command = listOf(
            serverExecutable.toAbsolutePath().toString(),
            "-m", modelPath.toAbsolutePath().toString(),
            "--mmproj", mmprojPath.toAbsolutePath().toString(),
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", "4096",
        )
        println("  starting llama-server on port $port (log: $logFile)")
        val builder = ProcessBuilder(command)
            .redirectOutput(logFile.toFile())
            .redirectErrorStream(true)
        process = builder.start()

        Runtime.getRuntime().addShutdownHook(Thread { stopQuietly() })

        waitForHealthy(healthTimeout)
    }

    private fun waitForHealthy(timeout: Duration) {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            val proc = process
            if (proc != null && !proc.isAlive) {
                throw RuntimeException(
                    "llama-server exited early (code ${proc.exitValue()}) — check $logFile"
                )
            }
            try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                val response = healthClient.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() == 200) {
                    println("  llama-server is ready")
                    return
                }
            } catch (e: Exception) {
                lastError = e
            }
            Thread.sleep(500)
        }
        throw RuntimeException("llama-server did not become healthy within $timeout — check $logFile", lastError)
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun stop() {
        stopQuietly()
    }

    private fun stopQuietly() {
        val proc = process ?: return
        if (proc.isAlive) {
            proc.destroy()
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
        process = null
    }

    override fun close() = stop()

    companion object {
        private fun findFreePort(): Int {
            ServerSocket(0).use { return it.localPort }
        }
    }
}

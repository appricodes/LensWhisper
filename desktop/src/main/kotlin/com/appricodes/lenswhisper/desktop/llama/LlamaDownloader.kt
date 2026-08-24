package com.appricodes.lenswhisper.desktop.llama

import com.appricodes.lenswhisper.desktop.config.AppConfig
import com.appricodes.lenswhisper.desktop.config.OsFamily
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipInputStream

object LlamaDownloader {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /** Downloads and extracts the pinned llama.cpp release into [binDir] if `llama-server` isn't
     * already present there, then makes it executable on Unix-like systems. */
    fun ensureInstalled(binDir: Path) {
        if (LlamaBinaries.isInstalled(binDir)) {
            println("  llama-server already installed under $binDir")
            return
        }
        Files.createDirectories(binDir)
        val os = AppConfig.detectOs()
        val url = LlamaBinaries.downloadUrlFor(os)
        val assetName = LlamaBinaries.assetNameFor(os)
        val archivePath = binDir.resolve(assetName)

        println("  downloading llama.cpp ($assetName) from $url")
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(archivePath))
        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(archivePath)
            throw RuntimeException("Download failed (HTTP ${response.statusCode()}) for $url")
        }
        println("  extracting $assetName")
        when {
            assetName.endsWith(".zip") -> extractZip(archivePath, binDir)
            assetName.endsWith(".tar.gz") -> extractTarGz(archivePath, binDir)
            else -> throw RuntimeException("Unknown archive format: $assetName")
        }
        Files.deleteIfExists(archivePath)

        val exe = LlamaBinaries.findServerExecutable(binDir)
            ?: throw RuntimeException("llama-server executable not found after extracting $assetName into $binDir")
        if (os != OsFamily.WINDOWS) {
            exe.toFile().setExecutable(true)
            // Sibling shared libraries (libllama.so / libggml*.so etc.) also need +x on some
            // distros' extraction, though tar preserves the archive's own permission bits normally.
            Files.walk(exe.parent).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { it.toFile().setExecutable(true) }
            }
        }
        println("  installed: $exe")
    }

    private fun extractZip(archivePath: Path, destDir: Path) {
        ZipInputStream(Files.newInputStream(archivePath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outPath = destDir.resolve(entry.name).normalize()
                if (!outPath.startsWith(destDir)) throw RuntimeException("Zip entry escapes destination: ${entry.name}")
                if (entry.isDirectory) {
                    Files.createDirectories(outPath)
                } else {
                    Files.createDirectories(outPath.parent)
                    Files.newOutputStream(outPath).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Shells out to the system `tar` (present on modern Windows 10+, macOS, and Linux) instead of
     * pulling in a commons-compress dependency just for gzip+tar. */
    private fun extractTarGz(archivePath: Path, destDir: Path) {
        val process = ProcessBuilder("tar", "-xzf", archivePath.toAbsolutePath().toString(), "-C", destDir.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("tar extraction failed (exit $exitCode): $output")
        }
    }
}

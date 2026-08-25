package com.appricodes.lenswhisper.desktop.models

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/** Called as a download progresses: (fileLabel, bytesDownloaded, totalBytes). */
typealias DownloadProgress = (String, Long, Long) -> Unit

object ModelDownloader {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL) // HF resolve/main URLs 302 to a CDN
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /** Downloads [spec]'s model + mmproj files into [modelsDir] if not already present, printing
     * simple progress to stdout and optionally reporting byte-level progress via [onProgress].
     * Returns the local (model, mmproj) paths. */
    fun ensureDownloaded(spec: ModelSpec, modelsDir: Path, onProgress: DownloadProgress? = null): Pair<Path, Path> {
        Files.createDirectories(modelsDir)
        val modelPath = modelsDir.resolve(spec.modelFile)
        val mmprojPath = modelsDir.resolve(spec.mmprojFile)
        downloadIfMissing(spec.modelUrl, modelPath, spec.modelSizeBytes, onProgress)
        downloadIfMissing(spec.mmprojUrl, mmprojPath, spec.mmprojSizeBytes, onProgress)
        return modelPath to mmprojPath
    }

    fun isDownloaded(spec: ModelSpec, modelsDir: Path): Boolean {
        val modelPath = modelsDir.resolve(spec.modelFile)
        val mmprojPath = modelsDir.resolve(spec.mmprojFile)
        return looksComplete(modelPath, spec.modelSizeBytes) && looksComplete(mmprojPath, spec.mmprojSizeBytes)
    }

    private fun looksComplete(path: Path, expectedSizeBytes: Long): Boolean {
        if (!Files.exists(path)) return false
        val actual = Files.size(path)
        // Allow slack since our pinned sizes are approximate (HF file sizes can drift slightly).
        return actual > expectedSizeBytes * 9 / 10
    }

    private fun downloadIfMissing(url: String, dest: Path, expectedSizeBytes: Long, onProgress: DownloadProgress?) {
        val label = dest.fileName.toString()
        if (looksComplete(dest, expectedSizeBytes)) {
            println("  already have $label (${humanSize(Files.size(dest))})")
            onProgress?.invoke(label, expectedSizeBytes, expectedSizeBytes)
            return
        }
        println("  downloading $label from $url")
        val partial = dest.resolveSibling("$label.part")
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Download failed (HTTP ${response.statusCode()}) for $url")
        }
        val totalBytes = response.headers().firstValueAsLong("content-length").orElse(expectedSizeBytes)
        var downloaded = 0L
        val buffer = ByteArray(64 * 1024)
        Files.newOutputStream(partial).use { out ->
            response.body().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(label, downloaded, totalBytes)
                }
            }
        }
        val actualSize = Files.size(partial)
        if (actualSize < expectedSizeBytes / 2) {
            Files.deleteIfExists(partial)
            throw RuntimeException(
                "Downloaded file for $url looks truncated (${humanSize(actualSize)}, expected ~${humanSize(expectedSizeBytes)})"
            )
        }
        Files.move(partial, dest, StandardCopyOption.REPLACE_EXISTING)
        println("  done: $label (${humanSize(actualSize)})")
        onProgress?.invoke(label, actualSize, expectedSizeBytes)
    }

    private fun humanSize(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1000) "%.2f GB".format(mb / 1000.0) else "%.1f MB".format(mb)
    }
}

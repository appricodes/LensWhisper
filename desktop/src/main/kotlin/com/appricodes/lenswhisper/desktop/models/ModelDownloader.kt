package com.appricodes.lenswhisper.desktop.models

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

object ModelDownloader {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL) // HF resolve/main URLs 302 to a CDN
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /** Downloads [spec]'s model + mmproj files into [modelsDir] if not already present, printing
     * simple progress to stdout. Returns the local (model, mmproj) paths. */
    fun ensureDownloaded(spec: ModelSpec, modelsDir: Path): Pair<Path, Path> {
        Files.createDirectories(modelsDir)
        val modelPath = modelsDir.resolve(spec.modelFile)
        val mmprojPath = modelsDir.resolve(spec.mmprojFile)
        downloadIfMissing(spec.modelUrl, modelPath, spec.modelSizeBytes)
        downloadIfMissing(spec.mmprojUrl, mmprojPath, spec.mmprojSizeBytes)
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

    private fun downloadIfMissing(url: String, dest: Path, expectedSizeBytes: Long) {
        if (looksComplete(dest, expectedSizeBytes)) {
            println("  already have ${dest.fileName} (${humanSize(Files.size(dest))})")
            return
        }
        println("  downloading ${dest.fileName} from $url")
        val partial = dest.resolveSibling("${dest.fileName}.part")
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial))
        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(partial)
            throw RuntimeException("Download failed (HTTP ${response.statusCode()}) for $url")
        }
        val actualSize = Files.size(partial)
        if (actualSize < expectedSizeBytes / 2) {
            Files.deleteIfExists(partial)
            throw RuntimeException(
                "Downloaded file for $url looks truncated (${humanSize(actualSize)}, expected ~${humanSize(expectedSizeBytes)})"
            )
        }
        Files.move(partial, dest, StandardCopyOption.REPLACE_EXISTING)
        println("  done: ${dest.fileName} (${humanSize(actualSize)})")
    }

    private fun humanSize(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1000) "%.2f GB".format(mb / 1000.0) else "%.1f MB".format(mb)
    }
}

package com.appricodes.lenswhisper.desktop.llama

import com.appricodes.lenswhisper.desktop.config.AppConfig
import com.appricodes.lenswhisper.desktop.config.OsFamily
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/** Maps the pinned llama.cpp release tag to the right per-OS asset name, and locates the
 * extracted `llama-server` executable. Only CPU builds are used (Phase 1 targets CPU-only). */
object LlamaBinaries {

    fun assetNameFor(os: OsFamily): String = when (os) {
        OsFamily.WINDOWS -> "llama-${AppConfig.LLAMA_RELEASE_TAG}-bin-win-cpu-x64.zip"
        OsFamily.LINUX -> "llama-${AppConfig.LLAMA_RELEASE_TAG}-bin-ubuntu-x64.tar.gz"
        OsFamily.MACOS -> "llama-${AppConfig.LLAMA_RELEASE_TAG}-bin-macos-arm64.tar.gz"
    }

    fun downloadUrlFor(os: OsFamily): String =
        "https://github.com/ggml-org/llama.cpp/releases/download/${AppConfig.LLAMA_RELEASE_TAG}/${assetNameFor(os)}"

    fun serverExecutableName(os: OsFamily): String =
        if (os == OsFamily.WINDOWS) "llama-server.exe" else "llama-server"

    /** Recursively finds the extracted server binary under [binDir] — archive layouts differ
     * between platforms (Windows zip is flat, the tarballs nest under build/bin/). */
    fun findServerExecutable(binDir: Path): Path? {
        if (!Files.isDirectory(binDir)) return null
        val name = serverExecutableName(AppConfig.detectOs())
        Files.walk(binDir).use { stream ->
            val matches = stream.filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                .collect(Collectors.toList())
            return matches.firstOrNull()
        }
    }

    fun isInstalled(binDir: Path): Boolean = findServerExecutable(binDir) != null
}

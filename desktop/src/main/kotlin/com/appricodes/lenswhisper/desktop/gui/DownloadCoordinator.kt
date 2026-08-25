package com.appricodes.lenswhisper.desktop.gui

import com.appricodes.lenswhisper.desktop.config.AppConfig
import com.appricodes.lenswhisper.desktop.config.OsFamily
import com.appricodes.lenswhisper.desktop.llama.Backend
import com.appricodes.lenswhisper.desktop.llama.LlamaDownloader
import com.appricodes.lenswhisper.desktop.models.ModelDownloader
import com.appricodes.lenswhisper.desktop.models.ModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class DownloadState(
    val state: String, // "idle" | "downloading" | "done" | "error"
    val currentFile: String? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

/** Runs the "download everything needed for this model+backend" action (llama.cpp engine binary
 * — CPU always, GPU too if requested — plus the model + mmproj files) in a background coroutine
 * that outlives any single HTTP request, so the control panel page can poll progress for it. One
 * combined action per model, mirroring what the CLI's `setup [--gpu]` already does in one step. */
class DownloadCoordinator(private val dataDir: Path) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ConcurrentHashMap<String, DownloadState>()

    fun statusOf(modelId: String): DownloadState = states[modelId] ?: DownloadState("idle")

    /** Returns false without doing anything if a download for [spec] is already in flight. */
    fun start(spec: ModelSpec, backend: Backend): Boolean {
        val key = spec.id
        if (states[key]?.state == "downloading") return false
        states[key] = DownloadState("downloading")
        scope.launch {
            try {
                LlamaDownloader.ensureInstalled(AppConfig.binDir(dataDir)) { file, downloaded, total ->
                    states[key] = DownloadState("downloading", file, downloaded, total)
                }
                if (backend == Backend.GPU && AppConfig.detectOs() != OsFamily.MACOS) {
                    LlamaDownloader.ensureVulkanInstalled(AppConfig.gpuBinDir(dataDir)) { file, downloaded, total ->
                        states[key] = DownloadState("downloading", file, downloaded, total)
                    }
                }
                ModelDownloader.ensureDownloaded(spec, AppConfig.modelsDir(dataDir)) { file, downloaded, total ->
                    states[key] = DownloadState("downloading", file, downloaded, total)
                }
                states[key] = DownloadState("done")
            } catch (e: Exception) {
                states[key] = DownloadState("error", error = e.message ?: "Download failed")
            }
        }
        return true
    }
}

package com.appricodes.lenswhisper.desktop.llama

import com.appricodes.lenswhisper.desktop.config.AppConfig
import com.appricodes.lenswhisper.desktop.config.OsFamily
import com.appricodes.lenswhisper.desktop.models.ModelSpec
import java.nio.file.Path

enum class Backend { CPU, GPU }

data class ServerLaunch(val manager: LlamaServerManager, val backend: String)

/** Starts llama-server for [spec] on exactly the requested [backend] — a single attempt, no
 * fallback. Throws (with [LlamaServerManager.start]'s real exception, process already cleaned up)
 * if the backend can't start, e.g. no compatible GPU driver. Callers that want a GPU→CPU fallback
 * (the CLI) or a hard failure (the GUI, which must show the user a real error) both build on this
 * one primitive — see Main.kt's `launchServer` for the CLI's fallback wrapper. Assumes the caller
 * already validated that the necessary files are present (see Main.kt's `requireArtifacts`). */
fun launchServerSingleAttempt(
    spec: ModelSpec,
    dataDir: Path,
    backend: Backend,
    logFile: Path,
    reasoningBudget: Int? = null,
): ServerLaunch {
    val modelsDir = AppConfig.modelsDir(dataDir)
    val modelPath = modelsDir.resolve(spec.modelFile)
    val mmprojPath = modelsDir.resolve(spec.mmprojFile)

    return when (backend) {
        Backend.CPU -> {
            val cpuExe = LlamaBinaries.findServerExecutable(AppConfig.binDir(dataDir))!!
            val manager = LlamaServerManager(cpuExe, modelPath, mmprojPath, logFile, reasoningBudget = reasoningBudget)
            manager.start()
            ServerLaunch(manager, "cpu")
        }
        Backend.GPU -> {
            if (AppConfig.detectOs() == OsFamily.MACOS) {
                // Metal is baked into the standard macOS build; no separate binary needed.
                val exe = LlamaBinaries.findServerExecutable(AppConfig.binDir(dataDir))!!
                val manager = LlamaServerManager(exe, modelPath, mmprojPath, logFile, nGpuLayers = 99, reasoningBudget = reasoningBudget)
                manager.start()
                ServerLaunch(manager, "gpu (metal)")
            } else {
                val vulkanExe = LlamaBinaries.findServerExecutable(AppConfig.gpuBinDir(dataDir))!!
                val manager = LlamaServerManager(vulkanExe, modelPath, mmprojPath, logFile, nGpuLayers = 99, reasoningBudget = reasoningBudget)
                manager.start()
                ServerLaunch(manager, "gpu (vulkan)")
            }
        }
    }
}

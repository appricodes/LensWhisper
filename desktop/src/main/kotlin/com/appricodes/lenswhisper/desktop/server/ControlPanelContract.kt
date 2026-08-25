package com.appricodes.lenswhisper.desktop.server

import kotlinx.serialization.Serializable

@Serializable
data class ModelListItem(
    val id: String,
    val displayName: String,
    val description: String,
    val modelSizeBytes: Long,
    val mmprojSizeBytes: Long,
    val downloaded: Boolean,
)

@Serializable
data class DownloadRequest(val backend: String = "cpu") // "cpu" | "gpu"

@Serializable
data class DownloadStatusResponse(
    val state: String, // "idle" | "downloading" | "done" | "error"
    val currentFile: String? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

@Serializable
data class GpuProbeResponse(val available: Boolean, val raw: String)

@Serializable
data class SetPasswordRequest(val password: String)

@Serializable
data class AuthStatusResponse(val enabled: Boolean)

@Serializable
data class StartServerRequest(
    val modelId: String,
    val backend: String = "cpu",
    /** Token budget for a reasoning model's "thinking" phase before it answers (see
     * `llama-server --reasoning-budget`); 0 disables thinking, higher allows more of it. Ignored
     * by models whose template doesn't support reasoning. Default matches the GUI's own default. */
    val reasoningBudget: Int = 50,
)

@Serializable
data class StartServerResponse(val modelId: String, val backend: String, val port: Int)

@Serializable
data class StopServerResponse(val stopped: Boolean)

@Serializable
data class StatsDto(
    val requestsSucceeded: Long,
    val requestsFailed: Long,
    val totalGenerationMs: Long,
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val tokensPerSecond: Double,
)

@Serializable
data class StatusResponse(
    val running: Boolean,
    val modelId: String? = null,
    val modelDisplayName: String? = null,
    val backend: String? = null,
    val reasoningBudget: Int? = null,
    val uptimeMs: Long? = null,
    /** llama-server's own internal port — NOT where /process.php is reachable (that's this
     * control panel's own port, i.e. wherever this response itself was fetched from). Included
     * only as a diagnostic detail. */
    val enginePort: Int? = null,
    val stats: StatsDto,
)

package com.appricodes.lenswhisper.desktop.gui

import com.appricodes.lenswhisper.desktop.analysis.AnalysisService
import com.appricodes.lenswhisper.desktop.llama.Backend
import com.appricodes.lenswhisper.desktop.llama.LlamaServerManager
import com.appricodes.lenswhisper.desktop.llama.launchServerSingleAttempt
import com.appricodes.lenswhisper.desktop.models.ModelSpec
import java.nio.file.Path

/** Owns the single currently-running llama-server (if any) for the GUI control panel. Unlike the
 * CLI's `launchServer`, [start] never falls back from GPU to CPU on failure — the GUI must show
 * the user a real error instead of silently substituting a different backend they didn't ask for. */
class AppController(private val dataDir: Path) {

    @Volatile private var manager: LlamaServerManager? = null
    @Volatile private var currentSpec: ModelSpec? = null
    @Volatile private var currentBackend: String? = null
    @Volatile private var currentReasoningBudget: Int? = null
    @Volatile private var startedAtEpochMs: Long? = null
    private val lock = Any()

    val stats = StatsTracker()

    data class StatusSnapshot(
        val running: Boolean,
        val modelId: String?,
        val modelDisplayName: String?,
        val backend: String?,
        val reasoningBudget: Int?,
        val uptimeMs: Long?,
        val port: Int?,
    )

    fun status(): StatusSnapshot {
        val m = manager
        val running = m?.isAlive() == true
        return StatusSnapshot(
            running = running,
            modelId = currentSpec?.id,
            modelDisplayName = currentSpec?.displayName,
            backend = currentBackend,
            reasoningBudget = currentReasoningBudget?.takeIf { running },
            uptimeMs = startedAtEpochMs?.takeIf { running }?.let { System.currentTimeMillis() - it },
            port = m?.takeIf { running }?.port,
        )
    }

    /** Returns an [AnalysisService] for the currently running server, or null if nothing is
     * running — the HTTP layer turns null into a clear "no model running" error response. */
    fun analysisServiceOrNull(): AnalysisService? =
        manager?.takeIf { it.isAlive() }?.let { AnalysisService(it.baseUrl) }

    /** Single-attempt start on exactly [backend] — throws the real exception on failure (e.g. no
     * compatible GPU driver), with no retry. Caller is responsible for turning that into an error
     * response; the process itself is already cleaned up by [LlamaServerManager.start] on failure.
     * [reasoningBudget] caps a reasoning model's internal "thinking" tokens (0 disables thinking;
     * null leaves it unrestricted) — see the GUI's "Thinking budget" control. */
    fun start(spec: ModelSpec, backend: Backend, logFile: Path, reasoningBudget: Int? = null) {
        synchronized(lock) {
            check(manager?.isAlive() != true) { "A server is already running — stop it first." }
            val launch = launchServerSingleAttempt(spec, dataDir, backend, logFile, reasoningBudget)
            manager = launch.manager
            currentSpec = spec
            currentBackend = launch.backend
            currentReasoningBudget = reasoningBudget
            startedAtEpochMs = System.currentTimeMillis()
            stats.reset()
        }
    }

    /** Idempotent — stopping when nothing is running is a no-op. */
    fun stop() {
        synchronized(lock) {
            manager?.stop()
            manager = null
            currentSpec = null
            currentBackend = null
            currentReasoningBudget = null
            startedAtEpochMs = null
        }
    }
}

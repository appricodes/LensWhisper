package com.appricodes.lenswhisper.desktop.gui

import java.util.concurrent.atomic.AtomicLong

/** Thread-safe request counters for the currently running server, shown live in the control
 * panel's stats panel. Reset each time a new model/backend is started. */
class StatsTracker {
    private val requestsSucceeded = AtomicLong(0)
    private val requestsFailed = AtomicLong(0)
    private val totalGenerationMs = AtomicLong(0)
    private val totalPromptTokens = AtomicLong(0)
    private val totalCompletionTokens = AtomicLong(0)

    fun reset() {
        requestsSucceeded.set(0)
        requestsFailed.set(0)
        totalGenerationMs.set(0)
        totalPromptTokens.set(0)
        totalCompletionTokens.set(0)
    }

    fun recordSuccess(durationMs: Long, promptTokens: Int, completionTokens: Int) {
        requestsSucceeded.incrementAndGet()
        totalGenerationMs.addAndGet(durationMs)
        totalPromptTokens.addAndGet(promptTokens.toLong())
        totalCompletionTokens.addAndGet(completionTokens.toLong())
    }

    fun recordFailure() {
        requestsFailed.incrementAndGet()
    }

    fun snapshot(): StatsSnapshot {
        val genMs = totalGenerationMs.get()
        val completionTokens = totalCompletionTokens.get()
        val tokensPerSecond = if (genMs > 0) completionTokens * 1000.0 / genMs else 0.0
        return StatsSnapshot(
            requestsSucceeded = requestsSucceeded.get(),
            requestsFailed = requestsFailed.get(),
            totalGenerationMs = genMs,
            totalPromptTokens = totalPromptTokens.get(),
            totalCompletionTokens = completionTokens,
            tokensPerSecond = tokensPerSecond,
        )
    }
}

data class StatsSnapshot(
    val requestsSucceeded: Long,
    val requestsFailed: Long,
    val totalGenerationMs: Long,
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val tokensPerSecond: Double,
)

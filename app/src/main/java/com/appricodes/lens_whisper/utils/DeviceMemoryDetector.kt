package com.appricodes.lens_whisper.utils

import android.app.ActivityManager
import android.content.Context
import com.appricodes.lens_whisper.domain.OfflineModelInfo

/**
 * Memory checks for the on-device models. Two different questions, deliberately kept apart:
 *
 * - [mightNotRun]: a rough, selection-time hint shown as a gentle note in the model lists — never
 *   a gate. A `.litertlm` file's size isn't its peak runtime RAM, and free memory changes from
 *   minute to minute, so this only suggests that a model *may* not fit; the user can always try.
 * - [canLoadNow]: the real decision, made by `OfflineInferenceEngine` right before a load starts —
 *   after the previous model has been closed — from the memory actually free at that moment.
 */
object DeviceMemoryDetector {
    private const val RUNTIME_RAM_MULTIPLIER = 3.5
    private const val AVAILABLE_RAM_MULTIPLIER = 2.0

    /** On top of the model file itself, a load needs room for the KV cache, activations and the
     *  GPU delegate's buffers. A deliberately modest figure: refusing a model that would in fact
     *  have loaded is its own failure, and the engine still copes with a load that runs out. */
    const val LOAD_HEADROOM_BYTES = 300L * 1024 * 1024

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info
    }

    fun totalRamBytes(context: Context): Long = memoryInfo(context).totalMem

    fun availableRamBytes(context: Context): Long = memoryInfo(context).availMem

    /** A hint only — see the class doc. */
    fun mightNotRun(context: Context, model: OfflineModelInfo): Boolean {
        val info = memoryInfo(context)
        return model.sizeBytes * RUNTIME_RAM_MULTIPLIER > info.totalMem ||
            model.sizeBytes * AVAILABLE_RAM_MULTIPLIER > info.availMem
    }

    /** Roughly how much free memory loading [model] needs. */
    fun bytesNeededToLoad(model: OfflineModelInfo): Long = model.sizeBytes + LOAD_HEADROOM_BYTES

    /** Whether [model] fits in the memory free right now. Call only once nothing else is loaded. */
    fun canLoadNow(context: Context, model: OfflineModelInfo): Boolean =
        availableRamBytes(context) >= bytesNeededToLoad(model)
}

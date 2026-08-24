package com.appricodes.lens_whisper.utils

import android.app.ActivityManager
import android.content.Context
import com.appricodes.lens_whisper.domain.OfflineModelInfo

/**
 * Flags on-device models that might not run well on this device's memory. Still a heuristic, not
 * a verified figure — a `.litertlm` file's on-disk size isn't its peak runtime RAM (weights + KV
 * cache + activations + the GPU delegate's own buffers, only partly offset by quantization) — but
 * tuned up from an earlier, too-lenient version: a 1.75x-of-total-RAM threshold let a real
 * on-device crash through undetected (a ~2.4GB model on an 8GB-RAM device closed the app during
 * analysis without ever showing this warning). Two independent checks now apply:
 *
 * 1. [RUNTIME_RAM_MULTIPLIER] against **total** RAM — is this device's memory *class* large
 *    enough for this model at all, regardless of what else happens to be running right now.
 * 2. [AVAILABLE_RAM_MULTIPLIER] against **available** RAM right now — a device that's nominally
 *    fine on paper can still be too full in the moment (background apps, system services). This
 *    does mean the warning can flicker across launches on the same device as available memory
 *    changes — accepted deliberately now: a missed crash is worse than an inconsistent warning.
 */
object DeviceMemoryDetector {
    private const val RUNTIME_RAM_MULTIPLIER = 3.5
    private const val AVAILABLE_RAM_MULTIPLIER = 2.0

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info
    }

    fun totalRamBytes(context: Context): Long = memoryInfo(context).totalMem

    fun availableRamBytes(context: Context): Long = memoryInfo(context).availMem

    fun mightNotRun(context: Context, model: OfflineModelInfo): Boolean {
        val info = memoryInfo(context)
        return model.sizeBytes * RUNTIME_RAM_MULTIPLIER > info.totalMem ||
            model.sizeBytes * AVAILABLE_RAM_MULTIPLIER > info.availMem
    }
}

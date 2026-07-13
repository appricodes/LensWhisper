package com.appricodes.lens_whisper.utils

import android.os.Build

/**
 * Picks the on-device model build best suited to this device's chipset, purely by matching
 * [Build] hardware fields against a token list from the catalog — nothing about the match is
 * ever surfaced to the user, it just silently picks a faster/smaller download when available.
 */
object DeviceHardwareDetector {

    private val deviceSignature: String by lazy {
        buildString {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append(' ')
            }
            append(Build.HARDWARE).append(' ').append(Build.BOARD).append(' ').append(Build.DEVICE)
        }.lowercase()
    }

    /** Returns the first candidate whose hardware tokens match this device, or null if none do. */
    fun <T> pickBest(candidates: List<T>, hardwareMatch: (T) -> List<String>): T? =
        candidates.firstOrNull { candidate ->
            hardwareMatch(candidate).any { token -> deviceSignature.contains(token.lowercase()) }
        }
}

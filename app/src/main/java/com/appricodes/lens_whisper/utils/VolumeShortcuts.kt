package com.appricodes.lens_whisper.utils

import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import com.appricodes.lens_whisper.data.PreferencesManager

/** Shared volume-key shortcut handling, used from each Activity's onKeyDown override. */
object VolumeShortcuts {
    fun interruptTalkBackSpeech(context: Context) {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (accessibilityManager.isEnabled) accessibilityManager.interrupt()
    }

    /**
     * VOLUME_UP repeats the last action, VOLUME_DOWN triggers the primary action
     * (voice query where available). Returns true if the event was consumed.
     */
    fun onKeyDown(
        context: Context,
        prefs: PreferencesManager,
        keyCode: Int,
        onRepeat: () -> Unit,
        onPrimary: () -> Unit
    ): Boolean {
        if (!prefs.volumeShortcutsEnabled) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                interruptTalkBackSpeech(context)
                onRepeat()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                interruptTalkBackSpeech(context)
                onPrimary()
                true
            }
            else -> false
        }
    }
}

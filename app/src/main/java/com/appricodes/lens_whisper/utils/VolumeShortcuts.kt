package com.appricodes.lens_whisper.utils

import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Small shared helper — the actual volume-key press/hold state machine lives in
 *  [VolumeKeyGestureHandler]; this object is kept only for [interruptTalkBackSpeech], which is
 *  also used outside volume-key handling (e.g. continuous-mode MLKit result changes). */
object VolumeShortcuts {
    fun interruptTalkBackSpeech(context: Context) {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (accessibilityManager.isEnabled) accessibilityManager.interrupt()
    }
}

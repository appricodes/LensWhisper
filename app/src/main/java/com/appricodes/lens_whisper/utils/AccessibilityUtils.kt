package com.appricodes.lens_whisper.utils
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
object AccessibilityUtils {
    fun announceToTalkBack(context: Context, text: String) {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (accessibilityManager.isEnabled) {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                className = javaClass.name
                packageName = context.packageName
                this.text.add(text)
            }
            accessibilityManager.sendAccessibilityEvent(event)
        }
    }
    fun formatTime(milliseconds: Long): String {
        if (milliseconds <= 0) return "0:00"
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

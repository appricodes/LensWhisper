package com.appricodes.lens_whisper.utils
import android.content.Context
object AccessibilityUtils {
    /** Says [text] — through TalkBack or the text-to-speech engine chosen in Settings; see
     *  [SpeechOutput]. Kept under its old name so every existing call site follows the setting. */
    fun announceToTalkBack(context: Context, text: String) {
        SpeechOutput.speak(context, text)
    }

    fun formatTime(milliseconds: Long): String {
        if (milliseconds <= 0) return "0:00"
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

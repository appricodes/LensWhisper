package com.appricodes.lens_whisper.utils

import android.content.Context

/** Small shared helper — the actual volume-key press/hold state machine lives in
 *  [VolumeKeyGestureHandler]; this object is kept only for [interruptTalkBackSpeech], which is
 *  also used outside volume-key handling (e.g. continuous-mode Basic Tasks result changes). */
object VolumeShortcuts {
    /** Silences whatever the app is saying, through TalkBack or the chosen text-to-speech engine —
     *  see [SpeechOutput.stop]. Kept under its old name for the existing callers. */
    fun interruptTalkBackSpeech(context: Context) {
        SpeechOutput.stop(context)
    }
}

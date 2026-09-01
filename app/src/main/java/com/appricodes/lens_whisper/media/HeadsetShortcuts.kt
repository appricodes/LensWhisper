package com.appricodes.lens_whisper.media

import android.view.KeyEvent
import com.appricodes.lens_whisper.data.normalizeHeadsetKeyCode

/**
 * Stateless helpers shared by the headset-shortcut capture flow (`HeadsetKeyCapture`,
 * `HeadsetShortcutsDialog` in [com.appricodes.lens_whisper.SettingsActivity]) and the actual
 * press-matching logic ([HeadsetShortcutGestureHandler]) — the latter is where a captured keycode
 * is actually checked against [com.appricodes.lens_whisper.data.PreferencesManager.headsetRepeatKeyCode]/
 * `headsetMicKeyCode` and fired.
 */
object HeadsetShortcuts {
    /** Keycodes never eligible for capture in Settings' "press a button" dialog — either already
     *  claimed by [com.appricodes.lens_whisper.utils.VolumeKeyGestureHandler]'s long-press
     *  shortcuts, or basic navigation that has to keep working (Back must always cancel the
     *  capture dialog, never get bound to a shortcut instead). */
    val RESERVED_KEY_CODES = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER
    )

    /** Collapses [KeyEvent.KEYCODE_MEDIA_PLAY]/[KeyEvent.KEYCODE_MEDIA_PAUSE] into
     *  [KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE] — confirmed on-device (Galaxy S21, Bluetooth AVRCP) that
     *  one single physical play/pause button reports PLAY on one press and PAUSE on the next,
     *  alternating, rather than always sending the same code (the phone's Bluetooth stack tracks
     *  its own play/pause toggle state independently of this app's fake, permanently-"playing"
     *  session). Without this, a shortcut captured while it happened to send PLAY would only ever
     *  match every *other* real press. Applied both when capturing a new binding (so whichever of
     *  the two arrives gets stored the same way) and when matching a press against one. Every other
     *  keycode passes through unchanged — next/previous/etc. are genuinely distinct physical
     *  buttons, not a toggle. Thin wrapper: the canonical mapping lives in
     *  [com.appricodes.lens_whisper.data.normalizeHeadsetKeyCode] so
     *  `PreferencesManager.headsetRepeatKeyCode`/`headsetMicKeyCode` can apply it on every read too
     *  (data can't depend on media, which already depends on data). */
    fun normalizeKeyCode(keyCode: Int): Int = normalizeHeadsetKeyCode(keyCode)

    /** A short, human-readable label for a captured keycode — used in the capture-confirmation
     *  dialog and the settings row ("Bound to: …"). Falls back to Android's own KEYCODE_ constant
     *  name for anything not called out explicitly, since headset remotes vary widely. */
    fun displayName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK -> "Headset button"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause button"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "Play button"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause button"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "Next button"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous button"
        KeyEvent.KEYCODE_MEDIA_STOP -> "Stop button"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Fast-forward button"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "Rewind button"
        else -> KeyEvent.keyCodeToString(keyCode)?.removePrefix("KEYCODE_") ?: "Button $keyCode"
    }
}

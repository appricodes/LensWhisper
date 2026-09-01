package com.appricodes.lens_whisper.media

import android.os.SystemClock
import android.view.KeyEvent
import com.appricodes.lens_whisper.data.PreferencesManager

private const val DEDUP_WINDOW_MS = 300L

/**
 * Fires the two user-configurable headset shortcuts ("repeat the last prompt", "ask a question by
 * voice" — bound in Settings, see `HeadsetShortcutsDialog` in
 * [com.appricodes.lens_whisper.SettingsActivity]) from physical key events. Each fires immediately
 * on its own key-down; the two must be bound to different buttons (Settings itself enforces this —
 * binding one shortcut to a button already used by the other unbinds it there), so there's never
 * any ambiguity to resolve here.
 *
 * A press-duration-sensitive version of this (short press = repeat, holding past one second =
 * mic, so a single physical button could drive both) was tried and removed 2026-09-01 — confirmed
 * on-device that the long-press branch simply didn't fire reliably, and it wasn't worth chasing
 * further given the simple immediate-fire behavior below is what every headset shortcut used
 * before that experiment and is known to work.
 *
 * Every Activity that owns these actions feeds this from *two* delivery paths — most Bluetooth
 * (AVRCP) presses only ever arrive via [MediaSessionHandler]'s `onConfiguredShortcut`, but some
 * wired in-line remotes instead arrive as an ordinary window [KeyEvent] with no session involved
 * at all — because neither alone is reliable for every device. A short, real-clock dedup window
 * ([DEDUP_WINDOW_MS]) collapses the rare case one physical press reaches both paths for the same
 * Activity. *Not* [KeyEvent.getEventTime] for that dedup — confirmed on-device (Bluetooth AVRCP,
 * Galaxy S21) that every media-session-synthesized KeyEvent reports `eventTime=0` regardless of
 * when the button was actually pressed.
 */
class HeadsetShortcutGestureHandler(
    private val prefs: PreferencesManager,
    private val onRepeat: () -> Unit,
    private val onMic: () -> Unit
) {
    private var lastKeyCode: Int? = null
    private var lastAtMs = 0L

    /** Call with every media-button/window KeyEvent while the owning screen is foreground. Only
     *  `ACTION_DOWN` does anything; `ACTION_UP` is ignored (returns false) so it falls through to
     *  whatever else the caller does with that key. Returns whether it was consumed — a keycode
     *  that matches neither configured shortcut always returns false. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return true
        val keyCode = HeadsetShortcuts.normalizeKeyCode(event.keyCode)
        val repeatKey = prefs.headsetRepeatKeyCode
        val micKey = prefs.headsetMicKeyCode
        if (keyCode != repeatKey && keyCode != micKey) return false

        val now = SystemClock.elapsedRealtime()
        if (keyCode == lastKeyCode && now - lastAtMs < DEDUP_WINDOW_MS) return true
        lastKeyCode = keyCode
        lastAtMs = now
        if (keyCode == repeatKey) onRepeat() else onMic()
        return true
    }
}

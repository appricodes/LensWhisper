package com.appricodes.lens_whisper.media

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Temporary listener behind Settings' "press the button you want to use" capture dialog (see
 * `HeadsetShortcutsDialog` in [com.appricodes.lens_whisper.SettingsActivity]). Different job from
 * [HeadsetShortcutGestureHandler]/[MediaSessionHandler] — those check a press against an
 * *already-configured* keycode; this is how a keycode gets configured in the first place, so it
 * has to accept whatever button arrives next rather than looking for a specific one.
 *
 * Reports the same fake `STATE_PLAYING` as [MediaSessionHandler], for the same reason (a session
 * only receives Bluetooth/media-session-routed button dispatch while it looks like something is
 * playing) — but only for the duration of one capture: [start]/[stop] make and release the session
 * on demand instead of holding one open for as long as Settings itself is open.
 */
class HeadsetKeyCapture(private val context: Context) {
    private var session: MediaSession? = null
    private var onKeyCaptured: ((Int) -> Unit)? = null

    /** Begin listening. [onKeyCaptured] fires at most once per [start] call, for the first fresh
     *  (non-repeat) key-down whose code isn't in [HeadsetShortcuts.RESERVED_KEY_CODES] — anything
     *  reserved is silently ignored and capture keeps waiting. Does not call [stop] itself; the
     *  caller decides when a capture is "done" (typically right away, to show a confirmation
     *  dialog before actually committing the binding). Safe to call again to restart. */
    fun start(onKeyCaptured: (Int) -> Unit) {
        stop()
        this.onKeyCaptured = onKeyCaptured
        val newSession = MediaSession(context, "LensWhisperHeadsetCapture")
        newSession.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && tryCapture(keyEvent)) return true
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        newSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY)
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build()
        )
        newSession.isActive = true
        session = newSession
    }

    /** Same capture check for the window-KeyEvent fallback path (see [HeadsetShortcuts]'s class
     *  doc for why both paths are needed) — call from the owning Activity's `onKeyDown` while a
     *  capture is in progress. A no-op returning false when no capture is active. */
    fun onWindowKeyDown(event: KeyEvent): Boolean = session != null && tryCapture(event)

    private fun tryCapture(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (event.keyCode in HeadsetShortcuts.RESERVED_KEY_CODES) return false
        // Normalized the same way HeadsetShortcutGestureHandler matches — a physical play/pause
        // button can send either PLAY or PAUSE depending on which one happens to arrive first, and
        // the stored binding needs to agree with the matcher regardless of which one that was.
        onKeyCaptured?.invoke(HeadsetShortcuts.normalizeKeyCode(event.keyCode))
        return true
    }

    /** Stop listening — call when the capture dialog is cancelled, a key was captured, or the
     *  owning Activity goes away. Safe to call repeatedly / when not currently listening. */
    fun stop() {
        session?.release()
        session = null
        onKeyCaptured = null
    }
}

package com.appricodes.lens_whisper.media
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * One MediaSession per Activity that needs to receive headset/media-button presses — currently
 * [com.appricodes.lens_whisper.CameraActivity] and [com.appricodes.lens_whisper.MediaDescriberActivity].
 * A session is the *only* reliable way to receive a Bluetooth (AVRCP) button press, and many wired
 * in-line remote buttons too — unlike an ordinary key (e.g. volume), they don't reliably reach a
 * plain Activity's `onKeyDown` at all, they go through the media-session dispatch path instead.
 * Reports a fake `STATE_PLAYING` because a session only receives button dispatch while it looks
 * like something is playing — true here even on the camera screen, which has no media of its own.
 *
 * [onConfiguredShortcut] (see [HeadsetShortcutGestureHandler]) is checked first, ahead of
 * [onPlayPause]/[onVoiceTrigger] — a shortcut the user explicitly bound in Settings always wins
 * over this class's own hard-coded bindings on whichever screen both exist, so a setting never
 * silently no-ops because the screen already used that button for something else. It receives
 * every `ACTION_UP` too, not just `ACTION_DOWN` (unlike the hard-coded bindings below, which only
 * ever look at DOWN) — [HeadsetShortcutGestureHandler] itself ignores UP, but forwarding it
 * doesn't cost anything and keeps this class agnostic to what that handler does internally.
 *
 * [hardcodedBindingsEnabled] gates [onPlayPause]/[onVoiceTrigger] entirely: only the Media
 * Describer screen wants "headset button = video play/pause, next/fast-forward = ask a question"
 * as an always-on convenience. The camera screen has no video to control and no built-in voice
 * shortcut, so it leaves those keys alone (un-consumed) unless the user has actually bound them —
 * this session still has to stay active to catch the *configured* shortcuts, but does not swallow
 * every stray play/pause/next press an external Bluetooth remote might send while it's foreground.
 *
 * [active] backs [setActive] independently of `session.isActive` — confirmed on-device (Galaxy
 * S21) that a headset press can still reach `onMediaButtonEvent` right after the owning Activity
 * calls `setActive(false)` (e.g. navigating away to Settings), on at least one OEM Bluetooth
 * stack. Every branch below checks this explicitly instead of trusting the OS to stop dispatching
 * once told the session is inactive, so a shortcut can never fire on a screen that isn't current.
 */
class MediaSessionHandler(
    context: Context,
    private val onConfiguredShortcut: (KeyEvent) -> Boolean = { false },
    private val hardcodedBindingsEnabled: Boolean = false,
    private val onPlayPause: () -> Unit = {},
    private val onVoiceTrigger: () -> Unit = {}
) {
    private val session = MediaSession(context, "LensWhisperMediaSession")
    private var active = false
    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                if (!active) return super.onMediaButtonEvent(mediaButtonIntent)
                val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null) {
                    // Every DOWN and UP goes to onConfiguredShortcut first (see class doc); the
                    // hard-coded bindings below only ever look at DOWN.
                    if (onConfiguredShortcut(keyEvent)) return true
                    if (hardcodedBindingsEnabled && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                                onPlayPause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                onVoiceTrigger()
                                return true
                            }
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
            override fun onPlay() { if (active && hardcodedBindingsEnabled) onPlayPause() }
            override fun onPause() { if (active && hardcodedBindingsEnabled) onPlayPause() }
            override fun onFastForward() { if (active && hardcodedBindingsEnabled) onVoiceTrigger() }
            override fun onSkipToNext() { if (active && hardcodedBindingsEnabled) onVoiceTrigger() }
        })
        val stateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_SKIP_TO_NEXT
            )
            .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
        session.setPlaybackState(stateBuilder.build())
    }
    fun setActive(isActive: Boolean) {
        active = isActive
        session.isActive = isActive
    }
    fun release() {
        active = false
        session.release()
    }
}

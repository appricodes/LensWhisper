package com.appricodes.lens_whisper.media
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
class MediaSessionHandler(
    context: Context,
    private val onPlayPause: () -> Unit,
    private val onVoiceTrigger: () -> Unit
) {
    private val session = MediaSession(context, "LensWhisperMediaSession")
    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
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
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
            override fun onPlay() { onPlayPause() }
            override fun onPause() { onPlayPause() }
            override fun onFastForward() { onVoiceTrigger() }
            override fun onSkipToNext() { onVoiceTrigger() }
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
        session.isActive = isActive
    }
    fun release() {
        session.release()
    }
}

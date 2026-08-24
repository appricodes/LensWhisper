package com.appricodes.lens_whisper

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.media.MediaSessionHandler
import com.appricodes.lens_whisper.network.XaiVisionEngine
import com.appricodes.lens_whisper.ui.screens.MediaDescriberScreen
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.VolumeKeyGestureHandler
import androidx.lifecycle.lifecycleScope

/**
 * Online AI description for a video/image file. Registered to open shared video/image files
 * (see AndroidManifest). Owns the app's single ExoPlayer + MediaSession for headset controls.
 */
class MediaDescriberActivity : ComponentActivity() {
    private var initialFileUri: Uri? = null
    private lateinit var prefs: PreferencesManager
    private lateinit var appSettings: AppSettings
    private lateinit var apiClient: XaiVisionEngine
    private var mediaSessionHandler: MediaSessionHandler? = null
    private var exoPlayer: ExoPlayer? = null

    private var globalVoiceTrigger: (() -> Unit)? = null
    private var globalMicStop: (() -> Unit)? = null
    private var globalPlayPause: (() -> Unit)? = null
    private var globalRepeatAction: (() -> Unit)? = null
    private var wasVideoPlaying = false
    private lateinit var volumeKeyHandler: VolumeKeyGestureHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)
        volumeKeyHandler = VolumeKeyGestureHandler(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            onRepeat = { globalRepeatAction?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous action to repeat.") },
            onMicStart = { globalVoiceTrigger?.invoke() },
            onMicStop = { globalMicStop?.invoke() }
        )

        // A login gate used to sit here — see LOGIN_AND_PURCHASES_REMOVED.md at the repo root.
        appSettings = SettingsRepository(this).loadSettingsFromAssets()
        apiClient = XaiVisionEngine()

        if (intent?.data != null) initialFileUri = intent.data

        mediaSessionHandler = MediaSessionHandler(
            context = this,
            onPlayPause = { globalPlayPause?.invoke() },
            onVoiceTrigger = { globalVoiceTrigger?.invoke() }
        )
    }

    override fun onPause() {
        super.onPause()
        wasVideoPlaying = exoPlayer?.isPlaying == true
        exoPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        mediaSessionHandler?.setActive(true)

        val player = exoPlayer ?: ExoPlayer.Builder(this).build().apply { setWakeMode(C.WAKE_MODE_LOCAL) }.also { exoPlayer = it }
        if (wasVideoPlaying) player.play()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                    AccessibilityUtils.announceToTalkBack(this@MediaDescriberActivity, "Video completed. Stopped at first frame.")
                }
            }
        })

        setContent {
            LensWhisperTheme {
                MediaDescriberScreen(
                    prefs = prefs,
                    settings = appSettings,
                    apiClient = apiClient,
                    initialFileUri = initialFileUri,
                    exoPlayer = player,
                    onVoiceTriggerReady = { globalVoiceTrigger = it },
                    onMicStopReady = { globalMicStop = it },
                    onPlayPauseReady = { globalPlayPause = it },
                    onRepeatReady = { globalRepeatAction = it }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        mediaSessionHandler?.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (volumeKeyHandler.onKeyDown(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (volumeKeyHandler.onKeyUp(keyCode)) true else super.onKeyUp(keyCode, event)
    }
}

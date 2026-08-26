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
import com.appricodes.lens_whisper.ui.components.CameraController
import com.appricodes.lens_whisper.ui.screens.MediaDescriberScreen
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.VolumeKeyGestureHandler
import androidx.lifecycle.lifecycleScope

/**
 * On-device AI description for a video/image file. Registered to open shared video/image files
 * (see AndroidManifest). Owns the app's single ExoPlayer + MediaSession for headset controls.
 *
 * This screen has no camera of its own, and [onResume] asserts that by releasing any CameraX
 * binding and torch the process may still be holding from the camera screen - which, since
 * `ProcessCameraProvider` is a process singleton, is possible whenever that screen was paused
 * rather than destroyed. "No camera, no flash while looking at a file" is then true by
 * construction rather than by assumption.
 */
class MediaDescriberActivity : ComponentActivity() {
    private var initialFileUri: Uri? = null
    private lateinit var prefs: PreferencesManager
    private lateinit var appSettings: AppSettings
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

        appSettings = SettingsRepository(this).loadSettingsFromAssets()

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
        // See this class's doc: nothing here uses the camera, so nothing may be left holding it.
        CameraController.releaseAllCameras(this)
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

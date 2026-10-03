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
import com.appricodes.lens_whisper.media.HeadsetShortcutGestureHandler
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
    private lateinit var headsetGestureHandler: HeadsetShortcutGestureHandler
    // True only between onResume and onPause — see the identical field/note in CameraActivity.
    // MediaSessionHandler now also self-gates on its own "active" flag (belt-and-braces), but this
    // still covers the onKeyDown/onKeyUp fallback path below, which doesn't go through that class
    // at all.
    private var isForeground = false

    // Shared by the media-session path (onConfiguredShortcut below) and the onKeyDown/onKeyUp
    // fallback — see HeadsetShortcutGestureHandler's class doc for why both exist. Checked ahead
    // of this screen's own hard-coded headset-button = play/pause and next/fast-forward = ask-a-
    // question bindings (see MediaSessionHandler), so a shortcut the user bound in Settings always
    // wins here too.
    private fun triggerHeadsetShortcut(event: KeyEvent): Boolean {
        if (!isForeground) return false
        return headsetGestureHandler.onKeyEvent(event)
    }

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
        headsetGestureHandler = HeadsetShortcutGestureHandler(
            prefs = prefs,
            onRepeat = { globalRepeatAction?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous action to repeat.") },
            onMic = { globalVoiceTrigger?.invoke() }
        )

        appSettings = SettingsRepository(this).loadSettingsFromAssets()

        if (intent?.data != null) initialFileUri = intent.data

        mediaSessionHandler = MediaSessionHandler(
            context = this,
            onConfiguredShortcut = { event -> triggerHeadsetShortcut(event) },
            hardcodedBindingsEnabled = true,
            onPlayPause = { globalPlayPause?.invoke() },
            onVoiceTrigger = { globalVoiceTrigger?.invoke() }
        )

        // Player, its listener and the composition are all built once, here — not in onResume.
        // Doing it on every resume stacked a new listener each time (N duplicate "Video completed"
        // announcements) and rebuilt the whole screen, which reloaded the file from position 0
        // and cancelled any analysis in flight, e.g. across a screen lock/unlock.
        val player = ExoPlayer.Builder(this).build().apply { setWakeMode(C.WAKE_MODE_LOCAL) }.also { exoPlayer = it }
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

    override fun onPause() {
        super.onPause()
        isForeground = false
        wasVideoPlaying = exoPlayer?.isPlaying == true
        exoPlayer?.pause()
        mediaSessionHandler?.setActive(false)
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // See this class's doc: nothing here uses the camera, so nothing may be left holding it.
        CameraController.releaseAllCameras(this)
        mediaSessionHandler?.setActive(true)

        if (wasVideoPlaying) exoPlayer?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        mediaSessionHandler?.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyHandler.onKeyDown(keyCode)) return true
        // Wired in-line "hook" buttons on some devices arrive here directly instead of through
        // the media session above — see HeadsetShortcutGestureHandler's class doc.
        if (event != null && triggerHeadsetShortcut(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyHandler.onKeyUp(keyCode)) return true
        if (event != null && triggerHeadsetShortcut(event)) return true
        return super.onKeyUp(keyCode, event)
    }
}

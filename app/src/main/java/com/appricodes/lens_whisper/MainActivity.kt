package com.appricodes.lens_whisper

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.media.MediaSessionHandler
import com.appricodes.lens_whisper.network.XaiVisionEngine
import com.appricodes.lens_whisper.ui.screens.LensWhisperPlayerScreen
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
	private var initialFileUri: Uri? = null
	private lateinit var appSettings: AppSettings
	private lateinit var prefs: PreferencesManager
	private lateinit var apiClient: XaiVisionEngine
	private var mediaSessionHandler: MediaSessionHandler? = null
	private var mainExoPlayer: ExoPlayer? = null

	private var globalVoiceTrigger: (() -> Unit)? = null
	private var globalPlayPause: (() -> Unit)? = null
	private var globalRepeatAction: (() -> Unit)? = null

	private var wasVideoPlaying = false
	private var activeNetworkJob: Job? = null
	private var activeAudioFeedbackJob: Job? = null

	// Holds Pair(Title, Message) for the global Alert Dialog
	private val dialogState = mutableStateOf<Pair<String, String>?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		prefs = PreferencesManager(this)
		prefs.volumeShortcutsEnabled = false
		appSettings = SettingsRepository(this).loadSettingsFromAssets()
		apiClient = XaiVisionEngine()

		if (intent?.action == Intent.ACTION_VIEW) {
			initialFileUri = intent.data
		}

		val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
			if (isGranted) AccessibilityUtils.announceToTalkBack(this, "Microphone permission granted.")
			else AccessibilityUtils.announceToTalkBack(this, "Microphone permission denied.")
		}
		requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)

		mediaSessionHandler = MediaSessionHandler(
			context = this,
			onPlayPause = { globalPlayPause?.invoke() },
			onVoiceTrigger = { globalVoiceTrigger?.invoke() }
		)
	}

	override fun onPause() {
		super.onPause()
		wasVideoPlaying = mainExoPlayer?.isPlaying == true
		mainExoPlayer?.pause()
	}

	override fun onResume() {
		super.onResume()
		mediaSessionHandler?.setActive(true)
		if (mainExoPlayer == null) {
			mainExoPlayer = ExoPlayer.Builder(this).build().apply {
				setWakeMode(C.WAKE_MODE_LOCAL)
			}
		} else if (wasVideoPlaying) {
			mainExoPlayer?.play()
		}

		mainExoPlayer?.addListener(object : Player.Listener {
			override fun onPlaybackStateChanged(playbackState: Int) {
				if (playbackState == Player.STATE_ENDED) {
					mainExoPlayer?.seekTo(0)
					mainExoPlayer?.pause()
					AccessibilityUtils.announceToTalkBack(this@MainActivity, "Video completed. Stopped at first frame.")
				}
			}
		})

		val autoStartAction = intent?.getStringExtra("AUTO_START_MEDIA")
		intent?.removeExtra("AUTO_START_MEDIA")

		setContent {
			LensWhisperTheme {
				LensWhisperPlayerScreen(
					prefs = prefs,
					apiClient = apiClient,
					initialFileUri = initialFileUri,
					settings = appSettings,
					exoPlayer = mainExoPlayer!!,
					autoStartAction = autoStartAction,
					onFileSelectedInUi = { updatedUri -> initialFileUri = updatedUri },
					onExecuteAnalysisRequested = { config, label, uris -> runPromptAnalysisPipeline(config, label, uris) },
					onVoiceTriggerReady = { globalVoiceTrigger = it },
					onPlayPauseReady = { globalPlayPause = it },
					onRepeatActionReady = { globalRepeatAction = it },
					onShowDialog = { title, msg -> dialogState.value = title to msg }
				)

				dialogState.value?.let { (title, msg) ->
					AlertDialog(
						onDismissRequest = {
							dialogState.value = null
						},
						title = { Text(title) },
						text = { Text(msg, fontSize = 18.sp) },
						confirmButton = {
							Button(onClick = { dialogState.value = null }) { Text("OK") }
						}
					)
				}
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		executeFullShutdown()
		mediaSessionHandler?.release()
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		if (prefs.volumeShortcutsEnabled) {
			when (keyCode) {
				KeyEvent.KEYCODE_VOLUME_UP -> {
					interruptTalkBackSpeech()
					globalRepeatAction?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous action to repeat.")
					return true
				}
				KeyEvent.KEYCODE_VOLUME_DOWN -> {
					interruptTalkBackSpeech()
					globalVoiceTrigger?.invoke()
					return true
				}
			}
		}
		return super.onKeyDown(keyCode, event)
	}

	private fun interruptTalkBackSpeech() {
		val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
		if (accessibilityManager.isEnabled) {
			accessibilityManager.interrupt()
		}
	}

	private fun handleAnalysisResponse(response: String) {
		activeAudioFeedbackJob?.cancel()

		if (response.startsWith("ERROR:")) {
			val errorMsg = response.removePrefix("ERROR:").trim()
			AccessibilityUtils.announceToTalkBack(this@MainActivity, "Error occurred.")
			dialogState.value = "Error" to errorMsg
		} else {
			AccessibilityUtils.announceToTalkBack(this@MainActivity, response)
			if (prefs.showResultsInDialog) {
				dialogState.value = "AI Description" to response
			}
		}
	}

	private fun runPromptAnalysisPipeline(config: PromptConfig, label: String, cameraStoryUris: List<Uri>? = null) {
		val userEmail = prefs.userEmail
		activeNetworkJob?.cancel()
		activeAudioFeedbackJob?.cancel()

		if (userEmail.isNullOrBlank() || !prefs.isLoggedIn) {
			AccessibilityUtils.announceToTalkBack(this, "Error. Please login to access online AI features.")
			return
		}

		if (cameraStoryUris != null && cameraStoryUris.isNotEmpty()) {
			AccessibilityUtils.announceToTalkBack(this, "Analyzing $label")
			activeAudioFeedbackJob = lifecycleScope.launch(Dispatchers.IO) {
				val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
				while (true) {
					toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 100)
					delay(750)
				}
			}
			activeNetworkJob = lifecycleScope.launch {
				val response = apiClient.analyzeMultipleStaticImages(this@MainActivity, cameraStoryUris, config, prefs)
				handleAnalysisResponse(response)
			}
			return
		}

		val uriToProcess = initialFileUri
		if (uriToProcess == null) {
			AccessibilityUtils.announceToTalkBack(this, "No media source loaded")
			return
		}

		AccessibilityUtils.announceToTalkBack(this, "Analyzing $label")

		activeAudioFeedbackJob = lifecycleScope.launch(Dispatchers.IO) {
			val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
			while (true) { toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 100); delay(750) }
		}

		val currentPlaybackPosition = mainExoPlayer?.currentPosition ?: 0L
		val mimeType = contentResolver.getType(uriToProcess)
		val isStaticMime = mimeType?.startsWith("image/") == true || uriToProcess.toString().endsWith(".jpg")

		activeNetworkJob = lifecycleScope.launch {
			val response = if (isStaticMime) {
				apiClient.analyzeStaticImageFile(this@MainActivity, uriToProcess, config, prefs)
			} else {
				apiClient.analyzeVideoFrame(this@MainActivity, uriToProcess, currentPlaybackPosition, config, prefs)
			}
			handleAnalysisResponse(response)
		}
	}

	private fun executeFullShutdown() {
		activeNetworkJob?.cancel()
		activeAudioFeedbackJob?.cancel()
		mainExoPlayer?.stop()
		mainExoPlayer?.release()
		mainExoPlayer = null
		initialFileUri = null
	}
}
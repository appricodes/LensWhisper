package com.appricodes.lens_whisper.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.appricodes.lens_whisper.LoginActivity
import com.appricodes.lens_whisper.SettingsActivity
import com.appricodes.lens_whisper.WebBrowserActivity
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.network.XaiVisionEngine
import com.appricodes.lens_whisper.offline.OfflineColorDetector
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.ui.components.*
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

enum class MediaSource { FILE, CAMERA_FRONT, CAMERA_BACK }

@Composable
fun LensWhisperPlayerScreen(
	prefs: PreferencesManager, apiClient: XaiVisionEngine, initialFileUri: Uri?,
	settings: AppSettings, exoPlayer: ExoPlayer, autoStartAction: String? = null,
	onFileSelectedInUi: (Uri) -> Unit, onExecuteAnalysisRequested: (PromptConfig, String, List<Uri>?) -> Unit,
	onVoiceTriggerReady: (() -> Unit) -> Unit, onPlayPauseReady: (() -> Unit) -> Unit,
	onRepeatActionReady: (() -> Unit) -> Unit, onShowDialog: (String, String) -> Unit
) {
	val context = LocalContext.current
	val coroutineScope = rememberCoroutineScope()
	val density = LocalDensity.current
	val lifecycleOwner = LocalLifecycleOwner.current

	var isOnlineMode by remember { mutableStateOf(prefs.isOnlineMode) }
	var currentMediaSource by remember { mutableStateOf(MediaSource.FILE) }
	var previewView by remember { mutableStateOf<PreviewView?>(null) }
	var activeCamera by remember { mutableStateOf<Camera?>(null) }

	var activeColorMode by remember { mutableStateOf(false) }
	var activeLightMode by remember { mutableStateOf(false) }
	var activeObjectMode by remember { mutableStateOf(false) }

	var currentFileUri by remember { mutableStateOf(initialFileUri) }
	var isImageMode by remember { mutableStateOf(false) }
	var runtimeLoadedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
	var isVideoPlayingState by remember { mutableStateOf(false) }

	var isExploreModeActive by remember { mutableStateOf(false) }
	var exploreGridMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
	var isExploreLoading by remember { mutableStateOf(false) }

	var screenWidthPx by remember { mutableStateOf(0f) }
	var screenHeightPx by remember { mutableStateOf(0f) }
	var nativeMediaWidth by remember { mutableStateOf(1f) }
	var nativeMediaHeight by remember { mutableStateOf(1f) }

	var customPrompt1Text by remember { mutableStateOf("") }
	var customPrompt1Title by remember { mutableStateOf("Custom Prompt 1") }
	var customPrompt2Text by remember { mutableStateOf("") }
	var customPrompt2Title by remember { mutableStateOf("Custom Prompt 2") }

	var lastOnlinePrompt by remember { mutableStateOf<PromptConfig?>(null) }
	var lastOfflineAction by remember { mutableStateOf<String?>(null) }
	var volKeysEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }

	var activeNetworkJob by remember { mutableStateOf<Job?>(null) }
	var activeAudioFeedbackJob by remember { mutableStateOf<Job?>(null) }

	var showUrlDialog by remember { mutableStateOf(false) }
	var lastColorName by remember { mutableStateOf("") }

	val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
	DisposableEffect(speechRecognizer) { onDispose { speechRecognizer.destroy() } }

	val bindCamera = {
		val view = previewView
		if (currentMediaSource != MediaSource.FILE && view != null) {
			val providerFuture = ProcessCameraProvider.getInstance(context)
			providerFuture.addListener({
				val provider = providerFuture.get()
				val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
				val selector = if (currentMediaSource == MediaSource.CAMERA_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
				try {
					provider.unbindAll()
					activeCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
				} catch (e: Exception) { e.printStackTrace() }
			}, ContextCompat.getMainExecutor(context))
		}
	}

	LaunchedEffect(currentMediaSource, previewView) { bindCamera() }

	AppLifecycleCameraHandler(
		context = context, lifecycleOwner = lifecycleOwner, currentMediaSource = currentMediaSource, previewView = previewView,
		onPauseTasks = {
			activeColorMode = false; activeLightMode = false; activeObjectMode = false
			activeNetworkJob?.cancel(); activeAudioFeedbackJob?.cancel()
		},
		onBindCamera = { bindCamera() }
	)

	CameraExposureFlashHandler(activeLightMode = activeLightMode, activeCamera = activeCamera)

	ContinuousDetectionLoop(
		context = context, prefs = prefs, activeColorMode = activeColorMode, activeLightMode = activeLightMode, activeObjectMode = activeObjectMode,
		previewView = previewView, runtimeLoadedImageBitmap = runtimeLoadedImageBitmap, lastColorName = lastColorName,
		onColorChanged = { lastColorName = it }
	)

	val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) { currentMediaSource = MediaSource.FILE; currentFileUri = uri; onFileSelectedInUi(uri) }
	}

	var pendingCameraSource by remember { mutableStateOf<MediaSource?>(null) }
	val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
		if (isGranted) {
			val providerFuture = ProcessCameraProvider.getInstance(context)
			providerFuture.addListener({
				val provider = providerFuture.get()
				if (pendingCameraSource != null && provider.hasCamera(if (pendingCameraSource == MediaSource.CAMERA_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA)) {
					currentMediaSource = pendingCameraSource!!
				} else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) currentMediaSource = MediaSource.CAMERA_BACK
				else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) currentMediaSource = MediaSource.CAMERA_FRONT
				else { currentMediaSource = MediaSource.FILE; filePickerLauncher.launch(arrayOf("video/*", "image/*")) }
				pendingCameraSource = null
			}, ContextCompat.getMainExecutor(context))
		} else {
			AccessibilityUtils.announceToTalkBack(context, "Camera permission denied."); currentMediaSource = MediaSource.FILE
		}
	}

	LaunchedEffect(autoStartAction) {
		if (autoStartAction != null) {
			when(autoStartAction) {
				"OPEN_FILE" -> { currentMediaSource = MediaSource.FILE; filePickerLauncher.launch(arrayOf("video/*", "image/*")) }
				"OPEN_FRONT_CAM" -> { pendingCameraSource = MediaSource.CAMERA_FRONT; cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }
				"OPEN_BACK_CAM" -> { pendingCameraSource = MediaSource.CAMERA_BACK; cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }
			}
		} else {
			if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
				val providerFuture = ProcessCameraProvider.getInstance(context)
				providerFuture.addListener({
					val provider = providerFuture.get()
					if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) currentMediaSource = MediaSource.CAMERA_BACK
					else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) currentMediaSource = MediaSource.CAMERA_FRONT
					else { currentMediaSource = MediaSource.FILE; filePickerLauncher.launch(arrayOf("video/*", "image/*")) }
				}, ContextCompat.getMainExecutor(context))
			} else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
		}
	}

	val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (currentMediaSource == MediaSource.FILE && !isImageMode) exoPlayer.play()
	}

	DisposableEffect(exoPlayer) {
		val listener = object : Player.Listener {
			override fun onIsPlayingChanged(isPlaying: Boolean) { isVideoPlayingState = isPlaying }
			override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
				if (videoSize.width > 0 && videoSize.height > 0) {
					val rot = videoSize.unappliedRotationDegrees
					if (rot == 90 || rot == 270) { nativeMediaWidth = videoSize.height.toFloat(); nativeMediaHeight = videoSize.width * videoSize.pixelWidthHeightRatio }
					else { nativeMediaWidth = videoSize.width * videoSize.pixelWidthHeightRatio; nativeMediaHeight = videoSize.height.toFloat() }
				}
			}
		}
		exoPlayer.addListener(listener)
		onDispose { exoPlayer.removeListener(listener) }
	}

	val displayRotation = remember(context) {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) context.display?.rotation ?: android.view.Surface.ROTATION_0
		else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
	}

	val uiRotationDegrees = remember(screenWidthPx, screenHeightPx, nativeMediaWidth, nativeMediaHeight, displayRotation) {
		if (screenHeightPx > screenWidthPx && nativeMediaWidth > nativeMediaHeight) 90f
		else if (screenWidthPx > screenHeightPx && nativeMediaHeight > nativeMediaWidth) if (displayRotation == android.view.Surface.ROTATION_270) 90f else -90f
		else 0f
	}

	val isRotated = uiRotationDegrees != 0f
	val virtualScreenWidth = if (isRotated) screenHeightPx else screenWidthPx
	val virtualScreenHeight = if (isRotated) screenWidthPx else screenHeightPx
	val virtualScreenAspect = if (virtualScreenHeight > 0) virtualScreenWidth / virtualScreenHeight else 1f
	val mediaAspect = nativeMediaWidth / nativeMediaHeight
	val mediaWidthDp = with(density) { (if (virtualScreenAspect > mediaAspect) virtualScreenHeight * mediaAspect else virtualScreenWidth).toDp() }
	val mediaHeightDp = with(density) { (if (virtualScreenAspect > mediaAspect) virtualScreenHeight else virtualScreenWidth / mediaAspect).toDp() }

	val captureCameraFrameAndSetUri: () -> Uri? = {
		var returnedUri = currentFileUri
		if (currentMediaSource != MediaSource.FILE) {
			val bmp = previewView?.bitmap
			if (bmp != null) {
				val maxDim = 448f
				val scale = minOf(maxDim / bmp.width, maxDim / bmp.height)
				val safeBmp = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
				val tempFile = File(context.cacheDir, "camera_frame.jpg")
				FileOutputStream(tempFile).use { safeBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
				returnedUri = Uri.fromFile(tempFile)
				currentFileUri = returnedUri
				onFileSelectedInUi(returnedUri)
			} else { AccessibilityUtils.announceToTalkBack(context, "Camera frame not ready. Please try again."); returnedUri = null }
		}
		returnedUri
	}

	val togglePlayPauseAction = {
		if (currentMediaSource == MediaSource.FILE && !isImageMode) {
			if (exoPlayer.isPlaying) { exoPlayer.pause(); AccessibilityUtils.announceToTalkBack(context, "Paused") }
			else { exoPlayer.play(); AccessibilityUtils.announceToTalkBack(context, "Playing") }
		}
	}

	val startVoiceRecognition = {
		if (!context.getSharedPreferences("LensWhisperPrefs", Context.MODE_PRIVATE).getBoolean("is_logged_in", false)) {
			context.startActivity(Intent(context, LoginActivity::class.java))
			AccessibilityUtils.announceToTalkBack(context, "Please login to use the microphone online features.")
		} else {
			val rawCode = prefs.selectedLanguageCode
			val localeCode = when {
				rawCode.startsWith("fa", ignoreCase = true) || prefs.selectedLanguage.equals("Persian", ignoreCase = true) -> "fa-IR"
				else -> rawCode
			}
			captureCameraFrameAndSetUri()
			if (currentMediaSource == MediaSource.FILE && !isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
			AccessibilityUtils.announceToTalkBack(context, "Listening... Please speak your question now.")

			val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
				putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
				putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeCode)
			}

			speechRecognizer.setRecognitionListener(object : RecognitionListener {
				override fun onReadyForSpeech(params: Bundle?) {}
				override fun onBeginningOfSpeech() {}
				override fun onRmsChanged(rmsd: Float) {}
				override fun onBufferReceived(buffer: ByteArray?) {}
				override fun onEndOfSpeech() {}
				override fun onError(error: Int) {}
				override fun onResults(results: Bundle?) {
					val recognizedQuery = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
					if (!recognizedQuery.isNullOrBlank()) {
						customPrompt2Text = customPrompt1Text; customPrompt2Title = customPrompt1Title
						customPrompt1Text = recognizedQuery; customPrompt1Title = if (recognizedQuery.length > 20) recognizedQuery.take(17) + "..." else recognizedQuery
						val dynamicMicConfig = PromptConfig("custom", "Voice Query", recognizedQuery, 1, 0.5)
						lastOnlinePrompt = dynamicMicConfig
						onExecuteAnalysisRequested(dynamicMicConfig, "Voice Query", null)
					}
				}
				override fun onPartialResults(partialResults: Bundle?) {}
				override fun onEvent(eventType: Int, params: Bundle?) {}
			})
			speechRecognizer.startListening(intent)
		}
	}

	LaunchedEffect(Unit) {
		onVoiceTriggerReady(startVoiceRecognition)
		onPlayPauseReady(togglePlayPauseAction)
		onRepeatActionReady {
			if (isOnlineMode) {
				if (lastOnlinePrompt != null) {
					captureCameraFrameAndSetUri()
					onExecuteAnalysisRequested(lastOnlinePrompt!!, lastOnlinePrompt!!.title, null)
				} else AccessibilityUtils.announceToTalkBack(context, "No previous prompt to repeat.")
			} else {
				if (lastOfflineAction != null) {
					if (lastOfflineAction == "Objects") {
						activeObjectMode = true; activeColorMode = false; activeLightMode = false
						AccessibilityUtils.announceToTalkBack(context, "Continuous object detection activated")
					} else if (lastOfflineAction == "Texts") {
						val uri = captureCameraFrameAndSetUri()
						if(uri != null) {
							AccessibilityUtils.announceToTalkBack(context, "Reading text...")
							OfflineTextRecognizer.recognize(context, uri, prefs.ocrLanguage) { res -> AccessibilityUtils.announceToTalkBack(context, res) }
						}
					} else if (lastOfflineAction == "Colors") {
						val bmp = previewView?.bitmap ?: runtimeLoadedImageBitmap
						if (bmp != null) {
							AccessibilityUtils.announceToTalkBack(context, "Analyzing colors...")
							OfflineColorDetector.detect(context, bmp, prefs)
						} else {
							AccessibilityUtils.announceToTalkBack(context, "Camera frame not ready.")
						}
					}
				} else AccessibilityUtils.announceToTalkBack(context, "No previous offline action to repeat.")
			}
		}
	}

	LaunchedEffect(currentFileUri, currentMediaSource) {
		if (currentMediaSource == MediaSource.FILE) {
			currentFileUri?.let { uri ->
				activeNetworkJob?.cancel(); activeAudioFeedbackJob?.cancel(); isExploreLoading = false
				customPrompt1Text = ""; customPrompt1Title = "Custom Prompt 1"; customPrompt2Text = ""; customPrompt2Title = "Custom Prompt 2"
				val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true || uri.toString().endsWith(".jpg")
				if (isImage) {
					isImageMode = true; exoPlayer.stop()
					runCatching {
						context.contentResolver.openInputStream(uri).use { stream ->
							val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
							BitmapFactory.decodeStream(stream, null, options)
							nativeMediaWidth = options.outWidth.toFloat().coerceAtLeast(1f)
							nativeMediaHeight = options.outHeight.toFloat().coerceAtLeast(1f)
						}
						context.contentResolver.openInputStream(uri).use { stream ->
							runtimeLoadedImageBitmap = BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 1 })
						}
					}
				} else {
					isImageMode = false; runtimeLoadedImageBitmap = null
					exoPlayer.setMediaItem(MediaItem.fromUri(uri)); exoPlayer.prepare(); exoPlayer.playWhenReady = true
				}
			}
		}
	}

	if (isExploreModeActive) {
		BackHandler {
			activeNetworkJob?.cancel(); activeAudioFeedbackJob?.cancel()
			isExploreLoading = false; isExploreModeActive = false; exploreGridMap = emptyMap()
			if (currentMediaSource == MediaSource.FILE && !isImageMode) exoPlayer.play()
			AccessibilityUtils.announceToTalkBack(context, "Exited frame exploration mode.")
		}
	}

	Box(
		modifier = Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { coords ->
			screenWidthPx = coords.size.width.toFloat(); screenHeightPx = coords.size.height.toFloat()
		},
		contentAlignment = Alignment.Center
	) {
		MediaPreviewArea(
			currentMediaSource = currentMediaSource, isImageMode = isImageMode, runtimeLoadedImageBitmap = runtimeLoadedImageBitmap,
			exoPlayer = exoPlayer, uiRotationDegrees = uiRotationDegrees, mediaWidthDp = mediaWidthDp, mediaHeightDp = mediaHeightDp,
			screenWidthPx = screenWidthPx, screenHeightPx = screenHeightPx, isRotated = isRotated, density = density,
			onPreviewViewCreated = { previewView = it }
		)

		if (isExploreModeActive) {
			ExploreGridOverlay(
				isExploreLoading = isExploreLoading, exploreGridMap = exploreGridMap,
				onCellClicked = { desc ->
					AccessibilityUtils.announceToTalkBack(context, desc); isExploreModeActive = false; exploreGridMap = emptyMap()
					if (currentMediaSource == MediaSource.FILE && !isImageMode) exoPlayer.play()
				}
			)
		}

		if (!isExploreModeActive) {
			val playPauseLabel = if (isImageMode) "N/A" else if (isVideoPlayingState) "Pause" else "Play"
			val rewindLabel = if (isImageMode) "N/A" else "Rewind"
			val forwardLabel = if (isImageMode) "N/A" else "Fast Forwarding"
			val volKeysLabel = if (volKeysEnabled) "Vol Keys: ON" else "Vol Keys: OFF"

			val gridRows = remember(customPrompt1Title, customPrompt2Title, playPauseLabel, rewindLabel, forwardLabel, volKeysLabel, isOnlineMode, currentMediaSource) {
				val rows = mutableListOf<List<String>>()
				if (!isOnlineMode) {
					rows.add(listOf("Objects", "Texts", "Colors", "Light intensity"))
					rows.add(listOf("Go online"))
					rows.add(listOf("Empty", "Empty", "Empty", "Empty"))
					rows.add(listOf("Microphone", "Options", volKeysLabel, "Open file, webpage or camera"))
					if (currentMediaSource == MediaSource.FILE && !isImageMode) rows.add(listOf(rewindLabel, playPauseLabel, forwardLabel, "Empty"))
				} else {
					rows.add(listOf("Brief Narration", "Detailed Scene", "People and Outfit", "Emotions"))
					rows.add(listOf("Objects", "Read Texts", "Story (5 seconds)", "Story (10 seconds)"))
					rows.add(listOf(customPrompt1Title, customPrompt2Title, "Explore", "Go offline"))
					rows.add(listOf("Microphone", "Options", volKeysLabel, "Open file, webpage or camera"))
					if (currentMediaSource == MediaSource.FILE && !isImageMode) rows.add(listOf(rewindLabel, playPauseLabel, forwardLabel, "Empty"))
				}
				rows
			}

			if (activeColorMode || activeObjectMode) {
				BackHandler { activeColorMode = false; activeObjectMode = false; AccessibilityUtils.announceToTalkBack(context, "Exited continuous detection") }
				Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
					val textMode = if (activeColorMode) "Color" else "Object"
					Text(text = "$textMode Mode Active\nPress System Back to exit.", color = Color.White, textAlign = TextAlign.Center, fontSize = 20.sp, modifier = Modifier.padding(bottom = 80.dp).background(Color(0x88000000)).padding(16.dp))
				}
			} else {
				ControlActionGrid(
					gridRows = gridRows, isImageMode = isImageMode, currentMediaSource = currentMediaSource, exoPlayer = exoPlayer,
					onSourceSelected = { sourceString ->
						when(sourceString) {
							"OPEN_FILE" -> filePickerLauncher.launch(arrayOf("video/*", "image/*"))
							"OPEN_FRONT_CAM" -> { pendingCameraSource = MediaSource.CAMERA_FRONT; cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }
							"OPEN_BACK_CAM" -> { pendingCameraSource = MediaSource.CAMERA_BACK; cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }
							"WEB_PAGE" -> showUrlDialog = true
						}
					},
					onActionClick = { cellLabel ->
						if (cellLabel != "Light intensity" && activeLightMode) activeLightMode = false
						when (cellLabel) {
							"Vol Keys: ON", "Vol Keys: OFF" -> {
								val newState = !volKeysEnabled; volKeysEnabled = newState; prefs.volumeShortcutsEnabled = newState
								AccessibilityUtils.announceToTalkBack(context, if (newState) "Volume shortcuts enabled" else "Volume shortcuts disabled")
							}
							"Options" -> {
								if (currentMediaSource == MediaSource.FILE && !isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
								settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
							}
							"Play", "Pause" -> togglePlayPauseAction()
							"Microphone" -> startVoiceRecognition()
							"Explore" -> {
								val activeUri = captureCameraFrameAndSetUri()
								if (!prefs.isLoggedIn) AccessibilityUtils.announceToTalkBack(context, "Error. Please login to use online AI features.")
								else if (activeUri != null) {
									activeNetworkJob?.cancel(); activeAudioFeedbackJob?.cancel()
									if (currentMediaSource == MediaSource.FILE && !isImageMode && exoPlayer.isPlaying) exoPlayer.pause()
									isExploreModeActive = true; isExploreLoading = true
									AccessibilityUtils.announceToTalkBack(context, "Entering exploration mode. Analyzing elements.")
									activeAudioFeedbackJob = coroutineScope.launch(Dispatchers.IO) {
										val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
										while (true) { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120); delay(600) }
									}
									activeNetworkJob = coroutineScope.launch {
										val isTreatAsStatic = isImageMode || currentMediaSource != MediaSource.FILE
										val jsonResponse = apiClient.analyzeFrameSpatialGrid(
											context = context, uri = activeUri, positionMs = if (isTreatAsStatic) 0L else exoPlayer.currentPosition,
											gridSize = 6, isStaticImage = isTreatAsStatic, prefs = prefs
										)
										isExploreLoading = false; activeAudioFeedbackJob?.cancel()
										if (jsonResponse.startsWith("ERROR:")) {
											isExploreModeActive = false; onShowDialog("Error", jsonResponse.removePrefix("ERROR:").trim())
											AccessibilityUtils.announceToTalkBack(context, "Exploration failed.")
										} else {
											val parsedGrid = apiClient.parseSpatialJson(jsonResponse, 6)
											if (parsedGrid.isNotEmpty()) { exploreGridMap = parsedGrid; AccessibilityUtils.announceToTalkBack(context, "Layout complete.") }
											else { isExploreModeActive = false; AccessibilityUtils.announceToTalkBack(context, "Analysis failed. Returning to screen.") }
										}
									}
								}
							}
							"Colors" -> {
								lastOfflineAction = "Colors"
								activeColorMode = false
								activeObjectMode = false
								activeLightMode = false
								val bmp = previewView?.bitmap ?: runtimeLoadedImageBitmap
								if (bmp != null) {
									AccessibilityUtils.announceToTalkBack(context, "Analyzing colors...")
									OfflineColorDetector.detect(context, bmp, prefs)
								} else {
									AccessibilityUtils.announceToTalkBack(context, "Camera frame not ready.")
								}
							}
							"Light intensity" -> { activeLightMode = true; activeColorMode = false; activeObjectMode = false; AccessibilityUtils.announceToTalkBack(context, "Light intensity activated") }
							"Objects" -> { lastOfflineAction = "Objects"; activeObjectMode = true; activeColorMode = false; activeLightMode = false; AccessibilityUtils.announceToTalkBack(context, "Continuous object detection activated") }
							"Go online" -> {
								if (!prefs.isLoggedIn) {
									context.startActivity(Intent(context, LoginActivity::class.java)); AccessibilityUtils.announceToTalkBack(context, "Please login to access online features.")
								} else { isOnlineMode = true; prefs.isOnlineMode = true; AccessibilityUtils.announceToTalkBack(context, "Switched to Online mode") }
							}
							"Go offline" -> { isOnlineMode = false; prefs.isOnlineMode = false; AccessibilityUtils.announceToTalkBack(context, "Switched to Offline mode") }
							"Texts" -> {
								lastOfflineAction = "Texts"
								val uri = captureCameraFrameAndSetUri()
								if(uri != null) {
									AccessibilityUtils.announceToTalkBack(context, "Reading text...")
									OfflineTextRecognizer.recognize(context, uri, prefs.ocrLanguage) { res -> AccessibilityUtils.announceToTalkBack(context, res) }
								}
							}
							else -> {
								val targetedPromptConfig = when (cellLabel) {
									"Brief Narration" -> settings.prompts.find { it.id == "brief_narration" }
									"Detailed Scene" -> settings.prompts.find { it.id == "detailed_scene" }
									"People and Outfit" -> settings.prompts.find { it.id == "people_and_outfit" }
									"Emotions" -> settings.prompts.find { it.id == "emotions" }
									"Objects" -> settings.prompts.find { it.id == "objects" }
									"Read Texts" -> settings.prompts.find { it.id == "read_texts" }
									"Story (5 seconds)" -> settings.prompts.find { it.id == "story_5" }
									"Story (10 seconds)" -> settings.prompts.find { it.id == "story_10" }
									customPrompt1Title -> PromptConfig("custom", customPrompt1Title, customPrompt1Text, 1, 0.5)
									customPrompt2Title -> PromptConfig("custom", customPrompt2Title, customPrompt2Text, 1, 0.5)
									else -> null
								}

								if (targetedPromptConfig != null) {
									val finalConfig = if (cellLabel == customPrompt1Title || cellLabel == customPrompt2Title) targetedPromptConfig.copy(framesCount = 1) else targetedPromptConfig
									lastOnlinePrompt = finalConfig

									if (currentMediaSource != MediaSource.FILE && cellLabel.startsWith("Story")) {
										coroutineScope.launch {
											AccessibilityUtils.announceToTalkBack(context, "Recording $cellLabel, please hold steady.")
											val capturedUris = mutableListOf<Uri>()
											val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
											for (i in 0 until finalConfig.framesCount) {
												toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
												val bmp = previewView?.bitmap
												if (bmp != null) {
													val maxDim = 448f
													val scale = minOf(maxDim / bmp.width, maxDim / bmp.height)
													val safeBmp = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
													val tempFile = File(context.cacheDir, "camera_story_frame_$i.jpg")
													FileOutputStream(tempFile).use { safeBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
													capturedUris.add(Uri.fromFile(tempFile))
												}
												if (i < finalConfig.framesCount - 1) delay((finalConfig.framesInterval * 1000).toLong())
											}
											toneGen.release()
											if (capturedUris.isNotEmpty()) onExecuteAnalysisRequested(finalConfig, cellLabel, capturedUris)
											else { AccessibilityUtils.announceToTalkBack(context, "Failed to capture frames."); onShowDialog("Error", "Failed to capture sequence frames.") }
										}
									} else {
										captureCameraFrameAndSetUri()
										onExecuteAnalysisRequested(finalConfig, cellLabel, null)
									}
								}
							}
						}
					}
				)
			}
		}
	}

	UrlInputDialog(
		showDialog = showUrlDialog, initialUrlHistoryList = prefs.getUrlHistoryList(), onDismiss = { showUrlDialog = false },
		onHistoryRemove = { url -> val updated = prefs.getUrlHistoryList().filter { it != url }; prefs.saveUrlHistory(updated); showUrlDialog = false; showUrlDialog = true },
		onUrlSubmit = { targetInput ->
			showUrlDialog = false
			val finalUrl = if (targetInput.startsWith("http://") || targetInput.startsWith("https://")) targetInput else "https://$targetInput"
			prefs.saveUrlHistory((listOf(finalUrl) + prefs.getUrlHistoryList()).distinct().take(5))
			context.startActivity(Intent(context, WebBrowserActivity::class.java).apply { putExtra("URL", finalUrl) })
			(context as? Activity)?.finish()
		}
	)
}
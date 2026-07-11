package com.appricodes.lens_whisper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.lifecycleScope
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.network.XaiVisionEngine
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class WebBrowserActivity : ComponentActivity() {

	private lateinit var prefs: PreferencesManager
	private lateinit var appSettings: AppSettings
	private lateinit var apiClient: XaiVisionEngine

	private var activeNetworkJob: Job? = null
	private var activeAudioFeedbackJob: Job? = null
	private var lastUsedPrompt: PromptConfig? = null

	private var globalVoiceTrigger: (() -> Unit)? = null
	private var globalRepeatLastPrompt: (() -> Unit)? = null

	private val dialogState = mutableStateOf<Pair<String, String>?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		prefs = PreferencesManager(this)
		appSettings = SettingsRepository(this).loadSettingsFromAssets()
		apiClient = XaiVisionEngine()

		val startUrl = intent?.getStringExtra("URL") ?: "https://google.com"

		setContent {
			LensWhisperTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
					WebBrowserScreen(
						startUrl = startUrl,
						settings = appSettings,
						prefs = prefs,
						apiClient = apiClient,
						onExecuteAnalysis = { uri, config -> runPromptAnalysisPipeline(uri, config) },
						onExecuteExplore = { uri, onResult -> runExplorePipeline(uri, onResult) },
						onVoiceTriggerReady = { globalVoiceTrigger = it },
						onRepeatLastPromptReady = { globalRepeatLastPrompt = it },
						onNavigateToMedia = { action ->
							val intent = Intent(this@WebBrowserActivity, MainActivity::class.java).apply { putExtra("AUTO_START_MEDIA", action) }
							startActivity(intent)
							finish()
						}
					)
				}

				dialogState.value?.let { (title, msg) ->
					AlertDialog(
						onDismissRequest = { dialogState.value = null },
						title = { Text(title) },
						text = { Text(msg, fontSize = 18.sp) },
						confirmButton = { Button(onClick = { dialogState.value = null }) { Text("OK") } }
					)
				}
			}
		}
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		if (prefs.volumeShortcutsEnabled) {
			when (keyCode) {
				KeyEvent.KEYCODE_VOLUME_UP -> {
					interruptTalkBackSpeech()
					globalRepeatLastPrompt?.invoke() ?: AccessibilityUtils.announceToTalkBack(this, "No previous prompt to repeat.")
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
		val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
		if (am.isEnabled) am.interrupt()
	}

	private fun handleAnalysisResponse(response: String) {
		activeAudioFeedbackJob?.cancel()
		if (response.startsWith("ERROR:")) {
			val errorMsg = response.removePrefix("ERROR:").trim()
			AccessibilityUtils.announceToTalkBack(this, "Error occurred.")
			dialogState.value = "Error" to errorMsg
		} else {
			AccessibilityUtils.announceToTalkBack(this, response)
			if (prefs.showResultsInDialog) {
				dialogState.value = "AI Description" to response
			}
		}
	}

	private fun runPromptAnalysisPipeline(uriToProcess: Uri, config: PromptConfig) {
		val userEmail = prefs.userEmail
		activeNetworkJob?.cancel()
		activeAudioFeedbackJob?.cancel()

		if (userEmail.isNullOrBlank() || !prefs.isLoggedIn) {
			AccessibilityUtils.announceToTalkBack(this, "Error. Please login to access online features.")
			return
		}

		lastUsedPrompt = config
		AccessibilityUtils.announceToTalkBack(this, "Analyzing web page...")

		activeAudioFeedbackJob = lifecycleScope.launch(Dispatchers.IO) {
			val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
			while (true) { toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 100); delay(750) }
		}

		activeNetworkJob = lifecycleScope.launch {
			val response = apiClient.analyzeStaticImageFile(
				context = this@WebBrowserActivity, uri = uriToProcess, config = config, prefs = prefs
			)
			handleAnalysisResponse(response)
		}
	}

	private fun runExplorePipeline(uriToProcess: Uri, onResult: (Map<String, String>?) -> Unit) {
		val userEmail = prefs.userEmail
		activeNetworkJob?.cancel()
		activeAudioFeedbackJob?.cancel()

		if (userEmail.isNullOrBlank() || !prefs.isLoggedIn) {
			AccessibilityUtils.announceToTalkBack(this, "Error. Please login to access online features.")
			onResult(null)
			return
		}

		AccessibilityUtils.announceToTalkBack(this, "Entering exploration mode. Analyzing web layout.")

		activeAudioFeedbackJob = lifecycleScope.launch(Dispatchers.IO) {
			val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
			while (true) { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120); delay(600) }
		}

		activeNetworkJob = lifecycleScope.launch {
			val jsonResponse = apiClient.analyzeFrameSpatialGrid(
				context = this@WebBrowserActivity, uri = uriToProcess, positionMs = 0L,
				gridSize = 6, isStaticImage = true, prefs = prefs
			)
			activeAudioFeedbackJob?.cancel()

			if (jsonResponse.startsWith("ERROR:")) {
				val errorMsg = jsonResponse.removePrefix("ERROR:").trim()
				AccessibilityUtils.announceToTalkBack(this@WebBrowserActivity, "Exploration failed.")
				dialogState.value = "Error" to errorMsg
				onResult(null)
			} else {
				val parsedGrid = apiClient.parseSpatialJson(jsonResponse, 6)
				if (parsedGrid.isNotEmpty()) {
					AccessibilityUtils.announceToTalkBack(this@WebBrowserActivity, "Layout complete. Explore the screen elements.")
					onResult(parsedGrid)
				} else {
					AccessibilityUtils.announceToTalkBack(this@WebBrowserActivity, "Analysis failed. Returning to web screen.")
					onResult(null)
				}
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		activeNetworkJob?.cancel()
		activeAudioFeedbackJob?.cancel()
	}
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
	startUrl: String, settings: AppSettings, prefs: PreferencesManager, apiClient: XaiVisionEngine,
	onExecuteAnalysis: (Uri, PromptConfig) -> Unit, onExecuteExplore: (Uri, (Map<String, String>?) -> Unit) -> Unit,
	onVoiceTriggerReady: (() -> Unit) -> Unit, onRepeatLastPromptReady: (() -> Unit) -> Unit,
	onNavigateToMedia: (String) -> Unit
) {
	val context = LocalContext.current
	var webViewRef by remember { mutableStateOf<WebView?>(null) }
	var currentUrl by remember { mutableStateOf(startUrl) }

	var dynamicPrompt by remember { mutableStateOf(if (settings.prompts.size > 1) settings.prompts[1] else settings.prompts[0]) }
	var volKeysEnabled by remember { mutableStateOf(prefs.volumeShortcutsEnabled) }

	var isExploreModeActive by remember { mutableStateOf(false) }
	var isExploreLoading by remember { mutableStateOf(false) }
	var exploreGridMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

	var showSourceMenu by remember { mutableStateOf(false) }
	var showMoreMenu by remember { mutableStateOf(false) }
	var showUrlDialog by remember { mutableStateOf(false) }
	var urlInput by remember { mutableStateOf("") }

	var lastUsedPrompt by remember { mutableStateOf<PromptConfig?>(null) }
	var isListeningVoice by remember { mutableStateOf(false) }
	val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
	DisposableEffect(speechRecognizer) { onDispose { speechRecognizer.destroy() } }

	val settingsLauncher = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.StartActivityForResult(),
		onResult = { }
	)

	fun captureWebView(): Uri? {
		val view = webViewRef ?: return null
		if (view.width <= 0 || view.height <= 0) return null
		val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bmp)
		view.draw(canvas)

		val maxDim = 448f
		val scale = minOf(maxDim / bmp.width, maxDim / bmp.height)
		val safeBmp = if (scale < 1f) {
			Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
		} else bmp

		val tempFile = File(context.cacheDir, "web_frame.jpg")
		FileOutputStream(tempFile).use { safeBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
		return Uri.fromFile(tempFile)
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
			AccessibilityUtils.announceToTalkBack(context, "Listening... Please speak your question now.")
			isListeningVoice = true

			val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
				putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
				putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeCode)
			}

			speechRecognizer.setRecognitionListener(object : RecognitionListener {
				override fun onReadyForSpeech(params: Bundle?) {}
				override fun onBeginningOfSpeech() {}
				override fun onRmsChanged(rmsd: Float) {}
				override fun onBufferReceived(buffer: ByteArray?) {}
				override fun onEndOfSpeech() { isListeningVoice = false }
				override fun onError(error: Int) { isListeningVoice = false }
				override fun onResults(results: Bundle?) {
					val recognizedQuery = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
					if (!recognizedQuery.isNullOrBlank()) {
						val uri = captureWebView()
						if (uri != null) {
							val customConfig = PromptConfig("custom", "Voice Query", recognizedQuery, 1, 0.5)
							lastUsedPrompt = customConfig
							onExecuteAnalysis(uri, customConfig)
						} else {
							AccessibilityUtils.announceToTalkBack(context, "Error capturing screen.")
						}
					}
				}
				override fun onPartialResults(partialResults: Bundle?) {}
				override fun onEvent(eventType: Int, params: Bundle?) {}
			})
			speechRecognizer.startListening(intent)
		}
	}

	val repeatLastUsedPrompt = {
		val uri = captureWebView()
		if (uri != null) {
			val prompt = lastUsedPrompt
			if (prompt != null) {
				onExecuteAnalysis(uri, prompt)
			} else {
				AccessibilityUtils.announceToTalkBack(context, "No previous prompt to repeat.")
			}
		} else {
			AccessibilityUtils.announceToTalkBack(context, "Error capturing screen.")
		}
	}

	LaunchedEffect(Unit) {
		onVoiceTriggerReady(startVoiceRecognition)
		onRepeatLastPromptReady(repeatLastUsedPrompt)
	}

	Column(modifier = Modifier.fillMaxSize()) {
		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			AndroidView(
				factory = { ctx ->
					WebView(ctx).apply {
						this.settings.javaScriptEnabled = true
						this.settings.domStorageEnabled = true
						this.settings.loadWithOverviewMode = true
						this.settings.useWideViewPort = true
						webViewClient = WebViewClient()
						webViewRef = this
						loadUrl(currentUrl)
					}
				},
				update = { view -> if (view.url != currentUrl) view.loadUrl(currentUrl) },
				modifier = Modifier.fillMaxSize()
			)

			if (isExploreModeActive) {
				val activeGridSize = 6
				Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
					for (screenRow in 1..activeGridSize) {
						Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
							for (screenCol in 1..activeGridSize) {
								val coordinateKey = "row${screenRow}_col${screenCol}"
								val cellDescription = if (isExploreLoading) "Analyzing elements" else exploreGridMap[coordinateKey] ?: "Empty"
								Box(
									modifier = Modifier.weight(1f).fillMaxHeight()
										.clickable(enabled = !isExploreLoading) {
											AccessibilityUtils.announceToTalkBack(context, cellDescription)
											isExploreModeActive = false; exploreGridMap = emptyMap()
										}.clearAndSetSemantics { contentDescription = cellDescription }
								)
							}
						}
					}
				}
			}
		}

		Row(modifier = Modifier.fillMaxWidth().height(80.dp).background(Color.DarkGray)) {
			val buttons = listOf("Microphone", settings.prompts.getOrNull(0)?.title ?: "Prompt 1", dynamicPrompt.title, "More", "Open file, webpage or camera")

			buttons.forEachIndexed { index, label ->
				Box(
					modifier = Modifier.weight(1f).fillMaxHeight()
						.clickable {
							when (label) {
								"Microphone" -> startVoiceRecognition()
								"More" -> showMoreMenu = true
								"Open file, webpage or camera" -> showSourceMenu = true
								else -> {
									val uri = captureWebView()
									if (uri != null) {
										val configToRun = if (index == 1) settings.prompts[0] else dynamicPrompt
										lastUsedPrompt = configToRun
										onExecuteAnalysis(uri, configToRun)
									} else {
										AccessibilityUtils.announceToTalkBack(context, "Error capturing screen.")
									}
								}
							}
						}.clearAndSetSemantics { contentDescription = label },
					contentAlignment = Alignment.Center
				) {
					Text(text = label, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))

					if (label == "More") {
						DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
							DropdownMenuItem(text = { Text("Explore Mode") }, onClick = {
								showMoreMenu = false
								val uri = captureWebView()
								if (uri != null) {
									isExploreModeActive = true
									isExploreLoading = true
									onExecuteExplore(uri) { map ->
										isExploreLoading = false
										if (map != null) exploreGridMap = map else isExploreModeActive = false
									}
								}
							})
							DropdownMenuItem(text = { Text(if (volKeysEnabled) "Vol Keys: ON" else "Vol Keys: OFF") }, onClick = {
								val newState = !volKeysEnabled
								volKeysEnabled = newState
								prefs.volumeShortcutsEnabled = newState
								AccessibilityUtils.announceToTalkBack(context, if (newState) "Volume shortcuts enabled" else "Volume shortcuts disabled")
								showMoreMenu = false
							})
							DropdownMenuItem(text = { Text("Settings") }, onClick = {
								showMoreMenu = false
								settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
							})
							settings.prompts.forEach { pConf ->
								DropdownMenuItem(text = { Text(pConf.title) }, onClick = {
									showMoreMenu = false
									dynamicPrompt = pConf
									lastUsedPrompt = pConf
									val uri = captureWebView()
									if (uri != null) onExecuteAnalysis(uri, pConf)
								})
							}
						}
					}

					if (label == "Open file, webpage or camera") {
						DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }) {
							DropdownMenuItem(text = { Text("Open File") }, onClick = {
								showSourceMenu = false
								onNavigateToMedia("OPEN_FILE")
							})
							DropdownMenuItem(text = { Text("Front Camera") }, onClick = {
								showSourceMenu = false
								onNavigateToMedia("OPEN_FRONT_CAM")
							})
							DropdownMenuItem(text = { Text("Back Camera") }, onClick = {
								showSourceMenu = false
								onNavigateToMedia("OPEN_BACK_CAM")
							})
							DropdownMenuItem(text = { Text("Web page") }, onClick = {
								showSourceMenu = false
								showUrlDialog = true
							})
						}
					}
				}
			}
		}
	}

	if (showUrlDialog) {
		var urlHistoryList by remember { mutableStateOf(prefs.getUrlHistoryList()) }

		fun openUrl(targetInput: String) {
			showUrlDialog = false
			val finalUrl = if (targetInput.startsWith("http://") || targetInput.startsWith("https://")) targetInput else "https://$targetInput"
			currentUrl = finalUrl
			val updated = (listOf(finalUrl) + urlHistoryList).distinct().take(5)
			prefs.saveUrlHistory(updated)
		}

		AlertDialog(
			onDismissRequest = { showUrlDialog = false },
			title = { Text("Enter Web URL") },
			text = {
				Column(modifier = Modifier.fillMaxWidth()) {
					OutlinedTextField(
						value = urlInput, onValueChange = { urlInput = it }, label = { Text("URL or Domain") }, singleLine = true,
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
						keyboardActions = KeyboardActions(onGo = { openUrl(urlInput) })
					)
					Spacer(modifier = Modifier.padding(top = 8.dp))
					urlHistoryList.forEach { url ->
						Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
							Text(text = url, modifier = Modifier.weight(1f).clickable { openUrl(url) }, color = Color.Gray)
							TextButton(onClick = {
								val updated = urlHistoryList.filter { it != url }
								urlHistoryList = updated
								prefs.saveUrlHistory(updated)
							}) { Text("Remove") }
						}
					}
				}
			},
			confirmButton = { Button(onClick = { openUrl(urlInput) }) { Text("OK") } },
			dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } }
		)
	}
}
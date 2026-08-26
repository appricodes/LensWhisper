package com.appricodes.lens_whisper

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.network.LocalServerAuth
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.offline.OfflineTranslator
import com.appricodes.lens_whisper.ui.analysis.rememberAnalysisSessionController
import com.appricodes.lens_whisper.ui.components.ModelTopBar
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val prefs = PreferencesManager(this)
		val appSettings = SettingsRepository(this).loadSettingsFromAssets()
		val availableLanguages = appSettings.languages
		val standardPersonas = listOf("Neutral", "Family", "Childish", "Technical", "explicit", "explicit and naughty", "Humorous", "poetry")
		val availableOcrLanguages = listOf("Latin (English/EU)", "Chinese", "Japanese", "Korean", "Devanagari")
		val availableQualities = listOf("Standard", "High")

		setContent {
			LensWhisperTheme {
				Surface(
					modifier = Modifier.fillMaxSize(),
					color = MaterialTheme.colorScheme.background
				) {
					SettingsScreen(
						prefs = prefs,
						availableLanguages = availableLanguages,
						availablePersonas = standardPersonas,
						availableOcrLanguages = availableOcrLanguages,
						availableQualities = availableQualities,
						onBack = { finish() }
					)
				}
			}
		}
	}

	override fun onStop() {
		super.onStop()
		val prefs = PreferencesManager(this)
		val langCode = prefs.selectedLanguageCode

		// If the user picked a non-English language, trigger background model sync for offline translation
		if (!langCode.startsWith("en", ignoreCase = true)) {
			OfflineTranslator.manageModelsInBackground(langCode)
		}
	}
}

@Composable
fun SettingsScreen(
	prefs: PreferencesManager,
	availableLanguages: Map<String, String>,
	availablePersonas: List<String>,
	availableOcrLanguages: List<String>,
	availableQualities: List<String>,
	onBack: () -> Unit
) {
	val context = androidx.compose.ui.platform.LocalContext.current
	val sharedPrefs = remember { context.getSharedPreferences("LensWhisperPrefs", Context.MODE_PRIVATE) }

	var selectedLanguageCode by remember { mutableStateOf(sharedPrefs.getString("selected_language_code", "en-US") ?: "en-US") }
	var selectedLanguageName by remember { mutableStateOf(sharedPrefs.getString("selected_language", "English") ?: "English") }

	var selectedOcrLanguage by remember { mutableStateOf(sharedPrefs.getString("ocr_language", "Latin (English/EU)") ?: "Latin (English/EU)") }
	var selectedPersona by remember { mutableStateOf(sharedPrefs.getString("selected_mode", "Neutral") ?: "Neutral") }
	var selectedQuality by remember { mutableStateOf(sharedPrefs.getString("analysis_quality", "High") ?: "High") }

	var showResultsInDialog by remember { mutableStateOf(sharedPrefs.getBoolean("show_results_in_dialog", false)) }
	var offlineTranslationEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("offline_translation_enabled", false)) }
	var streamingEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("streaming_enabled", false)) }
	var soundEffectsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("sound_effects_enabled", true)) }
	var vibrationEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("vibration_enabled", true)) }

	var localServerAddress by remember { mutableStateOf(sharedPrefs.getString("local_server_base_url", "") ?: "") }
	var showLocalServerEditDialog by remember { mutableStateOf(false) }
	var localServerEditText by remember { mutableStateOf(localServerAddress) }

	var hasLocalServerPassword by remember { mutableStateOf(sharedPrefs.getString("local_server_password_token", "")?.isNotBlank() == true) }
	var showPasswordEditDialog by remember { mutableStateOf(false) }
	var passwordEditText by remember { mutableStateOf("") }
	var isScanningForLocalServer by remember { mutableStateOf(false) }
	val coroutineScope = rememberCoroutineScope()

	var langDropdownExpanded by remember { mutableStateOf(false) }
	var ocrDropdownExpanded by remember { mutableStateOf(false) }
	var personaDropdownExpanded by remember { mutableStateOf(false) }
	var qualityDropdownExpanded by remember { mutableStateOf(false) }

	var segmentationSize by remember { mutableStateOf(prefs.segmentationModelSize) }
	var segmentationPrecision by remember { mutableStateOf(prefs.segmentationModelPrecision) }
	var segmentationSizeDropdownExpanded by remember { mutableStateOf(false) }
	var segmentationPrecisionDropdownExpanded by remember { mutableStateOf(false) }


	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp)
			.verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(28.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(text = "Configuration", fontSize = 26.sp, style = MaterialTheme.typography.titleLarge)
			TextButton(
				onClick = onBack,
				modifier = Modifier.clearAndSetSemantics { contentDescription = "Close settings and return to layout panel." }
			) { Text("Close", fontSize = 18.sp) }
		}

		// Model selector and More button are both hidden here: switching models has no clear use
		// from inside Settings, and the More menu leads back to Settings among other places. What's
		// left is just the Support button — see ModelTopBar's doc. With no way to switch models from
		// this screen, there's also nothing left for a download-progress/model-loading observer to
		// ever have to show, so neither is wired up here any more.
		val topBarController = rememberAnalysisSessionController(context, prefs)
		ModelTopBar(
			prefs = prefs,
			controller = topBarController,
			selectedModelId = prefs.selectedModelId,
			onModelSelected = {},
			showModelSelector = false,
			showMoreButton = false,
			modifier = Modifier.clip(RoundedCornerShape(12.dp)),
			backgroundColor = MaterialTheme.colorScheme.surfaceVariant
		)

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "AI Analysis Quality", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { qualityDropdownExpanded = true }
					.padding(16.dp)
					.clearAndSetSemantics { contentDescription = "Active AI analysis quality is $selectedQuality. Double tap to change." },
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = selectedQuality, fontSize = 18.sp)
				DropdownMenu(expanded = qualityDropdownExpanded, onDismissRequest = { qualityDropdownExpanded = false }) {
					availableQualities.forEach { quality ->
						DropdownMenuItem(text = { Text(quality) }, onClick = {
							selectedQuality = quality
							sharedPrefs.edit().putString("analysis_quality", quality).apply()
							qualityDropdownExpanded = false
						})
					}
				}
			}
		}

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Object Detection Model Size", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { segmentationSizeDropdownExpanded = true }
					.padding(16.dp)
					.clearAndSetSemantics {
						contentDescription = "Object detection model size is ${OfflineSegmentationDetector.sizeDisplayName(segmentationSize)}. Double tap to change."
					},
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = OfflineSegmentationDetector.sizeDisplayName(segmentationSize), fontSize = 18.sp)
				DropdownMenu(expanded = segmentationSizeDropdownExpanded, onDismissRequest = { segmentationSizeDropdownExpanded = false }) {
					OfflineSegmentationDetector.ALL_SIZES.forEach { size ->
						DropdownMenuItem(text = { Text(OfflineSegmentationDetector.sizeDisplayName(size)) }, onClick = {
							segmentationSize = size
							prefs.segmentationModelSize = size
							segmentationSizeDropdownExpanded = false
						})
					}
				}
			}
			Text(
				text = "Used by Object Detection, Object and Color Detection, and both Explore screens. " +
					"Larger models are slower but notice more, and get colors and mask edges more precisely.",
				fontSize = 13.sp,
				color = Color.Gray
			)
		}

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Object Detection Model Precision", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { segmentationPrecisionDropdownExpanded = true }
					.padding(16.dp)
					.clearAndSetSemantics {
						contentDescription = "Object detection model precision is ${OfflineSegmentationDetector.precisionDisplayName(segmentationPrecision)}. Double tap to change."
					},
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = OfflineSegmentationDetector.precisionDisplayName(segmentationPrecision), fontSize = 18.sp)
				DropdownMenu(expanded = segmentationPrecisionDropdownExpanded, onDismissRequest = { segmentationPrecisionDropdownExpanded = false }) {
					OfflineSegmentationDetector.ALL_PRECISIONS.forEach { precision ->
						DropdownMenuItem(text = { Text(OfflineSegmentationDetector.precisionDisplayName(precision)) }, onClick = {
							segmentationPrecision = precision
							prefs.segmentationModelPrecision = precision
							segmentationPrecisionDropdownExpanded = false
						})
					}
				}
			}
			Text(
				text = "int8 is smaller and faster; float16 keeps more numeric detail at some extra size and time.",
				fontSize = 13.sp,
				color = Color.Gray
			)
		}

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Narration Language Profile", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { langDropdownExpanded = true }
					.padding(16.dp)
					.clearAndSetSemantics { contentDescription = "Active AI narration language is $selectedLanguageName. Double tap to cycle selections." },
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = selectedLanguageName, fontSize = 18.sp)
				DropdownMenu(expanded = langDropdownExpanded, onDismissRequest = { langDropdownExpanded = false }) {
					availableLanguages.entries.sortedBy { it.value }.forEach { (code, name) ->
						DropdownMenuItem(text = { Text(name) }, onClick = {
							selectedLanguageCode = code
							selectedLanguageName = name
							sharedPrefs.edit().putString("selected_language_code", code).putString("selected_language", name).apply()
							langDropdownExpanded = false
						})
					}
				}
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 4.dp)
					.clickable {
						val targetState = !offlineTranslationEnabled
						offlineTranslationEnabled = targetState
						sharedPrefs.edit().putBoolean("offline_translation_enabled", targetState).apply()
					}
					.clearAndSetSemantics {
						val status = if (offlineTranslationEnabled) "enabled" else "disabled"
						contentDescription = "Translate offline results to $selectedLanguageName is currently $status. Applies to Basic Models and to on-device AI model responses, when a translation is available. Double tap to toggle."
					},
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(text = "Translate Offline Results (Basic Models + On-Device AI)", fontSize = 16.sp, color = Color.LightGray)
				Checkbox(checked = offlineTranslationEnabled, onCheckedChange = { targetState ->
					offlineTranslationEnabled = targetState
					sharedPrefs.edit().putBoolean("offline_translation_enabled", targetState).apply()
				})
			}
		}

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Narration Persona / Style Mode", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { personaDropdownExpanded = true }
					.padding(16.dp)
					.clearAndSetSemantics { contentDescription = "Active persona mode is $selectedPersona. Double tap to change." },
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = selectedPersona, fontSize = 18.sp)
				DropdownMenu(expanded = personaDropdownExpanded, onDismissRequest = { personaDropdownExpanded = false }) {
					availablePersonas.forEach { personaMode ->
						DropdownMenuItem(text = { Text(personaMode) }, onClick = {
							selectedPersona = personaMode
							sharedPrefs.edit().putString("selected_mode", personaMode).apply()
							personaDropdownExpanded = false
						})
					}
				}
			}
		}

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Offline OCR Language", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { ocrDropdownExpanded = true }
					.padding(16.dp)
					.clearAndSetSemantics { contentDescription = "Active Offline OCR language is $selectedOcrLanguage. Double tap to cycle selections." },
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = selectedOcrLanguage, fontSize = 18.sp)
				DropdownMenu(expanded = ocrDropdownExpanded, onDismissRequest = { ocrDropdownExpanded = false }) {
					availableOcrLanguages.forEach { lang ->
						DropdownMenuItem(text = { Text(lang) }, onClick = {
							selectedOcrLanguage = lang
							sharedPrefs.edit().putString("ocr_language", lang).apply()
							ocrDropdownExpanded = false
						})
					}
				}
			}
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable {
					val targetState = !showResultsInDialog
					showResultsInDialog = targetState
					sharedPrefs.edit().putBoolean("show_results_in_dialog", targetState).apply()
				}
				.clearAndSetSemantics {
					val statusText = if (showResultsInDialog) "enabled" else "disabled"
					contentDescription = "Show results in a text alert box option is currently $statusText. Double tap to toggle."
				},
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Text(text = "Display Results in Text Dialog", fontSize = 18.sp)
			Checkbox(checked = showResultsInDialog, onCheckedChange = { targetState ->
				showResultsInDialog = targetState
				sharedPrefs.edit().putBoolean("show_results_in_dialog", targetState).apply()
			})
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable {
					val targetState = !streamingEnabled
					streamingEnabled = targetState
					sharedPrefs.edit().putBoolean("streaming_enabled", targetState).apply()
				}
				.clearAndSetSemantics {
					val statusText = if (streamingEnabled) "enabled" else "disabled"
					contentDescription = "Stream responses sentence by sentence option is currently $statusText. When enabled, each sentence is spoken as soon as it's ready instead of waiting for the full response. Double tap to toggle."
				},
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Text(text = "Stream Responses (Speak Sentence by Sentence)", fontSize = 18.sp)
			Checkbox(checked = streamingEnabled, onCheckedChange = { targetState ->
				streamingEnabled = targetState
				sharedPrefs.edit().putBoolean("streaming_enabled", targetState).apply()
			})
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable {
					val targetState = !soundEffectsEnabled
					soundEffectsEnabled = targetState
					sharedPrefs.edit().putBoolean("sound_effects_enabled", targetState).apply()
				}
				.clearAndSetSemantics {
					val statusText = if (soundEffectsEnabled) "enabled" else "disabled"
					contentDescription = "Sound effects option is currently $statusText. Covers the shutter click, the waiting-for-results tone, and Explore's touch tones. Double tap to toggle."
				},
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Text(text = "Sound Effects", fontSize = 18.sp)
			Checkbox(checked = soundEffectsEnabled, onCheckedChange = { targetState ->
				soundEffectsEnabled = targetState
				sharedPrefs.edit().putBoolean("sound_effects_enabled", targetState).apply()
			})
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable {
					val targetState = !vibrationEnabled
					vibrationEnabled = targetState
					sharedPrefs.edit().putBoolean("vibration_enabled", targetState).apply()
				}
				.clearAndSetSemantics {
					val statusText = if (vibrationEnabled) "enabled" else "disabled"
					contentDescription = "Vibration option is currently $statusText. Covers Explore's touch feedback when moving your finger over a photo. Double tap to toggle."
				},
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Text(text = "Vibration", fontSize = 18.sp)
			Checkbox(checked = vibrationEnabled, onCheckedChange = { targetState ->
				vibrationEnabled = targetState
				sharedPrefs.edit().putBoolean("vibration_enabled", targetState).apply()
			})
		}

		if (LocalServerFeature.ENABLED) {
			// Hidden while the LAN feature is switched off - there is nothing to point these at.
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text = "Local Server Address", fontSize = 18.sp, color = Color.Gray)
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.background(MaterialTheme.colorScheme.surfaceVariant)
						.clickable { localServerEditText = localServerAddress; showLocalServerEditDialog = true }
						.padding(16.dp)
						.clearAndSetSemantics {
							contentDescription = if (localServerAddress.isBlank())
								"Local Server address not found yet. Double tap to enter one manually."
							else "Local Server address is $localServerAddress. Double tap to edit."
						},
					contentAlignment = Alignment.CenterStart
				) {
					Text(text = localServerAddress.ifBlank { "Not found — tap to enter manually" }, fontSize = 18.sp)
				}
				Text(
					text = "Auto-detected when you select Local Server and it's reachable on your Wi-Fi network. " +
						"Only needed here if auto-detection doesn't find it (e.g. a different subnet).",
					fontSize = 13.sp,
					color = Color.Gray
				)
				TextButton(
					onClick = {
						isScanningForLocalServer = true
						coroutineScope.launch {
							val found = LocalServerDiscovery.discover(context)
							if (found != null) {
								localServerAddress = found
								sharedPrefs.edit().putString("local_server_base_url", found).apply()
							}
							isScanningForLocalServer = false
						}
					},
					enabled = !isScanningForLocalServer
				) {
					Text(if (isScanningForLocalServer) "Scanning…" else "Rescan Network")
				}
			}

			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text = "Local Server Password", fontSize = 18.sp, color = Color.Gray)
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.background(MaterialTheme.colorScheme.surfaceVariant)
						.clickable { passwordEditText = ""; showPasswordEditDialog = true }
						.padding(16.dp)
						.clearAndSetSemantics {
							contentDescription = if (hasLocalServerPassword)
								"Local Server password is set. Double tap to change or remove it."
							else "No Local Server password set. Double tap to set one."
						},
					contentAlignment = Alignment.CenterStart
				) {
					Text(text = if (hasLocalServerPassword) "Password set" else "Not set — tap to set a password", fontSize = 18.sp)
				}
				Text(
					text = "Required to use Local Server mode — must match the password set on the computer. " +
						"Every request and response between this phone and that computer is encrypted using " +
						"it; only the password's token is ever stored here, never the password itself.",
					fontSize = 13.sp,
					color = Color.Gray
				)
			}
		}
	}

	if (showLocalServerEditDialog) {
		AlertDialog(
			onDismissRequest = { showLocalServerEditDialog = false },
			title = { Text("Local Server Address") },
			text = {
				OutlinedTextField(
					value = localServerEditText,
					onValueChange = { localServerEditText = it },
					label = { Text("e.g. http://192.168.1.42:8765") },
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = {
					localServerAddress = localServerEditText.trim()
					sharedPrefs.edit().putString("local_server_base_url", localServerAddress).apply()
					showLocalServerEditDialog = false
				}) { Text("Save") }
			},
			dismissButton = {
				TextButton(onClick = { showLocalServerEditDialog = false }) { Text("Cancel") }
			}
		)
	}

	if (showPasswordEditDialog) {
		AlertDialog(
			onDismissRequest = { showPasswordEditDialog = false },
			title = { Text("Local Server Password") },
			text = {
				OutlinedTextField(
					value = passwordEditText,
					onValueChange = { passwordEditText = it },
					label = { Text("New password") },
					visualTransformation = PasswordVisualTransformation(),
					singleLine = true
				)
			},
			confirmButton = {
				TextButton(onClick = {
					val token = LocalServerAuth.tokenFor(passwordEditText)
					sharedPrefs.edit().putString("local_server_password_token", token).apply()
					hasLocalServerPassword = true
					showPasswordEditDialog = false
				}, enabled = passwordEditText.isNotBlank()) { Text("Save") }
			},
			dismissButton = {
				Row {
					if (hasLocalServerPassword) {
						TextButton(onClick = {
							sharedPrefs.edit().remove("local_server_password_token").apply()
							hasLocalServerPassword = false
							showPasswordEditDialog = false
						}) { Text("Remove") }
					}
					TextButton(onClick = { showPasswordEditDialog = false }) { Text("Cancel") }
				}
			}
		)
	}
}
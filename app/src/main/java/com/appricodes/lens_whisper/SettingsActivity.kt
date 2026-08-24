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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.offline.OfflineTranslator
import com.appricodes.lens_whisper.ui.analysis.rememberAnalysisSessionController
import com.appricodes.lens_whisper.ui.components.ModelTopBar
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme

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

	var langDropdownExpanded by remember { mutableStateOf(false) }
	var ocrDropdownExpanded by remember { mutableStateOf(false) }
	var personaDropdownExpanded by remember { mutableStateOf(false) }
	var qualityDropdownExpanded by remember { mutableStateOf(false) }

	val freeCredits by remember { mutableStateOf(sharedPrefs.getInt("free_credits", 0)) }
	val purchasedCredits by remember { mutableStateOf(sharedPrefs.getInt("purchased_credits", 0)) }

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

		val topBarController = rememberAnalysisSessionController(context, prefs)
		var topBarSelectedModelId by remember { mutableStateOf(prefs.selectedModelId) }
		ModelTopBar(
			prefs = prefs,
			controller = topBarController,
			selectedModelId = topBarSelectedModelId,
			onModelSelected = { id -> prefs.selectedModelId = id; topBarSelectedModelId = id },
			modifier = Modifier.clip(RoundedCornerShape(12.dp)),
			backgroundColor = MaterialTheme.colorScheme.surfaceVariant
		)
		topBarController.ObserveDownloads()
		val topBarOfflineModels = remember { com.appricodes.lens_whisper.data.OfflineModelsRepository(context).loadModels() }
		com.appricodes.lens_whisper.ui.components.ModelLoadingObserver(context = context, selectedModelId = topBarSelectedModelId, offlineModels = topBarOfflineModels)

		Card(
			modifier = Modifier
				.fillMaxWidth()
				.clearAndSetSemantics {
					contentDescription = "You currently have $freeCredits free credits and $purchasedCredits purchased credits."
				},
			colors = CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.primaryContainer
			),
			elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(16.dp),
				horizontalAlignment = Alignment.CenterHorizontally
			) {
				Text(
					text = "Your Credits",
					fontSize = 16.sp,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onPrimaryContainer
				)
				Spacer(modifier = Modifier.height(8.dp))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceEvenly,
					verticalAlignment = Alignment.CenterVertically
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(
							text = freeCredits.toString(),
							fontSize = 32.sp,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.onPrimaryContainer
						)
						Text(
							text = "Free",
							fontSize = 14.sp,
							color = MaterialTheme.colorScheme.onPrimaryContainer
						)
					}
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(
							text = purchasedCredits.toString(),
							fontSize = 32.sp,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.onPrimaryContainer
						)
						Text(
							text = "Purchased",
							fontSize = 14.sp,
							color = MaterialTheme.colorScheme.onPrimaryContainer
						)
					}
				}
			}
		}

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
						contentDescription = "Translate offline results to $selectedLanguageName is currently $status. Applies to MLKit and to on-device AI model responses, when a translation is available. Double tap to toggle."
					},
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(text = "Translate Offline Results (MLKit + On-Device AI)", fontSize = 16.sp, color = Color.LightGray)
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
	}
}
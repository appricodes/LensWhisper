package com.appricodes.lens_whisper

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.appricodes.lens_whisper.data.NO_HEADSET_KEY_CODE
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.media.HeadsetKeyCapture
import com.appricodes.lens_whisper.media.HeadsetShortcuts
import com.appricodes.lens_whisper.network.LocalServerAuth
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.offline.OfflineTranslator
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
	private lateinit var headsetKeyCapture: HeadsetKeyCapture

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val prefs = PreferencesManager(this)
		val appSettings = SettingsRepository(this).loadSettingsFromAssets()
		val availableLanguages = appSettings.languages
		val standardPersonas = listOf("Neutral", "Family", "Technical", "poetry")
		val availableOcrLanguages = listOf("Latin (English/EU)", "Chinese", "Japanese", "Korean", "Devanagari")
		headsetKeyCapture = HeadsetKeyCapture(this)

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
						onBack = { finish() },
						onStartHeadsetCapture = { onCaptured -> headsetKeyCapture.start(onCaptured) },
						onStopHeadsetCapture = { headsetKeyCapture.stop() }
					)
				}
			}
		}
	}

	override fun onStop() {
		super.onStop()
		val prefs = PreferencesManager(this)
		val langCode = prefs.selectedLanguageCode

		// Backstop for the same call already made right at selection time (see the language
		// dropdown's onClick below) - cheap and idempotent (downloadModelIfNeeded no-ops once a
		// model's already present), so there's no harm keeping both.
		if (!langCode.startsWith("en", ignoreCase = true)) {
			OfflineTranslator.manageModelsInBackground(langCode)
		}
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		// Wired in-line "hook" buttons on some devices arrive here directly instead of through
		// HeadsetKeyCapture's media session — see HeadsetShortcuts' class doc. A no-op when no
		// capture is in progress.
		if (event != null && headsetKeyCapture.onWindowKeyDown(event)) return true
		return super.onKeyDown(keyCode, event)
	}

	override fun onDestroy() {
		super.onDestroy()
		headsetKeyCapture.stop()
	}
}

/** Which of the two headset shortcuts a capture-in-progress / pending-confirmation belongs to —
 *  see the "Headset Button Shortcuts" section below. */
private enum class HeadsetShortcutAction(val label: String) {
	REPEAT("Repeat Previous Prompt"),
	MIC("Get a Question from the Microphone")
}

@Composable
fun SettingsScreen(
	prefs: PreferencesManager,
	availableLanguages: Map<String, String>,
	availablePersonas: List<String>,
	availableOcrLanguages: List<String>,
	onBack: () -> Unit,
	onStartHeadsetCapture: ((Int) -> Unit) -> Unit,
	onStopHeadsetCapture: () -> Unit
) {
	val context = androidx.compose.ui.platform.LocalContext.current
	val sharedPrefs = remember { context.getSharedPreferences("LensWhisperPrefs", Context.MODE_PRIVATE) }

	var selectedLanguageCode by remember { mutableStateOf(sharedPrefs.getString("selected_language_code", "en-US") ?: "en-US") }
	var selectedLanguageName by remember { mutableStateOf(sharedPrefs.getString("selected_language", "English") ?: "English") }

	var selectedOcrLanguage by remember { mutableStateOf(sharedPrefs.getString("ocr_language", "Latin (English/EU)") ?: "Latin (English/EU)") }
	var selectedPersona by remember { mutableStateOf(sharedPrefs.getString("selected_mode", "Neutral") ?: "Neutral") }

	var showResultsInDialog by remember { mutableStateOf(sharedPrefs.getBoolean("show_results_in_dialog", false)) }
	var offlineTranslationEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("offline_translation_enabled", false)) }
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

	var reasoningEffort by remember { mutableStateOf(prefs.reasoningEffort) }
	var reasoningEffortDropdownExpanded by remember { mutableStateOf(false) }

	// Everything below lives in its own sub-window (see showHeadsetShortcutsDialog / the
	// HeadsetShortcutsDialog composable at the end of this file) rather than inline here, so this
	// otherwise-rarely-touched section doesn't permanently take up two rows' worth of the main
	// Settings screen.
	var showHeadsetShortcutsDialog by remember { mutableStateOf(false) }
	var headsetRepeatKeyCode by remember { mutableStateOf(prefs.headsetRepeatKeyCode) }
	var headsetMicKeyCode by remember { mutableStateOf(prefs.headsetMicKeyCode) }
	// Dialog #1 ("press a button now") is open while this is non-null; dialog #2 ("use this
	// button?") is open once a press has been captured, replacing it with the keycode to confirm.
	var listeningForHeadsetAction by remember { mutableStateOf<HeadsetShortcutAction?>(null) }
	var pendingHeadsetCapture by remember { mutableStateOf<Pair<HeadsetShortcutAction, Int>?>(null) }

	fun cancelHeadsetCapture() {
		listeningForHeadsetAction = null
		onStopHeadsetCapture()
	}

	fun beginHeadsetCapture(action: HeadsetShortcutAction) {
		listeningForHeadsetAction = action
		AccessibilityUtils.announceToTalkBack(context, "Press the headset button you want to use for ${action.label}, or Cancel.")
		onStartHeadsetCapture { keyCode ->
			listeningForHeadsetAction = null
			pendingHeadsetCapture = action to keyCode
			onStopHeadsetCapture()
			AccessibilityUtils.announceToTalkBack(
				context,
				"You pressed ${HeadsetShortcuts.displayName(keyCode)}. Confirm to use it for ${action.label}, or cancel."
			)
		}
	}

	fun confirmHeadsetCapture() {
		val (action, keyCode) = pendingHeadsetCapture ?: return
		// Both shortcuts fire immediately on press (see HeadsetShortcutGestureHandler) — there's no
		// duration-based disambiguation, so the same button can't usefully drive both at once.
		// Binding a button to one action steals it back from the other if it was already bound
		// there, same as before the (removed) press-duration experiment.
		val otherKeyCode = if (action == HeadsetShortcutAction.REPEAT) headsetMicKeyCode else headsetRepeatKeyCode
		if (otherKeyCode == keyCode) {
			when (action) {
				HeadsetShortcutAction.REPEAT -> { prefs.headsetMicKeyCode = NO_HEADSET_KEY_CODE; headsetMicKeyCode = NO_HEADSET_KEY_CODE }
				HeadsetShortcutAction.MIC -> { prefs.headsetRepeatKeyCode = NO_HEADSET_KEY_CODE; headsetRepeatKeyCode = NO_HEADSET_KEY_CODE }
			}
		}
		when (action) {
			HeadsetShortcutAction.REPEAT -> {
				prefs.headsetRepeatKeyCode = keyCode
				headsetRepeatKeyCode = keyCode
			}
			HeadsetShortcutAction.MIC -> {
				prefs.headsetMicKeyCode = keyCode
				headsetMicKeyCode = keyCode
			}
		}
		pendingHeadsetCapture = null
		val buttonName = HeadsetShortcuts.displayName(keyCode)
		val message = if (otherKeyCode == keyCode) {
			"${action.label} is now bound to $buttonName. The other shortcut was using that button too, so it's now unbound."
		} else {
			"${action.label} is now bound to $buttonName."
		}
		AccessibilityUtils.announceToTalkBack(context, message)
	}

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
							// Start fetching this language's translation model(s) right away rather
							// than waiting for the screen to close (see onStop below, kept as a
							// backstop) - selecting a language is as clear a signal as any that the
							// user wants translation ready for it.
							if (!code.startsWith("en", ignoreCase = true)) OfflineTranslator.manageModelsInBackground(code)
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
						contentDescription = "Translate offline results to $selectedLanguageName is currently $status. Translates Basic tasks and on-device AI results, except Read Text and Read Document, which always read text exactly as written. Double tap to toggle."
					},
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(text = "Translate Offline Results (Basic tasks + On-Device AI)", fontSize = 16.sp, color = Color.LightGray)
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
							// Start fetching this script's OCR module right away, same reasoning as
							// the narration-language dropdown above - see OfflineTextRecognizer's
							// own doc for why this is fire-and-forget and safe to call repeatedly.
							OfflineTextRecognizer.startModuleDownload(context, lang)
						})
					}
				}
			}
		}

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Reasoning Effort (Gemma models only)", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { reasoningEffortDropdownExpanded = true }
					.padding(16.dp)
					.clearAndSetSemantics {
						contentDescription = "Reasoning effort is ${OfflineInferenceEngine.reasoningEffortDisplayName(reasoningEffort)}. Double tap to change."
					},
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = OfflineInferenceEngine.reasoningEffortDisplayName(reasoningEffort), fontSize = 18.sp)
				DropdownMenu(expanded = reasoningEffortDropdownExpanded, onDismissRequest = { reasoningEffortDropdownExpanded = false }) {
					OfflineInferenceEngine.ALL_REASONING_EFFORTS.forEach { effort ->
						DropdownMenuItem(text = { Text(OfflineInferenceEngine.reasoningEffortDisplayName(effort)) }, onClick = {
							reasoningEffort = effort
							prefs.reasoningEffort = effort
							reasoningEffortDropdownExpanded = false
						})
					}
				}
			}
			Text(
				text = "How much the model thinks before answering, when the selected model supports it — currently " +
					"only Gemma. Higher takes longer and uses more battery. Has no effect on any other model.",
				fontSize = 13.sp,
				color = Color.Gray
			)
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

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Headset Button Shortcuts", fontSize = 18.sp, color = Color.Gray)
			val headsetSummary = when {
				headsetRepeatKeyCode == NO_HEADSET_KEY_CODE && headsetMicKeyCode == NO_HEADSET_KEY_CODE -> "Not set up"
				headsetRepeatKeyCode == NO_HEADSET_KEY_CODE -> "Microphone button set"
				headsetMicKeyCode == NO_HEADSET_KEY_CODE -> "Repeat button set"
				else -> "Both buttons set"
			}
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.clickable { showHeadsetShortcutsDialog = true }
					.padding(16.dp)
					.clearAndSetSemantics {
						contentDescription = "Headset Button Shortcuts. $headsetSummary. Double tap to open. Bind a wired or " +
							"Bluetooth headset button to repeat the last prompt, or to ask a question by voice."
					},
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = headsetSummary, fontSize = 18.sp)
			}
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

	if (showHeadsetShortcutsDialog) {
		HeadsetShortcutsDialog(
			headsetRepeatKeyCode = headsetRepeatKeyCode,
			headsetMicKeyCode = headsetMicKeyCode,
			onDismiss = { showHeadsetShortcutsDialog = false },
			onCapture = { action -> beginHeadsetCapture(action) },
			onClear = { action ->
				when (action) {
					HeadsetShortcutAction.REPEAT -> {
						prefs.headsetRepeatKeyCode = NO_HEADSET_KEY_CODE
						headsetRepeatKeyCode = NO_HEADSET_KEY_CODE
					}
					HeadsetShortcutAction.MIC -> {
						prefs.headsetMicKeyCode = NO_HEADSET_KEY_CODE
						headsetMicKeyCode = NO_HEADSET_KEY_CODE
					}
				}
				AccessibilityUtils.announceToTalkBack(context, "${action.label} shortcut cleared.")
			}
		)
	}

	// listeningForHeadsetAction/pendingHeadsetCapture's two dialogs render on top of
	// HeadsetShortcutsDialog above (each Dialog is its own window, so they stack correctly) —
	// beginHeadsetCapture/confirmHeadsetCapture don't close that sub-window themselves, so the
	// user lands back on it (with the newly-bound button visible) once they're done, rather than
	// being dropped all the way back to the main Settings screen.
	listeningForHeadsetAction?.let { action ->
		AlertDialog(
			onDismissRequest = { cancelHeadsetCapture() },
			title = { Text("Press a Headset Button") },
			text = {
				Text(
					"Press the button on your wired or Bluetooth headset you want to use for " +
						"\"${action.label}\"."
				)
			},
			confirmButton = {},
			dismissButton = {
				TextButton(onClick = { cancelHeadsetCapture() }) { Text("Cancel") }
			}
		)
	}

	pendingHeadsetCapture?.let { (action, keyCode) ->
		val otherKeyCode = if (action == HeadsetShortcutAction.REPEAT) headsetMicKeyCode else headsetRepeatKeyCode
		val message = if (otherKeyCode == keyCode) {
			"You pressed: ${HeadsetShortcuts.displayName(keyCode)} — the same button already bound to the other " +
				"shortcut. Use it for \"${action.label}\" instead? The other shortcut will be unbound."
		} else {
			"You pressed: ${HeadsetShortcuts.displayName(keyCode)}. Use it for \"${action.label}\"?"
		}
		AlertDialog(
			onDismissRequest = { pendingHeadsetCapture = null },
			title = { Text("Use This Button?") },
			text = { Text(message) },
			confirmButton = {
				TextButton(onClick = { confirmHeadsetCapture() }) { Text("Set") }
			},
			dismissButton = {
				TextButton(onClick = { pendingHeadsetCapture = null }) { Text("Cancel") }
			}
		)
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

/** The "Headset Button Shortcuts" sub-window — opened from its one summary row on the main
 *  Settings screen (see `showHeadsetShortcutsDialog`) rather than living inline there, so this
 *  rarely-touched section doesn't permanently take up two rows on the main screen. Holds the two
 *  [HeadsetShortcutRow]s; the actual capture/confirm flow still renders as its own dialogs layered
 *  on top of this one (see the call site). */
@Composable
private fun HeadsetShortcutsDialog(
	headsetRepeatKeyCode: Int,
	headsetMicKeyCode: Int,
	onDismiss: () -> Unit,
	onCapture: (HeadsetShortcutAction) -> Unit,
	onClear: (HeadsetShortcutAction) -> Unit
) {
	Dialog(onDismissRequest = onDismiss) {
		Surface(
			shape = RoundedCornerShape(12.dp),
			color = MaterialTheme.colorScheme.surface,
			modifier = Modifier.fillMaxWidth(0.92f)
		) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(text = "Headset Button Shortcuts", fontSize = 20.sp, style = MaterialTheme.typography.titleMedium)
					TextButton(
						onClick = onDismiss,
						modifier = Modifier.clearAndSetSemantics { contentDescription = "Close Headset Button Shortcuts." }
					) { Text("Close") }
				}
				Text(
					text = "Bind a button on a wired or Bluetooth headset to repeat the last prompt, or to ask a " +
						"question by voice, without touching the screen. Each needs its own button — binding one " +
						"shortcut to a button already used by the other unbinds it there.",
					fontSize = 13.sp,
					color = Color.Gray
				)
				HeadsetShortcutRow(
					title = HeadsetShortcutAction.REPEAT.label,
					boundKeyCode = headsetRepeatKeyCode,
					onClick = { onCapture(HeadsetShortcutAction.REPEAT) },
					onClear = { onClear(HeadsetShortcutAction.REPEAT) }
				)
				HeadsetShortcutRow(
					title = HeadsetShortcutAction.MIC.label,
					boundKeyCode = headsetMicKeyCode,
					onClick = { onCapture(HeadsetShortcutAction.MIC) },
					onClear = { onClear(HeadsetShortcutAction.MIC) }
				)
			}
		}
	}
}

/** One row of the "Headset Button Shortcuts" section — shows the currently bound button (if any)
 *  and opens the capture flow on tap; a "Clear" button appears only once something is bound. */
@Composable
private fun HeadsetShortcutRow(
	title: String,
	boundKeyCode: Int,
	onClick: () -> Unit,
	onClear: () -> Unit
) {
	val isBound = boundKeyCode != NO_HEADSET_KEY_CODE
	val boundLabel = if (isBound) HeadsetShortcuts.displayName(boundKeyCode) else null
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.surfaceVariant)
				.clickable(onClick = onClick)
				.padding(16.dp)
				.clearAndSetSemantics {
					contentDescription = if (isBound)
						"$title is bound to $boundLabel. Double tap to change it."
					else "$title is not bound to a headset button. Double tap to choose one."
				},
			contentAlignment = Alignment.CenterStart
		) {
			Column {
				Text(text = title, fontSize = 16.sp)
				Text(text = boundLabel ?: "Not set — tap to choose a button", fontSize = 13.sp, color = Color.Gray)
			}
		}
		if (isBound) {
			TextButton(
				onClick = onClear,
				modifier = Modifier.clearAndSetSemantics { contentDescription = "Clear the $title headset shortcut." }
			) { Text("Clear") }
		}
	}
}
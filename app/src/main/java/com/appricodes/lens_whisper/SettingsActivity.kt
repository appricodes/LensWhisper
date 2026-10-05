package com.appricodes.lens_whisper

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.appricodes.lens_whisper.data.NO_HEADSET_KEY_CODE
import com.appricodes.lens_whisper.data.DataTransferManager
import com.appricodes.lens_whisper.data.DataTransferResult
import com.appricodes.lens_whisper.data.ExportFileRef
import com.appricodes.lens_whisper.data.ExportSelection
import com.appricodes.lens_whisper.data.ProductDatabase
import com.appricodes.lens_whisper.data.ProductDbInfo
import com.appricodes.lens_whisper.data.ProductDbSource
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.data.SettingsRepository
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.media.HeadsetKeyCapture
import com.appricodes.lens_whisper.media.HeadsetShortcuts
import com.appricodes.lens_whisper.network.LocalServerAuth
import com.appricodes.lens_whisper.network.LocalServerDiscovery
import com.appricodes.lens_whisper.offline.OcrScript
import com.appricodes.lens_whisper.service.ProductDbDownloader
import com.appricodes.lens_whisper.service.ProductDbDownloadState
import com.appricodes.lens_whisper.ui.components.ExportDataDialog
import com.appricodes.lens_whisper.ui.components.ImportDataDialog
import com.appricodes.lens_whisper.ui.components.ProductDbConfirmDialog
import com.appricodes.lens_whisper.ui.components.ProductDbProgressDialog
import com.appricodes.lens_whisper.ui.components.ProductDbRemoveDialog
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
		val availableOcrLanguages = OcrScript.entries.map { it.label }
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

/** A named, collapsible group of related settings — closed by default, introduced 2026-09-02
 *  specifically to shorten [SettingsScreen]'s default scroll length on first open; the individual
 *  items inside a group are otherwise unchanged (same controls, same behavior, same
 *  contentDescriptions). Not persisted across a screen re-open on purpose, to keep this a pure UI
 *  convenience rather than a new SharedPreferences key — every group starts collapsed each time.
 *
 *  The header row is its own single accessibility node via `clearAndSetSemantics` (matching every
 *  other clickable row's contentDescription convention in this file) announcing the group's name,
 *  current expanded/collapsed state, and which double-tap it needs next — deliberately *not*
 *  [androidx.compose.ui.semantics.heading] the way the plain category-title `Text`s elsewhere on
 *  this screen are marked, since unlike those, this row really is interactive. */
@Composable
private fun CollapsibleSection(
	title: String,
	expanded: Boolean,
	onToggle: () -> Unit,
	content: @Composable ColumnScope.() -> Unit
) {
	Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(onClick = onToggle)
				.padding(vertical = 4.dp)
				.clearAndSetSemantics {
					val state = if (expanded) "expanded" else "collapsed"
					val action = if (expanded) "collapse" else "expand"
					contentDescription = "$title section, $state. Double tap to $action."
				},
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(text = title, fontSize = 20.sp, style = MaterialTheme.typography.titleMedium)
			Text(text = if (expanded) "▲" else "▼", fontSize = 16.sp, color = Color.Gray)
		}
		if (expanded) {
			Column(verticalArrangement = Arrangement.spacedBy(28.dp), content = content)
		}
	}
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

	// Collapsible-group state (see CollapsibleSection's own doc) — every group starts closed.
	var aiNarrationSectionExpanded by remember { mutableStateOf(false) }
	var feedbackSoundSectionExpanded by remember { mutableStateOf(false) }
	var localServerSectionExpanded by remember { mutableStateOf(false) }
	var yourDataSectionExpanded by remember { mutableStateOf(false) }

	// "Your Data" — export/import of the user's own barcode notes (see DataTransferManager). isBusy
	// blocks a second run and both dialogs' buttons while one is in flight; a half-written archive
	// is not something to let the user start twice.
	var showExportDialog by remember { mutableStateOf(false) }
	var showImportDialog by remember { mutableStateOf(false) }
	var exportSelection by remember { mutableStateOf(ExportSelection()) }
	var importableFiles by remember { mutableStateOf<List<ExportFileRef>>(emptyList()) }

	// "Product Database" — the optional offline product lookup (see ProductDbSource). There is one
	// database at one URL, so there is nothing to browse: tapping the row asks the server how big it
	// is and goes straight to the confirmation. Nothing is fetched until the user taps, and nothing
	// is downloaded until they confirm.
	var productDbInstalled by remember { mutableStateOf(false) }
	var productDbInstalledSize by remember { mutableStateOf("") }
	var productDbSummary by remember { mutableStateOf("Checking…") }
	var productDbChecking by remember { mutableStateOf(false) }
	var productDbPendingDownload by remember { mutableStateOf<ProductDbInfo?>(null) }
	var productDbPendingRemove by remember { mutableStateOf(false) }
	val productDbState by ProductDbDownloader.state.collectAsState()
	val productDbDownloading = productDbState is ProductDbDownloadState.Downloading ||
		productDbState == ProductDbDownloadState.Finishing

	suspend fun refreshProductDbStatus() {
		val bytes = ProductDatabase.installedBytes(context)
		productDbInstalled = bytes > 0
		productDbInstalledSize = ProductDbSource.formatBytes(bytes)
		productDbSummary = if (bytes > 0) {
			val products = ProductDatabase.productCount(context)
			if (products > 0) {
				"Installed — ${"%,d".format(products)} products, $productDbInstalledSize"
			} else {
				"Installed, using $productDbInstalledSize"
			}
		} else {
			"Not installed"
		}
	}

	LaunchedEffect(Unit) { refreshProductDbStatus() }

	// Reacts to whatever the download service last reported, including a download the user started
	// and then left the screen during — the service's state is process-wide, so coming back here
	// picks it up rather than showing a stale "not installed".
	LaunchedEffect(productDbState) {
		when (val state = productDbState) {
			is ProductDbDownloadState.Downloading, ProductDbDownloadState.Finishing -> Unit
			ProductDbDownloadState.Completed -> {
				refreshProductDbStatus()
				AccessibilityUtils.announceToTalkBack(context, "Product database installed. $productDbSummary.")
				ProductDbDownloader.acknowledgeState()
			}
			is ProductDbDownloadState.Failed -> {
				AccessibilityUtils.announceToTalkBack(context, "Download failed. ${state.message}")
				ProductDbDownloader.acknowledgeState()
			}
			ProductDbDownloadState.Cancelled -> {
				AccessibilityUtils.announceToTalkBack(context, "Download cancelled.")
				ProductDbDownloader.acknowledgeState()
			}
			ProductDbDownloadState.Idle -> Unit
		}
	}

	var dataTransferBusy by remember { mutableStateOf(false) }
	// The one-line outcome of the last export/import, shown under the two rows and announced —
	// there is no other place on this screen for "it worked, here is where it went".
	var dataTransferMessage by remember { mutableStateOf<String?>(null) }

	fun reportResult(result: DataTransferResult) {
		dataTransferBusy = false
		val message = when (result) {
			is DataTransferResult.ExportSucceeded ->
				"Exported ${result.noteCount} written ${if (result.noteCount == 1) "note" else "notes"} and " +
					"${result.voiceNoteCount} voice ${if (result.voiceNoteCount == 1) "note" else "notes"} " +
					"to your Downloads folder, as ${result.fileName}."
			is DataTransferResult.ImportSucceeded ->
				"Imported ${result.noteCount} written ${if (result.noteCount == 1) "note" else "notes"} and " +
					"${result.voiceNoteCount} voice ${if (result.voiceNoteCount == 1) "note" else "notes"}."
			is DataTransferResult.Failed -> result.message
		}
		dataTransferMessage = message
		AccessibilityUtils.announceToTalkBack(context, message)
	}

	// The Import window lists the .fdb files in Downloads and imports one directly; this picker is
	// only its fallback, for the Android 10+ case where scoped storage hides a file the user copied
	// in from another phone (see DataTransferManager.listExportsInDownloads). There is no export
	// picker any more — an export always goes to Downloads and nowhere else.
	val openDocumentLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri == null) {
			dataTransferBusy = false
			return@rememberLauncherForActivityResult
		}
		coroutineScope.launch { reportResult(DataTransferManager.importFrom(context, uri)) }
	}

	// Android 8/9 only: writing into the public Downloads folder there is a real file write, so it
	// needs the storage permission (declared with maxSdkVersion="28", so it does not exist at all on
	// newer versions — see DataTransferManager.needsLegacyStoragePermission).
	val legacyStorageLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { granted ->
		if (granted) {
			coroutineScope.launch { reportResult(DataTransferManager.exportToDownloads(context, exportSelection)) }
		} else {
			reportResult(DataTransferResult.Failed("Storage permission is needed to save the file to your Downloads folder."))
		}
	}

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
			Text(
				text = "Configuration",
				fontSize = 26.sp,
				style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.semantics { heading() }
			)
			TextButton(
				onClick = onBack,
				modifier = Modifier.clearAndSetSemantics { contentDescription = "Close settings and return to layout panel." }
			) { Text("Close", fontSize = 18.sp) }
		}

		CollapsibleSection(
			title = "AI & Narration",
			expanded = aiNarrationSectionExpanded,
			onToggle = { aiNarrationSectionExpanded = !aiNarrationSectionExpanded }
		) {
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Narration Language Profile", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.semantics { heading() })
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
		}

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Narration Persona / Style Mode", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.semantics { heading() })
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
			Text(text = "Offline OCR Language", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.semantics { heading() })
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

		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text = "Reasoning Effort (Gemma models only)", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.semantics { heading() })
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
		} // end AI & Narration CollapsibleSection

		CollapsibleSection(
			title = "Feedback & Sound",
			expanded = feedbackSoundSectionExpanded,
			onToggle = { feedbackSoundSectionExpanded = !feedbackSoundSectionExpanded }
		) {
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
		} // end Feedback & Sound CollapsibleSection

		CollapsibleSection(
			title = "Your Data",
			expanded = yourDataSectionExpanded,
			onToggle = { yourDataSectionExpanded = !yourDataSectionExpanded }
		) {
			DataRow(
				title = "Export My Data",
				summary = "Save your barcode notes to Downloads",
				description = "Export my data. Saves your written and voice notes about barcodes into a single " +
					"file in this phone's Downloads folder, so you can move them to another phone. Double tap to open.",
				enabled = !dataTransferBusy,
				onClick = { showExportDialog = true }
			)
			DataRow(
				title = "Import My Data",
				summary = "Restore notes from Downloads",
				description = "Import my data. Reads a Lens Whisper export file from this phone's Downloads " +
					"folder and adds its barcode notes to this phone. Double tap to open.",
				enabled = !dataTransferBusy,
				onClick = {
					// Listed up front so the dialog opens with its choices already spoken, rather
					// than appearing empty and filling in a moment later.
					coroutineScope.launch {
						importableFiles = DataTransferManager.listExportsInDownloads(context)
						showImportDialog = true
					}
				}
			)
			dataTransferMessage?.let { message ->
				Text(text = message, fontSize = 15.sp, color = Color.Gray)
			}
			DataRow(
				title = "Product Database",
				summary = if (productDbChecking) "Checking…" else productDbSummary,
				description = "Product database. $productDbSummary. An optional database of products kept on " +
					"this phone, so Scan Barcode can name a product without going online and without sending " +
					"the barcode anywhere. Double tap to " +
					(if (productDbInstalled) "update or remove it." else "download it."),
				enabled = !productDbChecking && !productDbDownloading,
				onClick = {
					coroutineScope.launch {
						productDbChecking = true
						// Ask the server how big it is at the moment the user taps, so the size in
						// the confirmation is the real one for whatever is published today rather
						// than a figure baked into the app.
						val info = ProductDbSource.probe()
						productDbChecking = false
						if (info == null) {
							AccessibilityUtils.announceToTalkBack(
								context,
								"The product database could not be reached. Check your internet connection and try again."
							)
						} else {
							productDbPendingDownload = info
						}
					}
				}
			)
			if (productDbInstalled) {
				DataRow(
					title = "Remove Product Database",
					summary = "Free up $productDbInstalledSize",
					description = "Remove product database. Deletes it from this phone and frees " +
						"$productDbInstalledSize. Double tap to remove.",
					enabled = !productDbDownloading,
					onClick = { productDbPendingRemove = true }
				)
			}
		}

		val headsetSummary = when {
			headsetRepeatKeyCode == NO_HEADSET_KEY_CODE && headsetMicKeyCode == NO_HEADSET_KEY_CODE -> "Not set up"
			headsetRepeatKeyCode == NO_HEADSET_KEY_CODE -> "Microphone button set"
			headsetMicKeyCode == NO_HEADSET_KEY_CODE -> "Repeat button set"
			else -> "Both buttons set"
		}
		// One accessibility node for the whole row (clickable + clearAndSetSemantics on the outer
		// Column, not the inner Box) rather than the separate-heading-Text-plus-clickable-Box shape
		// every other row on this screen uses — that shape exists so a plain value ("English", "Not
		// set") doesn't get spoken with no idea what it's the value *of*. This row's summary already
		// names itself ("Headset Button Shortcuts. Not set up. ..."), so a separate heading above it
		// would only double-announce the same title — confirmed while making the group headers above
		// single focus stops (see CollapsibleSection) and finding this row was the odd one out.
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.clickable { showHeadsetShortcutsDialog = true }
				.clearAndSetSemantics {
					contentDescription = "Headset Button Shortcuts. $headsetSummary. Double tap to open. Bind a wired or " +
						"Bluetooth headset button to repeat the last prompt, or to ask a question by voice."
				},
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Text(text = "Headset Button Shortcuts", fontSize = 18.sp, color = Color.Gray)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceVariant)
					.padding(16.dp),
				contentAlignment = Alignment.CenterStart
			) {
				Text(text = headsetSummary, fontSize = 18.sp)
			}
		}

		if (LocalServerFeature.ENABLED) {
			// Hidden while the LAN feature is switched off - there is nothing to point these at.
			CollapsibleSection(
				title = "Local Server",
				expanded = localServerSectionExpanded,
				onToggle = { localServerSectionExpanded = !localServerSectionExpanded }
			) {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text = "Local Server Address", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.semantics { heading() })
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
				Text(text = "Local Server Password", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.semantics { heading() })
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
			} // end Local Server CollapsibleSection
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

	if (showExportDialog) {
		ExportDataDialog(
			selection = exportSelection,
			isBusy = dataTransferBusy,
			onSelectionChange = { exportSelection = it },
			onExport = {
				showExportDialog = false
				dataTransferBusy = true
				dataTransferMessage = null
				coroutineScope.launch {
					// Checked before anything else happens, so an empty export never gets as far as
					// writing a file that would fail its own import.
					if (!DataTransferManager.hasAnythingToExport(context, exportSelection)) {
						reportResult(DataTransferResult.Failed("There is nothing to export yet. Save a note about a barcode first."))
						return@launch
					}
					if (DataTransferManager.needsLegacyStoragePermission() &&
						ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
					) {
						// Android 8/9 only; the launcher's callback runs the export once granted.
						legacyStorageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
					} else {
						reportResult(DataTransferManager.exportToDownloads(context, exportSelection))
					}
				}
			},
			onCancel = { showExportDialog = false }
		)
	}

	if (showImportDialog) {
		ImportDataDialog(
			availableFiles = importableFiles,
			isBusy = dataTransferBusy,
			onImportFile = { file ->
				showImportDialog = false
				dataTransferBusy = true
				dataTransferMessage = null
				coroutineScope.launch { reportResult(DataTransferManager.importFrom(context, file.uri)) }
			},
			onChooseOtherFile = {
				showImportDialog = false
				dataTransferBusy = true
				dataTransferMessage = null
				// "*/*", not a specific type: .fdb has no registered MIME type, and a picker
				// filtered to application/octet-stream hides the file on providers that report
				// an unknown extension as something else entirely.
				openDocumentLauncher.launch(arrayOf("*/*"))
			},
			onCancel = { showImportDialog = false }
		)
	}

	productDbPendingDownload?.let { info ->
		ProductDbConfirmDialog(
			info = info,
			isUpdate = productDbInstalled,
			onConfirm = {
				productDbPendingDownload = null
				ProductDbDownloader.start(context, info)
			},
			onCancel = { productDbPendingDownload = null }
		)
	}

	if (productDbPendingRemove) {
		ProductDbRemoveDialog(
			installedSize = productDbInstalledSize,
			onConfirm = {
				productDbPendingRemove = false
				coroutineScope.launch {
					ProductDatabase.delete(context)
					refreshProductDbStatus()
					AccessibilityUtils.announceToTalkBack(context, "Product database removed.")
				}
			},
			onCancel = { productDbPendingRemove = false }
		)
	}

	if (productDbDownloading) {
		val downloading = productDbState as? ProductDbDownloadState.Downloading
		ProductDbProgressDialog(
			bytesDownloaded = downloading?.bytesDownloaded ?: 0L,
			totalBytes = downloading?.totalBytes ?: 0L,
			isFinishing = productDbState == ProductDbDownloadState.Finishing,
			// No visibility flag to clear: the dialog goes away because the service stops
			// reporting a download, which is the thing that actually happened.
			onCancel = { ProductDbDownloader.cancel(context) }
		)
	}
}

/** One row in the "Your Data" section — a title, a one-line summary of what it does, and a single
 *  accessibility node for the pair, matching the Headset Button Shortcuts row's shape (see its own
 *  comment for why that shape rather than the separate-heading-plus-value one used above). */
@Composable
private fun DataRow(
	title: String,
	summary: String,
	description: String,
	enabled: Boolean,
	onClick: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.clearAndSetSemantics { contentDescription = description },
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		Text(text = title, fontSize = 18.sp, color = Color.Gray)
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.surfaceVariant)
				.padding(16.dp),
			contentAlignment = Alignment.CenterStart
		) {
			Text(text = summary, fontSize = 18.sp)
		}
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
					Text(
						text = "Headset Button Shortcuts",
						fontSize = 20.sp,
						style = MaterialTheme.typography.titleMedium,
						modifier = Modifier.semantics { heading() }
					)
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
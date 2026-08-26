package com.appricodes.lens_whisper.data

import android.content.Context
import com.appricodes.lens_whisper.network.LocalServerFeature

class PreferencesManager(val context: Context) {
	private val standardPrefs = context.getSharedPreferences("LensWhisperPrefs", Context.MODE_PRIVATE)

	var selectedLanguage: String
		get() = standardPrefs.getString("selected_language", "English") ?: "English"
		set(value) = standardPrefs.edit().putString("selected_language", value).apply()

	var selectedLanguageCode: String
		get() = standardPrefs.getString("selected_language_code", "en-US") ?: "en-US"
		set(value) = standardPrefs.edit().putString("selected_language_code", value).apply()

	var ocrLanguage: String
		get() = standardPrefs.getString("ocr_language", "Latin (English/EU)") ?: "Latin (English/EU)"
		set(value) = standardPrefs.edit().putString("ocr_language", value).apply()

	/** The narration persona, e.g. "Neutral" or "Technical" - see assets/settings.json.
	 *
	 *  Two personas ("explicit", "explicit and naughty") were removed: they instructed the model to
	 *  describe whatever is in front of the camera - including bystanders, and including people who
	 *  may be minors - in sexualised terms. [RETIRED_MODES] maps a stored one back to Neutral so a
	 *  device that had one selected before the update doesn't keep using it. */
	var selectedMode: String
		get() {
			val stored = standardPrefs.getString("selected_mode", "Neutral") ?: "Neutral"
			return if (stored.lowercase() in RETIRED_MODES) "Neutral" else stored
		}
		set(value) = standardPrefs.edit().putString("selected_mode", value).apply()

	// NEW: Analysis Quality Preference
	var analysisQuality: String
		get() = standardPrefs.getString("analysis_quality", "High") ?: "High"
		set(value) = standardPrefs.edit().putString("analysis_quality", value).apply()

	var showResultsInDialog: Boolean
		get() = standardPrefs.getBoolean("show_results_in_dialog", false)
		set(value) = standardPrefs.edit().putBoolean("show_results_in_dialog", value).apply()

	/** Whether the first-run AI-limitations notice has been accepted. The app is unusable until it
	 *  is — see [com.appricodes.lens_whisper.ui.components.DisclaimerDialog]. Deliberately has no
	 *  setter that clears it: re-showing the gate on every launch would be nagging, and the text
	 *  stays readable at any time from the main screen and from Legal Information. */
	var hasAcceptedDisclaimer: Boolean
		get() = standardPrefs.getBoolean("has_accepted_disclaimer", false)
		set(value) = standardPrefs.edit().putBoolean("has_accepted_disclaimer", value).apply()

	/** Whether the flash should fire when a photo is taken. This is an *intent*, not live torch
	 *  state: the camera is only bound for the moment of capture (see
	 *  [com.appricodes.lens_whisper.ui.components.CameraController]), so there is no continuously
	 *  burning torch for this to reflect. */
	var flashEnabled: Boolean
		get() = standardPrefs.getBoolean("flash_enabled", false)
		set(value) = standardPrefs.edit().putBoolean("flash_enabled", value).apply()

	var volumeShortcutsEnabled: Boolean
		get() = standardPrefs.getBoolean("volume_shortcuts_enabled", false)
		set(value) = standardPrefs.edit().putBoolean("volume_shortcuts_enabled", value).apply()

	/** Gates every synthesized UI sound effect this app plays — the shutter click, the "waiting for
	 *  the model" tone, and the Explore hover enter/exit tones (see
	 *  [com.appricodes.lens_whisper.utils.ProcessingSoundPlayer] and
	 *  [com.appricodes.lens_whisper.utils.HoverFeedback]). Independent of [vibrationEnabled] — a
	 *  user who wants haptics but not audio (or vice versa) can have exactly that. Default on. */
	var soundEffectsEnabled: Boolean
		get() = standardPrefs.getBoolean("sound_effects_enabled", true)
		set(value) = standardPrefs.edit().putBoolean("sound_effects_enabled", value).apply()

	/** Gates every haptic pulse this app triggers — currently only the Explore hover feedback (see
	 *  [com.appricodes.lens_whisper.utils.HoverFeedback]): a short hard pulse crossing into or out
	 *  of an object's mask, a soft continuous pulse while inside one. Default on. */
	var vibrationEnabled: Boolean
		get() = standardPrefs.getBoolean("vibration_enabled", true)
		set(value) = standardPrefs.edit().putBoolean("vibration_enabled", value).apply()

	/** Which of the four bundled YOLOv8-seg sizes powers "Object Detection", "Object and Color
	 *  Detection", and the two Explore screens — see
	 *  [com.appricodes.lens_whisper.offline.OfflineSegmentationDetector.ALL_SIZES] for the valid
	 *  values ("n"/"s"/"m"/"l") and what each costs in speed vs. accuracy. Set from Settings. */
	var segmentationModelSize: String
		get() = standardPrefs.getString("segmentation_model_size", "n") ?: "n"
		set(value) = standardPrefs.edit().putString("segmentation_model_size", value).apply()

	/** "int8" (the default — genuinely faster on CPU, and the one that held up on real devices) or
	 *  "float16" (more numeric detail, larger file, from a different converter — see
	 *  [com.appricodes.lens_whisper.offline.OfflineSegmentationDetector]'s doc for why, and for the
	 *  w8a16 precision that used to occupy this slot before on-device testing found it more than 4x
	 *  slower than int8), for the same model [segmentationModelSize] names. Both are bundled for
	 *  every size so they can be compared against each other, not because either is expected to be
	 *  the obviously better everyday choice — that's for the user to judge from actual results on
	 *  their own device. */
	var segmentationModelPrecision: String
		get() {
			val stored = standardPrefs.getString("segmentation_model_precision", "int8") ?: "int8"
			// "w8a16" was a real, bundled precision briefly — a device that picked it before it was
			// removed (no longer a bundled asset, see the class doc above) would otherwise crash the
			// very next time it tried to load a model, so it's treated the same as never having been set.
			return if (stored == "w8a16") "int8" else stored
		}
		set(value) = standardPrefs.edit().putString("segmentation_model_precision", value).apply()

	/** "mlkit", "local_server", or an [com.appricodes.lens_whisper.domain.OfflineModelInfo.id].
	 *  (The former "online" value belonged to a hosted backend that no longer exists.)
	 *
	 *  Read through [LocalServerFeature.sanitizeSelectedModelId] so a "local_server" selection
	 *  stored by an older build can't keep selecting a model the user can no longer see. */
	var selectedModelId: String
		get() = LocalServerFeature.sanitizeSelectedModelId(standardPrefs.getString("selected_model_id", "mlkit") ?: "mlkit")
		set(value) = standardPrefs.edit().putString("selected_model_id", value).apply()

	/** Base URL (e.g. "http://192.168.1.42:8765") of the user's LensWhisper Desktop companion app
	 *  on the local network — discovered automatically or entered manually in Settings, see
	 *  [com.appricodes.lens_whisper.network.LocalServerDiscovery]. Empty if never found. */
	var localServerBaseUrl: String
		get() = standardPrefs.getString("local_server_base_url", "") ?: ""
		set(value) = standardPrefs.edit().putString("local_server_base_url", value).apply()

	/** Whether the one-time "About Local Server" explainer dialog has already been shown — see
	 *  `ModelManagementActivity`'s Local Server card. */
	var hasSeenLocalServerIntro: Boolean
		get() = standardPrefs.getBoolean("has_seen_local_server_intro", false)
		set(value) = standardPrefs.edit().putBoolean("has_seen_local_server_intro", value).apply()

	/** SHA-256 token derived from the Local Server's mandatory password (see
	 *  [com.appricodes.lens_whisper.network.LocalServerAuth.tokenFor]). The token *is* the AES-256
	 *  key every request/response body is encrypted with — it is never transmitted, and neither is
	 *  the password, which is never stored anywhere. Empty until a password has been entered, which
	 *  blocks Local Server requests outright. */
	var localServerPasswordToken: String
		get() = standardPrefs.getString("local_server_password_token", "") ?: ""
		set(value) = standardPrefs.edit().putString("local_server_password_token", value).apply()

	/** "Small", "Medium", or "Large" — see [com.appricodes.lens_whisper.utils.OfflineImageSize]. */
	var offlineImageSize: String
		get() = standardPrefs.getString("offline_image_size", "Large") ?: "Large"
		set(value) = standardPrefs.edit().putString("offline_image_size", value).apply()

	var urlHistory: String
		get() = standardPrefs.getString("url_history", "") ?: ""
		set(value) = standardPrefs.edit().putString("url_history", value).apply()

	var offlineTranslationEnabled: Boolean
		get() = standardPrefs.getBoolean("offline_translation_enabled", false)
		set(value) = standardPrefs.edit().putBoolean("offline_translation_enabled", value).apply()

	/** Speak each sentence as it's generated instead of waiting for the whole response — see
	 *  `AnalysisSessionController`'s streaming path (both on-device models and, when this is on,
	 *  the Online Describer). Off by default: most of this app's actual usage is a single short
	 *  sentence anyway, where streaming buys nothing but adds a network-protocol edge (NDJSON, see
	 *  backend/readme.md) that's simplest to keep opt-in until it's had more field use. */
	var streamingEnabled: Boolean
		get() = standardPrefs.getBoolean("streaming_enabled", false)
		set(value) = standardPrefs.edit().putBoolean("streaming_enabled", value).apply()

	/** Pipe-delimited [com.appricodes.lens_whisper.domain.OfflineModelInfo.id]s the user has
	 *  dismissed the low-memory warning for, via [MemoryWarningDialog]'s "don't warn me again"
	 *  checkbox. Per-model (not global) so suppressing a small model's warning never silently
	 *  suppresses a much larger model added to the catalog later. */
	private var suppressedMemoryWarningModelIds: String
		get() = standardPrefs.getString("suppressed_memory_warning_model_ids", "") ?: ""
		set(value) = standardPrefs.edit().putString("suppressed_memory_warning_model_ids", value).apply()

	fun isMemoryWarningSuppressed(modelId: String): Boolean =
		suppressedMemoryWarningModelIds.split("|").filter { it.isNotEmpty() }.contains(modelId)

	fun suppressMemoryWarning(modelId: String) {
		val ids = suppressedMemoryWarningModelIds.split("|").filter { it.isNotEmpty() }.toMutableSet()
		ids.add(modelId)
		suppressedMemoryWarningModelIds = ids.joinToString("|")
	}

	/** Which delegate ([com.appricodes.lens_whisper.inference.OfflineInferenceEngine] reads this as
	 *  "CPU" or absent-meaning-GPU) an on-device model's *vision* encoder should use on *this*
	 *  device — auto-detected, not user-facing. GPU is tried first for every model on every device;
	 *  if that ever fails (some model/GPU-driver combos genuinely can't compile a given vision
	 *  encoder — confirmed on Exynos 2100/Mali with `lfm2_5_vl_450m`, 2026-08-24), the engine falls
	 *  back to CPU and calls [setVisionBackendOverride] so every later load skips straight to CPU
	 *  instead of re-failing on GPU every time. Per (model, device) rather than a static per-model
	 *  flag, since the whole point is that the same model can work fine on GPU elsewhere. */
	fun getVisionBackendOverride(modelId: String): String? =
		standardPrefs.getString("vision_backend_override_$modelId", null)

	fun setVisionBackendOverride(modelId: String, backend: String) {
		standardPrefs.edit().putString("vision_backend_override_$modelId", backend).apply()
	}

	private companion object {
		val RETIRED_MODES = setOf("explicit", "explicit and naughty")
	}

	fun getUrlHistoryList(): List<String> = urlHistory.split("|").filter { it.isNotEmpty() }

	fun saveUrlHistory(urls: List<String>) {
		urlHistory = urls.take(5).joinToString("|")
	}
}
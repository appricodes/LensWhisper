package com.appricodes.lens_whisper.data

import android.content.Context
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.network.LocalServerFeature

/** Sentinel for [PreferencesManager.headsetRepeatKeyCode]/[PreferencesManager.headsetMicKeyCode]
 *  meaning "no button bound yet" — real `android.view.KeyEvent` keycodes are never negative, so
 *  this can never collide with an actual captured button. */
const val NO_HEADSET_KEY_CODE = -1

/** Collapses `KEYCODE_MEDIA_PLAY`/`KEYCODE_MEDIA_PAUSE` into `KEYCODE_MEDIA_PLAY_PAUSE` — a single
 *  physical play/pause button on a Bluetooth headset alternates between sending the two, so
 *  anything that captures or matches a headset button must treat them as one. The canonical copy
 *  lives here (not in [com.appricodes.lens_whisper.media.HeadsetShortcuts], which already depends
 *  on this file) and [PreferencesManager.headsetRepeatKeyCode]/[PreferencesManager.headsetMicKeyCode]
 *  apply it on every *read*, not just at capture time — a value stored before this normalization
 *  existed (raw 126/127) is transparently migrated on the next read instead of silently never
 *  matching a normalized incoming event again. */
fun normalizeHeadsetKeyCode(keyCode: Int): Int = when (keyCode) {
	android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
	else -> keyCode
}

class PreferencesManager(val context: Context) {
	private val standardPrefs = context.getSharedPreferences("LensWhisperPrefs", Context.MODE_PRIVATE)

	var selectedLanguage: String
		get() = standardPrefs.getString("selected_language", "English") ?: "English"
		set(value) = standardPrefs.edit().putString("selected_language", value).apply()

	var selectedLanguageCode: String
		get() = standardPrefs.getString("selected_language_code", "en-US") ?: "en-US"
		set(value) = standardPrefs.edit().putString("selected_language_code", value).apply()

	/** The locale to hand Android's SpeechRecognizer — [selectedLanguageCode], except Persian is
	 *  always sent as "fa-IR", which recognizers expect. Shared by every screen that listens. */
	val speechLocaleCode: String
		get() {
			val rawCode = selectedLanguageCode
			return if (rawCode.startsWith("fa", ignoreCase = true) || selectedLanguage.equals("Persian", ignoreCase = true)) "fa-IR" else rawCode
		}

	var ocrLanguage: String
		get() = standardPrefs.getString("ocr_language", "Latin (English/EU)") ?: "Latin (English/EU)"
		set(value) = standardPrefs.edit().putString("ocr_language", value).apply()

	/** The narration persona, e.g. "Neutral" or "Technical" - see assets/settings.json.
	 *
	 *  "explicit"/"explicit and naughty" were removed: they instructed the model to describe
	 *  whatever is in front of the camera - including bystanders, and including people who may be
	 *  minors - in sexualised terms. "Childish"/"Humorous" were removed 2026-09-01, trimming the
	 *  picker down to Neutral/Family/Technical/poetry. [RETIRED_MODES] maps any of the four back to
	 *  Neutral so a device that had one selected before the update doesn't keep using it. */
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

	/** Whether the user has agreed, once, to let Scan Barcode send a *product* barcode's digits to
	 *  UPCitemdb (see [com.appricodes.lens_whisper.network.UpcLookupClient]) — the app's only
	 *  outbound transmission of anything the camera saw. False until the consent dialog in
	 *  [com.appricodes.lens_whisper.BarcodeScanActivity] is accepted, and while it is false no
	 *  lookup happens at all (the scan still works; the description just reads "Unknown, or no
	 *  information available"). QR and other 2D codes never consult this — their payload is their
	 *  own content, so nothing is ever sent for them. Settable back to false so declining once is
	 *  not permanent, but there is no UI that does: the dialog is only shown while this is false.
	 *  Deliberately separate from [hasAcceptedDisclaimer]; that gate is about trusting AI output,
	 *  this one is about data leaving the device. */
	var hasAcceptedBarcodeLookup: Boolean
		get() = standardPrefs.getBoolean("has_accepted_barcode_lookup", false)
		set(value) = standardPrefs.edit().putBoolean("has_accepted_barcode_lookup", value).apply()

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

	/** Physical media-button keycode (`android.view.KeyEvent`) bound to "repeat the last prompt"
	 *  via a wired or Bluetooth headset button, captured interactively in Settings — see
	 *  `HeadsetShortcutsDialog` in [com.appricodes.lens_whisper.SettingsActivity]. [NO_HEADSET_KEY_CODE]
	 *  if unset. Settings itself keeps this and [headsetMicKeyCode] from ever holding the same
	 *  keycode (binding one steals it back from the other) — [com.appricodes.lens_whisper.media.HeadsetShortcutGestureHandler],
	 *  which checks this, fires immediately on key-down with no way to disambiguate one button
	 *  driving both. Checked by that class from both the media-session path
	 *  ([com.appricodes.lens_whisper.media.MediaSessionHandler]) and the plain `onKeyDown`/`onKeyUp`
	 *  fallback every Activity that owns this action already wires up for
	 *  [com.appricodes.lens_whisper.utils.VolumeKeyGestureHandler]. */
	var headsetRepeatKeyCode: Int
		get() = normalizeHeadsetKeyCode(standardPrefs.getInt("headset_repeat_key_code", NO_HEADSET_KEY_CODE))
		set(value) = standardPrefs.edit().putInt("headset_repeat_key_code", value).apply()

	/** Same as [headsetRepeatKeyCode], for "ask a question by voice". */
	var headsetMicKeyCode: Int
		get() = normalizeHeadsetKeyCode(standardPrefs.getInt("headset_mic_key_code", NO_HEADSET_KEY_CODE))
		set(value) = standardPrefs.edit().putInt("headset_mic_key_code", value).apply()

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

	/** "mlkit", "local_server", or an [com.appricodes.lens_whisper.domain.OfflineModelInfo.id].
	 *  (The former "online" value belonged to a hosted backend that no longer exists.)
	 *
	 *  Read through [LocalServerFeature.sanitizeSelectedModelId] so a "local_server" selection
	 *  stored by an older build can't keep selecting a model the user can no longer see. */
	var selectedModelId: String
		get() = LocalServerFeature.sanitizeSelectedModelId(standardPrefs.getString("selected_model_id", "mlkit") ?: "mlkit")
		set(value) = standardPrefs.edit().putString("selected_model_id", value).apply()

	/** How hard the model is asked to reason before answering — see
	 *  [com.appricodes.lens_whisper.inference.OfflineInferenceEngine.ALL_REASONING_EFFORTS] for the
	 *  valid values and [com.appricodes.lens_whisper.inference.OfflineInferenceEngine]'s own doc for
	 *  what this actually does and why it's a no-op for every model except Gemma. Default "none" —
	 *  reasoning is opt-in, not opt-out, since it costs real time and battery on-device for models
	 *  that support it at all. Set from Settings. */
	var reasoningEffort: String
		get() {
			val stored = standardPrefs.getString("reasoning_effort", "none") ?: "none"
			return if (stored in OfflineInferenceEngine.ALL_REASONING_EFFORTS) stored else "none"
		}
		set(value) = standardPrefs.edit().putString("reasoning_effort", value).apply()

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
		val RETIRED_MODES = setOf("explicit", "explicit and naughty", "childish", "humorous")
	}

	fun getUrlHistoryList(): List<String> = urlHistory.split("|").filter { it.isNotEmpty() }

	fun saveUrlHistory(urls: List<String>) {
		urlHistory = urls.take(5).joinToString("|")
	}
}
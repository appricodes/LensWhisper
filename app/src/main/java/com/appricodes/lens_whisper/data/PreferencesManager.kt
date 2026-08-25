package com.appricodes.lens_whisper.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class PreferencesManager(val context: Context) {
	private val standardPrefs = context.getSharedPreferences("LensWhisperPrefs", Context.MODE_PRIVATE)

	private val securePrefs: SharedPreferences by lazy {
		try {
			createEncryptedPrefs(context)
		} catch (e: Exception) {
			Log.e("PreferencesManager", "Encrypted prefs corrupted. Wiping and recreating.", e)
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
				context.deleteSharedPreferences("SecureLensWhisperPrefs")
			} else {
				context.getSharedPreferences("SecureLensWhisperPrefs", Context.MODE_PRIVATE).edit().clear().commit()
				val dir = File(context.applicationInfo.dataDir, "shared_prefs")
				File(dir, "SecureLensWhisperPrefs.xml").delete()
			}
			createEncryptedPrefs(context)
		}
	}

	private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
		val masterKey = MasterKey.Builder(ctx)
			.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
			.build()
		return EncryptedSharedPreferences.create(
			ctx,
			"SecureLensWhisperPrefs",
			masterKey,
			EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
			EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
		)
	}

	var userEmail: String?
		get() = securePrefs.getString("user_email", null)
		set(value) = securePrefs.edit().putString("user_email", value).apply()

	var googleId: String?
		get() = securePrefs.getString("google_id", null)
		set(value) = securePrefs.edit().putString("google_id", value).apply()

	var selectedLanguage: String
		get() = standardPrefs.getString("selected_language", "English") ?: "English"
		set(value) = standardPrefs.edit().putString("selected_language", value).apply()

	var selectedLanguageCode: String
		get() = standardPrefs.getString("selected_language_code", "en-US") ?: "en-US"
		set(value) = standardPrefs.edit().putString("selected_language_code", value).apply()

	var ocrLanguage: String
		get() = standardPrefs.getString("ocr_language", "Latin (English/EU)") ?: "Latin (English/EU)"
		set(value) = standardPrefs.edit().putString("ocr_language", value).apply()

	var selectedMode: String
		get() = standardPrefs.getString("selected_mode", "Neutral") ?: "Neutral"
		set(value) = standardPrefs.edit().putString("selected_mode", value).apply()

	// NEW: Analysis Quality Preference
	var analysisQuality: String
		get() = standardPrefs.getString("analysis_quality", "High") ?: "High"
		set(value) = standardPrefs.edit().putString("analysis_quality", value).apply()

	var showResultsInDialog: Boolean
		get() = standardPrefs.getBoolean("show_results_in_dialog", false)
		set(value) = standardPrefs.edit().putBoolean("show_results_in_dialog", value).apply()

	var volumeShortcutsEnabled: Boolean
		get() = standardPrefs.getBoolean("volume_shortcuts_enabled", false)
		set(value) = standardPrefs.edit().putBoolean("volume_shortcuts_enabled", value).apply()

	/** "mlkit", "online", "local_server", or an [com.appricodes.lens_whisper.domain.OfflineModelInfo.id]. */
	var selectedModelId: String
		get() = standardPrefs.getString("selected_model_id", "mlkit") ?: "mlkit"
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

	/** SHA-256 token derived from the Local Server's optional password (see
	 *  [com.appricodes.lens_whisper.network.LocalServerAuth.tokenFor]) — sent as an `Authorization:
	 *  Bearer` header, never the password itself, which is never stored anywhere. Empty when no
	 *  password has been entered (matching a server with no password protection set). */
	var localServerPasswordToken: String
		get() = standardPrefs.getString("local_server_password_token", "") ?: ""
		set(value) = standardPrefs.edit().putString("local_server_password_token", value).apply()

	/** "Small", "Medium", or "Large" — see [com.appricodes.lens_whisper.utils.OfflineImageSize]. */
	var offlineImageSize: String
		get() = standardPrefs.getString("offline_image_size", "Large") ?: "Large"
		set(value) = standardPrefs.edit().putString("offline_image_size", value).apply()

	var isLoggedIn: Boolean
		get() = standardPrefs.getBoolean("is_logged_in", false)
		set(value) = standardPrefs.edit().putBoolean("is_logged_in", value).apply()

	var freeCredits: Int
		get() = standardPrefs.getInt("free_credits", 0)
		set(value) = standardPrefs.edit().putInt("free_credits", value).apply()

	var purchasedCredits: Int
		get() = standardPrefs.getInt("purchased_credits", 0)
		set(value) = standardPrefs.edit().putInt("purchased_credits", value).apply()

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

	/** Random per-install identifier, generated once on first access and persisted forever after
	 *  (an app reinstall/data-clear gets a new one). Never derived from any hardware/Play Services
	 *  identifier and never sent to Google's ad SDK — only this app's own backend sees it, to tie
	 *  anonymous ad-earned credits (see [com.appricodes.lens_whisper.ads.AdCreditsSession]) to a
	 *  `install_credits` row server-side. See backend/get_credits.php. */
	val installId: String
		get() {
			standardPrefs.getString("install_id", null)?.let { return it }
			val generated = java.util.UUID.randomUUID().toString()
			standardPrefs.edit().putString("install_id", generated).apply()
			return generated
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

	fun getUrlHistoryList(): List<String> = urlHistory.split("|").filter { it.isNotEmpty() }

	fun saveUrlHistory(urls: List<String>) {
		urlHistory = urls.take(5).joinToString("|")
	}
}
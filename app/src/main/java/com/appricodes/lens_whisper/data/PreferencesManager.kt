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

	var isOnlineMode: Boolean
		get() = standardPrefs.getBoolean("is_online_mode", false)
		set(value) = standardPrefs.edit().putBoolean("is_online_mode", value).apply()

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

	fun getUrlHistoryList(): List<String> = urlHistory.split("|").filter { it.isNotEmpty() }

	fun saveUrlHistory(urls: List<String>) {
		urlHistory = urls.take(5).joinToString("|")
	}
}
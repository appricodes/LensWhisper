package com.appricodes.lens_whisper.offline

import android.content.Context
import android.util.Log
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object OfflineTranslator {

    private fun getMlKitLanguageTag(localeCode: String): String {
        // e.g. "es-ES" -> "es", "fa-IR" -> "fa"
        val baseCode = localeCode.split("-").firstOrNull()?.lowercase() ?: "en"
        return TranslateLanguage.fromLanguageTag(baseCode) ?: TranslateLanguage.ENGLISH
    }

    /**
     * Downloads the required model and deletes any other non-English models to save space.
     */
    fun manageModelsInBackground(targetLocaleCode: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val targetLang = getMlKitLanguageTag(targetLocaleCode)
                if (targetLang == TranslateLanguage.ENGLISH) return@launch

                val modelManager = RemoteModelManager.getInstance()
                
                // 1. Delete unnecessary old models first
                modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                    .addOnSuccessListener { models ->
                        for (model in models) {
                            if (model.language != TranslateLanguage.ENGLISH && model.language != targetLang) {
                                modelManager.deleteDownloadedModel(model)
                                    .addOnSuccessListener { Log.i("OfflineTranslator", "Deleted old model: ${model.language}") }
                            }
                        }
                    }

                // 2. Download the new requested model
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(targetLang)
                    .build()
                val client = Translation.getClient(options)

                val conditions = DownloadConditions.Builder().build() // Allow over cellular as well
                client.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener { Log.i("OfflineTranslator", "Model ready for: $targetLang") }
                    .addOnFailureListener { e -> Log.e("OfflineTranslator", "Model download failed", e) }

            } catch (e: Exception) {
                Log.e("OfflineTranslator", "Error managing models", e)
            }
        }
    }

    /**
     * Translates input text if enabled and ready, then speaks it. 
     * Otherwise, speaks the original English text.
     */
    fun translateAndSpeak(context: Context, text: String, prefs: PreferencesManager) {
        val targetLocaleCode = prefs.selectedLanguageCode
        
        if (!prefs.offlineTranslationEnabled || targetLocaleCode.startsWith("en", ignoreCase = true)) {
            AccessibilityUtils.announceToTalkBack(context, text)
            return
        }

        val targetLang = getMlKitLanguageTag(targetLocaleCode)
        if (targetLang == TranslateLanguage.ENGLISH) {
            AccessibilityUtils.announceToTalkBack(context, text)
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLang)
            .build()
        val translator = Translation.getClient(options)

        // Only translate if the model is already downloaded to avoid freezing the UI/Talkback
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        AccessibilityUtils.announceToTalkBack(context, translatedText)
                        translator.close()
                    }
                    .addOnFailureListener {
                        // Fallback to original text if translation fails
                        AccessibilityUtils.announceToTalkBack(context, text)
                        translator.close()
                    }
            }
            .addOnFailureListener {
                // Fallback to original text if model isn't downloaded yet
                AccessibilityUtils.announceToTalkBack(context, text)
                translator.close()
            }
    }
}
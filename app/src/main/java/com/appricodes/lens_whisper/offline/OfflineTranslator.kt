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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
     * Translates [text] (assumed English — every offline caller's source text is) to the user's
     * selected language if the "Translate Offline Results" setting is on and a translator is
     * available or downloadable for it; otherwise returns [text] unchanged. Used both by
     * [translateAndSpeak] (MLKit detector results) and by
     * [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController] for on-device VLM
     * responses (Gemma4/FastVLM/Qwen-VL etc.) — those models are asked to answer directly in the
     * target language via their system instruction, but small on-device models frequently ignore
     * that and answer in English regardless, so this is applied as a fallback safety net on top,
     * not a replacement for the instruction. Never throws — falls back to [text] on any failure.
     */
    suspend fun translate(context: Context, text: String, prefs: PreferencesManager): String {
        val targetLocaleCode = prefs.selectedLanguageCode
        if (!prefs.offlineTranslationEnabled || targetLocaleCode.startsWith("en", ignoreCase = true)) return text

        val targetLang = getMlKitLanguageTag(targetLocaleCode)
        if (targetLang == TranslateLanguage.ENGLISH) return text

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLang)
            .build()
        val translator = Translation.getClient(options)
        return try {
            suspendCancellableCoroutine { cont ->
                // Only translate if the model is already downloaded (or downloads instantly) to
                // avoid freezing the UI/TalkBack on a slow first-time model download.
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { translated -> if (cont.isActive) cont.resume(translated) }
                            .addOnFailureListener { if (cont.isActive) cont.resume(text) }
                    }
                    .addOnFailureListener { if (cont.isActive) cont.resume(text) }
            }
        } catch (e: Exception) {
            Log.e("OfflineTranslator", "Translation failed, falling back to original text", e)
            text
        } finally {
            translator.close()
        }
    }

    /** Translates [text] if enabled/ready via [translate], then speaks it via TalkBack. */
    fun translateAndSpeak(context: Context, text: String, prefs: PreferencesManager) {
        CoroutineScope(Dispatchers.Main).launch {
            AccessibilityUtils.announceToTalkBack(context, translate(context, text, prefs))
        }
    }

    /** Deletes every downloaded MLKit translate model (any language) — used by "Clear All
     *  Downloaded Models" in `ModelManagementActivity` so it also cleans up translate models that
     *  were pulled down in the background (via [manageModelsInBackground]) for the "Translate
     *  Offline Results" setting, not just the on-device VLM catalog. */
    suspend fun deleteAllDownloadedModels() {
        suspendCancellableCoroutine<Unit> { cont ->
            val modelManager = RemoteModelManager.getInstance()
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    if (models.isEmpty()) {
                        if (cont.isActive) cont.resume(Unit)
                        return@addOnSuccessListener
                    }
                    var remaining = models.size
                    for (model in models) {
                        modelManager.deleteDownloadedModel(model).addOnCompleteListener {
                            remaining--
                            if (remaining <= 0 && cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(Unit) }
        }
    }
}

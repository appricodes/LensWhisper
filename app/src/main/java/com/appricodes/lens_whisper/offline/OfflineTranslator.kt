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
     * Downloads the model(s) [targetLocaleCode] needs to translate in *both* directions
     * (English<->target - [translate] only ever runs English->target, but a target->English
     * translator is what a future "ask a question in my own language" path would need, and per
     * request this should be ready proactively rather than only discovered missing later) and
     * deletes any other non-English models to save space. Fire-and-forget, safe to call
     * repeatedly (called both right when the language is selected in Settings and again from
     * [SettingsActivity]'s onStop as a backstop, and again from [translate] itself if it ever
     * finds a model missing) - [Translator.downloadModelIfNeeded] is a fast no-op once a model is
     * already present, and ML Kit stores one model per *language*, not per direction, so in
     * practice both calls below usually resolve from the same single download.
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

                // 2. Download the new requested model, both directions.
                val conditions = DownloadConditions.Builder().build() // Allow over cellular as well
                val forward = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(targetLang)
                    .build()
                Translation.getClient(forward).downloadModelIfNeeded(conditions)
                    .addOnSuccessListener { Log.i("OfflineTranslator", "Model ready for: English -> $targetLang") }
                    .addOnFailureListener { e -> Log.e("OfflineTranslator", "Model download failed", e) }

                val backward = TranslatorOptions.Builder()
                    .setSourceLanguage(targetLang)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
                Translation.getClient(backward).downloadModelIfNeeded(conditions)
                    .addOnSuccessListener { Log.i("OfflineTranslator", "Model ready for: $targetLang -> English") }
                    .addOnFailureListener { e -> Log.e("OfflineTranslator", "Model download failed", e) }

            } catch (e: Exception) {
                Log.e("OfflineTranslator", "Error managing models", e)
            }
        }
    }

    /** Languages [notifyNotReady] has already announced once this process - avoids repeating the
     *  same warning on every sentence of a streaming response (see [translate]'s caller,
     *  `AnalysisSessionController.runStreamingAnalysis`, which calls this per sentence). */
    private val warnedNotReadyLanguages = mutableSetOf<String>()

    private fun notifyNotReady(context: Context, targetLocaleCode: String, targetLang: String) {
        if (!warnedNotReadyLanguages.add(targetLang)) return
        AccessibilityUtils.announceToTalkBack(context, "Translation isn't ready yet. Showing the original text.")
        // Self-healing: normally already kicked off when the language was selected in Settings,
        // but this covers a language chosen before that existed, or a download that failed then.
        manageModelsInBackground(targetLocaleCode)
    }

    private suspend fun isModelDownloaded(targetLang: String): Boolean = suspendCancellableCoroutine { cont ->
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> if (cont.isActive) cont.resume(models.any { it.language == targetLang }) }
            .addOnFailureListener { if (cont.isActive) cont.resume(false) }
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
     * If the target language's model isn't downloaded yet, this never blocks waiting for it (a
     * first-time download is ~30MB) — it notifies once via TalkBack, kicks off the download for
     * next time, and returns [text] unchanged right away, same as every other failure path here.
     */
    suspend fun translate(context: Context, text: String, prefs: PreferencesManager): String {
        val targetLocaleCode = prefs.selectedLanguageCode
        if (!prefs.offlineTranslationEnabled || targetLocaleCode.startsWith("en", ignoreCase = true)) return text

        val targetLang = getMlKitLanguageTag(targetLocaleCode)
        if (targetLang == TranslateLanguage.ENGLISH) return text

        if (!isModelDownloaded(targetLang)) {
            notifyNotReady(context, targetLocaleCode, targetLang)
            return text
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLang)
            .build()
        val translator = Translation.getClient(options)
        return try {
            suspendCancellableCoroutine { cont ->
                // The model is already confirmed downloaded above, so this resolves near-instantly
                // — kept as a defensive belt (matches ML Kit's own recommended usage) rather than
                // calling translator.translate() directly, not as the thing standing between us
                // and a slow first-time download the way it used to be.
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

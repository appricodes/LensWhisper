package com.appricodes.lens_whisper.domain

data class AppSettings(
	val languages: Map<String, String>,
	val modes: List<String>,
	val prompts: List<PromptConfig>
)

data class PromptConfig(
	val id: String,
	val title: String,
	val prompt: String,
	val framesCount: Int,
	val framesInterval: Double
)

/** One entry in the downloadable on-device (LiteRT-LM) model catalog — see res/raw/offline_models.json. */
data class OfflineModelInfo(
	val id: String,
	val displayName: String,
	val family: String,
	val description: String,
	val sizeBytes: Long,
	val downloadUrl: String,
	val fileName: String,
	val backend: String,
	/** True when the model understands and answers in languages other than English. An
	 *  English-only model is always prompted in English, asked to reply in English, and voice
	 *  questions for it are recognized as English — whatever language the app is set to. */
	val multilingual: Boolean = false
)
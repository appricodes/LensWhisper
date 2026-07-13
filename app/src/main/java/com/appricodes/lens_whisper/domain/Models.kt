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
	val supportsExplore: Boolean
)
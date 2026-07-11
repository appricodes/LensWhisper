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
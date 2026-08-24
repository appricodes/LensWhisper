package com.appricodes.lenswhisper.desktop.analysis

import kotlinx.serialization.Serializable

/** Response DTOs for llama-server's OpenAI-compatible `/v1/chat/completions` endpoint (both the
 * non-streaming `message` shape and the streaming `delta` shape). Request bodies are built
 * directly as JsonObjects in AnalysisService, since `content` needs to be either a plain string
 * (system message) or an array of {type, text|image_url} parts (multimodal user message). */
@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(
        val message: MessageOut? = null,
        val delta: Delta? = null,
        val finish_reason: String? = null,
    )

    @Serializable
    data class MessageOut(val content: String? = null)

    @Serializable
    data class Delta(val content: String? = null)
}

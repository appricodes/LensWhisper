package com.appricodes.lenswhisper.desktop.server

import kotlinx.serialization.Serializable

/** Request/response shapes mirroring backend/process.php, so the Android app's XaiVisionEngine
 * can later point at this server by changing only its base URL. Credit fields are dummy values —
 * this server doesn't meter usage. */
@Serializable
data class ProcessRequest(
    val email: String? = null,
    val google_id: String? = null,
    val install_id: String? = null,
    val prompt_id: String? = null,
    val language: String = "en-US",
    val persona: String = "Neutral",
    val quality: String? = null,
    val custom_question: String? = null,
    val streaming: Boolean = false,
    val frames: List<String> = emptyList(),
)

@Serializable
data class ProcessResponse(
    val error: Boolean = false,
    val model_response: String,
    val cost: Int = 0,
    val free_credits: Int = 9999,
    val purchased_credits: Int = 9999,
)

@Serializable
data class ProcessErrorResponse(
    val error: Boolean = true,
    val model_response: String,
    val cost: Int = 0,
    val free_credits: Int? = null,
    val purchased_credits: Int? = null,
)

/** One NDJSON line of the streaming response. */
@Serializable
data class StreamDeltaLine(val delta: String)

@Serializable
data class StreamDoneLine(
    val done: Boolean = true,
    val cost: Int = 0,
    val free_credits: Int = 9999,
    val purchased_credits: Int = 9999,
)

@Serializable
data class StreamErrorLine(
    val error: Boolean = true,
    val model_response: String,
)

@Serializable
data class HealthResponse(
    val status: String,
    val model: String,
    val engine_ready: Boolean,
    val backend: String,
)

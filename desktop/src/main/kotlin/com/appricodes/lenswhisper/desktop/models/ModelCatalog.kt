package com.appricodes.lenswhisper.desktop.models

/** A downloadable GGUF vision-language model: a base model file plus its multimodal projector
 * ("mmproj") file, both required by `llama-server -m <model> --mmproj <mmproj>`. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val hfRepo: String,
    val modelFile: String,
    val mmprojFile: String,
    val modelSizeBytes: Long,
    val mmprojSizeBytes: Long,
) {
    val modelUrl: String get() = "https://huggingface.co/$hfRepo/resolve/main/$modelFile"
    val mmprojUrl: String get() = "https://huggingface.co/$hfRepo/resolve/main/$mmprojFile"
}

object ModelCatalog {

    val DEFAULT_MODEL_ID = "smolvlm2-500m"

    private val models = listOf(
        ModelSpec(
            id = "smolvlm2-500m",
            displayName = "SmolVLM2 500M",
            hfRepo = "ggml-org/SmolVLM2-500M-Video-Instruct-GGUF",
            modelFile = "SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
            mmprojFile = "mmproj-SmolVLM2-500M-Video-Instruct-f16.gguf",
            modelSizeBytes = 437_000_000L,
            mmprojSizeBytes = 199_000_000L,
        ),
        ModelSpec(
            id = "lfm2-vl-450m",
            displayName = "LFM2-VL 450M",
            hfRepo = "LiquidAI/LFM2-VL-450M-GGUF",
            modelFile = "LFM2-VL-450M-Q4_0.gguf",
            mmprojFile = "mmproj-LFM2-VL-450M-Q8_0.gguf",
            modelSizeBytes = 219_000_000L,
            mmprojSizeBytes = 104_000_000L,
        ),
    )

    fun all(): List<ModelSpec> = models

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}

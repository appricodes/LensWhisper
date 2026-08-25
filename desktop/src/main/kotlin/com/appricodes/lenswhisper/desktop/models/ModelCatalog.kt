package com.appricodes.lenswhisper.desktop.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A downloadable GGUF vision-language model: a base model file plus its multimodal projector
 * ("mmproj") file, both required by `llama-server -m <model> --mmproj <mmproj>`. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val description: String = "",
    val hfRepo: String,
    val modelFile: String,
    val mmprojFile: String,
    val modelSizeBytes: Long,
    val mmprojSizeBytes: Long,
) {
    val modelUrl: String get() = "https://huggingface.co/$hfRepo/resolve/main/$modelFile"
    val mmprojUrl: String get() = "https://huggingface.co/$hfRepo/resolve/main/$mmprojFile"
}

@Serializable
private data class ModelCatalogFile(val models: List<ModelCatalogEntry>)

@Serializable
private data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String = "",
    val hfRepo: String,
    val modelFile: String,
    val mmprojFile: String,
    val modelSizeBytes: Long,
    val mmprojSizeBytes: Long,
)

/** Loaded from the bundled `model_catalog.json` resource (see that file for the data) rather than
 * hardcoded, so the list can be edited/extended without touching Kotlin — also lets the GUI's
 * "list of models" requirement read directly from data. */
object ModelCatalog {

    val DEFAULT_MODEL_ID = "smolvlm2-500m"

    private val json = Json { ignoreUnknownKeys = true }
    private val models: List<ModelSpec> by lazy { loadFromResource() }

    private fun loadFromResource(): List<ModelSpec> {
        val text = ModelCatalog::class.java.getResourceAsStream("/model_catalog.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("model_catalog.json resource not found on classpath")
        val parsed = json.decodeFromString(ModelCatalogFile.serializer(), text)
        return parsed.models.map {
            ModelSpec(
                id = it.id,
                displayName = it.displayName,
                description = it.description,
                hfRepo = it.hfRepo,
                modelFile = it.modelFile,
                mmprojFile = it.mmprojFile,
                modelSizeBytes = it.modelSizeBytes,
                mmprojSizeBytes = it.mmprojSizeBytes,
            )
        }
    }

    fun all(): List<ModelSpec> = models

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}

package com.appricodes.lens_whisper.data

import android.content.Context
import android.util.Log
import com.appricodes.lens_whisper.R
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.utils.DeviceHardwareDetector
import org.json.JSONObject
import java.io.File

/**
 * Loads the catalog of downloadable on-device (LiteRT-LM) models from res/raw/offline_models.json
 * and tracks which ones are already downloaded to app-private storage.
 *
 * res/raw/offline_models.json schema — a single object with a "models" array; each entry has:
 *   id           unique, stable string — never reuse/change once shipped (used as the selection key)
 *   displayName  shown in the model picker
 *   family       grouping label (e.g. "PaliGemma 2", "FastVLM")
 *   description  short device/size hint shown under the name
 *   multilingual true if the model understands and answers in non-English languages (default false)
 *   variants     list of downloadable builds of this model, each with:
 *     hardwareMatch  chipset tokens (e.g. "sm8750", "tensor g5") matched against this device's
 *                    Build.SOC_MODEL/HARDWARE/BOARD/DEVICE; empty list = generic fallback build
 *     sizeBytes      exact download size in bytes
 *     downloadUrl    direct, unauthenticated HTTPS URL to the .litertlm file
 *     fileName       local file name saved under context.filesDir; must be unique per variant
 *     backend        "CPU", "GPU", or "NPU"
 * At load time, [loadModels] silently resolves each model to its single best-matching variant
 * for the current device (falling back to the generic build) — nothing about the choice is ever
 * shown to the user.
 */
class OfflineModelsRepository(private val context: Context) {

    private data class ParsedVariant(
        val hardwareMatch: List<String>,
        val sizeBytes: Long,
        val downloadUrl: String,
        val fileName: String,
        val backend: String
    )

    fun loadModels(): List<OfflineModelInfo> {
        return runCatching {
            val jsonString = context.resources.openRawResource(R.raw.offline_models).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            val array = JSONObject(jsonString).getJSONArray("models")
            // Smallest first — the model picker/management screen list MLKit, then these (in this
            // order), then the online model last, so on-device size is what orders this segment.
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val variantsArray = obj.getJSONArray("variants")
                val variants = (0 until variantsArray.length()).map { v ->
                    val variantObj = variantsArray.getJSONObject(v)
                    val hardwareArray = variantObj.optJSONArray("hardwareMatch")
                    val hardwareMatch = hardwareArray?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                    ParsedVariant(
                        hardwareMatch = hardwareMatch,
                        sizeBytes = variantObj.getLong("sizeBytes"),
                        downloadUrl = variantObj.getString("downloadUrl"),
                        fileName = variantObj.getString("fileName"),
                        backend = variantObj.optString("backend", "CPU")
                    )
                }
                val chosen = DeviceHardwareDetector.pickBest(variants.filter { it.hardwareMatch.isNotEmpty() }) { it.hardwareMatch }
                    ?: variants.firstOrNull { it.hardwareMatch.isEmpty() }
                    ?: variants.firstOrNull()
                    ?: return@mapNotNull null
                OfflineModelInfo(
                    id = obj.getString("id"),
                    displayName = obj.getString("displayName"),
                    family = obj.optString("family", ""),
                    description = obj.optString("description", ""),
                    sizeBytes = chosen.sizeBytes,
                    downloadUrl = chosen.downloadUrl,
                    fileName = chosen.fileName,
                    backend = chosen.backend,
                    multilingual = obj.optBoolean("multilingual", false)
                )
            }.sortedBy { it.sizeBytes }
        }.getOrElse { e ->
            Log.e("OfflineModelsRepository", "Failed to parse offline_models.json", e)
            emptyList()
        }
    }

    fun localFileFor(model: OfflineModelInfo): File = File(context.filesDir, model.fileName)

    fun isDownloaded(model: OfflineModelInfo): Boolean = localFileFor(model).let { it.exists() && it.length() > 0 }

    fun deleteDownload(model: OfflineModelInfo): Boolean = localFileFor(model).delete()
}

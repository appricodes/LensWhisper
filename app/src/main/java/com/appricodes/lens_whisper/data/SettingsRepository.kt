package com.appricodes.lens_whisper.data

import android.content.Context
import android.util.Log
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.PromptConfig
import org.json.JSONObject
import java.nio.charset.Charset

class SettingsRepository(private val context: Context) {
	fun loadSettingsFromAssets(): AppSettings {
		return runCatching {
			val jsonString = context.assets.open("settings.json").use { stream ->
				val size = stream.available()
				val buffer = ByteArray(size)
				stream.read(buffer)
				String(buffer, Charset.forName("UTF-8"))
			}
			val rootObj = JSONObject(jsonString)

			val languages = mutableMapOf<String, String>()
			val langObj = rootObj.getJSONObject("languages")
			val keys = langObj.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				languages[key] = langObj.getString(key)
			}

			val modes = mutableListOf<String>()
			val modeArray = rootObj.getJSONArray("modes")
			for (i in 0 until modeArray.length()) modes.add(modeArray.getString(i))

			val prompts = mutableListOf<PromptConfig>()
			val promptArray = rootObj.getJSONArray("prompts")
			for (i in 0 until promptArray.length()) {
				val pObj = promptArray.getJSONObject(i)
				prompts.add(
					PromptConfig(
						id = pObj.getString("id"),
						title = pObj.getString("title"),
						prompt = pObj.getString("prompt"),
						framesCount = pObj.getInt("frames_count"),
						framesInterval = pObj.getDouble("frames_interval")
					)
				)
			}
			AppSettings(languages, modes, prompts)
		}.getOrElse { error ->
			Log.e("LensWhisper", "Failed parsing settings.json, using safe fallbacks", error)
			AppSettings(
				languages = mapOf(
					"fa-IR" to "Persian", "en-US" to "English",
					"es-ES" to "Spanish", "fr-FR" to "French", "de-DE" to "German"
				),
				modes = listOf("Neutral", "Family", "Technical", "explicit", "Humorous", "poetry"),
				prompts = listOf(PromptConfig("brief_narration", "Brief Narration", "", 1, 0.5))
			)
		}
	}
}
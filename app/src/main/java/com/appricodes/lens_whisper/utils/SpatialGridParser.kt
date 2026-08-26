package com.appricodes.lens_whisper.utils

import org.json.JSONObject

/**
 * Parses the flat `row{n}_col{m}` JSON object an Explore analysis returns.
 *
 * Lives here rather than on the engine that fetches it: the same response shape comes back from an
 * on-device model and from a Local Server, so the parser belongs to neither. It previously sat on
 * the network client, which meant [com.appricodes.lens_whisper.ExploreActivity] had to hold a
 * network client purely to parse a string it already had.
 *
 * Returns an empty map on anything malformed — small on-device models routinely wrap their JSON in
 * a fenced code block, drop keys, or emit prose instead, and the caller treats "empty" as "analysis
 * failed" rather than needing to distinguish the failure modes.
 */
object SpatialGridParser {
    fun parse(jsonResponse: String, gridSize: Int): Map<String, String> = runCatching {
        val sanitized = jsonResponse.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val rootJson = JSONObject(sanitized)
        val cells = mutableMapOf<String, String>()
        for (row in 1..gridSize) {
            for (col in 1..gridSize) {
                val key = "row${row}_col${col}"
                if (rootJson.has(key)) cells[key] = rootJson.getString(key)
            }
        }
        cells
    }.getOrElse { emptyMap() }
}

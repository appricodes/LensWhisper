package com.appricodes.lens_whisper.network

/**
 * The Local Server ("run the model on your own desktop and let the phone talk to it over the LAN")
 * feature is **built but switched off**, and every entry point to it is hidden while [ENABLED] is
 * false. The code is deliberately kept rather than deleted — see `desktop/` for the companion app
 * it talks to, and `backend/local_server_setup.html` for the user-facing setup guide.
 *
 * Flipping this to `true` is all that is needed to bring it back. What it re-exposes:
 * - `ModelPickerPopup` / `ModelManagementActivity` list "Local Server" as a selectable model.
 * - `SettingsActivity` shows the address, password and rescan controls.
 * - `MoreMenu` offers "About Local Server".
 * - `AnalysisRouter` routes `selectedModelId == "local_server"` to [LocalServerEngine].
 *
 * When false, [sanitizeSelectedModelId] also stops a `"local_server"` value that survived in
 * SharedPreferences from a previous build from selecting a model the user can no longer see or
 * configure.
 *
 * Why it is off: the app is on-device only, and a LAN feature reintroduces network exposure,
 * pairing and setup that the current release deliberately does not carry.
 */
object LocalServerFeature {
    const val ENABLED = false

    const val MODEL_ID = "local_server"

    /** The model id to actually use — falls back to MLKit when a stored `"local_server"` selection
     *  outlives the feature being switched off. */
    fun sanitizeSelectedModelId(storedId: String): String =
        if (storedId == MODEL_ID && !ENABLED) "mlkit" else storedId

    fun isSelectable(modelId: String): Boolean = modelId != MODEL_ID || ENABLED
}

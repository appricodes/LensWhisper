package com.appricodes.lens_whisper.ui.models

import com.appricodes.lens_whisper.ui.components.GridCell

/**
 * Buttons for the Local Server model - the user's own desktop machine on the LAN. It gets the
 * fuller grid (Explore, Story) rather than [OfflineVlmButtonProvider]'s, because a desktop can
 * afford the multi-frame and spatial-grid inference that a phone cannot.
 *
 * Currently unreachable: [com.appricodes.lens_whisper.network.LocalServerFeature] is switched off,
 * so nothing selects this model. Kept intact so turning the feature back on needs no UI work.
 *
 * This was `OnlineButtonProvider`, driving a hosted cloud backend that no longer exists; the Local
 * Server inherited its grid because it speaks the same `/process.php` contract.
 */
object LocalServerButtonProvider : ModelButtonProvider {
    override fun rows(ctx: ModelButtonContext): List<List<GridCell?>> = promptGridRows(ctx)
}

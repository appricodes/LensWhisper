package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One cell in an [ActionButtonGrid] row. Pass `null` in a row's cell list for an empty spacer.
 *
 * [customModifier], when set, replaces the default tap/long-press handling entirely — used for
 * cells that need a bespoke gesture (e.g. press-and-hold seek scrubbing).
 * [trailingContent] renders extra composables (e.g. a DropdownMenu) anchored to this same cell.
 */
data class GridCell(
    val label: String,
    val contentDescription: String = label,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val customModifier: Modifier? = null,
    val trailingContent: (@Composable () -> Unit)? = null
)

/**
 * A grid of tappable cells laid out as rows of equal-width columns.
 * When [transparent] is true, no visible text/background is drawn — only the semantics
 * (contentDescription) remain, so TalkBack touch-exploration reads the label while the
 * underlying content (e.g. a video) stays fully visible.
 */
@Composable
fun ActionButtonGrid(
    rows: List<List<GridCell?>>,
    transparent: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        for (row in rows) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (cell in row) {
                    val cellModifier = Modifier.weight(1f).fillMaxHeight()
                    if (cell == null) {
                        Box(modifier = cellModifier)
                        continue
                    }

                    val hasDefaultGesture = cell.enabled && (cell.onClick != null || cell.onLongClick != null)
                    val finalModifier = when {
                        cell.customModifier != null -> cellModifier.then(cell.customModifier)
                        hasDefaultGesture -> cellModifier.combinedClickable(
                            onClick = { cell.onClick?.invoke() },
                            onLongClick = cell.onLongClick
                        )
                        else -> cellModifier
                    }.clearAndSetSemantics { contentDescription = cell.contentDescription }

                    Box(modifier = finalModifier, contentAlignment = Alignment.Center) {
                        if (!transparent) {
                            Text(
                                text = cell.label,
                                color = if (cell.enabled) Color.White else Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                        cell.trailingContent?.invoke()
                    }
                }
            }
        }
    }
}

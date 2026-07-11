package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun ExploreGridOverlay(
    isExploreLoading: Boolean,
    exploreGridMap: Map<String, String>,
    onCellClicked: (String) -> Unit
) {
    val activeGridSize = 6
    Column(modifier = Modifier.fillMaxSize()) {
        for (screenRow in 1..activeGridSize) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (screenCol in 1..activeGridSize) {
                    val key = "row${screenRow}_col${screenCol}"
                    val desc = if (isExploreLoading) "Analyzing elements" else exploreGridMap[key] ?: "Empty"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(enabled = !isExploreLoading) {
                                onCellClicked(desc)
                            }
                            .clearAndSetSemantics { contentDescription = desc }
                    )
                }
            }
        }
    }
}
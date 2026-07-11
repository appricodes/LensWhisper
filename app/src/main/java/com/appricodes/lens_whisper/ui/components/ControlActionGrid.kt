package com.appricodes.lens_whisper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.appricodes.lens_whisper.ui.screens.MediaSource
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ControlActionGrid(
    gridRows: List<List<String>>,
    isImageMode: Boolean,
    currentMediaSource: MediaSource,
    exoPlayer: ExoPlayer,
    onActionClick: (String) -> Unit,
    onSourceSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showSourceMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        for (row in gridRows) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val rowWeight = if (row.size == 1) 1f else (1f / row.size)
                for (cellLabel in row) {
                    val isSeekActionCell = (cellLabel == "Rewind" || cellLabel == "Fast Forwarding") && !isImageMode
                    val isCellDisabled = cellLabel == "N/A"
                    val isHidden = currentMediaSource != MediaSource.FILE && (cellLabel == "Rewind" || cellLabel == "Play" || cellLabel == "Pause" || cellLabel == "Fast Forwarding")

                    var boxModifier = Modifier.weight(rowWeight).fillMaxHeight()
                    
                    if (isHidden) { 
                        /* Renders empty spacer block automatically */ 
                    } else if (isCellDisabled) {
                        boxModifier = boxModifier.clearAndSetSemantics { contentDescription = "Feature Unavailable" }
                    } else if (isSeekActionCell) {
                        boxModifier = boxModifier.pointerInput(cellLabel) {
                            forEachGesture {
                                awaitPointerEventScope {
                                    val downChange = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.changedToDown() } ?: return@awaitPointerEventScope
                                    var continuousScrubJob: Job? = null
                                    var isLongPressTriggered = false
                                    val longPressTimerJob = coroutineScope.launch {
                                        delay(500); isLongPressTriggered = true
                                        AccessibilityUtils.announceToTalkBack(context, "Seeking continuously")
                                        continuousScrubJob = launch {
                                            while (true) {
                                                if (cellLabel == "Rewind") exoPlayer.seekTo((exoPlayer.currentPosition - 30000).coerceAtLeast(0))
                                                else exoPlayer.seekTo((exoPlayer.currentPosition + 30000).coerceAtMost(exoPlayer.duration))
                                                delay(1000)
                                            }
                                        }
                                    }
                                    var pointerUpEvent = awaitPointerEvent(PointerEventPass.Main)
                                    while (pointerUpEvent.changes.any { it.pressed }) pointerUpEvent = awaitPointerEvent(PointerEventPass.Main)
                                    longPressTimerJob.cancel(); continuousScrubJob?.cancel()
                                    val totalTimeText = AccessibilityUtils.formatTime(exoPlayer.duration)
                                    if (!isLongPressTriggered) {
                                        if (cellLabel == "Rewind") exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                        else exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                                    }
                                    AccessibilityUtils.announceToTalkBack(context, "${AccessibilityUtils.formatTime(exoPlayer.currentPosition)} of $totalTimeText")
                                }
                            }
                        }.clearAndSetSemantics { contentDescription = cellLabel }
                    } else if (cellLabel != "Empty") {
                        boxModifier = boxModifier.clickable {
                            if (cellLabel == "Open file, webpage or camera") {
                                showSourceMenu = true
                            } else {
                                onActionClick(cellLabel)
                            }
                        }.clearAndSetSemantics { contentDescription = cellLabel }
                    }

                    Box(modifier = boxModifier) {
                        if (!isHidden && cellLabel != "Empty") {
                            Text(text = cellLabel, color = if (isCellDisabled) Color.Gray else Color.White, modifier = Modifier.align(Alignment.Center).padding(4.dp), textAlign = TextAlign.Center)

                            if (cellLabel == "Open file, webpage or camera") {
                                DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }) {
                                    DropdownMenuItem(text = { Text("Open File") }, onClick = { showSourceMenu = false; onSourceSelected("OPEN_FILE") })
                                    DropdownMenuItem(text = { Text("Front Camera") }, onClick = { showSourceMenu = false; onSourceSelected("OPEN_FRONT_CAM") })
                                    DropdownMenuItem(text = { Text("Back Camera") }, onClick = { showSourceMenu = false; onSourceSelected("OPEN_BACK_CAM") })
                                    DropdownMenuItem(text = { Text("Web page") }, onClick = { showSourceMenu = false; onSourceSelected("WEB_PAGE") })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
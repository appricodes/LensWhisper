package com.appricodes.lens_whisper.ui.components

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer

/** Renders a static image or a video surface, rotated/sized to match the media's native aspect ratio. */
@Composable
fun MediaPreviewArea(
    isImageMode: Boolean,
    runtimeLoadedImageBitmap: Bitmap?,
    exoPlayer: ExoPlayer,
    uiRotationDegrees: Float,
    mediaWidthDp: Dp,
    mediaHeightDp: Dp,
    screenWidthPx: Float,
    screenHeightPx: Float,
    isRotated: Boolean,
    density: Density
) {
    Box(
        modifier = Modifier
            .graphicsLayer { rotationZ = uiRotationDegrees }
            .size(
                width = if (isRotated) with(density) { screenHeightPx.toDp() } else with(density) { screenWidthPx.toDp() },
                height = if (isRotated) with(density) { screenWidthPx.toDp() } else with(density) { screenHeightPx.toDp() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.width(mediaWidthDp).height(mediaHeightDp)) {
            if (isImageMode) {
                runtimeLoadedImageBitmap?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = "File", modifier = Modifier.fillMaxSize())
                }
            } else {
                AndroidView(factory = { ctx -> TextureView(ctx).apply { exoPlayer.setVideoTextureView(this) } }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

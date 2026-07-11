package com.appricodes.lens_whisper.ui.components

import android.graphics.Bitmap
import android.view.TextureView
import androidx.camera.view.PreviewView
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
import com.appricodes.lens_whisper.ui.screens.MediaSource

@Composable
fun MediaPreviewArea(
    currentMediaSource: MediaSource,
    isImageMode: Boolean,
    runtimeLoadedImageBitmap: Bitmap?,
    exoPlayer: ExoPlayer,
    uiRotationDegrees: Float,
    mediaWidthDp: Dp,
    mediaHeightDp: Dp,
    screenWidthPx: Float,
    screenHeightPx: Float,
    isRotated: Boolean,
    density: Density,
    onPreviewViewCreated: (PreviewView) -> Unit
) {
    if (currentMediaSource == MediaSource.FILE) {
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
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        onPreviewViewCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
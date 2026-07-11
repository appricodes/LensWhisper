package com.appricodes.lens_whisper.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.offline.OfflineLightDetector
import com.appricodes.lens_whisper.offline.OfflineObjectDetector
import kotlinx.coroutines.delay

@Composable
fun ContinuousDetectionLoop(
	context: Context,
	prefs: PreferencesManager,
	activeColorMode: Boolean,
	activeLightMode: Boolean,
	activeObjectMode: Boolean,
	previewView: PreviewView?,
	runtimeLoadedImageBitmap: Bitmap?,
	lastColorName: String,
	onColorChanged: (String) -> Unit
) {
	LaunchedEffect(activeColorMode, activeLightMode, activeObjectMode) {
		while (true) {
			if (activeLightMode || activeObjectMode) {
				val bmp = previewView?.bitmap ?: runtimeLoadedImageBitmap
				if (bmp != null) {
					if (activeLightMode) {
						OfflineLightDetector.detectAndPlayTone(bmp)
					} else if (activeObjectMode) {
						OfflineObjectDetector.detect(context, bmp, prefs)
					}
				}
			}
			// Delay adjusted since color is no longer continuously polled
			delay(if (activeObjectMode) 4000 else 500)
		}
	}
}
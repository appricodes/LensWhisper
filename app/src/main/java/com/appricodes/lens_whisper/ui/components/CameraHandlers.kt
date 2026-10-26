package com.appricodes.lens_whisper.ui.components

import android.content.Context
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Releases the camera on pause. There is deliberately no rebind-on-resume counterpart any more:
 * since [CameraController] only binds for the duration of a capture or a live analysis feed,
 * there is nothing to restore — the next button press opens the camera itself.
 *
 * [onPauseTasks] is where the screen stops anything that was holding the camera open (Basic Tasks'
 * continuous/light modes), and it runs *before* the release so those stop cleanly rather than
 * having the feed pulled out from under them.
 */
@Composable
fun AppLifecycleCameraHandler(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    onPauseTasks: () -> Unit
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                onPauseTasks()
                CameraController.releaseAllCameras(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CameraController.releaseAllCameras(context)
        }
    }
}

/**
 * Applies Basic Tasks Light Level mode's exposure setup to the live analysis feed. Only meaningful while
 * that mode holds the camera open — [activeCamera] is null the rest of the time, which is now the
 * normal case, and this then does nothing.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraExposureFlashHandler(
    activeLightMode: Boolean,
    activeCamera: Camera?
) {
    LaunchedEffect(activeLightMode, activeCamera) {
        val camera = activeCamera ?: return@LaunchedEffect
        val cameraControl = camera.cameraControl
        val cameraInfo = camera.cameraInfo

        if (activeLightMode) {
            cameraControl.enableTorch(false)
            try {
                val camera2Control = Camera2CameraControl.from(cameraControl)
                val builder = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                camera2Control.setCaptureRequestOptions(builder.build())

                val range = cameraInfo.exposureState.exposureCompensationRange
                if (range.contains(-1)) cameraControl.setExposureCompensationIndex(-1)
                else cameraControl.setExposureCompensationIndex(range.lower)
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            try {
                val camera2Control = Camera2CameraControl.from(cameraControl)
                val builder = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                camera2Control.setCaptureRequestOptions(builder.build())

                val range = cameraInfo.exposureState.exposureCompensationRange
                if (range.contains(0)) cameraControl.setExposureCompensationIndex(0)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}

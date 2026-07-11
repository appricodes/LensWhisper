package com.appricodes.lens_whisper.ui.components

import android.content.Context
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.appricodes.lens_whisper.ui.screens.MediaSource

@Composable
fun AppLifecycleCameraHandler(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    currentMediaSource: MediaSource,
    previewView: PreviewView?,
    onPauseTasks: () -> Unit,
    onBindCamera: () -> Unit
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                onPauseTasks()
                try {
                    ProcessCameraProvider.getInstance(context).get().unbindAll()
                } catch (e: Exception) { e.printStackTrace() }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                onBindCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

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

                // FIX: Use the explicit setter method instead of property assignment
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

                // FIX: Use the explicit setter method instead of property assignment
                camera2Control.setCaptureRequestOptions(builder.build())

                val range = cameraInfo.exposureState.exposureCompensationRange
                if (range.contains(0)) cameraControl.setExposureCompensationIndex(0)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
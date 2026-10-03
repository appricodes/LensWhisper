package com.appricodes.lens_whisper.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Asks for a runtime permission at the moment a feature actually needs it — the camera on the
 * first capture, the microphone on the first voice question, notifications when a download starts
 * — instead of all at once on first launch, before the user has done anything that explains why.
 *
 * [request] suspends until the user answers the system dialog, so a caller can simply write
 * `if (!permissions.request(CAMERA, "…")) return` in front of the work and carry on from there
 * once it is granted, with no second tap needed.
 *
 * Requests are serialized: Android shows one permission dialog at a time and silently drops a
 * second one launched while the first is still on screen. A denial is also remembered for a moment
 * ([RECENT_DENIAL_MS]), so a burst of callers (a continuous mode's next poll, a Story burst) does
 * not put the same dialog straight back up after the user has just said no — the next deliberate
 * tap asks again.
 */
class PermissionRequester internal constructor(private val context: Context) {

    internal var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null
    private val mutex = Mutex()
    private val lastDeniedAt = mutableMapOf<String, Long>()

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns true once [permission] is granted, asking for it first if needed. On a denial,
     * [deniedMessage] is announced through TalkBack — with a pointer to the system settings when
     * Android will no longer show the dialog at all ("Don't ask again", or a second denial).
     */
    suspend fun request(permission: String, deniedMessage: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (isGranted(permission)) return@withContext true
            mutex.withLock {
                if (isGranted(permission)) return@withLock true
                val deniedRecently = lastDeniedAt[permission]
                    ?.let { SystemClock.elapsedRealtime() - it < RECENT_DENIAL_MS } == true
                if (deniedRecently) return@withLock false
                val activeLauncher = launcher ?: return@withLock false
                val result = CompletableDeferred<Boolean>()
                pending = result
                val granted = try {
                    activeLauncher.launch(permission)
                    result.await()
                } finally {
                    pending = null
                }
                if (!granted) {
                    lastDeniedAt[permission] = SystemClock.elapsedRealtime()
                    val blocked = (context as? Activity)
                        ?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true
                    AccessibilityUtils.announceToTalkBack(
                        context,
                        if (blocked) "$deniedMessage You can allow it in the phone's Settings, under Apps, LensWhisper, Permissions."
                        else deniedMessage
                    )
                }
                granted
            }
        }

    /**
     * Notifications are only what makes a background download's progress (and its Cancel button)
     * visible — the download itself runs either way, so this never blocks the caller and announces
     * nothing on a denial. A no-op below Android 13, where there is no such permission to ask for.
     */
    suspend fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        withContext(Dispatchers.Main.immediate) {
            if (isGranted(permission)) return@withContext
            mutex.withLock {
                val activeLauncher = launcher ?: return@withLock
                val result = CompletableDeferred<Boolean>()
                pending = result
                try {
                    activeLauncher.launch(permission)
                    result.await()
                } finally {
                    pending = null
                }
            }
        }
    }

    internal fun onResult(granted: Boolean) {
        pending?.complete(granted)
    }

    private companion object {
        const val RECENT_DENIAL_MS = 3000L
    }
}

/** Creates a [PermissionRequester] bound to this composition's Activity. */
@Composable
fun rememberPermissionRequester(): PermissionRequester {
    val context = LocalContext.current
    val requester = remember { PermissionRequester(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requester.onResult(granted)
    }
    SideEffect { requester.launcher = launcher }
    return requester
}

/** Thrown by [CameraController] when a capture is attempted and the camera permission is refused;
 *  the refusal has already been announced, so callers drop it without a second error message. */
class CameraPermissionDeniedException : Exception("Camera permission denied")

const val CAMERA_DENIED_MESSAGE = "Camera permission is needed to take a photo."
const val MICROPHONE_DENIED_MESSAGE = "Microphone permission is needed to ask a question by voice."

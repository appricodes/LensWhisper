// Copyright 2026 Mohammad Amiri Moalla
// SPDX-License-Identifier: Apache-2.0
package com.appricodes.lens_whisper.segmenter.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import com.appricodes.lens_whisper.segmenter.ISegmenterService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One object found by the companion. [mask] spans the whole image; see [SegmenterContract]. */
class SegmentedObject(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val box: FloatArray,
    val mask: BooleanArray,
    val maskWidth: Int,
    val maskHeight: Int
)

/**
 * A minimal, complete client for the LensWhisper Object Segmenter (Apache-2.0 — copy it). It only
 * ever sends an image after checking that the installed companion is signed with one of
 * [trustedCertsSha256], and checks again once connected, so a look-alike app installed under the
 * same package name never receives anything.
 *
 * Needs, in your AndroidManifest.xml (Android 11+ package visibility):
 * ```
 * <queries><package android:name="com.appricodes.lens_whisper.segmenter" /></queries>
 * ```
 */
class SegmenterClient(
    context: Context,
    private val trustedCertsSha256: Set<String> = SegmenterContract.RELEASE_CERT_SHA256
) {
    private val appContext = context.applicationContext

    /** True if the companion is installed *and* signed with a trusted certificate. */
    fun isAvailable(): Boolean = isTrusted(appContext.packageManager)

    private fun isTrusted(pm: PackageManager): Boolean {
        if (trustedCertsSha256.isEmpty()) return false // fail closed
        return try {
            pm.getPackageInfo(SegmenterContract.PACKAGE, 0)
            trustedCertsSha256.any { hex ->
                PackageInfoCompat.hasSignatures(pm, SegmenterContract.PACKAGE, mapOf(hexToBytes(hex) to PackageManager.CERT_INPUT_SHA256), false)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Segments [imageFile] (an encoded image in your own app's private storage). Blocking — never
     * call it on the main thread. Returns null if the companion is missing, untrusted, too old, or
     * doesn't answer within [timeoutMs]; otherwise the service's status and objects.
     */
    fun segment(imageFile: File, options: Bundle? = null, timeoutMs: Long = 15_000): Pair<Int, List<SegmentedObject>>? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "SegmenterClient.segment blocks; call it off the main thread" }
        if (!isTrusted(appContext.packageManager)) return null

        val connected = CountDownLatch(1)
        var service: ISegmenterService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = ISegmenterService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
            override fun onNullBinding(name: ComponentName) { connected.countDown() }
        }
        // setPackage makes this an explicit intent: Android delivers it to that package only.
        val intent = Intent(SegmenterContract.ACTION_BIND).setPackage(SegmenterContract.PACKAGE)
        val executor = Executors.newSingleThreadExecutor()
        val bound = try {
            if (Build.VERSION.SDK_INT >= 29) appContext.bindService(intent, Context.BIND_AUTO_CREATE, executor, connection)
            else appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        try {
            if (!bound || !connected.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            // Re-check now that we're connected, in case the package changed in between.
            if (!isTrusted(appContext.packageManager)) return null
            val api = service ?: return null
            if (api.apiVersion < SegmenterContract.API_VERSION) return null
            val result = ParcelFileDescriptor.open(imageFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                api.segment(fd, options)
            } ?: return null
            return parse(result)
        } catch (e: Exception) { // RemoteException / DeadObjectException: the companion died
            return null
        } finally {
            if (bound) runCatching { appContext.unbindService(connection) }
            executor.shutdown()
        }
    }

    private fun parse(result: Bundle): Pair<Int, List<SegmentedObject>> {
        val status = result.getInt(SegmenterContract.KEY_STATUS, SegmenterContract.STATUS_INTERNAL_ERROR)
        if (status != SegmenterContract.STATUS_OK) return status to emptyList()
        @Suppress("DEPRECATION")
        val instances = result.getParcelableArrayList<Bundle>(SegmenterContract.KEY_INSTANCES).orEmpty()
        return status to instances.map { b ->
            val w = b.getInt(SegmenterContract.KEY_MASK_WIDTH)
            val h = b.getInt(SegmenterContract.KEY_MASK_HEIGHT)
            SegmentedObject(
                classId = b.getInt(SegmenterContract.KEY_CLASS_ID),
                label = b.getString(SegmenterContract.KEY_LABEL).orEmpty(),
                confidence = b.getFloat(SegmenterContract.KEY_CONFIDENCE),
                box = b.getFloatArray(SegmenterContract.KEY_BOX) ?: FloatArray(4),
                mask = SegmenterContract.unpackMask(b.getByteArray(SegmenterContract.KEY_MASK) ?: ByteArray((w * h + 7) / 8), w, h),
                maskWidth = w,
                maskHeight = h
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(":", "").trim()
        return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

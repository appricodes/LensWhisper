// Copyright 2026 Mohammad Amiri Moalla
// SPDX-License-Identifier: Apache-2.0
package com.appricodes.lens_whisper.segmenter.client

/**
 * Every name and number in the LensWhisper Object Segmenter API (see API.md). Copy this file (and
 * ISegmenterService.aidl) into your app; both are Apache-2.0.
 */
object SegmenterContract {

    /** The companion app's package, and the action its service is bound with. */
    const val PACKAGE = "com.appricodes.lens_whisper.segmenter"
    const val ACTION_BIND = "com.appricodes.lens_whisper.segmenter.action.SEGMENT"

    /** What [com.appricodes.lens_whisper.segmenter.ISegmenterService.getApiVersion] returns for
     *  the API this file describes. */
    const val API_VERSION = 1

    /**
     * SHA-256 fingerprints of the certificates genuine builds of the companion are signed with, as
     * uppercase hex without separators. Check the installed package against these *before* sending
     * it an image — see API.md, "Sending images only to the real app". API.md lists the current
     * values; a build is genuine if its signing certificate matches any one of them.
     */
    val RELEASE_CERT_SHA256: Set<String> = setOf(
        // Builds published on GitHub (signed by the developer's own key).
        "3DF72F3764EF8A0DD5B62D8F13138306DB58336BDAE2664C133A47F334F1F382",
        // Google Play app signing certificate — added once the app is on Google Play.
    )

    // ── Options (all optional) ───────────────────────────────────────────────────────────────

    /** Float, 0.05..1. Detections below this confidence are dropped. Default 0.25. */
    const val OPTION_MIN_CONFIDENCE = "minConfidence"
    /** Int, 1..25. At most this many objects, strongest first. Default 25. */
    const val OPTION_MAX_INSTANCES = "maxInstances"

    // ── Result ───────────────────────────────────────────────────────────────────────────────

    /** Int, one of the STATUS_ values. Always present. */
    const val KEY_STATUS = "status"
    /** String, a short English explanation when [KEY_STATUS] isn't [STATUS_OK]. */
    const val KEY_MESSAGE = "message"
    /** String naming the model that produced the result, e.g. "yolov8m-seg-coco-int8". */
    const val KEY_MODEL = "model"
    /** Ints: the image's size in pixels as decoded (EXIF orientation is NOT applied). */
    const val KEY_IMAGE_WIDTH = "imageWidth"
    const val KEY_IMAGE_HEIGHT = "imageHeight"
    /** ArrayList<Bundle>, one per object, strongest first. Keys below. */
    const val KEY_INSTANCES = "instances"

    /** Int, the COCO class index 0..79 (stable across label wording changes). */
    const val KEY_CLASS_ID = "classId"
    /** String, the English COCO class name, e.g. "cup". */
    const val KEY_LABEL = "label"
    /** Float 0..1, the model's per-class (sigmoid) score. */
    const val KEY_CONFIDENCE = "confidence"
    /** FloatArray of 4: left, top, right, bottom, normalized 0..1 against the whole image. */
    const val KEY_BOX = "box"
    /** Ints: the mask grid's size. The grid spans the whole image. */
    const val KEY_MASK_WIDTH = "maskWidth"
    const val KEY_MASK_HEIGHT = "maskHeight"
    /** ByteArray: the mask, one bit per grid cell, row-major, most significant bit first — see
     *  [unpackMask]. */
    const val KEY_MASK = "mask"

    const val STATUS_OK = 0
    /** The data could not be read or decoded as an image. */
    const val STATUS_BAD_IMAGE = 1
    /** The encoded image is larger than [MAX_IMAGE_BYTES]. */
    const val STATUS_TOO_LARGE = 2
    /** This caller already has a request running, or too many are queued. Retry later. */
    const val STATUS_BUSY = 3
    /** Something else went wrong inside the service. */
    const val STATUS_INTERNAL_ERROR = 4

    /** Largest encoded image the service accepts. */
    const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
    /** Images are downsampled on decode so neither side exceeds this. */
    const val MAX_DECODED_DIMENSION = 2048

    /** Unpacks [KEY_MASK]: element `y * width + x` is true when cell (x, y) belongs to the object. */
    fun unpackMask(packed: ByteArray, width: Int, height: Int): BooleanArray =
        BooleanArray(width * height) { i -> (packed[i ushr 3].toInt() shr (7 - (i and 7))) and 1 == 1 }

    /** The inverse of [unpackMask]. */
    fun packMask(mask: BooleanArray): ByteArray {
        val out = ByteArray((mask.size + 7) / 8)
        for (i in mask.indices) if (mask[i]) out[i ushr 3] = (out[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
        return out
    }
}

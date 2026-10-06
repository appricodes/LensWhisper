// Copyright 2026 Mohammad Amiri Moalla
// SPDX-License-Identifier: Apache-2.0
//
// The LensWhisper Object Segmenter service API. See api/API.md for the full contract (option and
// result keys, status codes, mask encoding, and how to make sure you are talking to the real app).
//
// Compatibility rule: the order of the methods below is the wire protocol. Methods are never
// removed or reordered; new ones are only ever added at the end, and getApiVersion() goes up.
package com.appricodes.lens_whisper.segmenter;

interface ISegmenterService {
    /** The API version this service implements. 1 for this file. */
    int getApiVersion();

    /**
     * Finds the objects in one image and returns each one's COCO class, confidence, box and
     * pixel mask. Blocking (typically 0.5-3 seconds): never call it on the main thread.
     *
     * image: a readable file descriptor for an encoded image (JPEG, PNG, WebP, ...). The service
     *        reads it once and closes its copy; nothing is written to storage.
     * options: optional, may be null. See API.md.
     * Returns a Bundle with an int "status" (0 = OK) and, on success, the results. See API.md.
     */
    Bundle segment(in ParcelFileDescriptor image, in Bundle options);
}

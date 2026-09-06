package com.appricodes.lens_whisper.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What the user ticked in the Export dialog. Both default on, matching the dialog's own
 *  all-checked default. */
data class ExportSelection(
    val textNotes: Boolean = true,
    val voiceNotes: Boolean = true
) {
    val isEmpty: Boolean get() = !textNotes && !voiceNotes
}

/** One `.fdb` file found in the Downloads folder, offered to the user to import. [label] is what
 *  is read out — the file's own name plus when it was written. */
data class ExportFileRef(
    val uri: Uri,
    val name: String,
    val lastModified: Long
) {
    val label: String
        get() = "$name, saved ${SimpleDateFormat("d MMMM yyyy 'at' HH:mm", Locale.getDefault()).format(Date(lastModified))}"
}

/** The outcome of an export or import, in the terms the user is told about — never an exception
 *  and never an IO detail. */
sealed interface DataTransferResult {
    data class ExportSucceeded(val fileName: String, val noteCount: Int, val voiceNoteCount: Int) : DataTransferResult
    data class ImportSucceeded(val noteCount: Int, val voiceNoteCount: Int) : DataTransferResult
    data class Failed(val message: String) : DataTransferResult
}

/**
 * Moves the user's own barcode notes in and out of the app, through the device's Downloads folder
 * and nowhere else.
 *
 * **Downloads only, deliberately.** An earlier version of this offered a second destination — the
 * system "create document" picker, where Google Drive and other cloud providers appear. That was
 * removed on request: the only supported route is now "export to Downloads, import from Downloads",
 * so the file never leaves the phone unless the user themselves moves it. Nothing here talks to a
 * cloud service, and the app has no way to reach one. (The notes are also excluded from Android's
 * own automatic cloud backup and device-to-device transfer — see `res/xml/backup_rules.xml` and
 * `res/xml/data_extraction_rules.xml` — so this really is the single path data can take.)
 *
 * **What is in the file.** A plain zip containing `notes.json` (the notes database, serialized —
 * see [BarcodeNoteStore.toJson], which merges cleanly rather than having to replace the database
 * wholesale) and `audio/<key>.m4a` for each voice note, encrypted as a whole by [ExportCrypto] and
 * given the `.fdb` extension. Nothing else is exported: not settings, not downloaded models, not
 * cached photos.
 *
 * **Two storage eras.** On Android 10+ (API 29) the export is written through `MediaStore.Downloads`
 * with no permission and no picker. On Android 8/9 `MediaStore.Downloads` does not exist, so the
 * file is written directly and that needs `WRITE_EXTERNAL_STORAGE` — declared in the manifest with
 * `maxSdkVersion="28"` so it is requested on exactly those two versions and does not exist at all
 * on anything newer. See [needsLegacyStoragePermission].
 *
 * Everything here runs on [Dispatchers.IO]; nothing on this path touches the main thread.
 */
object DataTransferManager {

    private const val TAG = "DataTransferManager"
    private const val NOTES_ENTRY = "notes.json"
    private const val AUDIO_PREFIX = "audio/"
    const val MIME_TYPE = "application/octet-stream"

    /** True on Android 8/9, where writing to the public Downloads folder still means a real file
     *  write and therefore a runtime storage permission. False (and the permission is not even in
     *  the manifest) on Android 10 and above. */
    fun needsLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        return "lenswhisper-notes-$stamp.${ExportCrypto.EXTENSION}"
    }

    // --- export ---------------------------------------------------------------------------

    /**
     * Whether there is anything at all to export under [selection]. Called before the export
     * starts — a file with no entries is written happily enough but then fails its own import for
     * being empty, which would reach the user as a confusing "could not be read" about a file the
     * app itself had just produced.
     */
    suspend fun hasAnythingToExport(context: Context, selection: ExportSelection): Boolean =
        withContext(Dispatchers.IO) {
            (selection.textNotes && BarcodeNoteStore.countNotes(context) > 0) ||
                (selection.voiceNotes && BarcodeNoteStore.audioNoteFiles(context).isNotEmpty())
        }

    /** Writes the export into the device's public Downloads folder. The only export path there is. */
    suspend fun exportToDownloads(context: Context, selection: ExportSelection): DataTransferResult =
        withContext(Dispatchers.IO) {
            val fileName = suggestedFileName()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext DataTransferResult.Failed("Could not create the file in your Downloads folder.")
                    val counts = try {
                        resolver.openOutputStream(uri).use { out ->
                            if (out == null) throw IllegalStateException("No output stream for $uri")
                            writeArchive(context, out, selection)
                        }
                    } catch (e: Exception) {
                        // Remove the half-written pending entry rather than leaving an unopenable
                        // file sitting in the user's Downloads folder.
                        runCatching { resolver.delete(uri, null, null) }
                        throw e
                    }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    DataTransferResult.ExportSucceeded(fileName, counts.first, counts.second)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    val file = File(dir, fileName)
                    val counts = FileOutputStream(file).use { out -> writeArchive(context, out, selection) }
                    DataTransferResult.ExportSucceeded(fileName, counts.first, counts.second)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Export to Downloads failed", e)
                DataTransferResult.Failed("Could not save the export file. ${e.friendlyReason()}")
            }
        }

    /** Returns (text note count, voice note count) actually written. */
    private suspend fun writeArchive(
        context: Context,
        rawOut: OutputStream,
        selection: ExportSelection
    ): Pair<Int, Int> {
        var noteCount = 0
        var voiceCount = 0
        // The encrypting stream must be closed for GCM to emit its authentication tag, and closing
        // it closes rawOut in turn — which is exactly what `use` on the caller's side expects.
        ExportCrypto.encryptingStream(rawOut).use { encrypted ->
            ZipOutputStream(encrypted).use { zip ->
                if (selection.textNotes) {
                    val notes = BarcodeNoteStore.allNotes(context)
                    noteCount = notes.size
                    zip.putNextEntry(ZipEntry(NOTES_ENTRY))
                    zip.write(BarcodeNoteStore.toJson(notes).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                if (selection.voiceNotes) {
                    for (file in BarcodeNoteStore.audioNoteFiles(context)) {
                        zip.putNextEntry(ZipEntry("$AUDIO_PREFIX${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        voiceCount++
                    }
                }
            }
        }
        return noteCount to voiceCount
    }

    // --- import ---------------------------------------------------------------------------

    /**
     * The `.fdb` files this app can see in the Downloads folder, newest first — what the Import
     * window offers the user to pick from, so importing is one choice from a spoken list rather
     * than navigating a file browser.
     *
     * **What this can and cannot see, by Android version.** On Android 8/9 it lists the Downloads
     * folder directly (given the storage permission), so a file copied over from another phone by a
     * file manager or over USB shows up like any other. On Android 10+ scoped storage restricts a
     * `MediaStore.Downloads` query to files this app itself created, and reaching anything else
     * requires the system document picker — so on those versions this list reliably covers
     * "restore an export I made on this phone", and the Import window's "Choose a different file"
     * button covers the rest. Filtered on the `.fdb` extension alone rather than on the
     * `lenswhisper-notes-` prefix, so a file the user has renamed — or one written by an older
     * build under a different name — is still found.
     */
    suspend fun listExportsInDownloads(context: Context): List<ExportFileRef> = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                context.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("%.${ExportCrypto.EXTENSION}"),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                ExportFileRef(
                                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                                    name = cursor.getString(nameCol),
                                    // MediaStore reports DATE_MODIFIED in seconds, not millis.
                                    lastModified = cursor.getLong(dateCol) * 1000L
                                )
                            )
                        }
                    }
                } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.listFiles()
                    ?.filter { it.isFile && it.extension.equals(ExportCrypto.EXTENSION, ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { ExportFileRef(Uri.fromFile(it), it.name, it.lastModified()) }
                    ?: emptyList()
            }
        } catch (e: Exception) {
            // A missing permission or an unreadable volume is "nothing to list", not a crash — the
            // Import window's "Choose a different file" button still works.
            Log.w(TAG, "Could not list exports in Downloads", e)
            emptyList()
        }
    }

    /**
     * Reads an `.fdb` and merges it in: every voice note replaces the file of the same name, and
     * every text note replaces the row under the same key (see [BarcodeNoteStore.upsertAll]). Notes
     * on this device that the file says nothing about are left alone — an import adds and replaces,
     * it never wipes.
     */
    suspend fun importFrom(context: Context, uri: Uri): DataTransferResult = withContext(Dispatchers.IO) {
        var noteCount = 0
        var voiceCount = 0
        try {
            val audioDir = BarcodeNoteStore.ensureAudioDir(context)
            val audioDirPath = audioDir.canonicalPath
            var notesJson: String? = null

            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext DataTransferResult.Failed("Could not open that file.")
                ExportCrypto.decryptingStream(input.buffered()).use { decrypted ->
                    ZipInputStream(decrypted).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (!entry.isDirectory) {
                                if (name == NOTES_ENTRY) {
                                    notesJson = zip.readBytes().toString(Charsets.UTF_8)
                                } else if (name.startsWith(AUDIO_PREFIX)) {
                                    val target = File(audioDir, File(name).name)
                                    // Zip-slip guard: the file is encrypted with the app's own key,
                                    // but it comes from the Downloads folder, which every app on
                                    // the phone can write to — so an entry name is untrusted input.
                                    // Reject anything that would land outside the voice-note
                                    // directory rather than trusting the name.
                                    if (!target.canonicalPath.startsWith("$audioDirPath${File.separator}")) {
                                        Log.w(TAG, "Skipping entry with an unsafe path")
                                    } else {
                                        FileOutputStream(target).use { out -> zip.copyTo(out) }
                                        voiceCount++
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            }

            notesJson?.let { json ->
                noteCount = BarcodeNoteStore.upsertAll(context, BarcodeNoteStore.fromJson(json))
            }
            if (noteCount == 0 && voiceCount == 0) {
                DataTransferResult.Failed("That file did not contain any notes to import.")
            } else {
                DataTransferResult.ImportSucceeded(noteCount, voiceCount)
            }
        } catch (e: ExportCrypto.InvalidExportFileException) {
            DataTransferResult.Failed(e.message ?: "That file is not a Lens Whisper export.")
        } catch (e: Exception) {
            // A wrong key or a tampered file surfaces here, as a GCM tag mismatch part-way through
            // the stream rather than as a clean header rejection — see ExportCrypto.
            Log.w(TAG, "Import failed", e)
            DataTransferResult.Failed("That file could not be read. It may be damaged, or it may have been exported by a different app.")
        }
    }

    private fun Exception.friendlyReason(): String = when (this) {
        is java.io.IOException -> "There may not be enough free space."
        else -> "Please try again."
    }
}

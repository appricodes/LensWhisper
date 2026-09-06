package com.appricodes.lens_whisper.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.appricodes.lens_whisper.offline.BarcodeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One user-written note about one barcode. [key] is [BarcodeKey.of]'s output — the same string
 *  the voice note's filename uses, see that object's doc for why that identity must be shared. */
data class BarcodeNote(
    val key: String,
    val barcodeValue: String,
    val barcodeFormat: String,
    val text: String,
    val updatedAt: Long
)

/**
 * The user's own notes about barcodes they have scanned — a text note in SQLite and/or a voice
 * recording as a file, both keyed by [BarcodeKey.of] so a barcode scanned again weeks later finds
 * whatever the user left on it the first time.
 *
 * **Storage location.** Both live in the app's private internal storage — the database under
 * `getDatabasesPath` and the recordings under `filesDir/barcode_notes/` — which on Android is the
 * app-private area other apps cannot read. That is what makes it "secure storage" here; there is
 * deliberately no SQLCipher-style encryption at rest on top, which would mean either a hardcoded
 * key (no real protection against someone with the device unlocked) or a user passphrase the app
 * has no other reason to ask for. Data only ever leaves this area through Settings' explicit
 * Export flow, which says so in its own legal notice.
 *
 * **Threading.** Every function here is `suspend` and does its work on [Dispatchers.IO], including
 * the ones that look trivial: [SQLiteOpenHelper.getWritableDatabase] is itself the call that opens
 * (and, on a version bump, migrates) the database file, so it is disk I/O even when the query that
 * follows returns nothing. Nothing in this file may be called from the main thread. The one
 * exception is [audioFileFor], which is pure path arithmetic and touches no disk at all.
 */
object BarcodeNoteStore {

    private const val DB_NAME = "barcode_notes.db"
    private const val DB_VERSION = 1
    private const val TABLE = "notes"

    private const val COL_KEY = "barcode_key"
    private const val COL_VALUE = "barcode_value"
    private const val COL_FORMAT = "barcode_format"
    private const val COL_NOTE = "note"
    private const val COL_UPDATED = "updated_at"

    const val AUDIO_DIR_NAME = "barcode_notes"
    const val AUDIO_EXTENSION = "m4a"

    private class Helper(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$COL_KEY TEXT PRIMARY KEY NOT NULL, " +
                    "$COL_VALUE TEXT NOT NULL, " +
                    "$COL_FORMAT TEXT NOT NULL, " +
                    "$COL_NOTE TEXT NOT NULL, " +
                    "$COL_UPDATED INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No schema has ever shipped but version 1; a real migration replaces this the first
            // time one does. Dropping user notes on upgrade would be exactly the wrong default, so
            // this is left deliberately empty rather than a DROP/CREATE.
        }
    }

    @Volatile
    private var helper: Helper? = null

    private fun helper(context: Context): Helper {
        helper?.let { return it }
        return synchronized(this) {
            helper ?: Helper(context.applicationContext).also { helper = it }
        }
    }

    // --- text notes -----------------------------------------------------------------------

    suspend fun getNote(context: Context, key: String): BarcodeNote? = withContext(Dispatchers.IO) {
        helper(context).readableDatabase.query(
            TABLE, null, "$COL_KEY = ?", arrayOf(key), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.readNote() else null
        }
    }

    suspend fun saveNote(
        context: Context,
        key: String,
        barcodeValue: String,
        barcodeFormat: String,
        text: String
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(COL_KEY, key)
            put(COL_VALUE, barcodeValue)
            put(COL_FORMAT, barcodeFormat)
            put(COL_NOTE, text)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        helper(context).writableDatabase.insertWithOnConflict(
            TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    suspend fun deleteNote(context: Context, key: String) = withContext(Dispatchers.IO) {
        helper(context).writableDatabase.delete(TABLE, "$COL_KEY = ?", arrayOf(key))
        Unit
    }

    suspend fun allNotes(context: Context): List<BarcodeNote> = withContext(Dispatchers.IO) {
        helper(context).readableDatabase.query(TABLE, null, null, null, null, null, "$COL_UPDATED DESC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.readNote())
                }
            }
    }

    suspend fun countNotes(context: Context): Int = withContext(Dispatchers.IO) {
        helper(context).readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** Import's merge step: every incoming note replaces whatever this device had under the same
     *  key, and keys not present in the import are left alone — "replace the existing barcode's
     *  information with the imported database's", per the feature's own spec. Written in one
     *  transaction so a failure part-way through cannot leave a half-merged database. */
    suspend fun upsertAll(context: Context, notes: List<BarcodeNote>): Int = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) return@withContext 0
        val db = helper(context).writableDatabase
        db.beginTransaction()
        try {
            for (note in notes) {
                val values = ContentValues().apply {
                    put(COL_KEY, note.key)
                    put(COL_VALUE, note.barcodeValue)
                    put(COL_FORMAT, note.barcodeFormat)
                    put(COL_NOTE, note.text)
                    put(COL_UPDATED, note.updatedAt)
                }
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notes.size
    }

    private fun android.database.Cursor.readNote() = BarcodeNote(
        key = getString(getColumnIndexOrThrow(COL_KEY)),
        barcodeValue = getString(getColumnIndexOrThrow(COL_VALUE)),
        barcodeFormat = getString(getColumnIndexOrThrow(COL_FORMAT)),
        text = getString(getColumnIndexOrThrow(COL_NOTE)),
        updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED))
    )

    // --- voice notes ----------------------------------------------------------------------

    /** Pure path arithmetic — the one function here safe to call from the main thread (a Compose
     *  screen needs the target path to hand to MediaRecorder/MediaPlayer without a coroutine hop).
     *  Creating the directory is left to [ensureAudioDir], which is not. */
    fun audioFileFor(context: Context, key: String): File =
        File(audioDir(context), "$key.$AUDIO_EXTENSION")

    fun audioDir(context: Context): File = File(context.filesDir, AUDIO_DIR_NAME)

    suspend fun ensureAudioDir(context: Context): File = withContext(Dispatchers.IO) {
        audioDir(context).apply { mkdirs() }
    }

    suspend fun hasAudioNote(context: Context, key: String): Boolean = withContext(Dispatchers.IO) {
        audioFileFor(context, key).let { it.exists() && it.length() > 0 }
    }

    suspend fun deleteAudioNote(context: Context, key: String): Boolean = withContext(Dispatchers.IO) {
        audioFileFor(context, key).delete()
    }

    suspend fun audioNoteFiles(context: Context): List<File> = withContext(Dispatchers.IO) {
        audioDir(context).listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.equals(AUDIO_EXTENSION, ignoreCase = true) }
            ?: emptyList()
    }

    // --- export/import serialization ------------------------------------------------------

    /** The `notes.json` entry inside an exported `.fdb` — see
     *  [com.appricodes.lens_whisper.data.DataTransferManager]. A JSON document rather than the raw
     *  `.db` file: it merges cleanly into an existing database (see [upsertAll]) instead of having
     *  to replace it wholesale, and it survives a future schema bump on either side. */
    fun toJson(notes: List<BarcodeNote>): String {
        val array = JSONArray()
        for (note in notes) {
            array.put(
                JSONObject().apply {
                    put("key", note.key)
                    put("value", note.barcodeValue)
                    put("format", note.barcodeFormat)
                    put("note", note.text)
                    put("updated_at", note.updatedAt)
                }
            )
        }
        return JSONObject().apply {
            put("version", 1)
            put("notes", array)
        }.toString()
    }

    /** Tolerant of anything missing except the key and the note text — a record without those is
     *  not a note at all and is skipped rather than imported as an empty row. */
    fun fromJson(json: String): List<BarcodeNote> {
        val root = JSONObject(json)
        val array = root.optJSONArray("notes") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("key").takeIf { it.isNotBlank() } ?: continue
                val text = obj.optString("note").takeIf { it.isNotBlank() } ?: continue
                add(
                    BarcodeNote(
                        key = key,
                        barcodeValue = obj.optString("value", ""),
                        barcodeFormat = obj.optString("format", ""),
                        text = text,
                        updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
                    )
                )
            }
        }
    }
}

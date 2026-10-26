package com.appricodes.lens_whisper.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The optional offline product database: one SQLite file built from the Open Food Facts / Open
 * Products Facts bulk dumps by `tools/barcode_db/build_barcode_db.py`, published by this project
 * (see [ProductDbSource]) and downloaded onto the phone only if the user asks for it.
 *
 * **Why this exists.** It is the offline, private answer to "what is this product?". When a barcode
 * is found here, nothing is sent anywhere at all — no consent prompt, no network, no third party,
 * and it works with the phone in aeroplane mode in a supermarket basement. That makes it strictly
 * better than the UPCitemdb lookup ([com.appricodes.lens_whisper.network.UpcLookupClient]) on every
 * axis except coverage, which is why the scan screen tries this **first** and only falls back to
 * the online lookup for a barcode this database has never heard of.
 *
 * **Schema** — exactly what the builder script writes, one row per barcode:
 * ```
 * products(barcode TEXT PRIMARY KEY, product_name TEXT NOT NULL,
 *          brands TEXT, category TEXT, countries TEXT)
 * ```
 * Every column here is something worth saying out loud, because the builder's whole job is to
 * throw away everything that is not: the raw dumps carry full taxonomy chains, language-prefixed
 * slugs and forty-country distribution lists, none of which survive into this file. `category` is
 * singular and deliberately so — one most-specific term, not a chain. If that schema ever changes,
 * `publish_barcode_db.py` refuses to publish a database whose columns do not match this list.
 *
 * **Threading and lifetime.** Opened lazily, read-only, and kept open for the process — a database
 * this size is expensive to open and the file only changes when the user installs or removes one,
 * which both go through [invalidate]. Every entry point is `suspend` on [Dispatchers.IO]; the query
 * is a primary-key lookup, so it stays fast at millions of rows, but opening the file is real disk
 * work and must never happen on the main thread.
 */
object ProductDatabase {

    private const val TAG = "ProductDatabase"
    const val DIR_NAME = "product_db"

    @Volatile
    private var db: SQLiteDatabase? = null

    fun directory(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Where the installed database lives. Pure path arithmetic. */
    fun file(context: Context): File = File(directory(context), ProductDbSource.LOCAL_FILE_NAME)

    fun isInstalled(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    /** How much space the installed database is taking up — shown in Settings so the user can see
     *  what they get back by removing it. Zero when nothing is installed. */
    suspend fun installedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        file(context).let { if (it.exists()) it.length() else 0L }
    }

    /** How many products the installed database holds. Zero if nothing is installed or the file
     *  cannot be read — the caller shows it only as a reassurance, never as a health check. */
    suspend fun productCount(context: Context): Long = withContext(Dispatchers.IO) {
        val database = openIfPresent(context) ?: return@withContext 0L
        try {
            database.rawQuery("SELECT COUNT(*) FROM products", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not count products", e)
            0L
        }
    }

    /**
     * Looks [barcode] up, returning presentable label-to-value pairs in the same shape
     * [com.appricodes.lens_whisper.network.UpcLookupResult.Found] uses, or null if the database is
     * not installed or does not have it. Only digits are ever looked up — a 2D code's payload has
     * no business reaching a product table.
     */
    suspend fun lookup(context: Context, barcode: String): List<Pair<String, String>>? =
        withContext(Dispatchers.IO) {
            val code = barcode.trim()
            if (code.isEmpty() || !code.all { it.isDigit() }) return@withContext null
            val database = openIfPresent(context) ?: return@withContext null
            // Some scanners report a UPC-A barcode as 12 digits, while Open Food Facts commonly files the
            // same product under its 13-digit EAN form (the same digits with a leading 0) — and the
            // reverse for a scanned EAN-13 that starts with 0. Try the code as scanned first, then
            // its other form.
            val candidates = buildList {
                add(code)
                if (code.length == 12) add("0$code")
                if (code.length == 13 && code.startsWith("0")) add(code.substring(1))
            }
            try {
                candidates.firstNotNullOfOrNull { candidate -> lookupExact(database, candidate) }
            } catch (e: Exception) {
                // A corrupt file should degrade to "not found" and let the online lookup have its
                // turn, not take the scan screen down.
                Log.w(TAG, "Product lookup failed", e)
                null
            }
        }

    private fun lookupExact(database: SQLiteDatabase, code: String): List<Pair<String, String>>? =
        database.query(
            "products",
            arrayOf("product_name", "brands", "category", "countries"),
            "barcode = ?",
            arrayOf(code),
            null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val fields = mutableListOf<Pair<String, String>>()
            fun add(label: String, columnIndex: Int) {
                val value = if (cursor.isNull(columnIndex)) null else cursor.getString(columnIndex)?.trim()
                if (!value.isNullOrEmpty()) fields.add(label to value)
            }
            add("Product", 0)
            add("Brand", 1)
            add("Category", 2)
            add("Sold in", 3)
            fields.ifEmpty { null }
        }

    /**
     * Whether [file] is a usable copy of this database. Run once straight after a download, because
     * a truncated SQLite file still has a valid header and would otherwise install cleanly and then
     * fail deep inside a query — see
     * [com.appricodes.lens_whisper.service.ProductDbDownloader], which deletes anything this
     * rejects.
     */
    fun isUsableDatabase(file: File): Boolean = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT barcode, product_name FROM products LIMIT 1", null).use { cursor ->
                cursor.moveToFirst()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Downloaded file is not a usable product database", e)
        false
    }

    private fun openIfPresent(context: Context): SQLiteDatabase? {
        val current = db
        if (current != null && current.isOpen) return current
        val target = file(context)
        if (!target.exists() || target.length() == 0L) return null
        return synchronized(this) {
            val again = db
            if (again != null && again.isOpen) return@synchronized again
            val opened = runCatching {
                SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            }.getOrNull()
            db = opened
            opened
        }
    }

    /** Closes any open handle. Must be called whenever the file is replaced or deleted — a handle
     *  to a deleted file would otherwise keep answering from a file that is no longer there, and
     *  keep its disk space allocated until the process dies. */
    fun invalidate() {
        synchronized(this) {
            runCatching { db?.close() }
            db = null
        }
    }

    suspend fun delete(context: Context): Boolean = withContext(Dispatchers.IO) {
        invalidate()
        file(context).delete()
    }
}

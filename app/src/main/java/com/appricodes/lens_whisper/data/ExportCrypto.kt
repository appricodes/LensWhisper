package com.appricodes.lens_whisper.data

import com.appricodes.lens_whisper.BuildConfig
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts the `.fdb` file Settings' Export/Import writes and reads.
 *
 * **What this protects against, honestly.** The key is derived from a fixed passphrase compiled
 * into the app (`BuildConfig.BACKUP_PASSPHRASE`, supplied by the gitignored `secrets.properties` —
 * see `secrets.properties.example`). A fixed in-app key means anyone who can pull the APK apart can
 * derive the same key, so this is *not* protection against a determined attacker who has both the
 * file and the app. What it genuinely does is stop an exported file sitting in the Downloads folder
 * — a folder every other app on the phone can see — from being an ordinary zip that any file
 * manager opens, indexes and previews by accident; the user's private notes about the things around
 * their home stay opaque at rest. The export dialog says exactly this in its own notice; nothing
 * here pretends to more.
 *
 * **Format.** `LWFDB` magic, a version byte, a 16-byte random salt, a 12-byte random IV, then the
 * AES-256-GCM ciphertext. The salt is per-export and stored in the clear (that is what a salt is
 * for) so two exports of the same data never produce the same bytes, and so a future build can
 * change the KDF parameters without breaking old files. GCM rather than CBC: it authenticates as
 * well as encrypts, so a truncated or tampered `.fdb` fails loudly on import instead of unzipping
 * into garbage.
 */
object ExportCrypto {

    /** File extension the encrypted archive is written with, per the feature's spec. */
    const val EXTENSION = "fdb"

    private val MAGIC = byteArrayOf('L'.code.toByte(), 'W'.code.toByte(), 'F'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BITS = 256

    class InvalidExportFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Writes the header to [out] and returns a stream that encrypts everything written to it.
     * **The caller must close the returned stream** — `CipherOutputStream.close()` is what writes
     * the GCM authentication tag; skipping it produces a file that always fails to import.
     */
    fun encryptingStream(out: OutputStream): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        out.write(MAGIC)
        out.write(byteArrayOf(VERSION))
        out.write(salt)
        out.write(iv)
        out.flush()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return CipherOutputStream(out, cipher)
    }

    /**
     * Reads the header from [input] and returns a stream that decrypts the rest. Throws
     * [InvalidExportFileException] if the file is not one of ours; a *wrong key* or *tampered content*
     * cannot be detected here — GCM only discovers that at the end of the stream, so the caller
     * must also treat a read failure part-way through as "this file could not be opened".
     *
     * Note that `CipherInputStream` is known to swallow GCM's `AEADBadTagException` on some
     * platforms rather than rethrowing it, so the authentication tag must not be relied on as the
     * only integrity check. In practice the layer above is what catches it: the plaintext is a zip,
     * and a stream decrypted with the wrong key (or altered in transit) is not a valid zip, so
     * [com.appricodes.lens_whisper.data.DataTransferManager.importFrom] fails on the archive structure
     * and reports the same "could not be read" either way.
     */
    fun decryptingStream(input: InputStream): InputStream {
        val magic = ByteArray(MAGIC.size)
        if (input.readFullyOrNull(magic) == null || !magic.contentEquals(MAGIC)) {
            throw InvalidExportFileException("This is not a Lens Whisper export file.")
        }
        val version = input.read()
        if (version != VERSION.toInt()) {
            throw InvalidExportFileException("This file was exported by a different version of the app.")
        }
        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        if (input.readFullyOrNull(salt) == null || input.readFullyOrNull(iv) == null) {
            throw InvalidExportFileException("This export file is incomplete.")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return CipherInputStream(input, cipher)
    }

    private fun deriveKey(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(
            BuildConfig.BACKUP_PASSPHRASE.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_BITS
        )
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    /** `InputStream.read(buf)` may legitimately return fewer bytes than asked for; the header
     *  fields are fixed-size and must be read in full or not at all. */
    private fun InputStream.readFullyOrNull(buffer: ByteArray): ByteArray? {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return null
            offset += read
        }
        return buffer
    }
}

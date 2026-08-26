package com.appricodes.lens_whisper.network

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the shared secret for a Local Server that has a password set (see
 * `ModelManagementActivity`'s companion desktop app, `desktop/.../config/AuthConfig.kt`) and
 * encrypts/decrypts every `/process.php` request and response with it. Only the token itself is
 * ever stored (in [com.appricodes.lens_whisper.data.PreferencesManager.localServerPasswordToken])
 * — the password the user types is never persisted. There's no separate "send it and let the
 * server compare it" step any more: the token *is* the raw AES-256 key (its hex digits decoded
 * back to bytes), so a request the server can decrypt is, by construction, an authorized one.
 * Local Server mode requires a password now — [com.appricodes.lens_whisper.network.LocalServerEngine]
 * refuses to make any request while the token is blank, matching the desktop server's own refusal
 * to start without one.
 *
 * This is an independent re-implementation of the desktop app's `AuthConfig`, not a shared
 * library — [PEPPER] and the hashing/AES-GCM scheme must stay byte-for-byte identical on both
 * sides for a phone and a desktop to agree on the same key from the same password.
 */
object LocalServerAuth {

    // Must exactly match PEPPER in desktop/src/main/kotlin/.../config/AuthConfig.kt.
    private const val PEPPER = "LensWhisper-LocalServer-v1"

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    fun tokenFor(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((PEPPER + password).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Raw AES-256 key bytes for a stored [token] — just its hex digits decoded back to bytes,
     *  since [tokenFor] already produces hex(SHA-256(...)). */
    fun keyFor(token: String): ByteArray = hexToBytes(token)

    /** Encrypts [plaintext] with [key] using AES-256-GCM. Wire format is a fresh random 12-byte
     *  IV followed by the ciphertext (GCM's 16-byte auth tag rides along inside it). Must produce
     *  output the desktop's `AuthConfig.decrypt` can read, and vice versa. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Reverses [encrypt]. Returns null — never throws — for anything that doesn't decrypt
     *  cleanly: too-short input, a tampered ciphertext, or (the common real case) a token that
     *  doesn't match what the server is configured with. */
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray? {
        if (data.size <= GCM_IV_LENGTH) return null
        return try {
            val iv = data.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}

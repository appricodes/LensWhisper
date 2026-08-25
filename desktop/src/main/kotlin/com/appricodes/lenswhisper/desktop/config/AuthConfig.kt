package com.appricodes.lenswhisper.desktop.config

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mandatory shared-password protection for the Local Server's `/process.php` — a server refuses
 * to start at all without one (see `cmdServe`/`/api/server/start`), and the phone app refuses to
 * use Local Server mode without one either (see `LocalServerAuth`/`XaiVisionEngine`). The
 * password itself is never persisted or transmitted; only its SHA-256 token is stored (here, and
 * in the Android app's `network/LocalServerAuth.kt`/`PreferencesManager`). That same token also
 * *is* the raw AES-256 key used to encrypt every `/process.php` request and response body (see
 * [currentKey]/[encrypt]/[decrypt]) — there's no separate "send the token, compare it" auth step
 * any more; a request that doesn't decrypt is, by construction, an unauthorized one. `/health`
 * deliberately stays unencrypted/open regardless, since the phone's subnet scan
 * (`LocalServerDiscovery`) has to find the server before it can know any password.
 *
 * Both this file and the Android app's matching implementation must use the identical [PEPPER],
 * hashing, and AES-GCM scheme to interoperate — they're two independent implementations of the
 * same derivation, not a shared library.
 */
object AuthConfig {

    // Must exactly match PEPPER in the Android app's network/LocalServerAuth.kt.
    private const val PEPPER = "LensWhisper-LocalServer-v1"

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    @Volatile private var storedTokenHash: String? = null
    private lateinit var configFile: Path
    private var initialized = false

    /** Loads any previously-set password's token from disk. Call once at startup (both the GUI
     * and `serve` CLI paths — a password set via the GUI stays enforced even if the server is
     * later started via the CLI, since both read the same data dir). */
    @Synchronized
    fun init(dataDir: Path) {
        if (initialized) return
        configFile = dataDir.resolve("auth.txt")
        storedTokenHash = runCatching {
            if (Files.exists(configFile)) Files.readString(configFile).trim().ifBlank { null } else null
        }.getOrNull()
        initialized = true
    }

    fun isEnabled(): Boolean = !storedTokenHash.isNullOrBlank()

    /** Derives the token a client should send as `Authorization: Bearer <token>` for [password]. */
    fun tokenFor(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((PEPPER + password).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun setPassword(password: String) {
        val hash = tokenFor(password)
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, hash)
        storedTokenHash = hash
    }

    @Synchronized
    fun clearPassword() {
        storedTokenHash = null
        Files.deleteIfExists(configFile)
    }

    /** Raw AES-256 key bytes for the currently-set password — literally the token's hex decoded
     *  back to bytes, since [tokenFor] already *is* hex(SHA-256(...)). Null when no password is
     *  set, which — now that starting a server requires one (see `cmdServe`/`/api/server/start`)
     *  — should only happen for the brief window before a first-run user has set one yet. */
    fun currentKey(): ByteArray? = storedTokenHash?.let(::hexToBytes)

    /** Encrypts [plaintext] with [key] using AES-256-GCM. Wire format is a fresh random 12-byte
     *  IV followed by the ciphertext (GCM appends its 16-byte auth tag to the ciphertext itself,
     *  so nothing extra needs to be tracked separately). */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Reverses [encrypt]. Returns null — never throws — for anything that doesn't decrypt
     *  cleanly: too-short input, a tampered ciphertext, or (the common real case) a key that
     *  doesn't match, i.e. the client's password token disagrees with this server's. Callers
     *  treat null exactly like the old header-mismatch case used to be treated: "unauthorized". */
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

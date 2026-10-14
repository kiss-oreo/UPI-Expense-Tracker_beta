package com.goushik.upiwallet.util

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-encrypted manual backup; no network, no third-party crypto and no stored password/key.
 * A random per-file salt and nonce, PBKDF2-HMAC-SHA256 and authenticated AES-256-GCM prevent silent
 * modification as well as disclosure. The envelope is versioned independently from the JSON ledger.
 * Automatic plaintext backups are off by default; enabling them is an explicit user choice. */
object BackupCrypto {
    private const val FORMAT = "UET-ENC-1"
    private const val ITERATIONS = 240_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val AAD = "UPI Expense Tracker local backup v1"
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun isEncrypted(root: JSONObject): Boolean = root.optString("format") == FORMAT

    fun encrypt(plaintext: String, password: CharArray): String {
        require(password.size >= 8) { "Use at least 8 characters for the backup password." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = derive(password, salt, ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            return JSONObject().apply {
                put("format", FORMAT)
                put("cipher", "AES-256-GCM")
                put("kdf", "PBKDF2-HMAC-SHA256")
                put("iterations", ITERATIONS)
                put("salt", b64.encodeToString(salt))
                put("nonce", b64.encodeToString(nonce))
                put("data", b64.encodeToString(ciphertext))
            }.toString()
        } finally { key.fill(0) }
    }

    fun decrypt(root: JSONObject, password: CharArray): String {
        require(isEncrypted(root) && root.optString("cipher") == "AES-256-GCM" &&
            root.optString("kdf") == "PBKDF2-HMAC-SHA256") { "Unsupported encrypted backup format." }
        val iterations = root.optInt("iterations")
        require(iterations in 100_000..1_000_000) { "Unsupported backup key derivation cost." }
        val salt = runCatching { decoder.decode(root.getString("salt")) }.getOrNull()
        val nonce = runCatching { decoder.decode(root.getString("nonce")) }.getOrNull()
        val bytes = runCatching { decoder.decode(root.getString("data")) }.getOrNull()
        require(salt != null && nonce != null && bytes != null &&
            salt.size == SALT_BYTES && nonce.size == NONCE_BYTES && bytes.size >= 16) {
            "Encrypted backup is damaged."
        }
        val key = derive(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
            return try {
                String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                throw IllegalArgumentException("Wrong backup password or damaged file.")
            }
        } finally { key.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

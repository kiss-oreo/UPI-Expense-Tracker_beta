package com.goushik.upiwallet.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    @Test fun `encrypts sensitive JSON with random salt and nonce and decrypts`() {
        val original = """{"format":2,"transactions":[{"raw":"Bank SMS ₹100 😀"}]}"""
        val pass = "correct horse battery staple".toCharArray()
        val first = BackupCrypto.encrypt(original, pass)
        val second = BackupCrypto.encrypt(original, pass)
        assertTrue(BackupCrypto.isEncrypted(JSONObject(first)))
        assertFalse(first.contains("Bank SMS"))
        assertFalse(first.contains("transactions"))
        assertFalse(first == second)
        assertEquals(original, BackupCrypto.decrypt(JSONObject(first), pass))
        assertEquals(original, BackupCrypto.decrypt(JSONObject(second), pass))
    }

    @Test fun `wrong password and tampering never return partial plaintext`() {
        val password = "a long password".toCharArray()
        val encrypted = JSONObject(BackupCrypto.encrypt("private data", password))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(encrypted, "wrong password".toCharArray())
        }
        encrypted.put("data", encrypted.getString("data").dropLast(2) + "AB")
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(encrypted, password) }
    }

    @Test fun `rejects short passwords and excessive KDF cost from untrusted files`() {
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.encrypt("secret", "short".toCharArray()) }
        val encrypted = JSONObject(BackupCrypto.encrypt("secret", "longer password".toCharArray()))
        encrypted.put("iterations", Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(encrypted, "longer password".toCharArray())
        }
    }
}

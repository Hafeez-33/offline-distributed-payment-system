package com.demo.upimesh.db

import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.db.keystore.EncryptedKeyEnvelope
import com.demo.upimesh.db.keystore.KeyStoreManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class KeyStoreManagerTest {

    private val keyStoreManager = TestFixtures.createTestMasterKeyManager()

    @Test
    fun testPrivateKeyEncryptionAndDecryptionRoundtrip() {
        val keyPair = Ed25519Crypto.generateKeyPair()
        val originalPrivateBytes = keyPair.private.encoded

        val envelope = keyStoreManager.encryptPrivateKey(originalPrivateBytes)

        assertNotNull(envelope.ciphertextBase64)
        assertNotNull(envelope.ivBase64)
        assertNotEquals(Base64.getEncoder().encodeToString(originalPrivateBytes), envelope.ciphertextBase64)

        val decryptedBytes = keyStoreManager.decryptPrivateKey(envelope)

        assertArrayEquals(originalPrivateBytes, decryptedBytes)
        val decodedPrivateKey = Ed25519Crypto.decodePrivateKey(Base64.getEncoder().encodeToString(decryptedBytes))
        assertEquals(keyPair.private, decodedPrivateKey)
    }

    @Test
    fun testTamperedCiphertextThrowsException() {
        val keyPair = Ed25519Crypto.generateKeyPair()
        val envelope = keyStoreManager.encryptPrivateKey(keyPair.private.encoded)

        val tamperedCiphertextBytes = Base64.getDecoder().decode(envelope.ciphertextBase64)
        tamperedCiphertextBytes[0] = (tamperedCiphertextBytes[0].toInt() xor 0xFF).toByte()
        val tamperedEnvelope = EncryptedKeyEnvelope(
            ciphertextBase64 = Base64.getEncoder().encodeToString(tamperedCiphertextBytes),
            ivBase64 = envelope.ivBase64
        )

        assertThrows(Exception::class.java) {
            keyStoreManager.decryptPrivateKey(tamperedEnvelope)
        }
    }

    @Test
    fun testBestEffortZeroization() {
        val secretBytes = byteArrayOf(1, 2, 3, 4, 5)
        keyStoreManager.bestEffortZeroize(secretBytes)
        for (b in secretBytes) {
            assertEquals(0.toByte(), b)
        }
    }
}

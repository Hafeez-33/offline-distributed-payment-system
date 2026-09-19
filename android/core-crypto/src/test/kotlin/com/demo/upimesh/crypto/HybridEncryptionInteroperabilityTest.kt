package com.demo.upimesh.crypto

import com.demo.upimesh.model.PaymentInstruction
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException

class HybridEncryptionInteroperabilityTest {

    companion object {
        private lateinit var rootNode: JsonNode
        private val mapper = ObjectMapper()

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val stream = HybridEncryptionInteroperabilityTest::class.java.classLoader
                .getResourceAsStream("upi_crypto_test_vectors_v1.json")
                ?: error("upi_crypto_test_vectors_v1.json not found in test resources")
            rootNode = mapper.readTree(stream)
        }
    }

    @Test
    fun testJavaGeneratedEnvelopeDecryptedByKotlin() {
        val serverPrivBase64 = rootNode["keys"]["rsa"]["server"]["privateKeyPkcs8Base64"].asText()
        val serverPriv = HybridCrypto.decodeRsaPrivateKey(serverPrivBase64)

        val vectorNode = rootNode["hybrid_encryption"]["vectors"][0]
        val wireBase64 = vectorNode["wireBase64Ciphertext"].asText()
        val expectedPlaintextJson = vectorNode["plaintextJson"].asText()

        val decryptedBytes = HybridCrypto.decrypt(wireBase64, serverPriv)
        val decryptedJson = String(decryptedBytes, StandardCharsets.UTF_8)

        // Compare JSON objects
        val expectedPi = mapper.readValue(expectedPlaintextJson, PaymentInstruction::class.java)
        val actualPi = mapper.readValue(decryptedJson, PaymentInstruction::class.java)

        assertEquals(expectedPi.senderVpa, actualPi.senderVpa)
        assertEquals(expectedPi.receiverVpa, actualPi.receiverVpa)
        assertEquals(expectedPi.amount, actualPi.amount)
        assertEquals(expectedPi.nonce, actualPi.nonce)
        assertEquals(expectedPi.signedAt, actualPi.signedAt)
    }

    @Test
    fun testKotlinEncryptsEnvelopeCompatibleWithJavaFormat() {
        val serverPubBase64 = rootNode["keys"]["rsa"]["server"]["publicKeyX509Base64"].asText()
        val serverPrivBase64 = rootNode["keys"]["rsa"]["server"]["privateKeyPkcs8Base64"].asText()
        val serverPub = HybridCrypto.decodeRsaPublicKey(serverPubBase64)
        val serverPriv = HybridCrypto.decodeRsaPrivateKey(serverPrivBase64)

        val pi = PaymentInstruction(
            senderVpa = "alice@demo",
            receiverVpa = "bob@demo",
            amount = BigDecimal("75.25"),
            pinHash = "pin123",
            nonce = "660e8400-e29b-41d4-a716-446655440099",
            signedAt = 1726481000000L
        )

        val plaintextBytes = mapper.writeValueAsBytes(pi)

        // Encrypt with Kotlin
        val wireBase64 = HybridCrypto.encrypt(plaintextBytes, serverPub)
        assertNotNull(wireBase64)

        // Verify packetHash can be computed from wire format
        val hash = PacketHasher.hashCiphertext(wireBase64)
        assertEquals(64, hash.length)

        // Decrypt with Kotlin
        val decryptedBytes = HybridCrypto.decrypt(wireBase64, serverPriv)
        val recoveredPi = mapper.readValue(decryptedBytes, PaymentInstruction::class.java)

        assertEquals(pi.senderVpa, recoveredPi.senderVpa)
        assertEquals(pi.receiverVpa, recoveredPi.receiverVpa)
        assertEquals(pi.amount, recoveredPi.amount)
        assertEquals(pi.nonce, recoveredPi.nonce)
    }

    @Test
    fun testTamperedCiphertextThrowsOnDecryption() {
        val serverPrivBase64 = rootNode["keys"]["rsa"]["server"]["privateKeyPkcs8Base64"].asText()
        val serverPriv = HybridCrypto.decodeRsaPrivateKey(serverPrivBase64)

        val vectorNode = rootNode["hybrid_encryption"]["vectors"][0]
        val wireBase64 = vectorNode["wireBase64Ciphertext"].asText()

        val rawBytes = Base64.getDecoder().decode(wireBase64)
        // Flip one bit in AES ciphertext / tag
        rawBytes[rawBytes.size - 1] = (rawBytes[rawBytes.size - 1].toInt() xor 0xFF).toByte()
        val tamperedWireBase64 = Base64.getEncoder().encodeToString(rawBytes)

        assertThrows(Exception::class.java) {
            HybridCrypto.decrypt(tamperedWireBase64, serverPriv)
        }
    }
}

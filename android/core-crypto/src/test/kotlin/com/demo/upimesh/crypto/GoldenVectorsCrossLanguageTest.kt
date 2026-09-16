package com.demo.upimesh.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class GoldenVectorsCrossLanguageTest {

    companion object {
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val mapper = ObjectMapper()
            val stream = GoldenVectorsCrossLanguageTest::class.java.classLoader
                .getResourceAsStream("upi_crypto_test_vectors_v1.json")
                ?: error("upi_crypto_test_vectors_v1.json not found in test resources")
            rootNode = mapper.readTree(stream)
        }
    }

    @Test
    fun testCorpusMetadataAndKeyIntegrity() {
        assertEquals("1.0.0", rootNode["version"].asText())
        assertNotNull(rootNode["keys"]["ed25519"]["sender"])
        assertNotNull(rootNode["keys"]["ed25519"]["issuer"])
        assertNotNull(rootNode["keys"]["ed25519"]["device"])
        assertNotNull(rootNode["keys"]["rsa"]["server"])
    }

    @Test
    fun testFullGoldenVectorCorpusVerification() {
        val senderPub = Ed25519Crypto.decodePublicKey(rootNode["keys"]["ed25519"]["sender"]["publicKeyX509Base64"].asText())
        val issuerPub = Ed25519Crypto.decodePublicKey(rootNode["keys"]["ed25519"]["issuer"]["publicKeyX509Base64"].asText())
        val devicePub = Ed25519Crypto.decodePublicKey(rootNode["keys"]["ed25519"]["device"]["publicKeyX509Base64"].asText())
        val serverPriv = HybridCrypto.decodeRsaPrivateKey(rootNode["keys"]["rsa"]["server"]["privateKeyPkcs8Base64"].asText())

        // 1. v1 transactions
        val v1Nodes = rootNode["canonicalization_and_signatures"]["v1_transactions"]
        for (node in v1Nodes) {
            val canonBytes = Hex.decode(node["expectedCanonicalBytesHex"].asText())
            val sig = node["expectedSignatureBase64"].asText()
            assertTrue(Ed25519Crypto.verify(canonBytes, sig, senderPub))
        }

        // 2. v3 transactions
        val v3Nodes = rootNode["canonicalization_and_signatures"]["v3_transactions"]
        for (node in v3Nodes) {
            val canonBytes = Hex.decode(node["expectedCanonicalBytesHex"].asText())
            val sig = node["expectedSignatureBase64"].asText()
            assertTrue(Ed25519Crypto.verify(canonBytes, sig, devicePub))
        }

        // 3. Certificates
        val certNodes = rootNode["canonicalization_and_signatures"]["certificates"]
        for (node in certNodes) {
            val canonBytes = Hex.decode(node["expectedCanonicalBytesHex"].asText())
            val sig = node["expectedSignatureBase64"].asText()
            assertTrue(Ed25519Crypto.verify(canonBytes, sig, issuerPub))
        }

        // 4. Receipts
        val receiptNodes = rootNode["canonicalization_and_signatures"]["receipts"]
        for (node in receiptNodes) {
            val canonBytes = Hex.decode(node["expectedCanonicalBytesHex"].asText())
            val sig = node["expectedSignatureBase64"].asText()
            assertTrue(Ed25519Crypto.verify(canonBytes, sig, issuerPub))
        }

        // 5. Hybrid vectors
        val hNodes = rootNode["hybrid_encryption"]["vectors"]
        for (node in hNodes) {
            val wireBase64 = node["wireBase64Ciphertext"].asText()
            val expectedHash = node["expectedPacketHash"].asText()
            val actualHash = PacketHasher.hashCiphertext(wireBase64)
            assertEquals(expectedHash, actualHash)

            val decrypted = HybridCrypto.decrypt(wireBase64, serverPriv)
            assertNotNull(decrypted)
            assertTrue(decrypted.isNotEmpty())
        }

        // 6. Packet hashes
        val hashNodes = rootNode["packet_hashes"]
        for (node in hashNodes) {
            val ciphertext = node["ciphertext"].asText()
            val expectedHash = node["expectedPacketHash"].asText()
            assertEquals(expectedHash, PacketHasher.hashCiphertext(ciphertext))
        }
    }
}

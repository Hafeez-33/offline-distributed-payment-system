package com.demo.upimesh.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Base64

class Ed25519InteroperabilityTest {

    companion object {
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val mapper = ObjectMapper()
            val stream = Ed25519InteroperabilityTest::class.java.classLoader
                .getResourceAsStream("upi_crypto_test_vectors_v1.json")
                ?: error("upi_crypto_test_vectors_v1.json not found in test resources")
            rootNode = mapper.readTree(stream)
        }
    }

    @Test
    fun testJavaGeneratedV1SignaturesVerifiedByKotlin() {
        val senderPubBase64 = rootNode["keys"]["ed25519"]["sender"]["publicKeyX509Base64"].asText()
        val senderPub = Ed25519Crypto.decodePublicKey(senderPubBase64)

        val v1Nodes = rootNode["canonicalization_and_signatures"]["v1_transactions"]
        for (node in v1Nodes) {
            val id = node["id"].asText()
            val canonBytesHex = node["expectedCanonicalBytesHex"].asText()
            val canonBytes = Hex.decode(canonBytesHex)
            val expectedSig = node["expectedSignatureBase64"].asText()

            // Verify signature bytes length is 64
            val sigBytes = Base64.getDecoder().decode(expectedSig)
            assertEquals(64, sigBytes.size, "Ed25519 signature must be exactly 64 bytes")

            // Verify signature with Kotlin Ed25519Crypto
            val valid = Ed25519Crypto.verify(canonBytes, expectedSig, senderPub)
            assertTrue(valid, "Java signature verification failed in Kotlin for vector: $id")

            // Tamper payload byte - must fail
            val tamperedBytes = canonBytes.copyOf()
            tamperedBytes[0] = (tamperedBytes[0].toInt() xor 0xFF).toByte()
            val tamperedValid = Ed25519Crypto.verify(tamperedBytes, expectedSig, senderPub)
            assertFalse(tamperedValid, "Tampered payload must fail verification for vector: $id")
        }
    }

    @Test
    fun testJavaGeneratedV3SignaturesVerifiedByKotlin() {
        val devicePubBase64 = rootNode["keys"]["ed25519"]["device"]["publicKeyX509Base64"].asText()
        val devicePub = Ed25519Crypto.decodePublicKey(devicePubBase64)

        val v3Nodes = rootNode["canonicalization_and_signatures"]["v3_transactions"]
        for (node in v3Nodes) {
            val id = node["id"].asText()
            val canonBytesHex = node["expectedCanonicalBytesHex"].asText()
            val canonBytes = Hex.decode(canonBytesHex)
            val expectedSig = node["expectedSignatureBase64"].asText()

            val valid = Ed25519Crypto.verify(canonBytes, expectedSig, devicePub)
            assertTrue(valid, "Java signature verification failed in Kotlin for v3 vector: $id")
        }
    }

    @Test
    fun testKotlinSignsAndProducesIdenticalDeterministicSignature() {
        val senderPrivBase64 = rootNode["keys"]["ed25519"]["sender"]["privateKeyPkcs8Base64"].asText()
        val senderPubBase64 = rootNode["keys"]["ed25519"]["sender"]["publicKeyX509Base64"].asText()
        val senderPriv = Ed25519Crypto.decodePrivateKey(senderPrivBase64)
        val senderPub = Ed25519Crypto.decodePublicKey(senderPubBase64)

        val v1Nodes = rootNode["canonicalization_and_signatures"]["v1_transactions"]
        for (node in v1Nodes) {
            val id = node["id"].asText()
            val canonBytesHex = node["expectedCanonicalBytesHex"].asText()
            val canonBytes = Hex.decode(canonBytesHex)
            val expectedSig = node["expectedSignatureBase64"].asText()

            val kotlinSig = Ed25519Crypto.sign(canonBytes, senderPriv)
            // In RFC 8032 deterministic Ed25519, Kotlin signature matches Java signature identically!
            assertEquals(expectedSig, kotlinSig, "Deterministic signature mismatch for vector: $id")
            assertTrue(Ed25519Crypto.verify(canonBytes, kotlinSig, senderPub))
        }
    }
}

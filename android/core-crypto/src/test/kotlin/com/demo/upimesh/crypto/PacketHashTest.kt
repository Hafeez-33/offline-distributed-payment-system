package com.demo.upimesh.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class PacketHashTest {

    companion object {
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val mapper = ObjectMapper()
            val stream = PacketHashTest::class.java.classLoader
                .getResourceAsStream("upi_crypto_test_vectors_v1.json")
                ?: error("upi_crypto_test_vectors_v1.json not found in test resources")
            rootNode = mapper.readTree(stream)
        }
    }

    @Test
    fun testPacketHashAgainstGoldenVectors() {
        val hashNodes = rootNode["packet_hashes"]
        assertNotNull(hashNodes)
        assertTrue(hashNodes.size() > 0)

        for (node in hashNodes) {
            val id = node["id"].asText()
            val ciphertext = node["ciphertext"].asText()
            val expectedHash = node["expectedPacketHash"].asText()

            val computedHash = PacketHasher.hashCiphertext(ciphertext)
            assertEquals(expectedHash, computedHash, "Packet hash mismatch for: $id")
            assertEquals(64, computedHash.length, "Packet hash must be exactly 64 hex characters")
            assertEquals(computedHash.lowercase(), computedHash, "Packet hash must be lowercase hex")
        }
    }
}

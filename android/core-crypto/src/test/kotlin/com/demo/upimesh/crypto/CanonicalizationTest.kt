package com.demo.upimesh.crypto

import com.demo.upimesh.model.OfflineWalletCertificate
import com.demo.upimesh.model.PaymentInstruction
import com.demo.upimesh.model.SettlementReceipt
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

class CanonicalizationTest {

    companion object {
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val mapper = ObjectMapper()
            val stream = CanonicalizationTest::class.java.classLoader
                .getResourceAsStream("upi_crypto_test_vectors_v1.json")
                ?: error("upi_crypto_test_vectors_v1.json not found in test resources")
            rootNode = mapper.readTree(stream)
        }

        private fun toHex(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    @Test
    fun testV1CanonicalizationAgainstGoldenVectors() {
        val v1Nodes = rootNode["canonicalization_and_signatures"]["v1_transactions"]
        assertNotNull(v1Nodes)
        assertTrue(v1Nodes.size() > 0)

        for (node in v1Nodes) {
            val id = node["id"].asText()
            val pi = PaymentInstruction(
                senderVpa = node["senderVpa"].asText(),
                receiverVpa = node["receiverVpa"].asText(),
                amount = BigDecimal(node["amount"].asText()),
                nonce = node["nonce"].asText(),
                signedAt = node["signedAt"].asLong()
            )

            val canonicalString = CanonicalSerializer.toCanonicalString(pi)
            val expectedString = node["expectedCanonicalString"].asText()
            assertEquals(expectedString, canonicalString, "Canonical string mismatch for v1 vector: $id")

            val canonicalBytes = CanonicalSerializer.toCanonicalBytes(pi)
            val expectedHex = node["expectedCanonicalBytesHex"].asText()
            assertEquals(expectedHex, toHex(canonicalBytes), "Canonical bytes hex mismatch for v1 vector: $id")
        }
    }

    @Test
    fun testV3CanonicalizationAgainstGoldenVectors() {
        val v3Nodes = rootNode["canonicalization_and_signatures"]["v3_transactions"]
        assertNotNull(v3Nodes)
        assertTrue(v3Nodes.size() > 0)

        for (node in v3Nodes) {
            val id = node["id"].asText()
            val walletEpoch = if (node.hasNonNull("walletEpoch")) node["walletEpoch"].asLong() else null
            val seqCounter = if (node.hasNonNull("sequenceCounter")) node["sequenceCounter"].asLong() else null
            val cumAmount = if (node.hasNonNull("cumulativeAmount")) BigDecimal(node["cumulativeAmount"].asText()) else null

            val pi = PaymentInstruction(
                walletId = node["walletId"].asText(),
                walletEpoch = walletEpoch,
                sequenceCounter = seqCounter,
                cumulativeAmount = cumAmount,
                senderVpa = node["senderVpa"].asText(),
                receiverVpa = node["receiverVpa"].asText(),
                amount = BigDecimal(node["amount"].asText()),
                nonce = node["nonce"].asText(),
                signedAt = node["signedAt"].asLong()
            )

            val canonicalString = CanonicalSerializer.toCanonicalString(pi)
            val expectedString = node["expectedCanonicalString"].asText()
            assertEquals(expectedString, canonicalString, "Canonical string mismatch for v3 vector: $id")

            val canonicalBytes = CanonicalSerializer.toCanonicalBytes(pi)
            val expectedHex = node["expectedCanonicalBytesHex"].asText()
            assertEquals(expectedHex, toHex(canonicalBytes), "Canonical bytes hex mismatch for v3 vector: $id")
        }
    }

    @Test
    fun testCertificateCanonicalizationAgainstGoldenVectors() {
        val certNodes = rootNode["canonicalization_and_signatures"]["certificates"]
        assertNotNull(certNodes)
        assertTrue(certNodes.size() > 0)

        for (node in certNodes) {
            val id = node["id"].asText()
            val walletId = if (node.hasNonNull("walletId")) node["walletId"].asText() else null
            val ownerVpa = if (node.hasNonNull("ownerVpa")) node["ownerVpa"].asText() else null
            val ownerPublicKey = if (node.hasNonNull("ownerPublicKey")) node["ownerPublicKey"].asText() else null
            val allocAmount = if (node.hasNonNull("allocatedAmount")) BigDecimal(node["allocatedAmount"].asText()) else null
            val walletEpoch = if (node.hasNonNull("walletEpoch")) node["walletEpoch"].asLong() else null
            val initialCounter = if (node.hasNonNull("initialCounter")) node["initialCounter"].asLong() else null

            val cert = OfflineWalletCertificate(
                walletId = walletId,
                ownerVpa = ownerVpa,
                ownerPublicKey = ownerPublicKey,
                allocatedAmount = allocAmount,
                walletEpoch = walletEpoch,
                validFrom = node["validFrom"].asLong(),
                validUntil = node["validUntil"].asLong(),
                initialCounter = initialCounter
            )

            val canonicalString = CanonicalSerializer.toCanonicalString(cert)
            val expectedString = node["expectedCanonicalString"].asText()
            assertEquals(expectedString, canonicalString, "Canonical string mismatch for cert vector: $id")

            val canonicalBytes = CanonicalSerializer.toCanonicalBytes(cert)
            val expectedHex = node["expectedCanonicalBytesHex"].asText()
            assertEquals(expectedHex, toHex(canonicalBytes), "Canonical bytes hex mismatch for cert vector: $id")
        }
    }

    @Test
    fun testReceiptCanonicalizationAgainstGoldenVectors() {
        val receiptNodes = rootNode["canonicalization_and_signatures"]["receipts"]
        assertNotNull(receiptNodes)
        assertTrue(receiptNodes.size() > 0)

        for (node in receiptNodes) {
            val id = node["id"].asText()
            val packetHash = if (node.hasNonNull("packetHash")) node["packetHash"].asText() else null
            val counter = if (node.hasNonNull("counter")) node["counter"].asLong() else null
            val status = if (node.hasNonNull("status")) node["status"].asText() else null

            val receipt = SettlementReceipt(
                transactionId = node["transactionId"].asLong(),
                packetHash = packetHash,
                counter = counter,
                status = status,
                settledAt = node["settledAt"].asLong()
            )

            val canonicalString = CanonicalSerializer.toCanonicalString(receipt)
            val expectedString = node["expectedCanonicalString"].asText()
            assertEquals(expectedString, canonicalString, "Canonical string mismatch for receipt vector: $id")

            val canonicalBytes = CanonicalSerializer.toCanonicalBytes(receipt)
            val expectedHex = node["expectedCanonicalBytesHex"].asText()
            assertEquals(expectedHex, toHex(canonicalBytes), "Canonical bytes hex mismatch for receipt vector: $id")
        }
    }
}

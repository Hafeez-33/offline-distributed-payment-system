package com.demo.upimesh.crypto

import com.demo.upimesh.model.OfflineWalletCertificate
import com.demo.upimesh.model.SettlementReceipt
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CertificateAndReceiptVerificationTest {

    companion object {
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val mapper = ObjectMapper()
            val stream = CertificateAndReceiptVerificationTest::class.java.classLoader
                .getResourceAsStream("upi_crypto_test_vectors_v1.json")
                ?: error("upi_crypto_test_vectors_v1.json not found in test resources")
            rootNode = mapper.readTree(stream)
        }
    }

    @Test
    fun testJavaIssuedCertificateVerificationInKotlin() {
        val issuerPubBase64 = rootNode["keys"]["ed25519"]["issuer"]["publicKeyX509Base64"].asText()
        val issuerPub = Ed25519Crypto.decodePublicKey(issuerPubBase64)

        val certNodes = rootNode["canonicalization_and_signatures"]["certificates"]
        for (node in certNodes) {
            val id = node["id"].asText()
            val walletId = if (node.hasNonNull("walletId")) node["walletId"].asText() else null
            val ownerVpa = if (node.hasNonNull("ownerVpa")) node["ownerVpa"].asText() else null
            val ownerPublicKey = if (node.hasNonNull("ownerPublicKey")) node["ownerPublicKey"].asText() else null
            val allocAmount = if (node.hasNonNull("allocatedAmount")) BigDecimal(node["allocatedAmount"].asText()) else null
            val walletEpoch = if (node.hasNonNull("walletEpoch")) node["walletEpoch"].asLong() else null
            val initialCounter = if (node.hasNonNull("initialCounter")) node["initialCounter"].asLong() else null
            val sig = node["expectedSignatureBase64"].asText()

            val cert = OfflineWalletCertificate(
                walletId = walletId,
                ownerVpa = ownerVpa,
                ownerPublicKey = ownerPublicKey,
                allocatedAmount = allocAmount,
                walletEpoch = walletEpoch,
                validFrom = node["validFrom"].asLong(),
                validUntil = node["validUntil"].asLong(),
                initialCounter = initialCounter,
                issuerSignature = sig
            )

            val canonBytes = CanonicalSerializer.toCanonicalBytes(cert)
            val valid = Ed25519Crypto.verify(canonBytes, cert.issuerSignature, issuerPub)
            assertTrue(valid, "Certificate signature verification failed for: $id")

            // Tamper allocated amount -> must fail
            val tamperedCert = cert.copy(allocatedAmount = BigDecimal("999999.00"))
            val tamperedBytes = CanonicalSerializer.toCanonicalBytes(tamperedCert)
            val tamperedValid = Ed25519Crypto.verify(tamperedBytes, cert.issuerSignature, issuerPub)
            assertFalse(tamperedValid, "Tampered certificate must fail signature verification")
        }
    }

    @Test
    fun testJavaIssuedReceiptVerificationInKotlin() {
        val issuerPubBase64 = rootNode["keys"]["ed25519"]["issuer"]["publicKeyX509Base64"].asText()
        val issuerPub = Ed25519Crypto.decodePublicKey(issuerPubBase64)

        val receiptNodes = rootNode["canonicalization_and_signatures"]["receipts"]
        for (node in receiptNodes) {
            val id = node["id"].asText()
            val packetHash = if (node.hasNonNull("packetHash")) node["packetHash"].asText() else null
            val counter = if (node.hasNonNull("counter")) node["counter"].asLong() else null
            val status = if (node.hasNonNull("status")) node["status"].asText() else null
            val sig = node["expectedSignatureBase64"].asText()

            val receipt = SettlementReceipt(
                transactionId = node["transactionId"].asLong(),
                packetHash = packetHash,
                counter = counter,
                status = status,
                settledAt = node["settledAt"].asLong(),
                serverSignature = sig
            )

            val canonBytes = CanonicalSerializer.toCanonicalBytes(receipt)
            val valid = Ed25519Crypto.verify(canonBytes, receipt.serverSignature, issuerPub)
            assertTrue(valid, "Receipt signature verification failed for: $id")

            // Tamper status -> must fail
            val tamperedReceipt = receipt.copy(status = "REVERTED")
            val tamperedBytes = CanonicalSerializer.toCanonicalBytes(tamperedReceipt)
            val tamperedValid = Ed25519Crypto.verify(tamperedBytes, receipt.serverSignature, issuerPub)
            assertFalse(tamperedValid, "Tampered receipt must fail signature verification")
        }
    }
}

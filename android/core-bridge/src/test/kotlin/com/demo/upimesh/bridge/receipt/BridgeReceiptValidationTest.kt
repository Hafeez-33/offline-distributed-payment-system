package com.demo.upimesh.bridge.receipt

import com.demo.upimesh.bridge.client.WanIngestResponse
import com.demo.upimesh.crypto.CanonicalSerializer
import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.model.SettlementReceipt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BridgeReceiptValidationTest {

    private val validator = BridgeReceiptValidator()

    // Deterministic test keypair for server issuer
    private val serverKeyPair = Ed25519Crypto.generateKeyPair()
    private val serverIssuerPublicKeyBase64 = Ed25519Crypto.encodePublicKey(serverKeyPair.public)
    private val serverIssuerPrivateKey = serverKeyPair.private

    private val samplePacketHash = "11112222333344445555666677778888aaaabbbbccccddddeeeeffff00001111"
    private val sampleTxId = 42L
    private val sampleCounter = 1L
    private val sampleSettledAt = 1700000000000L

    private lateinit var validSignature: String

    @BeforeEach
    fun setUp() {
        val receipt = SettlementReceipt(
            transactionId = sampleTxId,
            packetHash = samplePacketHash,
            counter = sampleCounter,
            status = "SETTLED",
            settledAt = sampleSettledAt,
            serverSignature = ""
        )
        val canonicalBytes = CanonicalSerializer.toCanonicalBytes(receipt)
        validSignature = Ed25519Crypto.sign(canonicalBytes, serverIssuerPrivateKey)
    }

    @Test
    fun testValidReceiptExtractedAndVerifiedSuccessfully() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = sampleTxId,
            receiptSignature = validSignature,
            counter = sampleCounter,
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(
            response = response,
            expectedPacketHash = samplePacketHash,
            serverIssuerPublicKeyBase64 = serverIssuerPublicKeyBase64
        )

        assertTrue(result is ReceiptValidationResult.Valid)
        val receipt = (result as ReceiptValidationResult.Valid).receipt
        assertEquals(sampleTxId, receipt.transactionId)
        assertEquals(samplePacketHash, receipt.packetHash)
        assertEquals(sampleCounter, receipt.counter)
        assertEquals("SETTLED", receipt.status)
        assertEquals(sampleSettledAt, receipt.settledAt)
        assertEquals(validSignature, receipt.serverSignature)
    }

    @Test
    fun testMissingTransactionIdIsRejected() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = null, // Missing!
            receiptSignature = validSignature,
            counter = sampleCounter,
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
        assertTrue((result as ReceiptValidationResult.Invalid).reason.contains("transactionId"))
    }

    @Test
    fun testNonPositiveTransactionIdIsRejected() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = -1L,
            receiptSignature = validSignature,
            counter = sampleCounter,
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
    }

    @Test
    fun testPacketHashMismatchIsRejected() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = "99998888777766665555444433332222aaaabbbbccccddddeeeeffff00009999", // Different!
            transactionId = sampleTxId,
            receiptSignature = validSignature,
            counter = sampleCounter,
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
        assertTrue((result as ReceiptValidationResult.Invalid).reason.contains("does not match expected"))
    }

    @Test
    fun testMissingCounterIsRejected() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = sampleTxId,
            receiptSignature = validSignature,
            counter = null, // Missing!
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
    }

    @Test
    fun testMissingSettledAtIsRejected() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = sampleTxId,
            receiptSignature = validSignature,
            counter = sampleCounter,
            settledAt = null // Missing!
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
    }

    @Test
    fun testMissingOrBlankSignatureIsRejected() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = sampleTxId,
            receiptSignature = "   ", // Blank!
            counter = sampleCounter,
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
    }

    @Test
    fun testForgedSignatureIsRejected() {
        val attackerKeyPair = Ed25519Crypto.generateKeyPair()
        val receipt = SettlementReceipt(
            transactionId = sampleTxId,
            packetHash = samplePacketHash,
            counter = sampleCounter,
            status = "SETTLED",
            settledAt = sampleSettledAt,
            serverSignature = ""
        )
        val forgedSig = Ed25519Crypto.sign(CanonicalSerializer.toCanonicalBytes(receipt), attackerKeyPair.private)

        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = sampleTxId,
            receiptSignature = forgedSig,
            counter = sampleCounter,
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
        assertTrue((result as ReceiptValidationResult.Invalid).reason.contains("cryptographic barrier"))
    }

    @Test
    fun testTamperedCounterFailsSignatureVerification() {
        val response = WanIngestResponse(
            outcome = "SETTLED",
            packetHash = samplePacketHash,
            transactionId = sampleTxId,
            receiptSignature = validSignature,
            counter = sampleCounter + 1, // Tampered counter!
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
    }

    @Test
    fun testNonSettledOutcomeIsRejected() {
        val response = WanIngestResponse(
            outcome = "REJECTED",
            packetHash = samplePacketHash,
            transactionId = sampleTxId,
            receiptSignature = validSignature,
            counter = sampleCounter,
            settledAt = sampleSettledAt
        )

        val result = validator.validateAndExtractReceipt(response, samplePacketHash, serverIssuerPublicKeyBase64)
        assertTrue(result is ReceiptValidationResult.Invalid)
    }
}

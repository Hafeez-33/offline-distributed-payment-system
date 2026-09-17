package com.demo.upimesh.bridge.receipt

import com.demo.upimesh.bridge.client.WanIngestResponse
import com.demo.upimesh.crypto.CanonicalSerializer
import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.model.SettlementReceipt
import java.security.PublicKey

/**
 * Result of receipt validation.
 */
sealed class ReceiptValidationResult {
    data class Valid(val receipt: SettlementReceipt) : ReceiptValidationResult()
    data class Invalid(val reason: String, val isPermanent: Boolean = true) : ReceiptValidationResult()
}

/**
 * Validates authoritative backend settlement receipts.
 *
 * STRICT NON-INFERENCE RULE:
 * Never infer, fabricate, or locally synthesize receipt fields (e.g. timestamps, counters, tx IDs).
 * All fields must be explicitly provided in the backend response and match canonical specifications.
 */
class BridgeReceiptValidator {

    /**
     * Strictly verifies that all required receipt fields are present in the response,
     * matches the expected packetHash, and verifies the server's Ed25519 issuer digital signature.
     */
    fun validateAndExtractReceipt(
        response: WanIngestResponse,
        expectedPacketHash: String,
        serverIssuerPublicKeyBase64: String
    ): ReceiptValidationResult {
        if (!response.outcome.equals("SETTLED", ignoreCase = true)) {
            return ReceiptValidationResult.Invalid("Outcome is '${response.outcome}', not SETTLED")
        }

        // 1. Strict null / value validation — NO INFERENCE
        val txId = response.transactionId
        if (txId == null || txId <= 0L) {
            return ReceiptValidationResult.Invalid("Missing or non-positive transactionId in settlement response")
        }

        val pHash = response.packetHash
        if (pHash.isNullOrBlank()) {
            return ReceiptValidationResult.Invalid("Missing packetHash in settlement response")
        }
        if (!pHash.equals(expectedPacketHash, ignoreCase = true)) {
            return ReceiptValidationResult.Invalid("Receipt packetHash '$pHash' does not match expected '$expectedPacketHash'")
        }

        val counter = response.counter
        if (counter == null || counter <= 0L) {
            return ReceiptValidationResult.Invalid("Missing or non-positive sequence counter in settlement response")
        }

        val settledAt = response.settledAt
        if (settledAt == null || settledAt <= 0L) {
            return ReceiptValidationResult.Invalid("Missing or non-positive settledAt timestamp in settlement response")
        }

        val sig = response.receiptSignature
        if (sig.isNullOrBlank()) {
            return ReceiptValidationResult.Invalid("Missing receiptSignature in settlement response")
        }

        // 2. Reconstruct exact model from backend fields
        val receipt = SettlementReceipt(
            transactionId = txId,
            packetHash = pHash,
            counter = counter,
            status = "SETTLED",
            settledAt = settledAt,
            serverSignature = sig
        )

        // 3. Cryptographic signature verification over canonical bytes
        return try {
            val canonicalBytes = CanonicalSerializer.toCanonicalBytes(receipt)
            val issuerPublicKey: PublicKey = Ed25519Crypto.decodePublicKey(serverIssuerPublicKeyBase64)
            val isValid = Ed25519Crypto.verify(canonicalBytes, sig, issuerPublicKey)

            if (isValid) {
                ReceiptValidationResult.Valid(receipt)
            } else {
                ReceiptValidationResult.Invalid("Invalid settlement receipt signature: rejected by cryptographic barrier")
            }
        } catch (e: Exception) {
            ReceiptValidationResult.Invalid("Receipt signature verification error: ${e.message}")
        }
    }
}

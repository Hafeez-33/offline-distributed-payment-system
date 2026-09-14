package com.demo.upimesh.model;

/**
 * Server-signed receipt generated upon successful reconciliation/settlement.
 *
 * Provides cryptographic proof of final settlement on the authoritative ledger.
 */
public record SettlementReceipt(
        Long transactionId,
        String packetHash,
        Long counter,
        String status,
        long settledAt,
        String serverSignature
) {
    public String toCanonicalString() {
        return "v1_receipt|txId=" + transactionId
                + "|hash=" + (packetHash != null ? packetHash.trim() : "")
                + "|counter=" + (counter != null ? counter : 0L)
                + "|status=" + (status != null ? status.trim() : "")
                + "|settledAt=" + settledAt;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public byte[] getCanonicalBytes() {
        return toCanonicalString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}

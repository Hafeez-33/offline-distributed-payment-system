package com.demo.upimesh.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Represents a server-issued offline wallet spending capability certificate.
 *
 * Authorizes a specific device to spend offline up to allocatedAmount.
 * Signed by the Server's Ed25519 issuer private key.
 */
public record OfflineWalletCertificate(
        String walletId,
        String ownerVpa,
        String ownerPublicKey,
        BigDecimal allocatedAmount,
        Long walletEpoch,
        long validFrom,
        long validUntil,
        Long initialCounter,
        String issuerSignature
) {
    /**
     * Deterministic canonical representation of the certificate for Ed25519 signing.
     */
    public String toCanonicalString() {
        String allocStr = (allocatedAmount != null ? allocatedAmount : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        return "v1_cert|walletId=" + (walletId != null ? walletId.trim() : "")
                + "|ownerVpa=" + (ownerVpa != null ? ownerVpa.trim().toLowerCase() : "")
                + "|ownerPublicKey=" + (ownerPublicKey != null ? ownerPublicKey.trim() : "")
                + "|allocatedAmount=" + allocStr
                + "|walletEpoch=" + (walletEpoch != null ? walletEpoch : 1L)
                + "|validFrom=" + validFrom
                + "|validUntil=" + validUntil
                + "|initialCounter=" + (initialCounter != null ? initialCounter : 0L);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public byte[] getCanonicalBytes() {
        return toCanonicalString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}

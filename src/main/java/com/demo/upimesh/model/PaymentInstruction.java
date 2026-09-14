package com.demo.upimesh.model;

import java.math.BigDecimal;

/**
 * The actual payment instruction. After the server decrypts MeshPacket.ciphertext,
 * it gets one of these.
 *
 * Critical fields for security:
 *   - nonce: a UUID unique to this payment. Even if everything else were identical
 *            for two legitimate payments (alice sends bob ₹100 twice), the nonces
 *            differ, so the resulting ciphertexts and their hashes also differ.
 *   - signedAt: lets the server reject stale packets ("freshness window"). Without
 *               this, an attacker who got the ciphertext could replay it weeks later.
 *   - pinHash: in a real system the user enters a UPI PIN; we'd verify it against
 *              a hash held by the bank. Here we just record it for realism.
 */
public class PaymentInstruction {

    private String senderVpa;
    private String receiverVpa;
    private BigDecimal amount;
    private String pinHash;
    private String nonce;              // UUID, unique per payment intent
    private Long signedAt;             // epoch millis, when sender signed
    private String signature;          // Base64-encoded Ed25519 digital signature over canonical data
    private String signatureAlgorithm; // e.g. "Ed25519"

    // --- Phase 3 Offline Wallet Extensions ---
    private String walletId;
    private Long walletEpoch;
    private Long sequenceCounter;
    private BigDecimal cumulativeAmount;
    private OfflineWalletCertificate walletCertificate;

    public PaymentInstruction() {}

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String pinHash, String nonce, Long signedAt) {
        this(senderVpa, receiverVpa, amount, pinHash, nonce, signedAt, null, "Ed25519");
    }

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String pinHash, String nonce, Long signedAt,
                              String signature, String signatureAlgorithm) {
        this(senderVpa, receiverVpa, amount, pinHash, nonce, signedAt, signature, signatureAlgorithm,
                null, null, null, null, null);
    }

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String pinHash, String nonce, Long signedAt,
                              String signature, String signatureAlgorithm,
                              String walletId, Long walletEpoch, Long sequenceCounter,
                              BigDecimal cumulativeAmount, OfflineWalletCertificate walletCertificate) {
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.pinHash = pinHash;
        this.nonce = nonce;
        this.signedAt = signedAt;
        this.signature = signature;
        this.signatureAlgorithm = signatureAlgorithm;
        this.walletId = walletId;
        this.walletEpoch = walletEpoch;
        this.sequenceCounter = sequenceCounter;
        this.cumulativeAmount = cumulativeAmount;
        this.walletCertificate = walletCertificate;
    }

    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public Long getSignedAt() { return signedAt; }
    public void setSignedAt(Long signedAt) { this.signedAt = signedAt; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getSignatureAlgorithm() { return signatureAlgorithm; }
    public void setSignatureAlgorithm(String signatureAlgorithm) { this.signatureAlgorithm = signatureAlgorithm; }

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public Long getWalletEpoch() { return walletEpoch; }
    public void setWalletEpoch(Long walletEpoch) { this.walletEpoch = walletEpoch; }

    public Long getSequenceCounter() { return sequenceCounter; }
    public void setSequenceCounter(Long sequenceCounter) { this.sequenceCounter = sequenceCounter; }

    public BigDecimal getCumulativeAmount() { return cumulativeAmount; }
    public void setCumulativeAmount(BigDecimal cumulativeAmount) { this.cumulativeAmount = cumulativeAmount; }

    public OfflineWalletCertificate getWalletCertificate() { return walletCertificate; }
    public void setWalletCertificate(OfflineWalletCertificate walletCertificate) { this.walletCertificate = walletCertificate; }
}

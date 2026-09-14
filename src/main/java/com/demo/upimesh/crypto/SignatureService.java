package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Service for Ed25519 digital signature generation, verification, and canonicalization.
 *
 * Provides cryptographic sender authorization and message integrity for offline
 * payment instructions before they are wrapped in the confidential encryption envelope.
 *
 * Uses Java 17's standard security provider (SunEC) for Ed25519.
 */
@Service
public class SignatureService {

    public static final String ALGORITHM = "Ed25519";
    public static final String CANONICAL_VERSION = "v1";
    public static final String CANONICAL_VERSION_V3 = "v3_tx";

    /**
     * Generate a new Ed25519 keypair for an account.
     */
    public KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
        return kpg.generateKeyPair();
    }

    /**
     * Deterministic canonical serialization of a payment instruction.
     *
     * Format for Phase 1 / backward compatible:
     *   v1|sender=<senderVpa>|receiver=<receiverVpa>|amount=<amount>|nonce=<nonce>|signedAt=<signedAt>
     *
     * Format for Phase 3 (when walletId is present):
     *   v3_tx|walletId=<walletId>|epoch=<epoch>|counter=<counter>|cumAmount=<cumAmount>|sender=<sender>|receiver=<receiver>|amount=<amount>|nonce=<nonce>|signedAt=<signedAt>
     */
    public byte[] getCanonicalBytes(PaymentInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        Objects.requireNonNull(instruction.getSenderVpa(), "senderVpa must not be null");
        Objects.requireNonNull(instruction.getReceiverVpa(), "receiverVpa must not be null");
        Objects.requireNonNull(instruction.getAmount(), "amount must not be null");
        Objects.requireNonNull(instruction.getNonce(), "nonce must not be null");
        Objects.requireNonNull(instruction.getSignedAt(), "signedAt must not be null");

        String sender = instruction.getSenderVpa().trim().toLowerCase();
        String receiver = instruction.getReceiverVpa().trim().toLowerCase();
        String amount = instruction.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String nonce = instruction.getNonce().trim().toLowerCase();
        String signedAt = String.valueOf(instruction.getSignedAt());

        if (instruction.getWalletId() != null) {
            String walletId = instruction.getWalletId().trim();
            long epoch = instruction.getWalletEpoch() != null ? instruction.getWalletEpoch() : 1L;
            long counter = instruction.getSequenceCounter() != null ? instruction.getSequenceCounter() : 1L;
            BigDecimal cum = instruction.getCumulativeAmount() != null ? instruction.getCumulativeAmount() : instruction.getAmount();
            String cumAmount = cum.setScale(2, RoundingMode.HALF_UP).toPlainString();

            String canonicalString = CANONICAL_VERSION_V3
                    + "|walletId=" + walletId
                    + "|epoch=" + epoch
                    + "|counter=" + counter
                    + "|cumAmount=" + cumAmount
                    + "|sender=" + sender
                    + "|receiver=" + receiver
                    + "|amount=" + amount
                    + "|nonce=" + nonce
                    + "|signedAt=" + signedAt;
            return canonicalString.getBytes(StandardCharsets.UTF_8);
        }

        String canonicalString = CANONICAL_VERSION
                + "|sender=" + sender
                + "|receiver=" + receiver
                + "|amount=" + amount
                + "|nonce=" + nonce
                + "|signedAt=" + signedAt;

        return canonicalString.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sign the canonical representation of a payment instruction using the sender's private key.
     *
     * @param instruction the payment instruction containing intent fields
     * @param privateKey the sender's Ed25519 private key
     * @return Base64-encoded 64-byte Ed25519 signature string
     */
    public String sign(PaymentInstruction instruction, PrivateKey privateKey) throws Exception {
        Objects.requireNonNull(privateKey, "privateKey must not be null");
        byte[] canonicalBytes = getCanonicalBytes(instruction);

        Signature sig = Signature.getInstance(ALGORITHM);
        sig.initSign(privateKey);
        sig.update(canonicalBytes);
        byte[] signatureBytes = sig.sign();

        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    /**
     * Verify the Ed25519 signature of a payment instruction against the authoritative public key.
     *
     * @param instruction the payment instruction
     * @param signatureBase64 Base64-encoded signature string
     * @param publicKey authoritative Ed25519 public key registered for the sender
     * @return true if valid signature over canonical bytes, false otherwise
     */
    public boolean verify(PaymentInstruction instruction, String signatureBase64, PublicKey publicKey) {
        if (instruction == null || signatureBase64 == null || signatureBase64.isBlank() || publicKey == null) {
            return false;
        }
        try {
            byte[] canonicalBytes = getCanonicalBytes(instruction);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(canonicalBytes);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Encode an Ed25519 public key to Base64 (standard X.509 SubjectPublicKeyInfo).
     */
    public String encodePublicKey(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Decode a Base64-encoded X.509 SubjectPublicKeyInfo string into an Ed25519 PublicKey.
     */
    public PublicKey decodePublicKey(String base64PublicKey) throws Exception {
        Objects.requireNonNull(base64PublicKey, "base64PublicKey must not be null");
        byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    /**
     * Sign an OfflineWalletCertificate using the server's Ed25519 issuer private key.
     */
    public String signCertificate(com.demo.upimesh.model.OfflineWalletCertificate cert, PrivateKey issuerPrivateKey) throws Exception {
        Objects.requireNonNull(cert, "cert must not be null");
        Objects.requireNonNull(issuerPrivateKey, "issuerPrivateKey must not be null");
        byte[] canonicalBytes = cert.getCanonicalBytes();

        Signature sig = Signature.getInstance(ALGORITHM);
        sig.initSign(issuerPrivateKey);
        sig.update(canonicalBytes);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /**
     * Verify an OfflineWalletCertificate signature using the server's Ed25519 issuer public key.
     */
    public boolean verifyCertificate(com.demo.upimesh.model.OfflineWalletCertificate cert, PublicKey issuerPublicKey) {
        if (cert == null || cert.issuerSignature() == null || cert.issuerSignature().isBlank() || issuerPublicKey == null) {
            return false;
        }
        try {
            byte[] canonicalBytes = cert.getCanonicalBytes();
            byte[] sigBytes = Base64.getDecoder().decode(cert.issuerSignature());

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(issuerPublicKey);
            sig.update(canonicalBytes);
            return sig.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sign a SettlementReceipt using the server's Ed25519 issuer private key.
     */
    public String signReceipt(com.demo.upimesh.model.SettlementReceipt receipt, PrivateKey issuerPrivateKey) throws Exception {
        Objects.requireNonNull(receipt, "receipt must not be null");
        Objects.requireNonNull(issuerPrivateKey, "issuerPrivateKey must not be null");
        byte[] canonicalBytes = receipt.getCanonicalBytes();

        Signature sig = Signature.getInstance(ALGORITHM);
        sig.initSign(issuerPrivateKey);
        sig.update(canonicalBytes);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /**
     * Verify a SettlementReceipt signature using the server's Ed25519 issuer public key.
     */
    public boolean verifyReceipt(com.demo.upimesh.model.SettlementReceipt receipt, PublicKey issuerPublicKey) {
        if (receipt == null || receipt.serverSignature() == null || receipt.serverSignature().isBlank() || issuerPublicKey == null) {
            return false;
        }
        try {
            byte[] canonicalBytes = receipt.getCanonicalBytes();
            byte[] sigBytes = Base64.getDecoder().decode(receipt.serverSignature());

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(issuerPublicKey);
            sig.update(canonicalBytes);
            return sig.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }
}

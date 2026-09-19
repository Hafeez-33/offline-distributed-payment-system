package com.demo.upimesh.crypto;

import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.OfflineWalletCertificate;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.SettlementReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9.1: Automated golden vector test suite verifying deterministic cross-language
 * cryptographic compatibility between Java backend and Kotlin/Android implementation.
 */
public class CrossLanguageCryptoCompatibilityTest {

    private static JsonNode rootNode;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final SignatureService signatureService = new SignatureService();
    private static final HybridCryptoService hybridCryptoService = new HybridCryptoService();

    @BeforeAll
    public static void setUp() throws Exception {
        try (InputStream is = CrossLanguageCryptoCompatibilityTest.class
                .getClassLoader().getResourceAsStream("upi_crypto_test_vectors_v1.json")) {
            assertNotNull(is, "upi_crypto_test_vectors_v1.json must be present in test resources");
            rootNode = mapper.readTree(is);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static PublicKey decodePublicKey(String base64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    private static PublicKey decodeRsaPublicKey(String base64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    private static PrivateKey decodeRsaPrivateKey(String base64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    @Test
    public void testCorpusMetadataAndKeyIntegrity() {
        assertEquals("1.0.0", rootNode.get("version").asText());
        assertNotNull(rootNode.get("keys").get("ed25519").get("sender"));
        assertNotNull(rootNode.get("keys").get("ed25519").get("issuer"));
        assertNotNull(rootNode.get("keys").get("ed25519").get("device"));
        assertNotNull(rootNode.get("keys").get("rsa").get("server"));
    }

    @Test
    public void testV1CanonicalizationAgainstGoldenVectors() {
        JsonNode v1Nodes = rootNode.get("canonicalization_and_signatures").get("v1_transactions");
        assertNotNull(v1Nodes);
        assertTrue(v1Nodes.size() > 0);

        for (JsonNode node : v1Nodes) {
            String id = node.get("id").asText();
            PaymentInstruction pi = new PaymentInstruction(
                    node.get("senderVpa").asText(),
                    node.get("receiverVpa").asText(),
                    new BigDecimal(node.get("amount").asText()),
                    "pin",
                    node.get("nonce").asText(),
                    node.get("signedAt").asLong()
            );

            byte[] canonicalBytes = signatureService.getCanonicalBytes(pi);
            String canonicalString = new String(canonicalBytes, StandardCharsets.UTF_8);

            assertEquals(node.get("expectedCanonicalString").asText(), canonicalString,
                    "Canonical string mismatch for v1 vector: " + id);
            assertEquals(node.get("expectedCanonicalBytesHex").asText(), toHex(canonicalBytes),
                    "Canonical bytes hex mismatch for v1 vector: " + id);
        }
    }

    @Test
    public void testV3CanonicalizationAgainstGoldenVectors() {
        JsonNode v3Nodes = rootNode.get("canonicalization_and_signatures").get("v3_transactions");
        assertNotNull(v3Nodes);
        assertTrue(v3Nodes.size() > 0);

        for (JsonNode node : v3Nodes) {
            String id = node.get("id").asText();
            Long walletEpoch = node.hasNonNull("walletEpoch") ? node.get("walletEpoch").asLong() : null;
            Long seqCounter = node.hasNonNull("sequenceCounter") ? node.get("sequenceCounter").asLong() : null;
            BigDecimal cumAmount = node.hasNonNull("cumulativeAmount")
                    ? new BigDecimal(node.get("cumulativeAmount").asText()) : null;

            PaymentInstruction pi = new PaymentInstruction(
                    node.get("senderVpa").asText(),
                    node.get("receiverVpa").asText(),
                    new BigDecimal(node.get("amount").asText()),
                    "pin",
                    node.get("nonce").asText(),
                    node.get("signedAt").asLong(),
                    null,
                    "Ed25519",
                    node.get("walletId").asText(),
                    walletEpoch,
                    seqCounter,
                    cumAmount,
                    null
            );

            byte[] canonicalBytes = signatureService.getCanonicalBytes(pi);
            String canonicalString = new String(canonicalBytes, StandardCharsets.UTF_8);

            assertEquals(node.get("expectedCanonicalString").asText(), canonicalString,
                    "Canonical string mismatch for v3 vector: " + id);
            assertEquals(node.get("expectedCanonicalBytesHex").asText(), toHex(canonicalBytes),
                    "Canonical bytes hex mismatch for v3 vector: " + id);
        }
    }

    @Test
    public void testCertificateCanonicalizationAgainstGoldenVectors() {
        JsonNode certNodes = rootNode.get("canonicalization_and_signatures").get("certificates");
        assertNotNull(certNodes);
        assertTrue(certNodes.size() > 0);

        for (JsonNode node : certNodes) {
            String id = node.get("id").asText();
            String walletId = node.hasNonNull("walletId") ? node.get("walletId").asText() : null;
            String ownerVpa = node.hasNonNull("ownerVpa") ? node.get("ownerVpa").asText() : null;
            String ownerPublicKey = node.hasNonNull("ownerPublicKey") ? node.get("ownerPublicKey").asText() : null;
            BigDecimal allocAmount = node.hasNonNull("allocatedAmount")
                    ? new BigDecimal(node.get("allocatedAmount").asText()) : null;
            Long walletEpoch = node.hasNonNull("walletEpoch") ? node.get("walletEpoch").asLong() : null;
            Long initialCounter = node.hasNonNull("initialCounter") ? node.get("initialCounter").asLong() : null;

            OfflineWalletCertificate cert = new OfflineWalletCertificate(
                    walletId,
                    ownerVpa,
                    ownerPublicKey,
                    allocAmount,
                    walletEpoch,
                    node.get("validFrom").asLong(),
                    node.get("validUntil").asLong(),
                    initialCounter,
                    null
            );

            byte[] canonicalBytes = cert.getCanonicalBytes();
            String canonicalString = cert.toCanonicalString();

            assertEquals(node.get("expectedCanonicalString").asText(), canonicalString,
                    "Canonical string mismatch for cert vector: " + id);
            assertEquals(node.get("expectedCanonicalBytesHex").asText(), toHex(canonicalBytes),
                    "Canonical bytes hex mismatch for cert vector: " + id);
        }
    }

    @Test
    public void testReceiptCanonicalizationAgainstGoldenVectors() {
        JsonNode receiptNodes = rootNode.get("canonicalization_and_signatures").get("receipts");
        assertNotNull(receiptNodes);
        assertTrue(receiptNodes.size() > 0);

        for (JsonNode node : receiptNodes) {
            String id = node.get("id").asText();
            String packetHash = node.hasNonNull("packetHash") ? node.get("packetHash").asText() : null;
            Long counter = node.hasNonNull("counter") ? node.get("counter").asLong() : null;
            String status = node.hasNonNull("status") ? node.get("status").asText() : null;

            SettlementReceipt receipt = new SettlementReceipt(
                    node.get("transactionId").asLong(),
                    packetHash,
                    counter,
                    status,
                    node.get("settledAt").asLong(),
                    null
            );

            byte[] canonicalBytes = receipt.getCanonicalBytes();
            String canonicalString = receipt.toCanonicalString();

            assertEquals(node.get("expectedCanonicalString").asText(), canonicalString,
                    "Canonical string mismatch for receipt vector: " + id);
            assertEquals(node.get("expectedCanonicalBytesHex").asText(), toHex(canonicalBytes),
                    "Canonical bytes hex mismatch for receipt vector: " + id);
        }
    }

    @Test
    public void testEd25519SignatureVerificationAgainstGoldenVectors() throws Exception {
        PublicKey senderPub = decodePublicKey(
                rootNode.get("keys").get("ed25519").get("sender").get("publicKeyX509Base64").asText());
        PublicKey issuerPub = decodePublicKey(
                rootNode.get("keys").get("ed25519").get("issuer").get("publicKeyX509Base64").asText());
        PublicKey devicePub = decodePublicKey(
                rootNode.get("keys").get("ed25519").get("device").get("publicKeyX509Base64").asText());

        // 1. v1
        JsonNode v1Nodes = rootNode.get("canonicalization_and_signatures").get("v1_transactions");
        for (JsonNode node : v1Nodes) {
            PaymentInstruction pi = new PaymentInstruction(
                    node.get("senderVpa").asText(),
                    node.get("receiverVpa").asText(),
                    new BigDecimal(node.get("amount").asText()),
                    "pin",
                    node.get("nonce").asText(),
                    node.get("signedAt").asLong()
            );
            String sig = node.get("expectedSignatureBase64").asText();
            assertTrue(signatureService.verify(pi, sig, senderPub),
                    "v1 signature verification failed for: " + node.get("id").asText());
        }

        // 2. v3
        JsonNode v3Nodes = rootNode.get("canonicalization_and_signatures").get("v3_transactions");
        for (JsonNode node : v3Nodes) {
            Long walletEpoch = node.hasNonNull("walletEpoch") ? node.get("walletEpoch").asLong() : null;
            Long seqCounter = node.hasNonNull("sequenceCounter") ? node.get("sequenceCounter").asLong() : null;
            BigDecimal cumAmount = node.hasNonNull("cumulativeAmount")
                    ? new BigDecimal(node.get("cumulativeAmount").asText()) : null;

            PaymentInstruction pi = new PaymentInstruction(
                    node.get("senderVpa").asText(),
                    node.get("receiverVpa").asText(),
                    new BigDecimal(node.get("amount").asText()),
                    "pin",
                    node.get("nonce").asText(),
                    node.get("signedAt").asLong(),
                    null,
                    "Ed25519",
                    node.get("walletId").asText(),
                    walletEpoch,
                    seqCounter,
                    cumAmount,
                    null
            );
            String sig = node.get("expectedSignatureBase64").asText();
            assertTrue(signatureService.verify(pi, sig, devicePub),
                    "v3 signature verification failed for: " + node.get("id").asText());
        }

        // 3. Certificates
        JsonNode certNodes = rootNode.get("canonicalization_and_signatures").get("certificates");
        for (JsonNode node : certNodes) {
            String walletId = node.hasNonNull("walletId") ? node.get("walletId").asText() : null;
            String ownerVpa = node.hasNonNull("ownerVpa") ? node.get("ownerVpa").asText() : null;
            String ownerPublicKey = node.hasNonNull("ownerPublicKey") ? node.get("ownerPublicKey").asText() : null;
            BigDecimal allocAmount = node.hasNonNull("allocatedAmount")
                    ? new BigDecimal(node.get("allocatedAmount").asText()) : null;
            Long walletEpoch = node.hasNonNull("walletEpoch") ? node.get("walletEpoch").asLong() : null;
            Long initialCounter = node.hasNonNull("initialCounter") ? node.get("initialCounter").asLong() : null;

            OfflineWalletCertificate cert = new OfflineWalletCertificate(
                    walletId,
                    ownerVpa,
                    ownerPublicKey,
                    allocAmount,
                    walletEpoch,
                    node.get("validFrom").asLong(),
                    node.get("validUntil").asLong(),
                    initialCounter,
                    node.get("expectedSignatureBase64").asText()
            );

            assertTrue(signatureService.verifyCertificate(cert, issuerPub),
                    "Cert signature verification failed for: " + node.get("id").asText());
        }

        // 4. Receipts
        JsonNode receiptNodes = rootNode.get("canonicalization_and_signatures").get("receipts");
        for (JsonNode node : receiptNodes) {
            String packetHash = node.hasNonNull("packetHash") ? node.get("packetHash").asText() : null;
            Long counter = node.hasNonNull("counter") ? node.get("counter").asLong() : null;
            String status = node.hasNonNull("status") ? node.get("status").asText() : null;

            SettlementReceipt receipt = new SettlementReceipt(
                    node.get("transactionId").asLong(),
                    packetHash,
                    counter,
                    status,
                    node.get("settledAt").asLong(),
                    node.get("expectedSignatureBase64").asText()
            );

            assertTrue(signatureService.verifyReceipt(receipt, issuerPub),
                    "Receipt signature verification failed for: " + node.get("id").asText());
        }
    }

    @Test
    public void testTamperedSignaturesFailVerification() throws Exception {
        PublicKey senderPub = decodePublicKey(
                rootNode.get("keys").get("ed25519").get("sender").get("publicKeyX509Base64").asText());

        JsonNode v1Standard = rootNode.get("canonicalization_and_signatures").get("v1_transactions").get(0);
        PaymentInstruction pi = new PaymentInstruction(
                v1Standard.get("senderVpa").asText(),
                v1Standard.get("receiverVpa").asText(),
                new BigDecimal(v1Standard.get("amount").asText()),
                "pin",
                v1Standard.get("nonce").asText(),
                v1Standard.get("signedAt").asLong()
        );
        String validSig = v1Standard.get("expectedSignatureBase64").asText();

        // Mutate signature byte
        byte[] sigBytes = Base64.getDecoder().decode(validSig);
        sigBytes[10] = (byte) (sigBytes[10] ^ 0xFF);
        String corruptedSig = Base64.getEncoder().encodeToString(sigBytes);

        assertFalse(signatureService.verify(pi, corruptedSig, senderPub),
                "Mutated signature must fail verification");

        // Mutate amount
        PaymentInstruction tamperedPi = new PaymentInstruction(
                pi.getSenderVpa(), pi.getReceiverVpa(), new BigDecimal("999999.00"),
                pi.getPinHash(), pi.getNonce(), pi.getSignedAt());
        assertFalse(signatureService.verify(tamperedPi, validSig, senderPub),
                "Instruction with altered amount must fail verification");
    }

    @Test
    public void testHybridEnvelopeDecryption() throws Exception {
        PrivateKey serverPriv = decodeRsaPrivateKey(
                rootNode.get("keys").get("rsa").get("server").get("privateKeyPkcs8Base64").asText());

        JsonNode vectorNode = rootNode.get("hybrid_encryption").get("vectors").get(0);
        String wireBase64 = vectorNode.get("wireBase64Ciphertext").asText();
        byte[] all = Base64.getDecoder().decode(wireBase64);

        int rsaKeyBytes = 256;
        int ivBytes = 12;
        byte[] encryptedAesKey = new byte[rsaKeyBytes];
        byte[] iv = new byte[ivBytes];
        byte[] aesCiphertext = new byte[all.length - rsaKeyBytes - ivBytes];

        ByteBuffer buf = ByteBuffer.wrap(all);
        buf.get(encryptedAesKey);
        buf.get(iv);
        buf.get(aesCiphertext);

        // 1. RSA-OAEP decrypt
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        rsa.init(Cipher.DECRYPT_MODE, serverPriv, oaep);
        byte[] aesKeyBytes = rsa.doFinal(encryptedAesKey);
        assertEquals(32, aesKeyBytes.length);
        assertEquals(vectorNode.get("aesKeyHex").asText(), toHex(aesKeyBytes));

        // 2. AES-GCM decrypt
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] plaintext = aes.doFinal(aesCiphertext);

        PaymentInstruction pi = mapper.readValue(plaintext, PaymentInstruction.class);
        assertEquals("alice@demo", pi.getSenderVpa());
        assertEquals("bob@demo", pi.getReceiverVpa());
        assertEquals(new BigDecimal("100.50"), pi.getAmount());
    }

    @Test
    public void testTamperedHybridEnvelopeThrows() throws Exception {
        PrivateKey serverPriv = decodeRsaPrivateKey(
                rootNode.get("keys").get("rsa").get("server").get("privateKeyPkcs8Base64").asText());

        JsonNode vectorNode = rootNode.get("hybrid_encryption").get("vectors").get(0);
        String wireBase64 = vectorNode.get("wireBase64Ciphertext").asText();
        byte[] all = Base64.getDecoder().decode(wireBase64);

        // Tamper last byte of ciphertext (which is part of GCM authentication tag)
        all[all.length - 1] = (byte) (all[all.length - 1] ^ 0xFF);

        int rsaKeyBytes = 256;
        int ivBytes = 12;
        byte[] encryptedAesKey = new byte[rsaKeyBytes];
        byte[] iv = new byte[ivBytes];
        byte[] aesCiphertext = new byte[all.length - rsaKeyBytes - ivBytes];

        ByteBuffer buf = ByteBuffer.wrap(all);
        buf.get(encryptedAesKey);
        buf.get(iv);
        buf.get(aesCiphertext);

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        rsa.init(Cipher.DECRYPT_MODE, serverPriv, oaep);
        byte[] aesKeyBytes = rsa.doFinal(encryptedAesKey);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));

        assertThrows(Exception.class, () -> aes.doFinal(aesCiphertext),
                "Tampered AES-GCM ciphertext/tag must throw AEADBadTagException");
    }

    @Test
    public void testPacketHashCalculationAgainstGoldenVectors() throws Exception {
        JsonNode hashNodes = rootNode.get("packet_hashes");
        assertNotNull(hashNodes);
        assertTrue(hashNodes.size() > 0);

        for (JsonNode node : hashNodes) {
            String ciphertext = node.get("ciphertext").asText();
            String expectedHash = node.get("expectedPacketHash").asText();

            String serviceHash = hybridCryptoService.hashCiphertext(ciphertext);
            assertEquals(expectedHash, serviceHash);

            MeshPacket packet = new MeshPacket();
            packet.setCiphertext(ciphertext);
            assertEquals(expectedHash, packet.getPacketHash());
        }
    }
}

package com.demo.upimesh.crypto;

import com.demo.upimesh.model.OfflineWalletCertificate;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.SettlementReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CrossLanguageTestVectorGenerator {

    private final SignatureService signatureService = new SignatureService();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private KeyPair generateDeterministicEd25519KeyPair(byte seedByte) throws Exception {
        byte[] seed = new byte[32];
        Arrays.fill(seed, seedByte);

        // Deterministic Ed25519 generation via SHA1PRNG initialized with seed
        SecureRandom rng = SecureRandom.getInstance("SHA1PRNG");
        rng.setSeed(seed);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        kpg.initialize(new NamedParameterSpec("Ed25519"), rng);
        return kpg.generateKeyPair();
    }

    private KeyPair generateDeterministicRsaKeyPair(byte seedByte) throws Exception {
        byte[] seed = new byte[32];
        Arrays.fill(seed, seedByte);

        SecureRandom rng = SecureRandom.getInstance("SHA1PRNG");
        rng.setSeed(seed);

        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048, rng);
        return rsaGen.generateKeyPair();
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    @Test
    public void generateTestVectorCorpus() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", "1.0.0");
        root.put("description", "Deterministic cross-language test vectors for UPI Without Internet Phase 9.1");

        // 1. Keys
        KeyPair senderEd = generateDeterministicEd25519KeyPair((byte) 0x11);
        KeyPair issuerEd = generateDeterministicEd25519KeyPair((byte) 0x22);
        KeyPair deviceEd = generateDeterministicEd25519KeyPair((byte) 0x33);
        KeyPair serverRsa = generateDeterministicRsaKeyPair((byte) 0x44);

        Map<String, Object> keys = new LinkedHashMap<>();
        Map<String, Object> edKeys = new LinkedHashMap<>();

        Map<String, String> senderKeyMap = new LinkedHashMap<>();
        senderKeyMap.put("publicKeyX509Base64", Base64.getEncoder().encodeToString(senderEd.getPublic().getEncoded()));
        senderKeyMap.put("privateKeyPkcs8Base64", Base64.getEncoder().encodeToString(senderEd.getPrivate().getEncoded()));
        edKeys.put("sender", senderKeyMap);

        Map<String, String> issuerKeyMap = new LinkedHashMap<>();
        issuerKeyMap.put("publicKeyX509Base64", Base64.getEncoder().encodeToString(issuerEd.getPublic().getEncoded()));
        issuerKeyMap.put("privateKeyPkcs8Base64", Base64.getEncoder().encodeToString(issuerEd.getPrivate().getEncoded()));
        edKeys.put("issuer", issuerKeyMap);

        Map<String, String> deviceKeyMap = new LinkedHashMap<>();
        deviceKeyMap.put("publicKeyX509Base64", Base64.getEncoder().encodeToString(deviceEd.getPublic().getEncoded()));
        deviceKeyMap.put("privateKeyPkcs8Base64", Base64.getEncoder().encodeToString(deviceEd.getPrivate().getEncoded()));
        edKeys.put("device", deviceKeyMap);

        keys.put("ed25519", edKeys);

        Map<String, Object> rsaKeys = new LinkedHashMap<>();
        Map<String, String> serverRsaMap = new LinkedHashMap<>();
        serverRsaMap.put("publicKeyX509Base64", Base64.getEncoder().encodeToString(serverRsa.getPublic().getEncoded()));
        serverRsaMap.put("privateKeyPkcs8Base64", Base64.getEncoder().encodeToString(serverRsa.getPrivate().getEncoded()));
        rsaKeys.put("server", serverRsaMap);

        keys.put("rsa", rsaKeys);
        root.put("keys", keys);

        // 2. Canonicalization and Signatures
        // v1 transactions
        List<Map<String, Object>> v1TxList = new ArrayList<>();
        {
            PaymentInstruction pi1 = new PaymentInstruction(
                    "alice@demo", "bob@demo", new BigDecimal("100.50"), "1234", "550e8400-e29b-41d4-a716-446655440000", 1726480800000L);
            byte[] canonBytes = signatureService.getCanonicalBytes(pi1);
            String canonStr = new String(canonBytes, StandardCharsets.UTF_8);
            String sig = signatureService.sign(pi1, senderEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "v1_standard");
            map.put("senderVpa", pi1.getSenderVpa());
            map.put("receiverVpa", pi1.getReceiverVpa());
            map.put("amount", pi1.getAmount().toPlainString());
            map.put("nonce", pi1.getNonce());
            map.put("signedAt", pi1.getSignedAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "sender");
            map.put("expectedSignatureBase64", sig);
            v1TxList.add(map);
        }
        {
            PaymentInstruction pi2 = new PaymentInstruction(
                    "  ALICE@DEMO  ", "  Bob@demo  ", new BigDecimal("500"), "9999", "550E8400-E29B-41D4-A716-446655440001", 1726480800000L);
            byte[] canonBytes = signatureService.getCanonicalBytes(pi2);
            String canonStr = new String(canonBytes, StandardCharsets.UTF_8);
            String sig = signatureService.sign(pi2, senderEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "v1_trim_case_and_scale");
            map.put("senderVpa", pi2.getSenderVpa());
            map.put("receiverVpa", pi2.getReceiverVpa());
            map.put("amount", pi2.getAmount().toPlainString());
            map.put("nonce", pi2.getNonce());
            map.put("signedAt", pi2.getSignedAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "sender");
            map.put("expectedSignatureBase64", sig);
            v1TxList.add(map);
        }
        {
            PaymentInstruction pi3 = new PaymentInstruction(
                    "alice@demo", "merchant@demo", new BigDecimal("123.456"), "0000", "550e8400-e29b-41d4-a716-446655440002", 1726480810000L);
            byte[] canonBytes = signatureService.getCanonicalBytes(pi3);
            String canonStr = new String(canonBytes, StandardCharsets.UTF_8);
            String sig = signatureService.sign(pi3, senderEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "v1_half_up_rounding");
            map.put("senderVpa", pi3.getSenderVpa());
            map.put("receiverVpa", pi3.getReceiverVpa());
            map.put("amount", pi3.getAmount().toPlainString());
            map.put("nonce", pi3.getNonce());
            map.put("signedAt", pi3.getSignedAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "sender");
            map.put("expectedSignatureBase64", sig);
            v1TxList.add(map);
        }

        // v3_tx transactions
        List<Map<String, Object>> v3TxList = new ArrayList<>();
        {
            PaymentInstruction piV3_1 = new PaymentInstruction(
                    "alice@demo", "bob@demo", new BigDecimal("100.50"), "1234", "550e8400-e29b-41d4-a716-446655440003", 1726480800000L,
                    null, "Ed25519", "WLT-DEV-001", 1L, 1L, new BigDecimal("100.50"), null);
            byte[] canonBytes = signatureService.getCanonicalBytes(piV3_1);
            String canonStr = new String(canonBytes, StandardCharsets.UTF_8);
            String sig = signatureService.sign(piV3_1, deviceEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "v3_standard");
            map.put("walletId", piV3_1.getWalletId());
            map.put("walletEpoch", piV3_1.getWalletEpoch());
            map.put("sequenceCounter", piV3_1.getSequenceCounter());
            map.put("cumulativeAmount", piV3_1.getCumulativeAmount().toPlainString());
            map.put("senderVpa", piV3_1.getSenderVpa());
            map.put("receiverVpa", piV3_1.getReceiverVpa());
            map.put("amount", piV3_1.getAmount().toPlainString());
            map.put("nonce", piV3_1.getNonce());
            map.put("signedAt", piV3_1.getSignedAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "device");
            map.put("expectedSignatureBase64", sig);
            v3TxList.add(map);
        }
        {
            PaymentInstruction piV3_2 = new PaymentInstruction(
                    "alice@demo", "merchant@demo", new BigDecimal("150.25"), "1234", "550e8400-e29b-41d4-a716-446655440004", 1726480820000L,
                    null, "Ed25519", "WLT-DEV-001", 1L, 2L, new BigDecimal("250.75"), null);
            byte[] canonBytes = signatureService.getCanonicalBytes(piV3_2);
            String canonStr = new String(canonBytes, StandardCharsets.UTF_8);
            String sig = signatureService.sign(piV3_2, deviceEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "v3_counter_and_accumulation");
            map.put("walletId", piV3_2.getWalletId());
            map.put("walletEpoch", piV3_2.getWalletEpoch());
            map.put("sequenceCounter", piV3_2.getSequenceCounter());
            map.put("cumulativeAmount", piV3_2.getCumulativeAmount().toPlainString());
            map.put("senderVpa", piV3_2.getSenderVpa());
            map.put("receiverVpa", piV3_2.getReceiverVpa());
            map.put("amount", piV3_2.getAmount().toPlainString());
            map.put("nonce", piV3_2.getNonce());
            map.put("signedAt", piV3_2.getSignedAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "device");
            map.put("expectedSignatureBase64", sig);
            v3TxList.add(map);
        }
        {
            PaymentInstruction piV3_3 = new PaymentInstruction(
                    "alice@demo", "bob@demo", new BigDecimal("75.00"), "1234", "550e8400-e29b-41d4-a716-446655440005", 1726480830000L,
                    null, "Ed25519", "  WLT-DEV-002  ", null, null, null, null);
            byte[] canonBytes = signatureService.getCanonicalBytes(piV3_3);
            String canonStr = new String(canonBytes, StandardCharsets.UTF_8);
            String sig = signatureService.sign(piV3_3, deviceEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "v3_null_defaults");
            map.put("walletId", piV3_3.getWalletId());
            map.put("walletEpoch", piV3_3.getWalletEpoch());
            map.put("sequenceCounter", piV3_3.getSequenceCounter());
            map.put("cumulativeAmount", null);
            map.put("senderVpa", piV3_3.getSenderVpa());
            map.put("receiverVpa", piV3_3.getReceiverVpa());
            map.put("amount", piV3_3.getAmount().toPlainString());
            map.put("nonce", piV3_3.getNonce());
            map.put("signedAt", piV3_3.getSignedAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "device");
            map.put("expectedSignatureBase64", sig);
            v3TxList.add(map);
        }

        // OfflineWalletCertificate
        List<Map<String, Object>> certList = new ArrayList<>();
        {
            OfflineWalletCertificate cert1 = new OfflineWalletCertificate(
                    "WLT-DEV-001",
                    "alice@demo",
                    Base64.getEncoder().encodeToString(deviceEd.getPublic().getEncoded()),
                    new BigDecimal("2000.00"),
                    1L,
                    1726480800000L,
                    1726567200000L,
                    0L,
                    null
            );
            byte[] canonBytes = cert1.getCanonicalBytes();
            String canonStr = cert1.toCanonicalString();
            String sig = signatureService.signCertificate(cert1, issuerEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "cert_standard");
            map.put("walletId", cert1.walletId());
            map.put("ownerVpa", cert1.ownerVpa());
            map.put("ownerPublicKey", cert1.ownerPublicKey());
            map.put("allocatedAmount", cert1.allocatedAmount().toPlainString());
            map.put("walletEpoch", cert1.walletEpoch());
            map.put("validFrom", cert1.validFrom());
            map.put("validUntil", cert1.validUntil());
            map.put("initialCounter", cert1.initialCounter());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "issuer");
            map.put("expectedSignatureBase64", sig);
            certList.add(map);
        }
        {
            OfflineWalletCertificate cert2 = new OfflineWalletCertificate(
                    null,
                    "  ALICE@DEMO  ",
                    null,
                    null,
                    null,
                    1726480800000L,
                    1726567200000L,
                    null,
                    null
            );
            byte[] canonBytes = cert2.getCanonicalBytes();
            String canonStr = cert2.toCanonicalString();
            String sig = signatureService.signCertificate(cert2, issuerEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "cert_null_defaults");
            map.put("walletId", cert2.walletId());
            map.put("ownerVpa", cert2.ownerVpa());
            map.put("ownerPublicKey", cert2.ownerPublicKey());
            map.put("allocatedAmount", null);
            map.put("walletEpoch", cert2.walletEpoch());
            map.put("validFrom", cert2.validFrom());
            map.put("validUntil", cert2.validUntil());
            map.put("initialCounter", cert2.initialCounter());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "issuer");
            map.put("expectedSignatureBase64", sig);
            certList.add(map);
        }

        // SettlementReceipt
        List<Map<String, Object>> receiptList = new ArrayList<>();
        {
            SettlementReceipt rec1 = new SettlementReceipt(
                    101L,
                    "6a09e667f3bcc908b482f710e30902c67cf75cfd371d184cf434e3d6f1406859",
                    1L,
                    "SETTLED",
                    1726480900000L,
                    null
            );
            byte[] canonBytes = rec1.getCanonicalBytes();
            String canonStr = rec1.toCanonicalString();
            String sig = signatureService.signReceipt(rec1, issuerEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "receipt_standard");
            map.put("transactionId", rec1.transactionId());
            map.put("packetHash", rec1.packetHash());
            map.put("counter", rec1.counter());
            map.put("status", rec1.status());
            map.put("settledAt", rec1.settledAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "issuer");
            map.put("expectedSignatureBase64", sig);
            receiptList.add(map);
        }
        {
            SettlementReceipt rec2 = new SettlementReceipt(
                    102L,
                    null,
                    null,
                    null,
                    1726480950000L,
                    null
            );
            byte[] canonBytes = rec2.getCanonicalBytes();
            String canonStr = rec2.toCanonicalString();
            String sig = signatureService.signReceipt(rec2, issuerEd.getPrivate());

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "receipt_null_defaults");
            map.put("transactionId", rec2.transactionId());
            map.put("packetHash", rec2.packetHash());
            map.put("counter", rec2.counter());
            map.put("status", rec2.status());
            map.put("settledAt", rec2.settledAt());
            map.put("expectedCanonicalString", canonStr);
            map.put("expectedCanonicalBytesHex", toHex(canonBytes));
            map.put("signerKey", "issuer");
            map.put("expectedSignatureBase64", sig);
            receiptList.add(map);
        }

        Map<String, Object> canonicalization = new LinkedHashMap<>();
        canonicalization.put("v1_transactions", v1TxList);
        canonicalization.put("v3_transactions", v3TxList);
        canonicalization.put("certificates", certList);
        canonicalization.put("receipts", receiptList);
        root.put("canonicalization_and_signatures", canonicalization);

        // 3. Hybrid Encryption Vector
        byte[] fixedAesKeyBytes = new byte[32];
        for (int i = 0; i < 32; i++) fixedAesKeyBytes[i] = (byte) (i + 1);
        SecretKeySpec aesKey = new SecretKeySpec(fixedAesKeyBytes, "AES");

        byte[] fixedIv = new byte[12];
        for (int i = 0; i < 12; i++) fixedIv[i] = (byte) (0xA0 + i);

        PaymentInstruction hybridPayload = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.50"), "1234", "550e8400-e29b-41d4-a716-446655440000", 1726480800000L);
        byte[] plaintextBytes = mapper.writeValueAsBytes(hybridPayload);
        String plainJson = mapper.writeValueAsString(hybridPayload);

        // AES-GCM
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, fixedIv));
        byte[] aesCiphertextTag = aes.doFinal(plaintextBytes);

        // RSA-OAEP encrypt AES key
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaep = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        SecureRandom encryptRng = SecureRandom.getInstance("SHA1PRNG");
        encryptRng.setSeed(new byte[]{9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6});
        rsa.init(Cipher.ENCRYPT_MODE, serverRsa.getPublic(), oaep, encryptRng);
        byte[] rsaEncryptedAesKey = rsa.doFinal(aesKey.getEncoded());

        ByteBuffer wireBuf = ByteBuffer.allocate(rsaEncryptedAesKey.length + fixedIv.length + aesCiphertextTag.length);
        wireBuf.put(rsaEncryptedAesKey);
        wireBuf.put(fixedIv);
        wireBuf.put(aesCiphertextTag);

        String wireBase64 = Base64.getEncoder().encodeToString(wireBuf.array());
        String packetHash = sha256Hex(wireBase64);

        Map<String, Object> hybridEncryption = new LinkedHashMap<>();
        List<Map<String, Object>> hybridVectors = new ArrayList<>();
        Map<String, Object> hVec = new LinkedHashMap<>();
        hVec.put("id", "hybrid_standard_envelope");
        hVec.put("recipientRsaKey", "server");
        hVec.put("plaintextJson", plainJson);
        hVec.put("aesKeyHex", toHex(fixedAesKeyBytes));
        hVec.put("ivHex", toHex(fixedIv));
        hVec.put("rsaEncryptedAesKeyBase64", Base64.getEncoder().encodeToString(rsaEncryptedAesKey));
        hVec.put("aesGcmCiphertextTagBase64", Base64.getEncoder().encodeToString(aesCiphertextTag));
        hVec.put("wireBase64Ciphertext", wireBase64);
        hVec.put("expectedPacketHash", packetHash);
        hybridVectors.add(hVec);
        hybridEncryption.put("vectors", hybridVectors);
        root.put("hybrid_encryption", hybridEncryption);

        // 4. Packet Hash Vectors
        List<Map<String, String>> packetHashVectors = new ArrayList<>();
        {
            Map<String, String> pv1 = new LinkedHashMap<>();
            pv1.put("id", "hash_hybrid_wire");
            pv1.put("ciphertext", wireBase64);
            pv1.put("expectedPacketHash", packetHash);
            packetHashVectors.add(pv1);
        }
        {
            String sampleText = "SGVsbG8gVVBJIE9mZmxpbmUgTWVzaCAyMDI2";
            Map<String, String> pv2 = new LinkedHashMap<>();
            pv2.put("id", "hash_sample_base64");
            pv2.put("ciphertext", sampleText);
            pv2.put("expectedPacketHash", sha256Hex(sampleText));
            packetHashVectors.add(pv2);
        }
        root.put("packet_hashes", packetHashVectors);

        // Write only to test resource directories
        File backendResFile = new File("src/test/resources/upi_crypto_test_vectors_v1.json");
        backendResFile.getParentFile().mkdirs();
        mapper.writeValue(backendResFile, root);

        File androidResFile = new File("android/core-crypto/src/test/resources/upi_crypto_test_vectors_v1.json");
        androidResFile.getParentFile().mkdirs();
        mapper.writeValue(androidResFile, root);

        System.out.println("Generated test vectors written to test resources: " + backendResFile.getAbsolutePath());
    }
}

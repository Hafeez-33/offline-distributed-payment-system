package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SignatureServiceTest {

    private SignatureService signatureService;

    @BeforeEach
    void setUp() {
        signatureService = new SignatureService();
    }

    @Test
    void keyGenerationAndEncodingRoundTrip() throws Exception {
        KeyPair kp = signatureService.generateKeyPair();
        assertNotNull(kp.getPublic());
        assertNotNull(kp.getPrivate());

        String encoded = signatureService.encodePublicKey(kp.getPublic());
        assertNotNull(encoded);
        assertFalse(encoded.isBlank());

        PublicKey decoded = signatureService.decodePublicKey(encoded);
        assertNotNull(decoded);
        assertEquals(kp.getPublic(), decoded);
    }

    @Test
    void canonicalizationIsDeterministic() {
        PaymentInstruction pi1 = new PaymentInstruction(
                "Alice@Demo ", " Bob@demo", new BigDecimal("500"),
                "pin123", "550E8400-E29B-41D4-A716-446655440000", 1730000000000L);

        PaymentInstruction pi2 = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("500.0000"),
                "differentPin", "550e8400-e29b-41d4-a716-446655440000", 1730000000000L);

        byte[] canonical1 = signatureService.getCanonicalBytes(pi1);
        byte[] canonical2 = signatureService.getCanonicalBytes(pi2);

        String expected = "v1|sender=alice@demo|receiver=bob@demo|amount=500.00|nonce=550e8400-e29b-41d4-a716-446655440000|signedAt=1730000000000";
        assertEquals(expected, new String(canonical1, StandardCharsets.UTF_8));
        assertArrayEquals(canonical1, canonical2);
    }

    @Test
    void signAndVerifyRoundTrip() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();

        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("250.50"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        String signature = signatureService.sign(pi, keyPair.getPrivate());
        assertNotNull(signature);
        assertFalse(signature.isBlank());

        boolean valid = signatureService.verify(pi, signature, keyPair.getPublic());
        assertTrue(valid, "Signature should verify successfully for unaltered instruction");
    }

    @Test
    void corruptedSignatureFailsVerification() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        String signature = signatureService.sign(pi, keyPair.getPrivate());

        // Corrupt signature
        byte[] sigBytes = Base64.getDecoder().decode(signature);
        sigBytes[sigBytes.length / 2] ^= 0xFF;
        String corruptedSignature = Base64.getEncoder().encodeToString(sigBytes);

        assertFalse(signatureService.verify(pi, corruptedSignature, keyPair.getPublic()));
    }

    @Test
    void changedAmountFailsVerification() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        String signature = signatureService.sign(pi, keyPair.getPrivate());

        PaymentInstruction tampered = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("1000.00"),
                "pin", pi.getNonce(), pi.getSignedAt());

        assertFalse(signatureService.verify(tampered, signature, keyPair.getPublic()));
    }

    @Test
    void changedSenderFailsVerification() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        String signature = signatureService.sign(pi, keyPair.getPrivate());

        PaymentInstruction tampered = new PaymentInstruction(
                "eve@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", pi.getNonce(), pi.getSignedAt());

        assertFalse(signatureService.verify(tampered, signature, keyPair.getPublic()));
    }

    @Test
    void changedReceiverFailsVerification() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        String signature = signatureService.sign(pi, keyPair.getPrivate());

        PaymentInstruction tampered = new PaymentInstruction(
                "alice@demo", "charlie@demo", new BigDecimal("100.00"),
                "pin", pi.getNonce(), pi.getSignedAt());

        assertFalse(signatureService.verify(tampered, signature, keyPair.getPublic()));
    }

    @Test
    void changedNonceFailsVerification() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        String signature = signatureService.sign(pi, keyPair.getPrivate());

        PaymentInstruction tampered = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), pi.getSignedAt());

        assertFalse(signatureService.verify(tampered, signature, keyPair.getPublic()));
    }

    @Test
    void changedTimestampFailsVerification() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), 1730000000000L);

        String signature = signatureService.sign(pi, keyPair.getPrivate());

        PaymentInstruction tampered = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", pi.getNonce(), 1730000001000L);

        assertFalse(signatureService.verify(tampered, signature, keyPair.getPublic()));
    }

    @Test
    void wrongPublicKeyFailsVerification() throws Exception {
        KeyPair keyPairAlice = signatureService.generateKeyPair();
        KeyPair keyPairBob = signatureService.generateKeyPair();

        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        // Alice signs, but we verify with Bob's public key
        String signature = signatureService.sign(pi, keyPairAlice.getPrivate());
        assertFalse(signatureService.verify(pi, signature, keyPairBob.getPublic()));
    }

    @Test
    void nullOrEmptySignatureReturnsFalse() throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        PaymentInstruction pi = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), System.currentTimeMillis());

        assertFalse(signatureService.verify(pi, null, keyPair.getPublic()));
        assertFalse(signatureService.verify(pi, "", keyPair.getPublic()));
        assertFalse(signatureService.verify(pi, "   ", keyPair.getPublic()));
        assertFalse(signatureService.verify(null, "sig", keyPair.getPublic()));
        assertFalse(signatureService.verify(pi, "sig", null));
    }
}

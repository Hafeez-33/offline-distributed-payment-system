package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CryptographicIdentityTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private SignatureService signatureService;

    @BeforeEach
    void setUp() {
        idempotency.clear();
    }

    @Test
    void validSignedPaymentSettlesSuccessfully() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal sendAmount = new BigDecimal("75.00");

        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", sendAmount, "1234", 5);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 2);

        assertEquals("SETTLED", result.outcome());
        assertNotNull(result.transactionId());
        assertNull(result.reason());

        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();

        assertEquals(aliceBefore.subtract(sendAmount), aliceAfter);
        assertEquals(bobBefore.add(sendAmount), bobAfter);
    }

    @Test
    void forgedSignatureByAttackerIsRejected() throws Exception {
        // Attacker creates a fresh keypair and signs a transaction pretending to be Alice
        KeyPair attackerKey = signatureService.generateKeyPair();

        PaymentInstruction forgedInstruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                "pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli());

        String attackerSignature = signatureService.sign(forgedInstruction, attackerKey.getPrivate());
        forgedInstruction.setSignature(attackerSignature);
        forgedInstruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ciphertext = crypto.encrypt(forgedInstruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("invalid_signature", result.reason());
    }

    @Test
    void crossAccountKeySigningIsRejected() throws Exception {
        // Bob signs a transaction where sender is Alice
        // Bob's key is valid, but not registered for Alice
        Account bob = accounts.findById("bob@demo").orElseThrow();
        KeyPair bobKeyPair = signatureService.generateKeyPair(); // or simulated key
        Account alice = accounts.findById("alice@demo").orElseThrow();

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                "pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli());

        // Sign with Bob's generated key
        String bobSignature = signatureService.sign(instruction, bobKeyPair.getPrivate());
        instruction.setSignature(bobSignature);
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("invalid_signature", result.reason());
    }

    @Test
    void unknownSenderAccountIsRejected() throws Exception {
        KeyPair ghostKey = signatureService.generateKeyPair();

        PaymentInstruction instruction = new PaymentInstruction(
                "nonexistent@demo", "bob@demo", new BigDecimal("50.00"),
                "pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli());

        String sig = signatureService.sign(instruction, ghostKey.getPrivate());
        instruction.setSignature(sig);
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("unknown_sender", result.reason());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        PaymentInstruction unsignedInstruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                "pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli());

        unsignedInstruction.setSignature(null); // No signature

        String ciphertext = crypto.encrypt(unsignedInstruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("missing_signature", result.reason());
    }

    @Test
    void unsupportedSignatureAlgorithmIsRejected() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                "pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli());

        instruction.setSignature("dummySigBase64");
        instruction.setSignatureAlgorithm("RSA-MD5"); // Unsupported algorithm

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("unsupported_signature_algorithm", result.reason());
    }

    @Test
    void missingSignatureAlgorithmIsRejected() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();

        // Create an otherwise valid signed payment
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("40.00"),
                "pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli());

        String sig = signatureService.sign(instruction, demoService.getSimulatedClientPrivateKey("alice@demo"));
        instruction.setSignature(sig);
        instruction.setSignatureAlgorithm(null); // Missing / null algorithm

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("unsupported_signature_algorithm", result.reason());

        // Verify no settlement occurred
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore, aliceAfter);
        assertEquals(bobBefore, bobAfter);
    }

    @Test
    void stalePacketFailsFreshnessCheck() throws Exception {
        // Packet signed 25 hours ago
        long staleTimestamp = Instant.now().minusSeconds(25 * 3600).toEpochMilli();

        PaymentInstruction staleInstruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"),
                "pin", UUID.randomUUID().toString(), staleTimestamp);
        String sig = signatureService.sign(staleInstruction, demoService.getSimulatedClientPrivateKey("alice@demo"));
        staleInstruction.setSignature(sig);
        staleInstruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ct = crypto.encrypt(staleInstruction, serverKey.getPublicKey());
        MeshPacket stalePacket = new MeshPacket();
        stalePacket.setPacketId(UUID.randomUUID().toString());
        stalePacket.setTtl(5);
        stalePacket.setCreatedAt(staleTimestamp);
        stalePacket.setCiphertext(ct);

        BridgeIngestionService.IngestResult result = bridge.ingest(stalePacket, "bridge-test", 1);
        assertEquals("INVALID", result.outcome());
        assertEquals("stale_packet", result.reason());
    }

    @Test
    void futureDatedPacketFailsFreshnessCheck() throws Exception {
        // Packet signed 10 minutes in the future
        long futureTimestamp = Instant.now().plusSeconds(600).toEpochMilli();

        PaymentInstruction futureInstruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"),
                "pin", UUID.randomUUID().toString(), futureTimestamp);
        String sig = signatureService.sign(futureInstruction, demoService.getSimulatedClientPrivateKey("alice@demo"));
        futureInstruction.setSignature(sig);
        futureInstruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ct = crypto.encrypt(futureInstruction, serverKey.getPublicKey());
        MeshPacket futurePacket = new MeshPacket();
        futurePacket.setPacketId(UUID.randomUUID().toString());
        futurePacket.setTtl(5);
        futurePacket.setCreatedAt(futureTimestamp);
        futurePacket.setCiphertext(ct);

        BridgeIngestionService.IngestResult result = bridge.ingest(futurePacket, "bridge-test", 1);
        assertEquals("INVALID", result.outcome());
        assertEquals("future_dated", result.reason());
    }
}

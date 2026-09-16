package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.metrics.UpiMetricsService;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ObservabilityMetricsTest {

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private UpiMetricsService metricsService;
    @Autowired private BridgeIngestionService bridgeIngestionService;
    @Autowired private SettlementService settlementService;
    @Autowired private OfflineWalletService offlineWalletService;
    @Autowired private MeshSimulatorService meshSimulatorService;
    @Autowired private InvariantAuditService invariantAuditService;
    @Autowired private InfrastructureMetrics infrastructureMetrics;
    @Autowired private HybridCryptoService cryptoService;
    @Autowired private ServerKeyHolder serverKeyHolder;
    @Autowired private SignatureService signatureService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private DemoService demoService;
    @Autowired private IdempotencyService idempotencyService;

    private KeyPair aliceKeys;
    private KeyPair bobKeys;

    @BeforeEach
    void setUp() throws Exception {
        idempotencyService.clear();
        aliceKeys = signatureService.generateKeyPair();
        bobKeys = signatureService.generateKeyPair();

        Account alice = accountRepository.findById("obs-alice@demo")
                .orElse(new Account("obs-alice@demo", "Alice Obs", new BigDecimal("1000.00")));
        alice.setBalance(new BigDecimal("1000.00"));
        alice.setOfflineLockedBalance(BigDecimal.ZERO);
        alice.setPublicKey(signatureService.encodePublicKey(aliceKeys.getPublic()));
        alice.setKeyAlgorithm("Ed25519");

        Account bob = accountRepository.findById("obs-bob@demo")
                .orElse(new Account("obs-bob@demo", "Bob Obs", new BigDecimal("500.00")));
        bob.setBalance(new BigDecimal("500.00"));
        bob.setOfflineLockedBalance(BigDecimal.ZERO);
        bob.setPublicKey(signatureService.encodePublicKey(bobKeys.getPublic()));
        bob.setKeyAlgorithm("Ed25519");

        accountRepository.save(alice);
        accountRepository.save(bob);
    }

    @Test
    @DisplayName("Transaction settlement increments settlement counters and timers")
    void testSettlementMetricsIncrement() throws Exception {
        double initialSettled = getCounterValue("upi.transactions.settled", "type", "online");

        PaymentInstruction instruction = new PaymentInstruction();
        instruction.setSenderVpa("obs-alice@demo");
        instruction.setReceiverVpa("obs-bob@demo");
        instruction.setAmount(new BigDecimal("100.00"));
        instruction.setNonce(UUID.randomUUID().toString());
        instruction.setSignedAt(Instant.now().toEpochMilli());
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String sig = signatureService.sign(instruction, aliceKeys.getPrivate());
        instruction.setSignature(sig);

        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setCiphertext(ciphertext);
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());

        BridgeIngestionService.IngestResult result = bridgeIngestionService.ingest(packet, "phone-bridge", 1);
        assertEquals("SETTLED", result.outcome());

        double postSettled = getCounterValue("upi.transactions.settled", "type", "online");
        assertEquals(initialSettled + 1.0, postSettled, 0.001);
    }

    @Test
    @DisplayName("Duplicate transaction increments duplicate metric")
    void testDuplicateMetricsIncrement() throws Exception {
        double initialDuplicates = getCounterValue("upi.transactions.duplicate");

        PaymentInstruction instruction = new PaymentInstruction();
        instruction.setSenderVpa("obs-alice@demo");
        instruction.setReceiverVpa("obs-bob@demo");
        instruction.setAmount(new BigDecimal("50.00"));
        instruction.setNonce(UUID.randomUUID().toString());
        instruction.setSignedAt(Instant.now().toEpochMilli());
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String sig = signatureService.sign(instruction, aliceKeys.getPrivate());
        instruction.setSignature(sig);

        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setCiphertext(ciphertext);
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());

        // First ingestion settles
        bridgeIngestionService.ingest(packet, "phone-bridge", 1);

        // Second ingestion of exact same packet is recognized as duplicate
        BridgeIngestionService.IngestResult dupResult = bridgeIngestionService.ingest(packet, "phone-bridge", 1);
        assertEquals("SETTLED", dupResult.outcome()); // committed result returned

        double postDuplicates = getCounterValue("upi.transactions.duplicate");
        assertTrue(postDuplicates > initialDuplicates, "Duplicate metric should have incremented");
    }

    @Test
    @DisplayName("Invalid signature increments rejected transaction counter with sanitized reason")
    void testRejectedTransactionMetricsIncrement() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction();
        instruction.setSenderVpa("obs-alice@demo");
        instruction.setReceiverVpa("obs-bob@demo");
        instruction.setAmount(new BigDecimal("50.00"));
        instruction.setNonce(UUID.randomUUID().toString());
        instruction.setSignedAt(Instant.now().toEpochMilli());
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);
        instruction.setSignature("corrupted_invalid_signature_base64");

        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setCiphertext(ciphertext);
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());

        BridgeIngestionService.IngestResult result = bridgeIngestionService.ingest(packet, "phone-bridge", 1);
        assertEquals("INVALID", result.outcome());

        double rejected = getCounterValue("upi.transactions.rejected", "reason", "invalid_signature");
        assertTrue(rejected >= 1.0, "Rejected counter with invalid_signature reason must be >= 1");
    }

    @Test
    @DisplayName("Wallet allocation and reconciliation increment wallet metrics")
    void testWalletMetricsIncrement() {
        double initialAllocated = getCounterValue("upi.wallets.allocated");
        double initialReconciled = getCounterValue("upi.wallets.reconciled");

        OfflineWalletService.AllocationResult alloc = offlineWalletService.allocate("obs-alice@demo", new BigDecimal("200.00"), 24);
        assertNotNull(alloc.wallet());

        double postAllocated = getCounterValue("upi.wallets.allocated");
        assertEquals(initialAllocated + 1.0, postAllocated, 0.001);

        offlineWalletService.reconcileAndClose(alloc.wallet().getWalletId());
        double postReconciled = getCounterValue("upi.wallets.reconciled");
        assertEquals(initialReconciled + 1.0, postReconciled, 0.001);
    }

    @Test
    @DisplayName("Mesh gossip, anti-entropy sync, partition, and heal increment mesh metrics")
    void testMeshMetricsIncrement() {
        double initialGossip = getCounterValue("upi.mesh.gossip.rounds");
        double initialPartitions = getCounterValue("upi.mesh.partition.events");
        double initialHeals = getCounterValue("upi.mesh.heal.events");

        meshSimulatorService.severLink("phone-alice", "phone-stranger1");
        double postPartitions = getCounterValue("upi.mesh.partition.events");
        assertEquals(initialPartitions + 1.0, postPartitions, 0.001);

        meshSimulatorService.healLink("phone-alice", "phone-stranger1");
        double postHeals = getCounterValue("upi.mesh.heal.events");
        assertEquals(initialHeals + 1.0, postHeals, 0.001);

        meshSimulatorService.gossipOnce();
        double postGossip = getCounterValue("upi.mesh.gossip.rounds");
        assertEquals(initialGossip + 1.0, postGossip, 0.001);

        meshSimulatorService.syncAntiEntropy();
        double syncInSync = getCounterValue("upi.mesh.sync.rounds", "result", "in_sync");
        assertTrue(syncInSync >= 1.0, "Sync in-sync rounds should be recorded");
    }

    @Test
    @DisplayName("Strict low-cardinality tag audit: No high-cardinality keys exist in registry")
    void testLowCardinalityTagAudit() {
        Set<String> prohibitedTags = Set.of(
                "packethash", "packet_hash", "transactionid", "transaction_id",
                "walletid", "wallet_id", "ownervpa", "owner_vpa", "sendervpa",
                "sender_vpa", "receivervpa", "receiver_vpa", "requestid", "request_id", "nonce"
        );

        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String tagKey = tag.getKey().toLowerCase();
                assertFalse(prohibitedTags.contains(tagKey),
                        "Found prohibited high-cardinality tag key: " + tag.getKey() + " on meter " + meter.getId().getName());

                // Ensure tag value is not a raw hash or UUID
                String tagValue = tag.getValue();
                assertFalse(tagValue.matches("^[a-fA-F0-9]{64}$"),
                        "Found SHA-256 hash in tag value for key: " + tag.getKey() + " on meter " + meter.getId().getName());
                assertFalse(tagValue.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"),
                        "Found UUID in tag value for key: " + tag.getKey() + " on meter " + meter.getId().getName());
            }
        }
    }

    @Test
    @DisplayName("Redis fallback and database retries increment infrastructure metrics")
    void testInfrastructureMetricsIncrement() {
        double initialFallbacks = getCounterValue("upi.infra.redis.fallbacks");
        double initialDbRetries = getCounterValue("upi.infra.db.retries");

        infrastructureMetrics.recordRedisFallback();
        infrastructureMetrics.recordDbRetry();

        double postFallbacks = getCounterValue("upi.infra.redis.fallbacks");
        double postDbRetries = getCounterValue("upi.infra.db.retries");

        assertEquals(initialFallbacks + 1.0, postFallbacks, 0.001);
        assertEquals(initialDbRetries + 1.0, postDbRetries, 0.001);
    }

    private double getCounterValue(String name, String... tags) {
        try {
            var search = meterRegistry.find(name);
            if (tags.length >= 2) {
                for (int i = 0; i < tags.length; i += 2) {
                    search = search.tag(tags[i], tags[i + 1]);
                }
            }
            Counter counter = search.counter();
            return counter != null ? counter.count() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}

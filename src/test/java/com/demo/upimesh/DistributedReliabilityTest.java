package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.fault.*;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import com.demo.upimesh.service.BridgeIngestionService.IngestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 Verification Test Suite:
 * Fault Injection & Distributed Reliability Testing
 *
 * Implements all 25 approved Revision 2 tests across 4 groups:
 *   - Group 1: Isolated Network Fault Tests (7 tests)
 *   - Group 2: Isolated Bridge & Backend Fault Tests (6 tests)
 *   - Group 3: Compound & Combination Fault Tests (7 tests)
 *   - Group 4: Invariant & Property Tests (5 tests)
 *
 * Verifies Invariants I1 through I12 deterministically without Thread.sleep().
 */
@SpringBootTest
public class DistributedReliabilityTest {

    @Autowired private MeshSimulatorService mesh;
    @Autowired private AntiEntropyService antiEntropyService;
    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridgeIngestion;
    @Autowired private SettlementService settlementService;
    @Autowired private OfflineWalletService walletService;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private OfflineWalletRepository walletRepository;
    @Autowired private HybridCryptoService crypto;
    @Autowired private FaultInjector faultInjector;
    @Autowired private ReliabilityMetrics metrics;

    private BigDecimal initialSystemFunds;

    @BeforeEach
    void setUp() {
        faultInjector.reset();
        metrics.reset();
        mesh.resetMesh();
        idempotency.clear();
        transactions.deleteAll();
        walletRepository.deleteAll();

        // Configure fast settlement backoff for test speed
        settlementService.setBaseBackoffMs(1L);

        // Reset demo account balances
        Account alice = accounts.findById("alice@demo").orElse(null);
        if (alice != null) {
            alice.setBalance(new BigDecimal("5000.00"));
            alice.setOfflineLockedBalance(BigDecimal.ZERO);
            accounts.save(alice);
        }
        Account bob = accounts.findById("bob@demo").orElse(null);
        if (bob != null) {
            bob.setBalance(new BigDecimal("1000.00"));
            bob.setOfflineLockedBalance(BigDecimal.ZERO);
            accounts.save(bob);
        }
        Account carol = accounts.findById("carol@demo").orElse(null);
        if (carol != null) {
            carol.setBalance(new BigDecimal("500.00"));
            carol.setOfflineLockedBalance(BigDecimal.ZERO);
            accounts.save(carol);
        }

        initialSystemFunds = calculateTotalSystemFunds();
    }

    @AfterEach
    void tearDown() {
        faultInjector.reset();
        metrics.reset();
        settlementService.setBaseBackoffMs(25L);
    }

    // ---------------------------------------------------------------- Test Helpers

    private BigDecimal calculateTotalSystemFunds() {
        return accounts.findAll().stream()
                .map(a -> a.getBalance().add(a.getOfflineLockedBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private MeshPacket createTestPacket(String id, String content, int ttl) {
        MeshPacket p = new MeshPacket();
        p.setPacketId(id);
        p.setTtl(ttl);
        p.setCreatedAt(Instant.now().toEpochMilli());
        p.setCiphertext(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        return p;
    }

    private MeshPacket createSignedPaymentPacket(String sender, String receiver, BigDecimal amount) throws Exception {
        return demoService.createPacket(sender, receiver, amount, "1234", 5);
    }

    private MeshPacket createSignedOfflinePacket(String sender, String receiver, BigDecimal amount,
                                                 String walletId, Long epoch, long counter,
                                                 BigDecimal cumulative, OfflineWalletCertificate cert) throws Exception {
        return demoService.createOfflinePacket(sender, receiver, amount, "1234", 5, walletId, epoch, counter, cumulative, cert);
    }

    // =========================================================================
    // GROUP 1: Isolated Network Fault Tests (7 tests)
    // =========================================================================

    /**
     * 1. testPacketDropRecoveredBySubsequentAntiEntropy
     * Packet drop during push gossip is subsequently detected and repaired by pairwise anti-entropy.
     * Invariants verified: I1, I2, I7.
     */
    @Test
    void testPacketDropRecoveredBySubsequentAntiEntropy() throws Exception {
        VirtualDevice devA = mesh.getDevice("phone-alice");
        VirtualDevice devB = mesh.getDevice("phone-stranger1");

        MeshPacket packet = createTestPacket("pkt-drop-1", "drop-content-payload", 5);
        devA.hold(packet);

        // Inject drop rule for gossip push from alice to stranger1
        faultInjector.failNext(FaultType.DROP, "phone-alice", "phone-stranger1");

        // Execute push gossip round -> packet is dropped
        MeshSimulatorService.GossipResult gossipRes = mesh.gossipOnce();
        assertFalse(devB.holds(packet.getPacketHash()), "Packet should not be held on devB after dropped push");
        assertEquals(1, metrics.getFaultDropsTotal(), "Metrics should record 1 dropped packet");

        // Run anti-entropy synchronization
        MeshSimulatorService.AntiEntropySyncResult syncRes = mesh.syncAntiEntropy();
        assertTrue(syncRes.totalTransfers() > 0, "Anti-entropy should repair dropped packet");
        assertTrue(devB.holds(packet.getPacketHash()), "devB must hold packet after anti-entropy");
        assertEquals(devA.getStateDigest(), devB.getStateDigest(), "State digests must match after repair");

        // Invariant I1: packetHash == SHA-256(ciphertext)
        assertEquals(crypto.hashCiphertext(packet.getCiphertext()), packet.getPacketHash());
    }

    /**
     * 2. testPacketDuplicationSuppressedByAuthoritativeHash
     * Injected duplicate delivery over network does not create duplicate entries in device buffer.
     * Invariant verified: I2.
     */
    @Test
    void testPacketDuplicationSuppressedByAuthoritativeHash() {
        VirtualDevice devA = mesh.getDevice("phone-alice");
        VirtualDevice devB = mesh.getDevice("phone-stranger1");

        MeshPacket packet = createTestPacket("pkt-dup-1", "payload-content-dup", 5);
        devA.hold(packet);

        // Inject DUPLICATE rule: delivers 10 duplicate copies of packet
        faultInjector.addRule(new FaultRule("dup-rule", FaultType.DUPLICATE, "phone-alice", "phone-stranger1", null, null, 10, 0));

        mesh.gossipOnce();

        assertEquals(1, devB.packetCount(), "Device B must hold exactly 1 packet despite 10 injected network deliveries");
        assertTrue(devB.holds(packet.getPacketHash()));
        assertTrue(metrics.getFaultDuplicatesTotal() > 0, "Duplicate metrics should record fault");
    }

    /**
     * 3. testReorderedPacketDeliveryObservedAsSequenceGap
     * Delivering offline payments out-of-order triggers Phase 3 sequence gap behavior.
     * Invariants verified: I3, I6.
     */
    @Test
    void testReorderedPacketDeliveryObservedAsSequenceGap() throws Exception {
        OfflineWalletService.AllocationResult alloc = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24);
        String walletId = alloc.wallet().getWalletId();
        OfflineWalletCertificate cert = alloc.certificate();

        // Create Counter 1 (₹20) and Counter 2 (₹30)
        MeshPacket pkt1 = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("20.00"),
                walletId, 1L, 1L, new BigDecimal("20.00"), cert);
        MeshPacket pkt2 = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("30.00"),
                walletId, 1L, 2L, new BigDecimal("50.00"), cert);

        // Invert delivery: deliver Counter 2 FIRST to backend
        IngestResult res2 = bridgeIngestion.ingest(pkt2, "bridge-reorder", 1);
        assertEquals("PENDING_SEQUENCE_GAP", res2.outcome(), "Counter 2 delivered first must be marked PENDING_SEQUENCE_GAP");
        assertEquals("missing_prior_sequence_counter", res2.reason());

        // Now deliver Counter 1
        IngestResult res1 = bridgeIngestion.ingest(pkt1, "bridge-reorder", 1);
        assertEquals("SETTLED", res1.outcome(), "Counter 1 must settle cleanly");

        // Verify Counter 2 was promoted to SETTLED by existing Phase 3 engine
        Transaction tx2 = transactions.findByPacketHash(pkt2.getPacketHash()).orElseThrow();
        assertEquals(Transaction.Status.SETTLED, tx2.getStatus(), "Counter 2 must be promoted to SETTLED after gap resolution");

        // Invariant I3: exactly 2 distinct settled transactions
        assertEquals(2, transactions.count());
    }

    /**
     * 4. testDelayedPacketArrivalAfterAntiEntropyIsDroppedAsDuplicate
     * A delayed gossip packet arriving after anti-entropy has already synchronized is dropped as duplicate.
     * Invariant verified: I2.
     */
    @Test
    void testDelayedPacketArrivalAfterAntiEntropyIsDroppedAsDuplicate() {
        VirtualDevice devA = mesh.getDevice("phone-alice");
        VirtualDevice devB = mesh.getDevice("phone-stranger1");

        MeshPacket packet = createTestPacket("pkt-delay-1", "delayed-payload", 5);
        devA.hold(packet);

        // Withhold packet from gossip push using DELAY fault
        faultInjector.failNext(FaultType.DELAY, "phone-alice", "phone-stranger1");
        mesh.gossipOnce();
        assertFalse(devB.holds(packet.getPacketHash()), "Packet was delayed, should not be on devB yet");

        // Anti-entropy runs and repairs the missing packet
        mesh.syncAntiEntropy();
        assertTrue(devB.holds(packet.getPacketHash()), "devB acquired packet via anti-entropy");

        // Now the delayed packet arrives via push; devB.hold() must return false (duplicate suppressed)
        boolean acceptedLate = devB.hold(packet);
        assertFalse(acceptedLate, "Delayed packet arriving after anti-entropy repair must be dropped idempotently");
        assertEquals(1, devB.packetCount());
    }

    /**
     * 5. testPeerUnavailableTriggersAlternatePeerFallback
     * Primary peer unavailability during anti-entropy sync triggers fallback to an alternate peer.
     * Invariant verified: I7.
     */
    @Test
    void testPeerUnavailableTriggersAlternatePeerFallback() {
        VirtualDevice devA = mesh.getDevice("phone-alice");
        VirtualDevice devB = mesh.getDevice("phone-stranger1");
        VirtualDevice devC = mesh.getDevice("phone-stranger2");

        MeshPacket packet = createTestPacket("pkt-fallback-1", "fallback-content", 5);
        devC.hold(packet);

        // Make devB unavailable to devA
        faultInjector.failNext(FaultType.PEER_UNAVAILABLE, "phone-alice", "phone-stranger1");

        List<VirtualDevice> candidates = List.of(devB, devC);
        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncWithFallback(
                devA, devB.getDeviceId(), candidates, mesh::isReachable);

        assertNotNull(res);
        assertTrue(devA.holds(packet.getPacketHash()), "devA must have synced packet from alternate peer devC");
        assertEquals(devA.getStateDigest(), devC.getStateDigest());
    }

    /**
     * 6. testNodeRestartRecoversBufferWithoutCorruptingBackend
     * A node that suffers an in-memory crash wipes its buffer and re-syncs state from peers without mutating backend ledger.
     * Invariant verified: I11.
     */
    @Test
    void testNodeRestartRecoversBufferWithoutCorruptingBackend() {
        VirtualDevice devA = mesh.getDevice("phone-alice");
        VirtualDevice devB = mesh.getDevice("phone-stranger1");

        for (int i = 0; i < 5; i++) {
            MeshPacket p = createTestPacket("p-" + i, "content-" + i, 5);
            devA.hold(p);
            devB.hold(p);
        }

        BigDecimal balanceAliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal balanceBobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();

        // Node A crashes (volatile memory reset)
        devA.clear();
        assertEquals(0, devA.packetCount(), "Node A must have 0 packets after crash");
        assertNotEquals(devA.getStateDigest(), devB.getStateDigest());

        // Node A executes anti-entropy with Node B
        AntiEntropyService.PairwiseSyncResult syncRes = antiEntropyService.syncPair(devA, devB);
        assertEquals(5, syncRes.packetsTransferredToLocal(), "Node A must re-acquire all 5 packets");
        assertEquals(devA.getStateDigest(), devB.getStateDigest(), "Digests must match after crash recovery");

        // Backend ledger remains strictly untouched
        BigDecimal balanceAliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal balanceBobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(balanceAliceBefore, balanceAliceAfter);
        assertEquals(balanceBobBefore, balanceBobAfter);
    }

    /**
     * 7. testRepeatedPartitionHealCyclesAchieveEventualConvergence
     * Repeated partition and heal churn cycles maintain submesh consistency and achieve mathematical convergence.
     * Invariant verified: I7.
     */
    @Test
    void testRepeatedPartitionHealCyclesAchieveEventualConvergence() {
        List<String> submeshA = List.of("phone-alice", "phone-stranger1");
        List<String> submeshB = List.of("phone-stranger2", "phone-stranger3");

        for (int cycle = 1; cycle <= 3; cycle++) {
            // Partition
            mesh.partitionSubmeshes(submeshA, submeshB);

            // Inject independent packets in each submesh
            MeshPacket pktA = createTestPacket("cycle-" + cycle + "-A", "content-A-" + cycle, 5);
            MeshPacket pktB = createTestPacket("cycle-" + cycle + "-B", "content-B-" + cycle, 5);
            mesh.getDevice("phone-alice").hold(pktA);
            mesh.getDevice("phone-stranger2").hold(pktB);

            // Gossip inside submeshes
            mesh.gossipOnce();
            assertTrue(mesh.getDevice("phone-stranger1").holds(pktA.getPacketHash()));
            assertTrue(mesh.getDevice("phone-stranger3").holds(pktB.getPacketHash()));
            assertFalse(mesh.getDevice("phone-stranger2").holds(pktA.getPacketHash()));

            // Heal
            mesh.healAll();

            // Run anti-entropy
            mesh.syncAntiEntropy();
            mesh.syncAntiEntropy();

            // Assert convergence
            assertTrue(mesh.isMeshFullyConverged(), "Mesh must fully converge after heal cycle " + cycle);
        }
    }

    // =========================================================================
    // GROUP 2: Isolated Bridge & Backend Fault Tests (6 tests)
    // =========================================================================

    /**
     * 8. testBridgeUnavailableKeepsMeshBuffersIntact
     * When bridge upload transport fails, packets remain completely intact in mesh buffers.
     * Invariants verified: I2, I3, I4.
     */
    @Test
    void testBridgeUnavailableKeepsMeshBuffersIntact() {
        VirtualDevice bridgeNode = mesh.getDevice("phone-bridge");
        MeshPacket p1 = createTestPacket("br-1", "upload-1", 5);
        MeshPacket p2 = createTestPacket("br-2", "upload-2", 5);
        bridgeNode.hold(p1);
        bridgeNode.hold(p2);

        // Inject BRIDGE_UNAVAILABLE fault
        faultInjector.failN(FaultType.BRIDGE_UNAVAILABLE, "phone-bridge", "*", 5);

        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        assertEquals(0, uploads.size(), "Upload should fail while bridge is unavailable");

        // Packets in bridgeNode must remain intact
        assertEquals(2, bridgeNode.packetCount(), "Mesh buffer must remain completely intact when bridge upload fails");
        assertTrue(bridgeNode.holds(p1.getPacketHash()));
        assertTrue(bridgeNode.holds(p2.getPacketHash()));

        // Clear fault rule; upload now succeeds
        faultInjector.reset();
        List<MeshSimulatorService.BridgeUpload> recoveredUploads = mesh.collectBridgeUploads();
        assertEquals(2, recoveredUploads.size(), "All packets must be harvested once bridge is restored");
    }

    /**
     * 9. testDuplicateBridgeUploadIdempotentlyDeduplicated
     * Concurrent duplicate bridge submissions result in exactly 1 settlement and 0 duplicate debits.
     * Invariant verified: I3.
     */
    @Test
    void testDuplicateBridgeUploadIdempotentlyDeduplicated() throws Exception {
        MeshPacket packet = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("45.00"));

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return bridgeIngestion.ingest(packet, "bridge-dup", 1);
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        int settledCount = 0;
        for (Future<IngestResult> f : futures) {
            IngestResult r = f.get();
            if ("SETTLED".equals(r.outcome())) {
                settledCount++;
            }
        }

        // Fast-path recovery or DB barrier ensures exactly 1 debit
        assertEquals(1, transactions.count(), "Database must contain exactly 1 transaction record");
        Account alice = accounts.findById("alice@demo").orElseThrow();
        assertEquals(new BigDecimal("4955.00"), alice.getBalance(), "Alice must be debited exactly once");
    }

    /**
     * 10. testLostHttpResponseRecoversCommittedSettlement
     * If the HTTP response is lost after settlement commits to DB, a retried submission returns the cached result without double debit.
     * Invariants verified: I3, I4.
     */
    @Test
    void testLostHttpResponseRecoversCommittedSettlement() throws Exception {
        MeshPacket packet = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("75.00"));
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Inject STALE_RESPONSE: post-commit response is dropped/thrown
        faultInjector.failPacket(FaultType.STALE_RESPONSE, hash);

        assertThrows(Exception.class, () -> bridgeIngestion.ingest(packet, "bridge-lost", 1));

        // Verify transaction was already committed in DB
        Optional<Transaction> txOpt = transactions.findByPacketHash(hash);
        assertTrue(txOpt.isPresent(), "Transaction must have committed before response was lost");
        assertEquals(Transaction.Status.SETTLED, txOpt.get().getStatus());

        // Client/bridge retries the same packet
        IngestResult retryResult = bridgeIngestion.ingest(packet, "bridge-lost", 1);
        assertEquals("SETTLED", retryResult.outcome(), "Retried submission must return SETTLED from database fast-path");

        // Single debit verified
        assertEquals(1, transactions.count());
        Account alice = accounts.findById("alice@demo").orElseThrow();
        assertEquals(new BigDecimal("4925.00"), alice.getBalance());
        assertEquals(initialSystemFunds, calculateTotalSystemFunds(), "System funds must be strictly conserved");
    }

    /**
     * 11. testTransientOptimisticLockExceptionSucceedsOnRetry
     * Injected transient OptimisticLockException on attempt 1 succeeds on retry attempt 2.
     * Invariants verified: I3, I9.
     */
    @Test
    void testTransientOptimisticLockExceptionSucceedsOnRetry() throws Exception {
        MeshPacket packet = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("50.00"));
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Inject TRANSIENT_DATABASE_FAILURE for attempt 1
        faultInjector.failPacket(FaultType.TRANSIENT_DATABASE_FAILURE, hash);

        IngestResult res = bridgeIngestion.ingest(packet, "bridge-retry", 1);
        assertEquals("SETTLED", res.outcome(), "Settlement must succeed on attempt 2 after transient failure");
        assertEquals(1, metrics.getRetryAttemptsTotal(), "Retry metrics must record 1 retry");
        assertEquals(1, transactions.count());
    }

    /**
     * 12. testExhaustedRetriesThrowsTransientExceptionAndReleasesLock
     * Exhausting all 3 attempts releases in-flight lock, returning TRANSIENT_FAILURE and allowing subsequent retry.
     * Invariants verified: I9, I10.
     */
    @Test
    void testExhaustedRetriesThrowsTransientExceptionAndReleasesLock() throws Exception {
        MeshPacket packet = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("35.00"));
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Invalidate all 3 attempts
        faultInjector.failN(FaultType.TRANSIENT_DATABASE_FAILURE, "*", "*", 3);

        IngestResult res = bridgeIngestion.ingest(packet, "bridge-exhaust", 1);
        assertEquals("TRANSIENT_FAILURE", res.outcome());

        // In-flight gate must be released
        assertFalse(idempotency.isTracked(hash), "In-flight gate must be released after exhausted retries");

        // Clear rule; external retry succeeds
        faultInjector.reset();
        IngestResult retryRes = bridgeIngestion.ingest(packet, "bridge-exhaust", 1);
        assertEquals("SETTLED", retryRes.outcome(), "Subsequent retry after transient failure must settle");
    }

    /**
     * 13. testPermanentValidationFailureNeverRetried
     * Permanent validation failure (corrupted ciphertext or invalid signature) is immediately rejected without retries.
     * Invariants verified: I10, I12.
     */
    @Test
    void testPermanentValidationFailureNeverRetried() throws Exception {
        MeshPacket packet = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("60.00"));

        // Inject payload corruption
        faultInjector.failNext(FaultType.CORRUPTED_PACKET_PAYLOAD);
        MeshPacket corrupted = faultInjector.interceptPacketPayload("client", "bridge", packet);

        IngestResult res = bridgeIngestion.ingest(corrupted, "bridge-perm", 1);
        assertEquals("INVALID", res.outcome());
        assertEquals("decryption_failed", res.reason());
        assertEquals(0, metrics.getRetryAttemptsTotal(), "Validation failures must never trigger retry attempts");
        assertEquals(0, transactions.count());
    }

    // =========================================================================
    // GROUP 3: Compound & Combination Fault Tests (7 tests)
    // =========================================================================

    /**
     * 14. testCompoundDropAndAntiEntropy
     * Multi-bucket push packet drops are completely resolved in a single pairwise anti-entropy round.
     */
    @Test
    void testCompoundDropAndAntiEntropy() {
        VirtualDevice devA = mesh.getDevice("phone-alice");
        VirtualDevice devB = mesh.getDevice("phone-stranger1");

        // Generate 6 packets
        for (int i = 0; i < 6; i++) {
            devA.hold(createTestPacket("compound-drop-" + i, "content-" + i, 5));
        }

        // Drop all gossip push transfers
        faultInjector.failN(FaultType.DROP, "phone-alice", "phone-stranger1", 10);
        mesh.gossipOnce();
        assertEquals(0, devB.packetCount(), "All packets dropped in gossip push");

        // Pairwise anti-entropy recovers all packets
        mesh.syncAntiEntropy();
        assertEquals(6, devB.packetCount(), "Anti-entropy must recover all 6 dropped packets");
        assertEquals(devA.getStateDigest(), devB.getStateDigest());
    }

    /**
     * 15. testCompoundDelayAndReorderOfflineWalletSequence
     * Out-of-order delivery with 3 offline payments resolves sequence gaps sequentially upon arrival.
     */
    @Test
    void testCompoundDelayAndReorderOfflineWalletSequence() throws Exception {
        OfflineWalletService.AllocationResult alloc = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24);
        String walletId = alloc.wallet().getWalletId();
        OfflineWalletCertificate cert = alloc.certificate();

        MeshPacket p1 = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("10.00"), walletId, 1L, 1L, new BigDecimal("10.00"), cert);
        MeshPacket p2 = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("15.00"), walletId, 1L, 2L, new BigDecimal("25.00"), cert);
        MeshPacket p3 = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("20.00"), walletId, 1L, 3L, new BigDecimal("45.00"), cert);

        // Deliver Counter 3, then Counter 2, then Counter 1
        IngestResult res3 = bridgeIngestion.ingest(p3, "b1", 1);
        assertEquals("PENDING_SEQUENCE_GAP", res3.outcome());

        IngestResult res2 = bridgeIngestion.ingest(p2, "b1", 1);
        assertEquals("PENDING_SEQUENCE_GAP", res2.outcome());

        IngestResult res1 = bridgeIngestion.ingest(p1, "b1", 1);
        assertEquals("SETTLED", res1.outcome());

        // All 3 transactions are settled
        assertEquals(3, transactions.count());
        long settledCount = transactions.findAll().stream().filter(t -> t.getStatus() == Transaction.Status.SETTLED).count();
        assertEquals(3, settledCount, "All 3 transactions must resolve to SETTLED");

        OfflineWallet wallet = walletRepository.findByWalletId(walletId).orElseThrow();
        assertEquals(new BigDecimal("55.00"), wallet.getRemainingEscrow(), "Remaining escrow must match ₹55.00");
    }

    /**
     * 16. testCompoundDuplicateAndLostResponse
     * Duplicate mesh delivery combined with dropped HTTP response preserves single debit invariant.
     */
    @Test
    void testCompoundDuplicateAndLostResponse() throws Exception {
        MeshPacket packet = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("40.00"));
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Drop response on first commit
        faultInjector.failPacket(FaultType.STALE_RESPONSE, hash);
        assertThrows(Exception.class, () -> bridgeIngestion.ingest(packet, "b1", 1));

        // Subsequent duplicate deliveries return cached result
        IngestResult r1 = bridgeIngestion.ingest(packet, "b1", 1);
        IngestResult r2 = bridgeIngestion.ingest(packet, "b2", 1);

        assertEquals("SETTLED", r1.outcome());
        assertEquals("SETTLED", r2.outcome());
        assertEquals(1, transactions.count());
        assertEquals(new BigDecimal("4960.00"), accounts.findById("alice@demo").orElseThrow().getBalance());
    }

    /**
     * 17. testCompoundPartitionAndConcurrentPayments
     * Payments generated concurrently across partitioned submeshes settle cleanly after healing.
     */
    @Test
    void testCompoundPartitionAndConcurrentPayments() throws Exception {
        mesh.partitionSubmeshes(List.of("phone-alice"), List.of("phone-bridge"));

        MeshPacket pAlice = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("100.00"));
        MeshPacket pBob = createSignedPaymentPacket("bob@demo", "carol@demo", new BigDecimal("50.00"));

        mesh.getDevice("phone-alice").hold(pAlice);
        mesh.getDevice("phone-bridge").hold(pBob);

        // Heal mesh and sync
        mesh.healAll();
        mesh.syncAntiEntropy();
        mesh.syncAntiEntropy();

        // Ingest all uploads
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        for (MeshSimulatorService.BridgeUpload u : uploads) {
            bridgeIngestion.ingest(u.packet(), u.bridgeNodeId(), 1);
        }

        assertEquals(2, transactions.count());
        assertEquals(initialSystemFunds, calculateTotalSystemFunds());
    }

    /**
     * 18. testCompoundPartitionAndBridgeUnavailable
     * Isolated submesh with disabled bridge upload buffers packets until both heal.
     */
    @Test
    void testCompoundPartitionAndBridgeUnavailable() throws Exception {
        mesh.partitionSubmeshes(List.of("phone-alice"), List.of("phone-stranger1"));
        faultInjector.failN(FaultType.BRIDGE_UNAVAILABLE, "phone-bridge", "*", 10);

        MeshPacket pkt = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("70.00"));
        mesh.getDevice("phone-alice").hold(pkt);

        // Upload attempted while partitioned and bridge disabled
        List<MeshSimulatorService.BridgeUpload> earlyUploads = mesh.collectBridgeUploads();
        assertEquals(0, earlyUploads.size());

        // Heal partition & restore bridge
        mesh.healAll();
        faultInjector.reset();

        mesh.syncAntiEntropy();
        List<MeshSimulatorService.BridgeUpload> recoveredUploads = mesh.collectBridgeUploads();
        for (MeshSimulatorService.BridgeUpload u : recoveredUploads) {
            bridgeIngestion.ingest(u.packet(), u.bridgeNodeId(), 1);
        }

        assertEquals(1, transactions.count());
        assertEquals(new BigDecimal("4930.00"), accounts.findById("alice@demo").orElseThrow().getBalance());
    }

    /**
     * 19. testCompoundNodeRestartAndAntiEntropy
     * Multi-node simultaneous crash recovers full cluster state from surviving peer.
     */
    @Test
    void testCompoundNodeRestartAndAntiEntropy() {
        VirtualDevice devA = mesh.getDevice("phone-alice");
        VirtualDevice devB = mesh.getDevice("phone-stranger1");
        VirtualDevice devC = mesh.getDevice("phone-stranger2");

        for (int i = 0; i < 4; i++) {
            MeshPacket p = createTestPacket("compound-restart-" + i, "data-" + i, 5);
            devA.hold(p);
            devB.hold(p);
            devC.hold(p);
        }

        // Two nodes crash simultaneously
        devA.clear();
        devB.clear();

        assertEquals(0, devA.packetCount());
        assertEquals(0, devB.packetCount());
        assertEquals(4, devC.packetCount());

        // Re-sync from surviving node devC
        antiEntropyService.syncPair(devA, devC);
        antiEntropyService.syncPair(devB, devC);

        assertEquals(4, devA.packetCount());
        assertEquals(4, devB.packetCount());
        assertEquals(devC.getStateDigest(), devA.getStateDigest());
        assertEquals(devC.getStateDigest(), devB.getStateDigest());
    }

    /**
     * 20. testCompoundDuplicateRequestAndOptimisticLockContention
     * Concurrent duplicate requests contending on the same account settle cleanly.
     */
    @Test
    void testCompoundDuplicateRequestAndOptimisticLockContention() throws Exception {
        MeshPacket pkt1 = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("10.00"));
        MeshPacket pkt2 = createSignedPaymentPacket("alice@demo", "carol@demo", new BigDecimal("20.00"));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new ArrayList<>();

        // Submit pkt1 twice and pkt2 twice concurrently
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> { latch.await(); return bridgeIngestion.ingest(pkt1, "b1", 1); }));
            futures.add(pool.submit(() -> { latch.await(); return bridgeIngestion.ingest(pkt2, "b2", 1); }));
        }

        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(2, transactions.count(), "Exactly 2 distinct settlements must be committed");
        assertEquals(initialSystemFunds, calculateTotalSystemFunds());
    }

    // =========================================================================
    // GROUP 4: Invariant & Property Tests (5 tests)
    // =========================================================================

    /**
     * 21. testPropertyConservationOfTotalFunds
     * System total funds (liquid + escrow) are strictly conserved across high-churn transactions.
     * Invariant verified: I4.
     */
    @Test
    void testPropertyConservationOfTotalFunds() throws Exception {
        for (int i = 1; i <= 10; i++) {
            MeshPacket p = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("10.00"));
            bridgeIngestion.ingest(p, "b-" + i, 1);
        }

        // Allocate offline wallet
        OfflineWalletService.AllocationResult alloc = walletService.allocate("bob@demo", new BigDecimal("200.00"), 24);
        MeshPacket offPkt = createSignedOfflinePacket("bob@demo", "carol@demo", new BigDecimal("50.00"),
                alloc.wallet().getWalletId(), 1L, 1L, new BigDecimal("50.00"), alloc.certificate());
        bridgeIngestion.ingest(offPkt, "b-off", 1);

        // System total funds must be strictly identical to initial
        assertEquals(initialSystemFunds, calculateTotalSystemFunds(),
                "Total system funds (liquid + escrow) must be invariant");
    }

    /**
     * 22. testPropertyCommutativeStateDigest
     * State digest is strictly commutative and order-independent across any insertion permutation.
     */
    @Test
    void testPropertyCommutativeStateDigest() {
        VirtualDevice dev1 = new VirtualDevice("dev-order-1", false);
        VirtualDevice dev2 = new VirtualDevice("dev-order-2", false);
        VirtualDevice dev3 = new VirtualDevice("dev-order-3", false);

        MeshPacket p1 = createTestPacket("p1", "comm-1", 5);
        MeshPacket p2 = createTestPacket("p2", "comm-2", 5);
        MeshPacket p3 = createTestPacket("p3", "comm-3", 5);

        // Permutation 1: [1, 2, 3]
        dev1.hold(p1); dev1.hold(p2); dev1.hold(p3);

        // Permutation 2: [3, 1, 2]
        dev2.hold(p3); dev2.hold(p1); dev2.hold(p2);

        // Permutation 3 with duplicates: [2, 1, 2, 3, 1]
        dev3.hold(p2); dev3.hold(p1); dev3.hold(p2); dev3.hold(p3); dev3.hold(p1);

        assertEquals(dev1.getStateDigest(), dev2.getStateDigest(), "State digest must be order-independent");
        assertEquals(dev1.getStateDigest(), dev3.getStateDigest(), "State digest must be duplicate-invariant");
        assertEquals(dev1.getBucketChecksums(), dev2.getBucketChecksums(), "Bucket checksums must be order-independent");
    }

    /**
     * 23. testPropertyOfflineEscrowCannotBecomeNegative
     * Escrow balance can never drop below zero; overspending transactions are rejected.
     * Invariant verified: I5.
     */
    @Test
    void testPropertyOfflineEscrowCannotBecomeNegative() throws Exception {
        OfflineWalletService.AllocationResult alloc = walletService.allocate("alice@demo", new BigDecimal("50.00"), 24);
        String walletId = alloc.wallet().getWalletId();
        OfflineWalletCertificate cert = alloc.certificate();

        // Valid spend ₹30
        MeshPacket p1 = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("30.00"),
                walletId, 1L, 1L, new BigDecimal("30.00"), cert);
        IngestResult r1 = bridgeIngestion.ingest(p1, "b1", 1);
        assertEquals("SETTLED", r1.outcome());

        // Overspend attempt ₹30 (cumulative ₹60 > ₹50)
        MeshPacket p2 = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("30.00"),
                walletId, 1L, 2L, new BigDecimal("60.00"), cert);
        IngestResult r2 = bridgeIngestion.ingest(p2, "b1", 1);
        assertEquals("INVALID", r2.outcome());
        assertEquals("allocation_exceeded", r2.reason());

        OfflineWallet wallet = walletRepository.findByWalletId(walletId).orElseThrow();
        assertTrue(wallet.getRemainingEscrow().compareTo(BigDecimal.ZERO) >= 0, "Escrow cannot become negative");
        assertEquals(new BigDecimal("20.00"), wallet.getRemainingEscrow());
    }

    /**
     * 24. testPropertyConflictingCounterAlwaysObservable
     * Double-spend counter collision records losing transaction as CONFLICTING and freezes wallet.
     * Invariant verified: I6.
     */
    @Test
    void testPropertyConflictingCounterAlwaysObservable() throws Exception {
        OfflineWalletService.AllocationResult alloc = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24);
        String walletId = alloc.wallet().getWalletId();
        OfflineWalletCertificate cert = alloc.certificate();

        // Conflicting counter 1 spends: Packet A to Bob, Packet B to Carol
        MeshPacket pktA = createSignedOfflinePacket("alice@demo", "bob@demo", new BigDecimal("40.00"),
                walletId, 1L, 1L, new BigDecimal("40.00"), cert);
        MeshPacket pktB = createSignedOfflinePacket("alice@demo", "carol@demo", new BigDecimal("40.00"),
                walletId, 1L, 1L, new BigDecimal("40.00"), cert);

        IngestResult rA = bridgeIngestion.ingest(pktA, "b1", 1);
        assertEquals("SETTLED", rA.outcome());

        IngestResult rB = bridgeIngestion.ingest(pktB, "b2", 1);
        assertEquals("CONFLICTING", rB.outcome());
        assertEquals("double_spend_counter_collision", rB.reason());

        // Wallet is frozen
        OfflineWallet wallet = walletRepository.findByWalletId(walletId).orElseThrow();
        assertEquals(OfflineWallet.WalletStatus.LOCKED_DISPUTED, wallet.getStatus());

        // Both transactions exist in DB
        Transaction txA = transactions.findByPacketHash(pktA.getPacketHash()).orElseThrow();
        Transaction txB = transactions.findByPacketHash(pktB.getPacketHash()).orElseThrow();
        assertEquals(Transaction.Status.SETTLED, txA.getStatus());
        assertEquals(Transaction.Status.CONFLICTING, txB.getStatus());
        assertEquals(txA.getId(), txB.getWinningTransactionId());
    }

    /**
     * 25. testPropertyZeroInvariantViolationsUnderAdverseConditions
     * Complex compound execution verifying zero invariant violations across the entire run.
     */
    @Test
    void testPropertyZeroInvariantViolationsUnderAdverseConditions() throws Exception {
        // Multi-fault execution: drop + retry + delay
        faultInjector.enable();
        faultInjector.failNext(FaultType.DROP, "phone-alice", "phone-stranger1");

        MeshPacket p1 = createSignedPaymentPacket("alice@demo", "bob@demo", new BigDecimal("15.00"));
        mesh.getDevice("phone-alice").hold(p1);

        // Drop push, repair via anti-entropy
        mesh.gossipOnce();
        mesh.syncAntiEntropy();

        // Settle with injected transient retry
        faultInjector.failPacket(FaultType.TRANSIENT_DATABASE_FAILURE, p1.getPacketHash());
        IngestResult res = bridgeIngestion.ingest(p1, "bridge-adv", 1);
        assertEquals("SETTLED", res.outcome());

        // Invariant checks
        assertEquals(0, metrics.getInvariantViolationsTotal(), "Zero invariant violations must occur");
        assertEquals(initialSystemFunds, calculateTotalSystemFunds(), "Total funds must be invariant");
    }
}

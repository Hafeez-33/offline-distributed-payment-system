package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.AntiEntropyService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.MeshSimulatorService;
import com.demo.upimesh.service.VirtualDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 Verification Test Suite:
 * Advanced Gossip & Distributed Synchronization
 *
 * Implements all 15 approved Revision 2 tests.
 */
@SpringBootTest
public class AdvancedGossipSyncTest {

    @Autowired private AntiEntropyService antiEntropyService;
    @Autowired private MeshSimulatorService meshSimulator;
    @Autowired private DemoService demoService;
    @Autowired private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        meshSimulator.resetMesh();
    }

    private MeshPacket createTestPacket(String id, String content, int ttl) {
        MeshPacket p = new MeshPacket();
        p.setPacketId(id);
        p.setTtl(ttl);
        p.setCreatedAt(Instant.now().toEpochMilli());
        p.setCiphertext(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        return p;
    }

    // 1. identicalPeersProduceImmediateDigestMatchWithoutTransfers
    @Test
    void identicalPeersProduceImmediateDigestMatchWithoutTransfers() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        MeshPacket p1 = createTestPacket("p1", "payload-1", 5);
        MeshPacket p2 = createTestPacket("p2", "payload-2", 5);

        devA.hold(p1);
        devA.hold(p2);
        devB.hold(p1);
        devB.hold(p2);

        assertEquals(devA.getStateDigest(), devB.getStateDigest());

        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncPair(devA, devB);
        assertTrue(res.wasInSync());
        assertEquals(0, res.totalTransfers());
        assertEquals(0, res.divergentBucketsCount());
    }

    // 2. singleMissingPacketRepairedViaAntiEntropy
    @Test
    void singleMissingPacketRepairedViaAntiEntropy() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        MeshPacket p1 = createTestPacket("p1", "payload-1", 5);
        MeshPacket p2 = createTestPacket("p2", "payload-2", 5);

        devA.hold(p1);
        devA.hold(p2);
        devB.hold(p1); // missing p2

        assertNotEquals(devA.getStateDigest(), devB.getStateDigest());

        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncPair(devA, devB);
        assertFalse(res.wasInSync());
        assertEquals(0, res.packetsTransferredToLocal());
        assertEquals(1, res.packetsTransferredToRemote());
        assertEquals(1, res.totalTransfers());

        assertTrue(devB.holds(p2.getPacketHash()));
        assertEquals(devA.getStateDigest(), devB.getStateDigest());
    }

    // 3. biDirectionalMissingPacketsRepairedSimultaneously
    @Test
    void biDirectionalMissingPacketsRepairedSimultaneously() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        MeshPacket p1 = createTestPacket("p1", "payload-1", 5);
        MeshPacket p2 = createTestPacket("p2", "payload-2", 5);
        MeshPacket p3 = createTestPacket("p3", "payload-3", 5);

        devA.hold(p1);
        devA.hold(p2);

        devB.hold(p2);
        devB.hold(p3);

        assertNotEquals(devA.getStateDigest(), devB.getStateDigest());

        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncPair(devA, devB);
        assertFalse(res.wasInSync());
        assertEquals(1, res.packetsTransferredToLocal()); // p3 to devA
        assertEquals(1, res.packetsTransferredToRemote()); // p1 to devB
        assertEquals(2, res.totalTransfers());

        assertTrue(devA.holds(p3.getPacketHash()));
        assertTrue(devB.holds(p1.getPacketHash()));
        assertEquals(3, devA.packetCount());
        assertEquals(3, devB.packetCount());
        assertEquals(devA.getStateDigest(), devB.getStateDigest());
    }

    // 4. duplicateSyncMessageSuppression
    @Test
    void duplicateSyncMessageSuppression() {
        VirtualDevice dev = new VirtualDevice("dev-dup", false);
        MeshPacket p = createTestPacket("p-dup", "payload-dup", 5);

        boolean first = dev.hold(p);
        assertTrue(first);
        assertEquals(1, dev.packetCount());

        boolean second = dev.hold(p);
        assertFalse(second);
        assertEquals(1, dev.packetCount());
    }

    // 5. packetLossRecoveredBySubsequentAntiEntropy
    @Test
    void packetLossRecoveredBySubsequentAntiEntropy() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        MeshPacket p = createTestPacket("p-lost", "payload-lost", 5);
        devA.hold(p);
        // devB simulated drop: holds nothing

        assertEquals(0, devB.packetCount());
        assertNotEquals(devA.getStateDigest(), devB.getStateDigest());

        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncPair(devA, devB);
        assertEquals(1, res.totalTransfers());
        assertTrue(devB.holds(p.getPacketHash()));
        assertEquals(devA.getStateDigest(), devB.getStateDigest());
    }

    // 6. delayedSyncResponseHandledWithoutDeadlock
    @Test
    void delayedSyncResponseHandledWithoutDeadlock() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        MeshPacket p1 = createTestPacket("p1", "payload-1", 5);
        devA.hold(p1);

        // Run sync round 1
        antiEntropyService.syncPair(devA, devB);
        assertEquals(devA.getStateDigest(), devB.getStateDigest());

        // Introduce new packet while holding prior state
        MeshPacket p2 = createTestPacket("p2", "payload-2", 5);
        devA.hold(p2);

        // Run sync round 2 without deadlock
        AntiEntropyService.PairwiseSyncResult res2 = antiEntropyService.syncPair(devA, devB);
        assertEquals(1, res2.totalTransfers());
        assertEquals(devA.getStateDigest(), devB.getStateDigest());
    }

    // 7. alternatePeerSelectedWhenSyncTargetTimesOut
    @Test
    void alternatePeerSelectedWhenSyncTargetTimesOut() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false); // primary, will be severed
        VirtualDevice devC = new VirtualDevice("dev-C", false); // alternate

        MeshPacket p = createTestPacket("p-alt", "payload-alt", 5);
        devC.hold(p);

        List<VirtualDevice> candidates = List.of(devB, devC);

        // Reachability: devA <-> devB is severed, devA <-> devC is reachable
        java.util.function.BiPredicate<String, String> reachability = (x, y) -> {
            if ((x.equals("dev-A") && y.equals("dev-B")) || (x.equals("dev-B") && y.equals("dev-A"))) {
                return false;
            }
            return true;
        };

        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncWithFallback(
                devA, "dev-B", candidates, reachability);

        assertEquals("dev-C", res.remoteNodeId());
        assertTrue(devA.holds(p.getPacketHash()));
        assertEquals(devA.getStateDigest(), devC.getStateDigest());
    }

    // 8. nodeRestartReSyncsBufferFromPeersWithoutAlteringBackend
    @Test
    void nodeRestartReSyncsBufferFromPeersWithoutAlteringBackend() {
        Account alice = accountRepository.findById("alice@demo").orElseThrow();
        BigDecimal balanceBefore = alice.getBalance();

        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        MeshPacket p1 = createTestPacket("p1", "payload-1", 5);
        MeshPacket p2 = createTestPacket("p2", "payload-2", 5);
        devA.hold(p1);
        devA.hold(p2);
        devB.hold(p1);
        devB.hold(p2);

        // Simulate volatile restart of devA: in-memory store cleared
        devA.clear();
        assertEquals(0, devA.packetCount());

        // Re-sync from devB
        antiEntropyService.syncPair(devA, devB);
        assertEquals(2, devA.packetCount());
        assertEquals(devB.getStateDigest(), devA.getStateDigest());

        // Authoritative backend account balance remains completely unaffected
        Account aliceAfter = accountRepository.findById("alice@demo").orElseThrow();
        assertEquals(balanceBefore, aliceAfter.getBalance());
    }

    // 9. networkPartitionMaintainsSubMeshConsistency
    @Test
    void networkPartitionMaintainsSubMeshConsistency() {
        VirtualDevice nA = new VirtualDevice("node-A", false);
        VirtualDevice nB = new VirtualDevice("node-B", false);
        VirtualDevice nC = new VirtualDevice("node-C", false);
        VirtualDevice nD = new VirtualDevice("node-D", false);

        MeshPacket pAlpha = createTestPacket("pAlpha", "submesh-alpha", 5);
        MeshPacket pBeta = createTestPacket("pBeta", "submesh-beta", 5);

        nA.hold(pAlpha);
        nC.hold(pBeta);

        // Sync within partition 1 (A <-> B)
        antiEntropyService.syncPair(nA, nB);
        // Sync within partition 2 (C <-> D)
        antiEntropyService.syncPair(nC, nD);

        // Submesh 1 consistent with pAlpha
        assertEquals(nA.getStateDigest(), nB.getStateDigest());
        assertTrue(nB.holds(pAlpha.getPacketHash()));
        assertFalse(nB.holds(pBeta.getPacketHash()));

        // Submesh 2 consistent with pBeta
        assertEquals(nC.getStateDigest(), nD.getStateDigest());
        assertTrue(nD.holds(pBeta.getPacketHash()));
        assertFalse(nD.holds(pAlpha.getPacketHash()));

        assertNotEquals(nA.getStateDigest(), nC.getStateDigest());
    }

    // 10. partitionHealTriggersCompleteBiDirectionalConvergence
    @Test
    void partitionHealTriggersCompleteBiDirectionalConvergence() {
        MeshSimulatorService sim = new MeshSimulatorService(antiEntropyService);

        VirtualDevice nA = sim.getDevice("phone-alice");
        VirtualDevice nB = sim.getDevice("phone-stranger1");
        VirtualDevice nC = sim.getDevice("phone-stranger2");

        // Partition [phone-alice, phone-stranger1] from [phone-stranger2, phone-stranger3, phone-bridge]
        sim.partitionSubmeshes(
                List.of("phone-alice", "phone-stranger1"),
                List.of("phone-stranger2", "phone-stranger3", "phone-bridge")
        );

        MeshPacket p1 = createTestPacket("p1", "alice-data", 5);
        MeshPacket p2 = createTestPacket("p2", "stranger2-data", 5);

        nA.hold(p1);
        nC.hold(p2);

        // Sync submeshes independently
        sim.syncAntiEntropy();
        assertTrue(nB.holds(p1.getPacketHash()));
        assertFalse(nC.holds(p1.getPacketHash()));

        // Heal partition
        sim.healAll();
        assertFalse(sim.isMeshFullyConverged());

        // Sync post-heal
        sim.syncAntiEntropy();
        assertTrue(sim.isMeshFullyConverged());
        assertTrue(nC.holds(p1.getPacketHash()));
        assertTrue(nA.holds(p2.getPacketHash()));
        assertEquals(nA.getStateDigest(), nC.getStateDigest());
    }

    // 11. concurrentTransactionsDuringPartitionSynchronizeOnHeal
    @Test
    void concurrentTransactionsDuringPartitionSynchronizeOnHeal() throws Exception {
        MeshSimulatorService sim = new MeshSimulatorService(antiEntropyService);

        // Partition mesh into 2 submeshes: [alice, stranger1] vs [stranger2, stranger3, bridge]
        sim.partitionSubmeshes(
                List.of("phone-alice", "phone-stranger1"),
                List.of("phone-stranger2", "phone-stranger3", "phone-bridge")
        );

        MeshPacket pktAlice = demoService.createPacket("alice@demo", "bob@demo", new BigDecimal("10.00"), "1234", 5);
        MeshPacket pktCarol = demoService.createPacket("carol@demo", "dave@demo", new BigDecimal("15.00"), "1234", 5);

        sim.inject("phone-alice", pktAlice);
        sim.inject("phone-stranger2", pktCarol);

        // Run partitioned sync
        sim.syncAntiEntropy();

        VirtualDevice bridge = sim.getDevice("phone-bridge");
        VirtualDevice alice = sim.getDevice("phone-alice");

        assertTrue(bridge.holds(pktCarol.getPacketHash()));
        assertFalse(bridge.holds(pktAlice.getPacketHash()));

        // Heal network
        sim.healAll();

        // Run reconciliation
        sim.syncAntiEntropy();

        assertTrue(bridge.holds(pktAlice.getPacketHash()));
        assertTrue(alice.holds(pktCarol.getPacketHash()));
        assertEquals(alice.getStateDigest(), bridge.getStateDigest());
    }

    // 12. prefixBucketDigestPinpointsDivergentSlices
    @Test
    void prefixBucketDigestPinpointsDivergentSlices() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        MeshPacket p1 = createTestPacket("p1", "content-A", 5);
        devA.hold(p1);
        devB.hold(p1);

        assertEquals(devA.getStateDigest(), devB.getStateDigest());

        // Add a packet to devA that falls into a specific prefix bucket
        MeshPacket p2 = createTestPacket("p2", "content-B-unique", 5);
        devA.hold(p2);

        int targetBucket = VirtualDevice.getBucketIndex(p2.getPacketHash());

        // Verify that only the target bucket differs between devA and devB
        List<String> bA = devA.getBucketChecksums();
        List<String> bB = devB.getBucketChecksums();

        int diffCount = 0;
        for (int i = 0; i < 16; i++) {
            if (!bA.get(i).equals(bB.get(i))) {
                diffCount++;
                assertEquals(targetBucket, i);
            }
        }
        assertEquals(1, diffCount);

        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncPair(devA, devB);
        assertEquals(1, res.divergentBucketsCount());
        assertEquals(1, res.totalTransfers());
        assertEquals(devA.getStateDigest(), devB.getStateDigest());
    }

    // 13. mathematicalConvergenceAchievedAcrossAllDevices
    @Test
    void mathematicalConvergenceAchievedAcrossAllDevices() {
        MeshSimulatorService sim = new MeshSimulatorService(antiEntropyService);

        MeshPacket p1 = createTestPacket("p1", "data-1", 5);
        MeshPacket p2 = createTestPacket("p2", "data-2", 5);
        MeshPacket p3 = createTestPacket("p3", "data-3", 5);

        sim.inject("phone-alice", p1);
        sim.inject("phone-stranger1", p2);
        sim.inject("phone-stranger3", p3);

        assertFalse(sim.isMeshFullyConverged());

        // Run anti-entropy
        sim.syncAntiEntropy();

        assertTrue(sim.isMeshFullyConverged());

        String canonicalDigest = sim.getDevice("phone-alice").getStateDigest();
        for (VirtualDevice d : sim.getDevices()) {
            assertEquals(canonicalDigest, d.getStateDigest());
            assertEquals(3, d.packetCount());
        }
    }

    // 14. ttlExhaustionDoesNotPreventAntiEntropyRepair
    @Test
    void ttlExhaustionDoesNotPreventAntiEntropyRepair() {
        MeshSimulatorService sim = new MeshSimulatorService(antiEntropyService);
        VirtualDevice alice = sim.getDevice("phone-alice");
        VirtualDevice bridge = sim.getDevice("phone-bridge");

        // Packet with TTL = 0
        MeshPacket p = createTestPacket("p-zero-ttl", "expired-push-ttl", 0);
        alice.hold(p);

        // Epidemic push should NOT forward it because TTL <= 0
        var pushResult = sim.gossipOnce();
        assertEquals(0, pushResult.transfers());
        assertFalse(bridge.holds(p.getPacketHash()));

        // Anti-entropy pull MUST repair it regardless of push TTL!
        var syncResult = sim.syncAntiEntropy();
        assertTrue(syncResult.totalTransfers() > 0);
        assertTrue(bridge.holds(p.getPacketHash()));
        assertEquals(alice.getStateDigest(), bridge.getStateDigest());
    }

    // 15. largeBatchSynchronizationRespectsPagingLimits
    @Test
    void largeBatchSynchronizationRespectsPagingLimits() {
        VirtualDevice devA = new VirtualDevice("dev-A", false);
        VirtualDevice devB = new VirtualDevice("dev-B", false);

        // Populate devA with 120 packets
        for (int i = 0; i < 120; i++) {
            devA.hold(createTestPacket("batch-p-" + i, "large-batch-payload-" + i, 5));
        }

        assertEquals(120, devA.packetCount());
        assertEquals(0, devB.packetCount());

        // Sync with max batch size 50
        AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncPair(devA, devB, 50);

        assertEquals(120, res.totalTransfers());
        assertEquals(120, devB.packetCount());
        assertEquals(devA.getStateDigest(), devB.getStateDigest());
    }
}

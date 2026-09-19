package com.demo.upimesh.mesh.sync

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.mesh.digest.StateDigestBuilder
import com.demo.upimesh.mesh.model.BucketChecksumsPayload
import com.demo.upimesh.mesh.session.MeshSession
import com.demo.upimesh.mesh.store.InMemoryMeshPacketStore
import com.demo.upimesh.mesh.store.MeshPacketData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PartitionHealConvergenceTest {

    private fun sha256Hex(text: String): String {
        return PacketHasher.hashCiphertext(text).lowercase()
    }

    private fun createPacket(id: String, content: String): MeshPacketData {
        val hash = sha256Hex(content)
        return MeshPacketData(
            packetHash = hash,
            packetId = id,
            ciphertext = content,
            ttl = 3,
            hopCount = 0
        )
    }

    /**
     * Executes a deterministic pairwise reconciliation round between Peer 1 and Peer 2.
     */
    private fun reconcilePeers(sync1: MeshSynchronizer, sync2: MeshSynchronizer) {
        val session1 = MeshSession(peerDeviceId = "peer-2", isInitiator = true)
        val session2 = MeshSession(peerDeviceId = "peer-1", isInitiator = false)

        // 1. Peer 1 sends StateSummary to Peer 2
        val summary1 = sync1.createLocalStateSummary()
        val step1Resp = sync2.processRemoteStateSummary(session2, summary1)

        if (step1Resp is BucketChecksumsPayload) {
            // 2. Peer 1 receives Peer 2's bucket checksums and produces offers
            val offersFrom1 = sync1.processRemoteBucketChecksums(session1, step1Resp)

            for (offer in offersFrom1) {
                // Peer 2 processes hash offer from Peer 1
                val requestFrom2 = sync2.processBucketHashOffer(session2, offer)
                if (requestFrom2 != null) {
                    val packetsFrom1 = sync1.fulfillSyncRequest(session1, requestFrom2)
                    for (pkt in packetsFrom1) {
                        sync2.ingestReceivedPacket(session2, pkt)
                    }
                }
            }
        }

        // 3. Complete session
        sync1.finishSync(session1)
        sync2.finishSync(session2)
    }

    @Test
    fun testPartitionHealEventualConvergence() {
        val storeA = InMemoryMeshPacketStore()
        val storeB = InMemoryMeshPacketStore()

        // 1. Initial common set S (3 packets)
        for (i in 1..3) {
            val pkt = createPacket("s-$i", "ciphertext-common-s-$i")
            storeA.addLocalPacket(pkt)
            storeB.addLocalPacket(pkt)
        }

        // Initial verify: identical digests
        val initialDigestA = StateDigestBuilder.calculateDigest(storeA.getAllKnownPacketHashes())
        val initialDigestB = StateDigestBuilder.calculateDigest(storeB.getAllKnownPacketHashes())
        assertEquals(initialDigestA, initialDigestB)

        // 2. Partition: Peer A receives X (2 packets), Peer B receives Y (2 packets)
        val pktX1 = createPacket("x-1", "ciphertext-x-1")
        val pktX2 = createPacket("x-2", "ciphertext-x-2")
        storeA.addLocalPacket(pktX1)
        storeA.addLocalPacket(pktX2)

        val pktY1 = createPacket("y-1", "ciphertext-y-1")
        val pktY2 = createPacket("y-2", "ciphertext-y-2")
        storeB.addLocalPacket(pktY1)
        storeB.addLocalPacket(pktY2)

        // During partition: digests mismatch
        val partitionedDigestA = StateDigestBuilder.calculateDigest(storeA.getAllKnownPacketHashes())
        val partitionedDigestB = StateDigestBuilder.calculateDigest(storeB.getAllKnownPacketHashes())
        assertNotEquals(partitionedDigestA, partitionedDigestB)

        // 3. Reconnect / Heal: Anti-entropy reconciliation
        val syncA = MeshSynchronizer(storeA)
        val syncB = MeshSynchronizer(storeB)

        // Reconcile: A pulls missing from B & B pulls missing from A
        reconcilePeers(syncA, syncB) // Syncs from A to B
        reconcilePeers(syncB, syncA) // Syncs from B to A

        // 4. Final verification: Both peers hold exactly 7 packets (3 Common + 2 X + 2 Y)
        val finalHashesA = storeA.getAllKnownPacketHashes()
        val finalHashesB = storeB.getAllKnownPacketHashes()

        assertEquals(7, finalHashesA.size)
        assertEquals(7, finalHashesB.size)
        assertEquals(finalHashesA, finalHashesB)

        val finalDigestA = StateDigestBuilder.calculateDigest(finalHashesA)
        val finalDigestB = StateDigestBuilder.calculateDigest(finalHashesB)
        assertEquals(finalDigestA, finalDigestB, "Both devices must reach identical state digest after healing")
    }

    @Test
    fun testReconciliationOverLargeBatchesConverges() {
        val storeA = InMemoryMeshPacketStore()
        val storeB = InMemoryMeshPacketStore()

        // 60 packets on B (exceeds single batch size of 50)
        for (i in 1..60) {
            val pkt = createPacket("b-$i", "bulk-ciphertext-b-$i")
            storeB.addLocalPacket(pkt)
        }

        val syncA = MeshSynchronizer(storeA)
        val syncB = MeshSynchronizer(storeB)

        reconcilePeers(syncA, syncB)
        reconcilePeers(syncB, syncA)

        val finalHashesA = storeA.getAllKnownPacketHashes()
        val finalHashesB = storeB.getAllKnownPacketHashes()

        assertEquals(60, finalHashesA.size)
        assertEquals(60, finalHashesB.size)
        assertEquals(finalHashesA, finalHashesB)
    }
}

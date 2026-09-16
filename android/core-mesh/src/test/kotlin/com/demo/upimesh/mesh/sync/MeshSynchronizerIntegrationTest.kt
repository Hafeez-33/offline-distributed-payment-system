package com.demo.upimesh.mesh.sync

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.mesh.model.*
import com.demo.upimesh.mesh.session.MeshSession
import com.demo.upimesh.mesh.session.MeshSyncState
import com.demo.upimesh.mesh.store.InMemoryMeshPacketStore
import com.demo.upimesh.mesh.store.MeshPacketData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MeshSynchronizerIntegrationTest {

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

    @Test
    fun testInstantExitWhenStateDigestsMatch() {
        val storeA = InMemoryMeshPacketStore()
        val storeB = InMemoryMeshPacketStore()

        val p1 = createPacket("p1", "encrypted_payload_1")
        val p2 = createPacket("p2", "encrypted_payload_2")

        storeA.addLocalPacket(p1)
        storeA.addLocalPacket(p2)
        storeB.addLocalPacket(p1)
        storeB.addLocalPacket(p2)

        val syncA = MeshSynchronizer(storeA)
        val sessionA = MeshSession(peerDeviceId = "peer-B", isInitiator = true)

        val summaryB = MeshSynchronizer(storeB).createLocalStateSummary()
        val response = syncA.processRemoteStateSummary(sessionA, summaryB)

        assertTrue(response is SyncAckPayload)
        assertEquals(MeshSyncState.COMPLETED, sessionA.state)
        assertEquals("SUCCESS", (response as SyncAckPayload).status)
    }

    @Test
    fun testReconciliationFlowForMissingPackets() {
        val storeA = InMemoryMeshPacketStore()
        val storeB = InMemoryMeshPacketStore()

        val common = createPacket("p-common", "ciphertext-common")
        val uniqueB = createPacket("p-b-unique", "ciphertext-b-unique")

        storeA.addLocalPacket(common)
        storeB.addLocalPacket(common)
        storeB.addLocalPacket(uniqueB)

        val syncA = MeshSynchronizer(storeA)
        val syncB = MeshSynchronizer(storeB)

        val sessionA = MeshSession(peerDeviceId = "peer-B", isInitiator = true)

        // 1. Peer B sends StateSummary to A
        val summaryB = syncB.createLocalStateSummary()
        val step1Resp = syncA.processRemoteStateSummary(sessionA, summaryB)
        assertTrue(step1Resp is BucketChecksumsPayload, "Must request bucket comparison on digest mismatch")
        assertEquals(MeshSyncState.BUCKET_COMPARISON, sessionA.state)

        // 2. Peer B receives A's bucket checksums and produces hash offers for divergent buckets
        val sessionB = MeshSession(peerDeviceId = "peer-A", isInitiator = false)
        val step2Offers = syncB.processRemoteBucketChecksums(sessionB, step1Resp as BucketChecksumsPayload)
        assertEquals(1, step2Offers.size)

        // 3. Peer A processes B's hash offer and generates a SyncRequest
        val syncRequest = syncA.processBucketHashOffer(sessionA, step2Offers[0])
        assertNotNull(syncRequest)
        assertEquals(listOf(uniqueB.packetHash), syncRequest!!.requestedPacketHashes)
        assertEquals(MeshSyncState.REQUESTING_PACKETS, sessionA.state)

        // 4. Peer B fulfills the SyncRequest
        val fulfilledPackets = syncB.fulfillSyncRequest(sessionB, syncRequest)
        assertEquals(1, fulfilledPackets.size)
        assertEquals(uniqueB.packetHash, fulfilledPackets[0].packetHash)

        // 5. Peer A ingests the packet
        val ingested = syncA.ingestReceivedPacket(sessionA, fulfilledPackets[0])
        assertTrue(ingested)
        assertEquals(MeshSyncState.RECEIVING_PACKETS, sessionA.state)

        // 6. Peer A completes the sync
        val syncAck = syncA.finishSync(sessionA)
        assertEquals(MeshSyncState.COMPLETED, sessionA.state)
        assertEquals(listOf(uniqueB.packetHash), syncAck.syncedPacketHashes)

        // Final verification: store A now contains uniqueB
        assertNotNull(storeA.getPacketData(uniqueB.packetHash))
    }

    @Test
    fun testCorruptedCiphertextFailsIntegrityCheck() {
        val store = InMemoryMeshPacketStore()
        val sync = MeshSynchronizer(store)
        val session = MeshSession(peerDeviceId = "peer-X", isInitiator = true)

        val validPacket = createPacket("p-valid", "valid_ciphertext")
        val corrupted = validPacket.copy(ciphertext = "corrupted_tampered_content")

        assertThrows(IllegalArgumentException::class.java) {
            sync.ingestReceivedPacket(session, corrupted)
        }
    }

    @Test
    fun testIdempotentRepeatedPacketIngestion() {
        val store = InMemoryMeshPacketStore()
        val sync = MeshSynchronizer(store)
        val session = MeshSession(peerDeviceId = "peer-X", isInitiator = true)

        val packet = createPacket("p1", "content_1")
        val firstResult = sync.ingestReceivedPacket(session, packet)
        val secondResult = sync.ingestReceivedPacket(session, packet)

        assertTrue(firstResult, "First ingestion should be new")
        assertFalse(secondResult, "Second ingestion must be deduplicated / idempotent")
    }
}

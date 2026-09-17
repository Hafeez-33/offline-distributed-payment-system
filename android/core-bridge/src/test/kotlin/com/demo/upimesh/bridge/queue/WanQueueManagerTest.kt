package com.demo.upimesh.bridge.queue

import com.demo.upimesh.bridge.role.BridgeConfig
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.entity.ReceivedPacket
import com.demo.upimesh.db.entity.ReceivedPacketStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WanQueueManagerTest {

    private lateinit var db: UpiMeshDatabase
    private lateinit var queueManager: WanQueueManager

    @BeforeEach
    fun setUp() {
        db = UpiMeshDatabase.inMemory()
        queueManager = WanQueueManager(db, BridgeConfig(maxBatchSize = 50, maxQueueDepth = 1000))
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun testEmptyQueue() {
        val batch = queueManager.getNextBatch()
        assertTrue(batch.isEmpty())
        assertEquals(0, queueManager.getQueueDepth())
    }

    @Test
    fun testPendingPacketsRetrievedUpToLimit() {
        for (i in 1..60) {
            val hash = "hash_%04d".format(i)
            db.receivedPacketDao.insert(
                ReceivedPacket(
                    packetHash = hash,
                    packetId = "pkt_$i",
                    ciphertext = "cipher_$i",
                    ttl = 3,
                    hopCount = 1,
                    receivedAt = 1000L + i,
                    uploadedToBridge = false,
                    status = ReceivedPacketStatus.STORED
                )
            )
        }

        assertEquals(60, queueManager.getQueueDepth())

        val batch1 = queueManager.getNextBatch(50)
        assertEquals(50, batch1.size)
        assertEquals("hash_0001", batch1[0].packetHash)

        // Custom limit
        val batch2 = queueManager.getNextBatch(10)
        assertEquals(10, batch2.size)
    }

    @Test
    fun testInFlightLeaseExclusion() {
        val hash1 = "hash_alpha"
        val hash2 = "hash_beta"

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = hash1,
                packetId = "p1",
                ciphertext = "c1",
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )
        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = hash2,
                packetId = "p2",
                ciphertext = "c2",
                ttl = 3,
                hopCount = 1,
                receivedAt = 2000L,
                uploadedToBridge = false
            )
        )

        assertTrue(queueManager.tryAcquireInFlight(hash1))
        assertFalse(queueManager.tryAcquireInFlight(hash1)) // Already leased!
        assertEquals(1, queueManager.getInFlightCount())

        // getNextBatch should exclude in-flight items
        val batch = queueManager.getNextBatch()
        assertEquals(1, batch.size)
        assertEquals(hash2, batch[0].packetHash)

        // Release lease
        queueManager.releaseInFlight(hash1)
        assertEquals(0, queueManager.getInFlightCount())

        val batchAfterRelease = queueManager.getNextBatch()
        assertEquals(2, batchAfterRelease.size)
    }

    @Test
    fun testMarkUploadedUpdatesPersistenceAndQueueDepth() {
        val hash = "hash_settled"
        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = hash,
                packetId = "p1",
                ciphertext = "c1",
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        queueManager.tryAcquireInFlight(hash)
        val success = queueManager.markUploaded(hash)
        assertTrue(success)

        val updated = db.receivedPacketDao.getPacket(hash)
        assertNotNull(updated)
        assertTrue(updated!!.uploadedToBridge)

        val batch = queueManager.getNextBatch()
        assertTrue(batch.isEmpty())
        assertEquals(0, queueManager.getQueueDepth())
    }
}

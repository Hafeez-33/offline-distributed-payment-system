package com.demo.upimesh.bridge.queue

import com.demo.upimesh.bridge.metrics.WanBridgeMetrics
import com.demo.upimesh.bridge.role.BridgeConfig
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.entity.ReceivedPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages durable upload queue retrieval, bounded batching, and in-flight concurrency gating.
 */
class WanQueueManager(
    private val database: UpiMeshDatabase,
    private val config: BridgeConfig = BridgeConfig()
) {
    // In-flight concurrency gate to prevent concurrent workers from uploading the same packet simultaneously
    private val inFlightHashes = ConcurrentHashMap.newKeySet<String>()

    /**
     * Retrieves up to [batchLimit] un-uploaded packets from Room, filtering out in-flight items.
     */
    fun getNextBatch(batchLimit: Int = config.maxBatchSize): List<ReceivedPacket> {
        val effectiveLimit = minOf(batchLimit, config.maxBatchSize)
        val pending = database.receivedPacketDao.getPendingBridgePackets()
        WanBridgeMetrics.setQueueDepth(pending.size.toLong())

        val available = pending
            .filter { !inFlightHashes.contains(it.packetHash) }
            .take(effectiveLimit)

        return available
    }

    /**
     * Attempts to acquire an in-flight lease for a packet hash.
     * Returns true if leased, false if already in-flight.
     */
    fun tryAcquireInFlight(packetHash: String): Boolean {
        return inFlightHashes.add(packetHash)
    }

    /**
     * Releases an in-flight lease.
     */
    fun releaseInFlight(packetHash: String) {
        inFlightHashes.remove(packetHash)
    }

    /**
     * Clears all in-flight leases (e.g. on worker restart or recovery).
     */
    fun clearInFlight() {
        inFlightHashes.clear()
    }

    /**
     * Marks a packet as uploaded in Room persistence and releases its in-flight lease.
     */
    fun markUploaded(packetHash: String, updatedAt: Long = System.currentTimeMillis()): Boolean {
        inFlightHashes.remove(packetHash)
        val rows = database.receivedPacketDao.markUploadedToBridge(packetHash, updatedAt)
        val pending = database.receivedPacketDao.getPendingBridgePackets().size
        WanBridgeMetrics.setQueueDepth(pending.toLong())
        return rows > 0
    }

    /**
     * Returns the current count of un-uploaded packets in Room.
     */
    fun getQueueDepth(): Int {
        val depth = database.receivedPacketDao.getPendingBridgePackets().size
        WanBridgeMetrics.setQueueDepth(depth.toLong())
        return depth
    }

    /**
     * Returns the count of items currently in-flight.
     */
    fun getInFlightCount(): Int = inFlightHashes.size
}

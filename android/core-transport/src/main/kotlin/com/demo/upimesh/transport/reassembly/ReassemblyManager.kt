package com.demo.upimesh.transport.reassembly

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.entity.PacketFragment
import com.demo.upimesh.db.entity.ReceivedPacket
import com.demo.upimesh.db.entity.ReceivedPacketStatus
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import com.demo.upimesh.transport.abstraction.SystemTimeProvider
import com.demo.upimesh.transport.abstraction.TimeProvider
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.frame.BleFrameConstants
import com.demo.upimesh.transport.limits.TransportLimits
import com.demo.upimesh.transport.metrics.BleTransportMetrics
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages chunk reassembly of multi-fragment BLE packets.
 *
 * Integrated directly with Room's PacketFragment and ReceivedPacket persistence.
 *
 * SAFETY INVARIANT:
 * Packet reception over BLE is strictly an untrusted transport operation.
 * It NEVER increments settledAmountPaisa and NEVER claims authoritative settlement.
 */
class ReassemblyManager(
    private val database: UpiMeshDatabase,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val metrics: BleTransportMetrics = BleTransportMetrics(),
    private val timeoutMs: Long = TransportLimits.DEFAULT_REASSEMBLY_TIMEOUT_MS
) {

    /**
     * In-flight reassembly tracking buffer state.
     */
    data class ReassemblySession(
        val transferId: Int,
        val packetHashHex: String,
        val totalFragments: Int,
        val fragments: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap(),
        val createdAt: Long,
        var lastUpdatedAt: Long,
        var totalBytes: Int = 0
    ) {
        val isComplete: Boolean
            get() = fragments.size == totalFragments && (0 until totalFragments).all { fragments.containsKey(it) }

        fun getMissingIndices(): List<Int> {
            val missing = mutableListOf<Int>()
            for (i in 0 until totalFragments) {
                if (!fragments.containsKey(i)) {
                    missing.add(i)
                }
            }
            return missing
        }
    }

    private val activeSessions = ConcurrentHashMap<String, ReassemblySession>()
    private val totalMemoryAllocated = AtomicLong(0)

    /**
     * Processes an incoming BLE frame chunk.
     *
     * @param frame Received frame
     * @param expectedFullPacketHash Optional full 64-char packet hash if known in advance
     * @return Completed ReceivedPacket if this frame completes reassembly, null otherwise
     * @throws BleProtocolException on conflicting duplicates, resource limit exceeded, or integrity mismatch
     */
    fun processFrame(frame: BleFrame, expectedFullPacketHash: String? = null): ReceivedPacket? {
        val header = frame.header
        val fragmentIndex = header.fragmentIndex
        val totalFragments = header.totalFragments
        val transferId = header.transferId
        val chunkData = frame.payload

        // Periodic timeout cleanup
        cleanExpiredSessions()

        // Derive packet hash identifier (either full expected hash or 6-char hex prefix)
        val prefixHex = bytesToHex(header.packetHashPrefix)
        val sessionKey = expectedFullPacketHash ?: "prefix_$prefixHex"

        // Handle Single Fragment fast-path
        if (totalFragments == 1 && fragmentIndex == 0) {
            return processSingleFragmentPacket(frame, expectedFullPacketHash)
        }

        // Check active session limits
        if (!activeSessions.containsKey(sessionKey) && activeSessions.size >= TransportLimits.MAX_ACTIVE_REASSEMBLY_BUFFERS) {
            throw BleProtocolException(
                BleProtocolError.OVERSIZED_PAYLOAD,
                "Maximum active reassembly buffers (${TransportLimits.MAX_ACTIVE_REASSEMBLY_BUFFERS}) exceeded"
            )
        }

        val session = activeSessions.computeIfAbsent(sessionKey) {
            metrics.recordReassemblyStarted()
            ReassemblySession(
                transferId = transferId,
                packetHashHex = sessionKey,
                totalFragments = totalFragments,
                createdAt = timeProvider.currentTimeMillis(),
                lastUpdatedAt = timeProvider.currentTimeMillis()
            )
        }

        synchronized(session) {
            session.lastUpdatedAt = timeProvider.currentTimeMillis()

            // Validate total fragments consistency
            if (session.totalFragments != totalFragments) {
                throw BleProtocolException(
                    BleProtocolError.EXCESSIVE_FRAGMENTS,
                    "Total fragments mismatch for transfer $transferId (existing ${session.totalFragments}, incoming $totalFragments)"
                )
            }

            // Duplicate Detection
            val existingData = session.fragments[fragmentIndex]
            if (existingData != null) {
                if (existingData.contentEquals(chunkData)) {
                    // Idempotent duplicate: safe, do nothing
                    return null
                } else {
                    // Conflicting duplicate: data corruption or protocol violation
                    throw BleProtocolException(
                        BleProtocolError.DUPLICATE_FRAME,
                        "Conflicting chunk data for fragment $fragmentIndex of transfer $transferId"
                    )
                }
            }

            // Memory Budget Check
            val newMemory = totalMemoryAllocated.addAndGet(chunkData.size.toLong())
            if (newMemory > TransportLimits.MAX_REASSEMBLY_MEMORY_BYTES) {
                totalMemoryAllocated.addAndGet(-chunkData.size.toLong())
                throw BleProtocolException(
                    BleProtocolError.OVERSIZED_PAYLOAD,
                    "Total reassembly memory budget (${TransportLimits.MAX_REASSEMBLY_MEMORY_BYTES} bytes) exceeded"
                )
            }

            session.totalBytes += chunkData.size
            if (session.totalBytes > TransportLimits.MAX_PACKET_TRANSFER_BYTES) {
                throw BleProtocolException(
                    BleProtocolError.OVERSIZED_PAYLOAD,
                    "Transfer size (${session.totalBytes} bytes) exceeds maximum packet transfer limit (${TransportLimits.MAX_PACKET_TRANSFER_BYTES} bytes)"
                )
            }

            // Buffer in memory
            session.fragments[fragmentIndex] = chunkData

            // Persist fragment chunk into Room for durability across crashes
            val fragmentEntity = PacketFragment(
                packetHash = sessionKey,
                chunkIndex = fragmentIndex,
                totalChunks = totalFragments,
                data = chunkData,
                receivedAt = timeProvider.currentTimeMillis()
            )
            database.packetFragmentDao.saveFragment(fragmentEntity)

            // Check if all fragments have arrived
            if (session.isComplete) {
                return finalizeReassembly(session, expectedFullPacketHash)
            }
        }

        return null
    }

    /**
     * Finalizes reassembly when all chunks are present.
     */
    private fun finalizeReassembly(session: ReassemblySession, expectedFullPacketHash: String?): ReceivedPacket {
        val totalSize = session.fragments.values.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until session.totalFragments) {
            val chunk = session.fragments[i] ?: throw BleProtocolException(
                BleProtocolError.UNKNOWN_PACKET_HASH,
                "Missing chunk $i during finalization"
            )
            System.arraycopy(chunk, 0, combined, offset, chunk.size)
            offset += chunk.size
        }

        val ciphertext = String(combined, StandardCharsets.UTF_8)
        val calculatedHash = PacketHasher.hashCiphertext(ciphertext)

        // Integrity verification
        if (expectedFullPacketHash != null && calculatedHash != expectedFullPacketHash) {
            // Hash mismatch -> reject and discard
            cleanSessionResources(session)
            throw BleProtocolException(
                BleProtocolError.UNKNOWN_PACKET_HASH,
                "Reassembled packetHash mismatch: expected $expectedFullPacketHash, calculated $calculatedHash"
            )
        }

        // Create Room ReceivedPacket
        val receivedPacket = ReceivedPacket(
            packetHash = calculatedHash,
            packetId = UUID.randomUUID().toString(),
            ciphertext = ciphertext,
            ttl = 5,
            hopCount = 0,
            receivedAt = timeProvider.currentTimeMillis(),
            uploadedToBridge = false,
            status = ReceivedPacketStatus.STORED,
            updatedAt = timeProvider.currentTimeMillis()
        )

        // Persist received packet atomically
        database.receivedPacketDao.insert(receivedPacket)

        // Clean up durable fragments from Room
        database.packetFragmentDao.deleteFragments(session.packetHashHex)
        cleanSessionResources(session)
        metrics.recordReassemblyCompleted()

        return receivedPacket
    }

    /**
     * Fast-path processing for single-fragment packets.
     */
    private fun processSingleFragmentPacket(frame: BleFrame, expectedFullPacketHash: String?): ReceivedPacket {
        val ciphertext = String(frame.payload, StandardCharsets.UTF_8)
        val calculatedHash = PacketHasher.hashCiphertext(ciphertext)

        if (expectedFullPacketHash != null && calculatedHash != expectedFullPacketHash) {
            throw BleProtocolException(
                BleProtocolError.UNKNOWN_PACKET_HASH,
                "Single-frame packetHash mismatch: expected $expectedFullPacketHash, calculated $calculatedHash"
            )
        }

        val packet = ReceivedPacket(
            packetHash = calculatedHash,
            packetId = UUID.randomUUID().toString(),
            ciphertext = ciphertext,
            ttl = 5,
            hopCount = 0,
            receivedAt = timeProvider.currentTimeMillis(),
            uploadedToBridge = false,
            status = ReceivedPacketStatus.STORED,
            updatedAt = timeProvider.currentTimeMillis()
        )

        database.receivedPacketDao.insert(packet)
        metrics.recordReassemblyCompleted()
        return packet
    }

    /**
     * Restores in-memory reassembly buffers from persisted Room fragments upon restart.
     */
    fun restoreFromDatabase(packetHash: String): ReassemblySession? {
        val fragments = database.packetFragmentDao.queryFragments(packetHash)
        if (fragments.isEmpty()) return null

        val totalChunks = fragments[0].totalChunks
        val session = ReassemblySession(
            transferId = 0,
            packetHashHex = packetHash,
            totalFragments = totalChunks,
            createdAt = fragments.minOf { it.receivedAt },
            lastUpdatedAt = fragments.maxOf { it.receivedAt }
        )

        for (frag in fragments) {
            session.fragments[frag.chunkIndex] = frag.data
            session.totalBytes += frag.data.size
            totalMemoryAllocated.addAndGet(frag.data.size.toLong())
        }

        activeSessions[packetHash] = session
        return session
    }

    /**
     * Returns missing fragment indices for selective retransmission.
     */
    fun getMissingFragmentIndices(sessionKey: String): List<Int> {
        val session = activeSessions[sessionKey] ?: return emptyList()
        return session.getMissingIndices()
    }

    /**
     * Cleans up stale reassembly sessions exceeding the timeout window.
     */
    fun cleanExpiredSessions() {
        val now = timeProvider.currentTimeMillis()
        val cutoff = now - timeoutMs

        val iterator = activeSessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value
            if (session.lastUpdatedAt < cutoff) {
                iterator.remove()
                cleanSessionResources(session)
                database.packetFragmentDao.deleteFragments(session.packetHashHex)
                metrics.recordReassemblyTimeout()
            }
        }
    }

    fun getActiveSessionCount(): Int = activeSessions.size

    fun getActiveMemoryBytes(): Long = totalMemoryAllocated.get()

    private fun cleanSessionResources(session: ReassemblySession) {
        val bytes = session.fragments.values.sumOf { it.size }
        totalMemoryAllocated.addAndGet(-bytes.toLong())
        activeSessions.remove(session.packetHashHex)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}

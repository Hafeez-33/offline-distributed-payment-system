package com.demo.upimesh.mesh.store

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.entity.ReceivedPacket
import com.demo.upimesh.db.entity.ReceivedPacketStatus

/**
 * Encapsulates the immutable payload of an encrypted mesh payment packet.
 */
data class MeshPacketData(
    val packetHash: String,
    val packetId: String,
    val ciphertext: String,
    val ttl: Int = 3,
    val hopCount: Int = 0
)

/**
 * Storage abstraction for reading known hashes and persisting peer-received packets.
 */
interface MeshPacketStore {
    fun getAllKnownPacketHashes(): Set<String>
    fun getPacketData(packetHash: String): MeshPacketData?
    fun storeReceivedPacket(packet: MeshPacketData): Boolean
}

/**
 * Room-backed implementation of MeshPacketStore.
 *
 * Strict financial authority boundaries:
 * - Replicated packets from peers are ONLY saved to `received_packets`.
 * - NEVER inserts into `outbound_payments`.
 * - NEVER alters `local_spent_amount_paisa` or `settled_amount_paisa`.
 * - Strictly verifies SHA-256(ciphertext) == packetHash.
 */
class RoomMeshPacketStore(
    private val database: UpiMeshDatabase
) : MeshPacketStore {

    override fun getAllKnownPacketHashes(): Set<String> {
        val outboundHashes = database.outboundPaymentDao
            .getPaymentsByState(com.demo.upimesh.db.entity.OutboundPaymentState.READY_FOR_TRANSPORT)
            .mapNotNull { it.packetHash?.trim()?.lowercase() }

        val receivedHashes = database.receivedPacketDao.getAllPackets()
            .map { it.packetHash.trim().lowercase() }

        val allOutboundHashes = mutableSetOf<String>()
        for (wallet in database.offlineWalletDao.getAllWallets()) {
            for (p in database.outboundPaymentDao.getPaymentsForWallet(wallet.walletId)) {
                p.packetHash?.let { allOutboundHashes.add(it.trim().lowercase()) }
            }
        }
        allOutboundHashes.addAll(outboundHashes)
        allOutboundHashes.addAll(receivedHashes)
        return allOutboundHashes
    }

    override fun getPacketData(packetHash: String): MeshPacketData? {
        val cleanHash = packetHash.trim().lowercase()
        val received = database.receivedPacketDao.getPacket(cleanHash)
        if (received != null) {
            return MeshPacketData(
                packetHash = received.packetHash,
                packetId = received.packetId,
                ciphertext = received.ciphertext,
                ttl = received.ttl,
                hopCount = received.hopCount
            )
        }
        val outbound = database.outboundPaymentDao.getPaymentByPacketHash(cleanHash)
        if (outbound != null) {
            val ciphertext = outbound.ciphertext
            if (ciphertext != null) {
                return MeshPacketData(
                    packetHash = outbound.packetHash ?: cleanHash,
                    packetId = outbound.paymentId,
                    ciphertext = ciphertext,
                    ttl = 3,
                    hopCount = 0
                )
            }
        }
        return null
    }

    override fun storeReceivedPacket(packet: MeshPacketData): Boolean {
        val cleanHash = packet.packetHash.trim().lowercase()
        // 1. Verify SHA-256(ciphertext) == packetHash
        val computedHash = PacketHasher.hashCiphertext(packet.ciphertext).lowercase()
        if (!computedHash.equals(cleanHash, ignoreCase = true)) {
            throw IllegalArgumentException(
                "Packet ciphertext integrity check failed: expected $cleanHash, computed $computedHash"
            )
        }

        // 2. Check existence for idempotency
        if (database.receivedPacketDao.exists(cleanHash)) {
            return false // already known
        }
        if (database.outboundPaymentDao.getPaymentByPacketHash(cleanHash) != null) {
            return false // already known as outbound
        }

        // 3. Persist strictly into received_packets
        val entity = ReceivedPacket(
            packetHash = cleanHash,
            packetId = packet.packetId,
            ciphertext = packet.ciphertext,
            ttl = packet.ttl,
            hopCount = packet.hopCount,
            status = ReceivedPacketStatus.STORED
        )
        val rowId = database.receivedPacketDao.insert(entity)
        return rowId != -1L
    }
}

/**
 * In-memory implementation of MeshPacketStore for fast, deterministic unit & integration tests.
 */
class InMemoryMeshPacketStore : MeshPacketStore {
    private val packets = mutableMapOf<String, MeshPacketData>()

    @Synchronized
    override fun getAllKnownPacketHashes(): Set<String> {
        return packets.keys.toSet()
    }

    @Synchronized
    override fun getPacketData(packetHash: String): MeshPacketData? {
        return packets[packetHash.trim().lowercase()]
    }

    @Synchronized
    override fun storeReceivedPacket(packet: MeshPacketData): Boolean {
        val cleanHash = packet.packetHash.trim().lowercase()
        val computedHash = PacketHasher.hashCiphertext(packet.ciphertext).lowercase()
        if (!computedHash.equals(cleanHash, ignoreCase = true)) {
            throw IllegalArgumentException(
                "Packet ciphertext integrity check failed: expected $cleanHash, computed $computedHash"
            )
        }
        if (packets.containsKey(cleanHash)) {
            return false // duplicate / idempotent
        }
        packets[cleanHash] = packet
        return true
    }

    @Synchronized
    fun addLocalPacket(packet: MeshPacketData) {
        packets[packet.packetHash.trim().lowercase()] = packet
    }
}

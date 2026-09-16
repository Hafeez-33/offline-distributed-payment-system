package com.demo.upimesh.mesh.gossip

import com.demo.upimesh.mesh.metrics.MeshMetrics
import com.demo.upimesh.mesh.model.PacketOfferPayload
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a gossip push forwarding decision.
 */
data class ForwardDecision(
    val forwarded: Boolean,
    val targetPeers: List<String>,
    val nextTtl: Int,
    val nextHopCount: Int,
    val reason: String
)

/**
 * Coordinates TTL-limited epidemic push gossip across connected BLE mesh peers.
 *
 * Rules:
 * 1. TTL is decremented on every hop.
 * 2. When TTL == 0, epidemic push halts. (Anti-entropy remains fully capable of discovering it).
 * 3. Bounded fan-out (max 3 peers).
 * 4. Loop suppression: Never forwards back to the immediate source peer.
 * 5. Deduplicates repeated push broadcasts using a bounded cache.
 */
class GossipForwarder(
    val maxFanOut: Int = 3,
    val metrics: MeshMetrics = MeshMetrics()
) {
    // Bounded LRU cache of recently forwarded (peerId -> packetHashes)
    private val forwardedToPeers: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    /**
     * Determines eligibility and prepares the forward payload for a packet.
     */
    fun evaluateForward(
        packetHash: String,
        currentTtl: Int,
        currentHopCount: Int,
        sourcePeerId: String?,
        connectedPeers: List<String>
    ): ForwardDecision {
        val cleanHash = packetHash.trim().lowercase()

        if (currentTtl <= 0) {
            metrics.pushTtlExhausted.incrementAndGet()
            return ForwardDecision(
                forwarded = false,
                targetPeers = emptyList(),
                nextTtl = 0,
                nextHopCount = currentHopCount,
                reason = "TTL_EXHAUSTED"
            )
        }

        // Exclude immediate source peer
        val eligiblePeers = connectedPeers
            .filter { it != sourcePeerId }
            .filter { peerId ->
                val alreadyForwarded = forwardedToPeers[peerId]?.contains(cleanHash) == true
                !alreadyForwarded
            }
            .take(maxFanOut)

        if (eligiblePeers.isEmpty()) {
            return ForwardDecision(
                forwarded = false,
                targetPeers = emptyList(),
                nextTtl = currentTtl - 1,
                nextHopCount = currentHopCount + 1,
                reason = "NO_ELIGIBLE_PEERS"
            )
        }

        // Record forwarding in deduplication cache
        for (peer in eligiblePeers) {
            forwardedToPeers.computeIfAbsent(peer) { Collections.newSetFromMap(ConcurrentHashMap()) }
                .add(cleanHash)
        }

        metrics.pushForwarded.addAndGet(eligiblePeers.size.toLong())

        return ForwardDecision(
            forwarded = true,
            targetPeers = eligiblePeers,
            nextTtl = currentTtl - 1,
            nextHopCount = currentHopCount + 1,
            reason = "FORWARDED"
        )
    }

    fun buildOfferPayload(
        packetHash: String,
        nextTtl: Int,
        nextHopCount: Int
    ): PacketOfferPayload {
        return PacketOfferPayload(
            offeredPacketHashes = listOf(packetHash.trim().lowercase()),
            ttl = nextTtl,
            hopCount = nextHopCount
        )
    }

    fun clearPeerHistory(peerId: String) {
        forwardedToPeers.remove(peerId)
    }

    fun clearAllHistory() {
        forwardedToPeers.clear()
    }
}

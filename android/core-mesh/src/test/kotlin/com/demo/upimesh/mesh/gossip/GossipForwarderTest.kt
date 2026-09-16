package com.demo.upimesh.mesh.gossip

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GossipForwarderTest {

    @Test
    fun testTtlDecrementsAndHopsIncrement() {
        val forwarder = GossipForwarder(maxFanOut = 3)
        val decision = forwarder.evaluateForward(
            packetHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            currentTtl = 3,
            currentHopCount = 0,
            sourcePeerId = null,
            connectedPeers = listOf("peer-1", "peer-2")
        )

        assertTrue(decision.forwarded)
        assertEquals(2, decision.nextTtl)
        assertEquals(1, decision.nextHopCount)
        assertEquals(listOf("peer-1", "peer-2"), decision.targetPeers)
    }

    @Test
    fun testTtlZeroHaltsEpidemicForwarding() {
        val forwarder = GossipForwarder(maxFanOut = 3)
        val decision = forwarder.evaluateForward(
            packetHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            currentTtl = 0,
            currentHopCount = 3,
            sourcePeerId = null,
            connectedPeers = listOf("peer-1", "peer-2")
        )

        assertFalse(decision.forwarded)
        assertEquals("TTL_EXHAUSTED", decision.reason)
        assertEquals(0, decision.targetPeers.size)
    }

    @Test
    fun testBoundedFanOutLimitsTargetPeersToMax3() {
        val forwarder = GossipForwarder(maxFanOut = 3)
        val decision = forwarder.evaluateForward(
            packetHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            currentTtl = 3,
            currentHopCount = 0,
            sourcePeerId = null,
            connectedPeers = listOf("peer-1", "peer-2", "peer-3", "peer-4", "peer-5")
        )

        assertTrue(decision.forwarded)
        assertEquals(3, decision.targetPeers.size, "Fan-out must be bounded to max 3")
    }

    @Test
    fun testLoopSuppressionExcludesSourcePeer() {
        val forwarder = GossipForwarder(maxFanOut = 3)
        val decision = forwarder.evaluateForward(
            packetHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            currentTtl = 2,
            currentHopCount = 1,
            sourcePeerId = "peer-1",
            connectedPeers = listOf("peer-1", "peer-2")
        )

        assertTrue(decision.forwarded)
        assertEquals(listOf("peer-2"), decision.targetPeers, "Immediate source peer-1 must not be in targets")
    }

    @Test
    fun testDeduplicationPreventsRepeatedPushToSamePeer() {
        val forwarder = GossipForwarder(maxFanOut = 3)
        val hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        val d1 = forwarder.evaluateForward(hash, 3, 0, null, listOf("peer-1"))
        assertTrue(d1.forwarded)

        val d2 = forwarder.evaluateForward(hash, 3, 0, null, listOf("peer-1"))
        assertFalse(d2.forwarded, "Must not repeatedly push same hash to same peer")
    }
}

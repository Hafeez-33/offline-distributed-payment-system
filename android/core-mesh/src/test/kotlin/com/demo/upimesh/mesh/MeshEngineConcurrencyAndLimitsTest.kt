package com.demo.upimesh.mesh

import com.demo.upimesh.mesh.model.StateSummaryPayload
import com.demo.upimesh.mesh.session.MeshSyncState
import com.demo.upimesh.mesh.store.InMemoryMeshPacketStore
import com.demo.upimesh.transport.BleProtocolException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MeshEngineConcurrencyAndLimitsTest {

    @Test
    fun testMaxConcurrentSessionsEnforcedAt8() {
        val store = InMemoryMeshPacketStore()
        val engine = MeshEngine(localDeviceId = "local-device", store = store, maxConcurrentSessions = 8, maxConnectedPeers = 10)

        for (i in 1..8) {
            engine.registerPeer("peer-$i")
            engine.startSyncSession("peer-$i")
        }
        assertEquals(8, engine.getActiveSessionCount())

        // 9th session must be rejected with BleProtocolException
        engine.registerPeer("peer-9")
        assertThrows(BleProtocolException::class.java) {
            engine.startSyncSession("peer-9")
        }
    }

    @Test
    fun testMaxConnectedPeersEnforcedAt4() {
        val store = InMemoryMeshPacketStore()
        val engine = MeshEngine(localDeviceId = "local-device", store = store, maxConcurrentSessions = 8, maxConnectedPeers = 4)

        for (i in 1..4) {
            engine.registerPeer("peer-$i")
        }
        assertEquals(4, engine.getConnectedPeerCount())

        assertThrows(BleProtocolException::class.java) {
            engine.registerPeer("peer-5")
        }
    }

    @Test
    fun testPeerDisconnectCleansUpSessionAndHistory() {
        val store = InMemoryMeshPacketStore()
        val engine = MeshEngine(localDeviceId = "local-device", store = store)

        engine.registerPeer("peer-1")
        val session = engine.startSyncSession("peer-1")
        assertEquals(1, engine.getActiveSessionCount())

        engine.unregisterPeer("peer-1")
        assertEquals(0, engine.getActiveSessionCount())
        assertEquals(0, engine.getConnectedPeerCount())
        assertEquals(MeshSyncState.FAILED, session.state)
    }

    @Test
    fun testCleanupExpiredSessions() {
        val store = InMemoryMeshPacketStore()
        val engine = MeshEngine(localDeviceId = "local-device", store = store)

        engine.registerPeer("peer-1")
        val session = engine.startSyncSession("peer-1")
        assertEquals(1, engine.getActiveSessionCount())

        // Simulate 40 seconds passed (timeout is 30s)
        engine.cleanupExpiredSessions(currentTime = session.createdAt + 40_000L)
        assertEquals(0, engine.getActiveSessionCount())
        assertEquals(MeshSyncState.FAILED, session.state)
    }

    @Test
    fun testMetricsTracking() {
        val store = InMemoryMeshPacketStore()
        val engine = MeshEngine(localDeviceId = "local-device", store = store)

        engine.registerPeer("peer-1")
        val session = engine.startSyncSession("peer-1")

        val summary = StateSummaryPayload(
            packetCount = 0,
            stateDigestHex = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        engine.handleIncomingMessage(session, summary)

        val metrics = engine.metrics.snapshot()
        assertTrue(metrics["mesh_sessions_started_total"]!! >= 1)
        assertTrue(metrics["mesh_state_digest_mismatch_total"]!! >= 1)
    }
}

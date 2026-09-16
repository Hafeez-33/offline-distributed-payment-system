package com.demo.upimesh.mesh

import com.demo.upimesh.mesh.gossip.ForwardDecision
import com.demo.upimesh.mesh.gossip.GossipForwarder
import com.demo.upimesh.mesh.metrics.MeshMetrics
import com.demo.upimesh.mesh.model.*
import com.demo.upimesh.mesh.session.MeshSession
import com.demo.upimesh.mesh.session.MeshSyncState
import com.demo.upimesh.mesh.store.MeshPacketData
import com.demo.upimesh.mesh.store.MeshPacketStore
import com.demo.upimesh.mesh.sync.MeshSynchronizer
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import java.util.concurrent.ConcurrentHashMap

/**
 * Top-level Mesh Engine coordinator.
 *
 * Enforces resource bounds:
 * - Max concurrent mesh sessions: 8
 * - Max peer connections: 4
 * - Max sync batch: 50 packets
 * - Epidemic push fan-out: 3 peers
 */
class MeshEngine(
    val localDeviceId: String,
    val store: MeshPacketStore,
    val maxConcurrentSessions: Int = 8,
    val maxConnectedPeers: Int = 4,
    val metrics: MeshMetrics = MeshMetrics()
) {
    val synchronizer: MeshSynchronizer = MeshSynchronizer(store, metrics)
    val gossipForwarder: GossipForwarder = GossipForwarder(maxFanOut = 3, metrics = metrics)

    private val activeSessions = ConcurrentHashMap<String, MeshSession>()
    private val connectedPeers = ConcurrentHashMap.newKeySet<String>()

    /**
     * Registers a new peer connection.
     */
    @Synchronized
    fun registerPeer(peerDeviceId: String) {
        if (connectedPeers.size >= maxConnectedPeers && !connectedPeers.contains(peerDeviceId)) {
            throw BleProtocolException(
                BleProtocolError.OVERSIZED_PAYLOAD,
                "Maximum connected peers limit reached ($maxConnectedPeers)"
            )
        }
        connectedPeers.add(peerDeviceId)
    }

    /**
     * Unregisters a peer connection and cleans up any associated session.
     */
    @Synchronized
    fun unregisterPeer(peerDeviceId: String) {
        connectedPeers.remove(peerDeviceId)
        gossipForwarder.clearPeerHistory(peerDeviceId)
        val sessionsToClean = activeSessions.filter { it.value.peerDeviceId == peerDeviceId }.keys
        for (sid in sessionsToClean) {
            val session = activeSessions.remove(sid)
            session?.stateMachine?.fail("Peer disconnected")
            metrics.sessionsFailed.incrementAndGet()
        }
    }

    /**
     * Starts a new pairwise anti-entropy synchronization session with a connected peer.
     */
    @Synchronized
    fun startSyncSession(peerDeviceId: String): MeshSession {
        if (!connectedPeers.contains(peerDeviceId)) {
            registerPeer(peerDeviceId)
        }
        if (activeSessions.size >= maxConcurrentSessions) {
            throw BleProtocolException(
                BleProtocolError.OVERSIZED_PAYLOAD,
                "Maximum concurrent mesh sessions limit reached ($maxConcurrentSessions)"
            )
        }

        val session = MeshSession(
            peerDeviceId = peerDeviceId,
            isInitiator = true
        )
        activeSessions[session.sessionId] = session
        metrics.sessionsStarted.incrementAndGet()
        return session
    }

    /**
     * Retrieves an active session by ID.
     */
    fun getSession(sessionId: String): MeshSession? = activeSessions[sessionId]

    /**
     * Handles incoming protocol message for a session.
     */
    fun handleIncomingMessage(
        session: MeshSession,
        payload: MeshProtocolPayload
    ): MeshProtocolPayload? {
        session.updateActivity()

        return when (payload) {
            is HelloPayload -> {
                if (session.state == MeshSyncState.IDLE) {
                    session.stateMachine.transitionTo(MeshSyncState.HELLO)
                }
                synchronizer.createLocalStateSummary()
            }
            is StateSummaryPayload -> {
                synchronizer.processRemoteStateSummary(session, payload)
            }
            is BucketChecksumsPayload -> {
                val offers = synchronizer.processRemoteBucketChecksums(session, payload)
                offers.firstOrNull()
            }
            is PacketOfferPayload -> {
                synchronizer.processBucketHashOffer(session, payload)
            }
            is SyncRequestPayload -> {
                null
            }
            is SyncAckPayload -> {
                synchronizer.finishSync(session)
            }
            is ReceiptNotifyPayload -> {
                synchronizer.validateReceiptNotify(payload)
                null
            }
            is MeshErrorPayload -> {
                session.stateMachine.fail(payload.errorMessage)
                metrics.sessionsFailed.incrementAndGet()
                null
            }
            else -> null
        }
    }

    /**
     * Evaluates epidemic push forwarding for a newly created or received packet.
     */
    fun onNewPacketAvailable(
        packet: MeshPacketData,
        sourcePeerId: String? = null
    ): ForwardDecision {
        return gossipForwarder.evaluateForward(
            packetHash = packet.packetHash,
            currentTtl = packet.ttl,
            currentHopCount = packet.hopCount,
            sourcePeerId = sourcePeerId,
            connectedPeers = connectedPeers.toList()
        )
    }

    /**
     * Cleans up expired sessions.
     */
    @Synchronized
    fun cleanupExpiredSessions(currentTime: Long = System.currentTimeMillis()) {
        val iterator = activeSessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(currentTime)) {
                entry.value.stateMachine.fail("Session expired")
                metrics.sessionsFailed.incrementAndGet()
                iterator.remove()
            }
        }
    }

    fun getActiveSessionCount(): Int = activeSessions.size
    fun getConnectedPeerCount(): Int = connectedPeers.size
}

package com.demo.upimesh.fault;

import com.demo.upimesh.model.MeshPacket;

/**
 * Narrow interception adapter interface for fault injection.
 * Integrates cleanly into transport, sync, payload, and settlement boundaries.
 * When disabled or no rules match, implementations pass through with zero behavioral modification.
 */
public interface FaultInterceptor {

    /**
     * Determines whether an outbound gossip push packet should be transmitted.
     * @return true to allow transmission, false to DROP.
     */
    default boolean allowGossipPush(String src, String dst, MeshPacket packet) {
        return true;
    }

    /**
     * Inspects or duplicates an outbound gossip push packet.
     * @return number of duplicate transmissions (1 = normal single delivery).
     */
    default int getGossipDuplicateCount(String src, String dst, MeshPacket packet) {
        return 1;
    }

    /**
     * Determines whether an anti-entropy sync message should be delivered.
     * @return true to allow delivery, false to DROP.
     */
    default boolean allowSyncMessage(String src, String dst, Object message) {
        return true;
    }

    /**
     * Determines whether a target peer is reachable during anti-entropy sync.
     * @return true if peer is reachable, false if PEER_UNAVAILABLE.
     */
    default boolean isPeerAvailable(String src, String target) {
        return true;
    }

    /**
     * Intercepts and potentially corrupts an inbound packet's payload/ciphertext.
     * @return original packet, or a modified packet with corrupted ciphertext.
     */
    default MeshPacket interceptPacketPayload(String src, String dst, MeshPacket packet) {
        return packet;
    }

    /**
     * Determines whether mesh-to-bridge upload should succeed.
     * @return true to allow upload, false if BRIDGE_UNAVAILABLE.
     */
    default boolean allowBridgeUpload(String bridgeNodeId, MeshPacket packet) {
        return true;
    }

    /**
     * Inspects a database settlement attempt. May throw transient exceptions.
     */
    default void inspectSettlementAttempt(String packetHash, int attempt) throws Exception {
        // default no-op
    }

    /**
     * Inspects post-commit HTTP response delivery.
     * @return true if response should be returned, false if STALE_RESPONSE (lost response).
     */
    default boolean allowPostCommitResponse(String packetHash) {
        return true;
    }
}

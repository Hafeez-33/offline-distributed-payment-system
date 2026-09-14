package com.demo.upimesh.fault;

/**
 * Enumeration of all discrete fault types supported by the Phase 5 reliability testing layer.
 */
public enum FaultType {
    /** Discard packet or sync message silently. */
    DROP,

    /** Duplicate packet transmission N times. */
    DUPLICATE,

    /** Withhold packet for delayed delivery. */
    DELAY,

    /** Invert delivery order of outgoing packets. */
    REORDER,

    /** Sever communication links between submeshes or nodes. */
    PARTITION,

    /** Peer unavailable / unreachable during anti-entropy sync. */
    PEER_UNAVAILABLE,

    /** Failure of mesh-to-bridge network transport/upload operation. */
    BRIDGE_UNAVAILABLE,

    /** Invalid or malformed anti-entropy synchronization control message. */
    MALFORMED_SYNC_MESSAGE,

    /** Corrupted MeshPacket ciphertext causing decryption/integrity failure. */
    CORRUPTED_PACKET_PAYLOAD,

    /** Transient database exception (e.g. OptimisticLockException) during settlement. */
    TRANSIENT_DATABASE_FAILURE,

    /** Lost HTTP response after committed database settlement. */
    STALE_RESPONSE,

    /** Concurrent duplicate submission of identical packet hash. */
    DUPLICATE_REQUEST,

    /** Volatile node buffer wipe to simulate crash and reboot. */
    CRASH_AND_RESTART
}

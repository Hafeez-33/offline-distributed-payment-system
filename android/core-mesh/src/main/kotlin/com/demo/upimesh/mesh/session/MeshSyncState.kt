package com.demo.upimesh.mesh.session

/**
 * Explicit deterministic states for a pairwise Mesh Anti-Entropy session.
 */
enum class MeshSyncState {
    IDLE,
    HELLO,
    SUMMARY_EXCHANGE,
    BUCKET_COMPARISON,
    HASH_EXCHANGE,
    REQUESTING_PACKETS,
    RECEIVING_PACKETS,
    ACKNOWLEDGING,
    COMPLETED,
    FAILED;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED
}

package com.demo.upimesh.mesh.session

import java.util.UUID

/**
 * Tracks the in-memory context and progress of a single pairwise anti-entropy synchronization session.
 */
data class MeshSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val peerDeviceId: String,
    val isInitiator: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
    val timeoutMs: Long = 30_000L
) {
    val stateMachine: MeshSyncStateMachine = MeshSyncStateMachine()

    val state: MeshSyncState get() = stateMachine.currentState

    val divergentBuckets: MutableList<Int> = mutableListOf()
    val missingLocallyHashes: MutableSet<String> = mutableSetOf()
    val missingRemotelyHashes: MutableSet<String> = mutableSetOf()
    val syncedHashes: MutableSet<String> = mutableSetOf()

    fun updateActivity(timestamp: Long = System.currentTimeMillis()) {
        lastActivityAt = timestamp
    }

    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return (currentTime - lastActivityAt) > timeoutMs
    }
}

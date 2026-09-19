package com.demo.upimesh.mesh.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe observability metrics for Mesh Anti-Entropy and Gossip operations.
 *
 * NOTE: In strict accordance with privacy rules, no sensitive financial identifiers,
 * wallet IDs, or packet hashes are ever recorded in metric names or labels.
 */
class MeshMetrics {
    val sessionsStarted = AtomicLong(0)
    val sessionsCompleted = AtomicLong(0)
    val sessionsFailed = AtomicLong(0)
    val stateDigestMismatch = AtomicLong(0)
    val bucketMismatch = AtomicLong(0)
    val packetsOffered = AtomicLong(0)
    val packetsRequested = AtomicLong(0)
    val packetsReceived = AtomicLong(0)
    val packetsDeduplicated = AtomicLong(0)
    val pushForwarded = AtomicLong(0)
    val pushTtlExhausted = AtomicLong(0)
    val syncBatches = AtomicLong(0)
    val syncConvergence = AtomicLong(0)

    fun reset() {
        sessionsStarted.set(0)
        sessionsCompleted.set(0)
        sessionsFailed.set(0)
        stateDigestMismatch.set(0)
        bucketMismatch.set(0)
        packetsOffered.set(0)
        packetsRequested.set(0)
        packetsReceived.set(0)
        packetsDeduplicated.set(0)
        pushForwarded.set(0)
        pushTtlExhausted.set(0)
        syncBatches.set(0)
        syncConvergence.set(0)
    }

    fun snapshot(): Map<String, Long> {
        return mapOf(
            "mesh_sessions_started_total" to sessionsStarted.get(),
            "mesh_sessions_completed_total" to sessionsCompleted.get(),
            "mesh_sessions_failed_total" to sessionsFailed.get(),
            "mesh_state_digest_mismatch_total" to stateDigestMismatch.get(),
            "mesh_bucket_mismatch_total" to bucketMismatch.get(),
            "mesh_packets_offered_total" to packetsOffered.get(),
            "mesh_packets_requested_total" to packetsRequested.get(),
            "mesh_packets_received_total" to packetsReceived.get(),
            "mesh_packets_deduplicated_total" to packetsDeduplicated.get(),
            "mesh_push_forwarded_total" to pushForwarded.get(),
            "mesh_push_ttl_exhausted_total" to pushTtlExhausted.get(),
            "mesh_sync_batches_total" to syncBatches.get(),
            "mesh_sync_convergence_total" to syncConvergence.get()
        )
    }
}

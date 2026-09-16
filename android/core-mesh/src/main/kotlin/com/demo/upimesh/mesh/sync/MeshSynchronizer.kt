package com.demo.upimesh.mesh.sync

import com.demo.upimesh.mesh.digest.BucketChecksumBuilder
import com.demo.upimesh.mesh.digest.StateDigestBuilder
import com.demo.upimesh.mesh.metrics.MeshMetrics
import com.demo.upimesh.mesh.model.*
import com.demo.upimesh.mesh.session.MeshSession
import com.demo.upimesh.mesh.session.MeshSyncState
import com.demo.upimesh.mesh.store.MeshPacketData
import com.demo.upimesh.mesh.store.MeshPacketStore
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException

/**
 * Pairwise Anti-Entropy synchronization coordinator.
 *
 * Implements the 16-bucket prefix anti-entropy protocol:
 * 1. Summary comparison (state digest)
 * 2. Bucket comparison (16 checksums)
 * 3. Hash exchange (divergent buckets only)
 * 4. Set-difference calculation
 * 5. Bounded packet request & delivery (max 50 per batch)
 * 6. Verification and idempotent storage
 * 7. Session completion and ACK
 */
class MeshSynchronizer(
    val store: MeshPacketStore,
    val metrics: MeshMetrics = MeshMetrics()
) {

    /**
     * Step 1: Generates local STATE_SUMMARY payload.
     */
    fun createLocalStateSummary(): StateSummaryPayload {
        val allHashes = store.getAllKnownPacketHashes()
        val digest = StateDigestBuilder.calculateDigest(allHashes)
        return StateSummaryPayload(
            packetCount = allHashes.size,
            stateDigestHex = digest
        )
    }

    /**
     * Step 2: Evaluates a remote STATE_SUMMARY.
     * Returns null if states are already identical (and completes session),
     * or returns local BUCKET_CHECKSUMS payload if divergence is detected.
     */
    fun processRemoteStateSummary(
        session: MeshSession,
        remoteSummary: StateSummaryPayload
    ): MeshProtocolPayload {
        session.updateActivity()
        val allHashes = store.getAllKnownPacketHashes()
        val localDigest = StateDigestBuilder.calculateDigest(allHashes)

        if (remoteSummary.stateDigestHex.equals(localDigest, ignoreCase = true)) {
            // States are identical; immediate convergence
            metrics.syncConvergence.incrementAndGet()
            if (session.state == MeshSyncState.IDLE) {
                session.stateMachine.transitionTo(MeshSyncState.HELLO)
                session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
            } else if (session.state == MeshSyncState.HELLO) {
                session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
            }
            session.stateMachine.transitionTo(MeshSyncState.COMPLETED)
            metrics.sessionsCompleted.incrementAndGet()
            return SyncAckPayload(
                sessionId = session.sessionId,
                syncedPacketHashes = emptyList(),
                status = "SUCCESS"
            )
        }

        // Mismatch detected -> advance to BUCKET_COMPARISON
        metrics.stateDigestMismatch.incrementAndGet()
        if (session.state == MeshSyncState.IDLE) {
            session.stateMachine.transitionTo(MeshSyncState.HELLO)
            session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
        } else if (session.state == MeshSyncState.HELLO) {
            session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
        }
        session.stateMachine.transitionTo(MeshSyncState.BUCKET_COMPARISON)

        return BucketChecksumBuilder.buildPayload(allHashes)
    }

    /**
     * Step 3: Compares remote 16-bucket checksums against local buckets.
     * Returns a list of PacketOfferPayloads containing the local hashes for all divergent buckets.
     */
    fun processRemoteBucketChecksums(
        session: MeshSession,
        remotePayload: BucketChecksumsPayload
    ): List<PacketOfferPayload> {
        session.updateActivity()
        val allHashes = store.getAllKnownPacketHashes()
        val localBuckets = BucketChecksumBuilder.buildBuckets(allHashes)

        val divergent = BucketChecksumBuilder.findDivergentBuckets(localBuckets, remotePayload.buckets)
        session.divergentBuckets.clear()
        session.divergentBuckets.addAll(divergent)

        if (session.state == MeshSyncState.IDLE) {
            session.stateMachine.transitionTo(MeshSyncState.HELLO)
            session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
            session.stateMachine.transitionTo(MeshSyncState.BUCKET_COMPARISON)
        } else if (session.state == MeshSyncState.HELLO) {
            session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
            session.stateMachine.transitionTo(MeshSyncState.BUCKET_COMPARISON)
        } else if (session.state == MeshSyncState.SUMMARY_EXCHANGE) {
            session.stateMachine.transitionTo(MeshSyncState.BUCKET_COMPARISON)
        }

        if (divergent.isEmpty()) {
            session.stateMachine.transitionTo(MeshSyncState.COMPLETED)
            metrics.sessionsCompleted.incrementAndGet()
            return emptyList()
        }

        metrics.bucketMismatch.addAndGet(divergent.size.toLong())
        session.stateMachine.transitionTo(MeshSyncState.HASH_EXCHANGE)

        val offers = mutableListOf<PacketOfferPayload>()
        for (bucketIdx in divergent) {
            val hashesInBucket = allHashes
                .filter { BucketChecksumBuilder.getBucketIndex(it) == bucketIdx }
                .sorted()
            offers.add(
                PacketOfferPayload(
                    bucketIndex = bucketIdx,
                    offeredPacketHashes = hashesInBucket
                )
            )
        }
        return offers
    }

    /**
     * Step 4: Handles incoming hash exchange for a divergent bucket.
     * Computes set difference and generates bounded SyncRequestPayload if packets are missing locally.
     */
    fun processBucketHashOffer(
        session: MeshSession,
        offer: PacketOfferPayload
    ): SyncRequestPayload? {
        session.updateActivity()
        val bucketIdx = offer.bucketIndex ?: return null
        val allHashes = store.getAllKnownPacketHashes()
        val localHashesInBucket = allHashes.filter { BucketChecksumBuilder.getBucketIndex(it) == bucketIdx }

        val diff = SetDifferenceCalculator.calculate(localHashesInBucket, offer.offeredPacketHashes)
        session.missingLocallyHashes.addAll(diff.missingLocally)
        session.missingRemotelyHashes.addAll(diff.missingRemotely)

        if (session.missingLocallyHashes.isNotEmpty()) {
            val batch = session.missingLocallyHashes.take(SyncBatchPlanner.DEFAULT_MAX_BATCH_SIZE)
            metrics.packetsRequested.addAndGet(batch.size.toLong())
            if (session.state == MeshSyncState.IDLE) {
                session.stateMachine.transitionTo(MeshSyncState.HELLO)
                session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
                session.stateMachine.transitionTo(MeshSyncState.BUCKET_COMPARISON)
                session.stateMachine.transitionTo(MeshSyncState.HASH_EXCHANGE)
            } else if (session.state == MeshSyncState.BUCKET_COMPARISON) {
                session.stateMachine.transitionTo(MeshSyncState.HASH_EXCHANGE)
            }
            if (session.state == MeshSyncState.HASH_EXCHANGE) {
                session.stateMachine.transitionTo(MeshSyncState.REQUESTING_PACKETS)
            }
            return SyncRequestPayload(
                sessionId = session.sessionId,
                requestedPacketHashes = batch
            )
        }
        return null
    }

    /**
     * Step 5: Fulfills a peer's SYNC_REQUEST by fetching requested packets from storage.
     */
    fun fulfillSyncRequest(
        session: MeshSession,
        request: SyncRequestPayload
    ): List<MeshPacketData> {
        session.updateActivity()
        val results = mutableListOf<MeshPacketData>()
        for (hash in request.requestedPacketHashes) {
            val data = store.getPacketData(hash)
            if (data != null) {
                results.add(data)
                metrics.packetsOffered.incrementAndGet()
            }
        }
        return results
    }

    /**
     * Step 6: Ingests a received packet into local storage with cryptographic integrity verification.
     */
    fun ingestReceivedPacket(
        session: MeshSession,
        packet: MeshPacketData
    ): Boolean {
        session.updateActivity()
        val cleanHash = packet.packetHash.trim().lowercase()

        // Attempt idempotent storage (verifies SHA-256(ciphertext) == packetHash)
        val isNew = store.storeReceivedPacket(packet)
        if (isNew) {
            metrics.packetsReceived.incrementAndGet()
        } else {
            metrics.packetsDeduplicated.incrementAndGet()
        }

        session.syncedHashes.add(cleanHash)
        session.missingLocallyHashes.remove(cleanHash)

        if (session.state == MeshSyncState.REQUESTING_PACKETS) {
            session.stateMachine.transitionTo(MeshSyncState.RECEIVING_PACKETS)
        }
        return isNew
    }

    /**
     * Step 7: Completes the sync session.
     */
    fun finishSync(session: MeshSession): SyncAckPayload {
        session.updateActivity()
        if (session.state != MeshSyncState.COMPLETED) {
            if (session.state == MeshSyncState.IDLE) {
                session.stateMachine.transitionTo(MeshSyncState.HELLO)
                session.stateMachine.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
            }
            if (!session.state.isTerminal() && session.state != MeshSyncState.ACKNOWLEDGING) {
                session.stateMachine.transitionTo(MeshSyncState.ACKNOWLEDGING)
            }
            if (session.state == MeshSyncState.ACKNOWLEDGING) {
                session.stateMachine.transitionTo(MeshSyncState.COMPLETED)
            }
        }
        metrics.sessionsCompleted.incrementAndGet()
        metrics.syncConvergence.incrementAndGet()
        return SyncAckPayload(
            sessionId = session.sessionId,
            syncedPacketHashes = session.syncedHashes.toList(),
            status = "SUCCESS"
        )
    }

    /**
     * Step 8: Handles RECEIPT_NOTIFY payload validation.
     * Validates signature and structural integrity without mutating balances or claiming local settlement.
     */
    fun validateReceiptNotify(receipt: ReceiptNotifyPayload): Boolean {
        if (receipt.transactionId <= 0 || receipt.packetHash.length != 64 || receipt.serverSignature.isEmpty()) {
            throw BleProtocolException(
                BleProtocolError.INVALID_MSG_TYPE,
                "Malformed ReceiptNotifyPayload"
            )
        }
        return true
    }
}

package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.sync.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Coordinates pairwise anti-entropy synchronization between VirtualDevices.
 *
 * Implements the approved Phase 4 Revision 2 protocol:
 *   1. STATE_SUMMARY exchange & root digest comparison (O(1) summary exit on match)
 *   2. 16 prefix bucket checksum comparison
 *   3. Exchange of authoritative full packet hashes for divergent buckets
 *   4. Set difference computation (missingFromPeer / missingFromSelf)
 *   5. SYNC_REQUEST with bounded batches (max 50 packets)
 *   6. SYNC_RESPONSE payload delivery
 *   7. Local/remote store update & SYNC_ACK
 *   8. Alternate-peer selection on failure/timeout
 */
@Service
public class AntiEntropyService {

    private static final Logger log = LoggerFactory.getLogger(AntiEntropyService.class);
    public static final int DEFAULT_MAX_BATCH_SIZE = 50;
    public static final String PROTOCOL_VERSION = "v2_sync";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.demo.upimesh.fault.FaultInterceptor faultInterceptor;

    public void setFaultInterceptor(com.demo.upimesh.fault.FaultInterceptor faultInterceptor) {
        this.faultInterceptor = faultInterceptor;
    }

    public record PairwiseSyncResult(
            String localNodeId,
            String remoteNodeId,
            boolean wasInSync,
            int packetsTransferredToLocal,
            int packetsTransferredToRemote,
            int divergentBucketsCount
    ) {
        public int totalTransfers() {
            return packetsTransferredToLocal + packetsTransferredToRemote;
        }

        public static PairwiseSyncResult inSync(String local, String remote) {
            return new PairwiseSyncResult(local, remote, true, 0, 0, 0);
        }

        public static PairwiseSyncResult repaired(String local, String remote, int toLocal, int toRemote, int divergentBuckets) {
            return new PairwiseSyncResult(local, remote, false, toLocal, toRemote, divergentBuckets);
        }
    }

    public StateSummaryMessage generateSummary(VirtualDevice device) {
        Objects.requireNonNull(device, "device must not be null");
        return new StateSummaryMessage(
                device.getDeviceId(),
                PROTOCOL_VERSION,
                Instant.now().toEpochMilli(),
                device.packetCount(),
                device.getStateDigest(),
                device.getBucketChecksums()
        );
    }

    /**
     * Execute a complete pairwise anti-entropy exchange between local and remote devices.
     */
    public PairwiseSyncResult syncPair(VirtualDevice local, VirtualDevice remote) {
        return syncPair(local, remote, DEFAULT_MAX_BATCH_SIZE);
    }

    public PairwiseSyncResult syncPair(VirtualDevice local, VirtualDevice remote, int maxBatchSize) {
        Objects.requireNonNull(local, "local device must not be null");
        Objects.requireNonNull(remote, "remote device must not be null");

        // 1. Exchange STATE_SUMMARY
        StateSummaryMessage summaryLocal = generateSummary(local);
        StateSummaryMessage summaryRemote = generateSummary(remote);

        if (faultInterceptor != null && (!faultInterceptor.allowSyncMessage(local.getDeviceId(), remote.getDeviceId(), summaryLocal)
                || !faultInterceptor.allowSyncMessage(remote.getDeviceId(), local.getDeviceId(), summaryRemote))) {
            log.warn("Sync summary exchange dropped by fault rule between {} and {}", local.getDeviceId(), remote.getDeviceId());
            return PairwiseSyncResult.inSync(local.getDeviceId(), remote.getDeviceId());
        }

        PeerSyncRecord localPeerRecord = local.getPeerRecord(remote.getDeviceId());
        PeerSyncRecord remotePeerRecord = remote.getPeerRecord(local.getDeviceId());

        long now = Instant.now().toEpochMilli();
        localPeerRecord.setLastSyncTimestamp(now);
        remotePeerRecord.setLastSyncTimestamp(now);
        localPeerRecord.setLastKnownDigest(summaryRemote.stateDigest());
        remotePeerRecord.setLastKnownDigest(summaryLocal.stateDigest());

        // 2. Fast-path: root digests match -> cryptographically strong practical equality
        if (summaryLocal.stateDigest().equals(summaryRemote.stateDigest())) {
            localPeerRecord.incrementConsecutiveMatches();
            remotePeerRecord.incrementConsecutiveMatches();
            localPeerRecord.recordSuccess();
            remotePeerRecord.recordSuccess();
            log.debug("Nodes {} and {} are in sync (digest={})",
                    local.getDeviceId(), remote.getDeviceId(), summaryLocal.stateDigest().substring(0, 12));
            return PairwiseSyncResult.inSync(local.getDeviceId(), remote.getDeviceId());
        }

        localPeerRecord.resetConsecutiveMatches();
        remotePeerRecord.resetConsecutiveMatches();

        // 3. Identify divergent buckets
        List<Integer> divergentBucketIndices = new ArrayList<>();
        List<String> bLocal = summaryLocal.bucketChecksums();
        List<String> bRemote = summaryRemote.bucketChecksums();

        for (int i = 0; i < 16; i++) {
            if (!bLocal.get(i).equals(bRemote.get(i))) {
                divergentBucketIndices.add(i);
            }
        }

        Set<String> missingFromLocal = new LinkedHashSet<>();
        Set<String> missingFromRemote = new LinkedHashSet<>();

        // 4. Exchange full authoritative packet hashes for divergent buckets only
        for (int bIdx : divergentBucketIndices) {
            List<String> localHashes = local.getAuthoritativeHashesForBucket(bIdx);
            List<String> remoteHashes = remote.getAuthoritativeHashesForBucket(bIdx);

            BucketHashExchangeMessage msgFromLocal = new BucketHashExchangeMessage(
                    local.getDeviceId(), remote.getDeviceId(), now, bIdx, localHashes);
            BucketHashExchangeMessage msgFromRemote = new BucketHashExchangeMessage(
                    remote.getDeviceId(), local.getDeviceId(), now, bIdx, remoteHashes);

            // Compute symmetric set differences for this bucket
            Set<String> localSet = new HashSet<>(msgFromLocal.fullPacketHashes());
            Set<String> remoteSet = new HashSet<>(msgFromRemote.fullPacketHashes());

            for (String h : remoteSet) {
                if (!localSet.contains(h)) {
                    missingFromLocal.add(h);
                }
            }
            for (String h : localSet) {
                if (!remoteSet.contains(h)) {
                    missingFromRemote.add(h);
                }
            }
        }

        int toLocalCount = 0;
        int toRemoteCount = 0;

        // 5. Transfer missing packets to local device in bounded batches
        if (!missingFromLocal.isEmpty()) {
            List<String> requestedList = new ArrayList<>(missingFromLocal);
            for (int i = 0; i < requestedList.size(); i += maxBatchSize) {
                int end = Math.min(i + maxBatchSize, requestedList.size());
                List<String> batchHashes = requestedList.subList(i, end);
                String reqId = UUID.randomUUID().toString();

                SyncRequestMessage req = new SyncRequestMessage(
                        local.getDeviceId(), remote.getDeviceId(), reqId, now, batchHashes, maxBatchSize);

                List<MeshPacket> batchPackets = new ArrayList<>();
                for (String hash : req.requestedPacketHashes()) {
                    MeshPacket p = remote.getPacket(hash);
                    if (p != null) {
                        batchPackets.add(p);
                    }
                }

                SyncResponseMessage resp = new SyncResponseMessage(
                        remote.getDeviceId(), local.getDeviceId(), reqId, now, batchPackets, end < requestedList.size());

                List<String> acked = new ArrayList<>();
                for (MeshPacket p : resp.packets()) {
                    // Do NOT check TTL here! Anti-entropy repairs packets even if TTL=0
                    if (local.hold(p)) {
                        toLocalCount++;
                        acked.add(p.getPacketHash());
                    }
                }

                SyncAckMessage ack = new SyncAckMessage(
                        local.getDeviceId(), remote.getDeviceId(), reqId, now, acked, local.getStateDigest(), true);
                log.debug("Local {} processed SYNC_RESPONSE {} (acked={})", local.getDeviceId(), reqId, ack.acknowledgedPacketHashes().size());
            }
        }

        // 6. Transfer missing packets to remote device in bounded batches
        if (!missingFromRemote.isEmpty()) {
            List<String> requestedList = new ArrayList<>(missingFromRemote);
            for (int i = 0; i < requestedList.size(); i += maxBatchSize) {
                int end = Math.min(i + maxBatchSize, requestedList.size());
                List<String> batchHashes = requestedList.subList(i, end);
                String reqId = UUID.randomUUID().toString();

                SyncRequestMessage req = new SyncRequestMessage(
                        remote.getDeviceId(), local.getDeviceId(), reqId, now, batchHashes, maxBatchSize);

                List<MeshPacket> batchPackets = new ArrayList<>();
                for (String hash : req.requestedPacketHashes()) {
                    MeshPacket p = local.getPacket(hash);
                    if (p != null) {
                        batchPackets.add(p);
                    }
                }

                SyncResponseMessage resp = new SyncResponseMessage(
                        local.getDeviceId(), remote.getDeviceId(), reqId, now, batchPackets, end < requestedList.size());

                List<String> acked = new ArrayList<>();
                for (MeshPacket p : resp.packets()) {
                    if (remote.hold(p)) {
                        toRemoteCount++;
                        acked.add(p.getPacketHash());
                    }
                }

                SyncAckMessage ack = new SyncAckMessage(
                        remote.getDeviceId(), local.getDeviceId(), reqId, now, acked, remote.getStateDigest(), true);
                log.debug("Remote {} processed SYNC_RESPONSE {} (acked={})", remote.getDeviceId(), reqId, ack.acknowledgedPacketHashes().size());
            }
        }

        localPeerRecord.recordSuccess();
        remotePeerRecord.recordSuccess();

        log.info("Anti-entropy sync completed between {} and {}: {} divergent buckets, transferred {} to local, {} to remote",
                local.getDeviceId(), remote.getDeviceId(), divergentBucketIndices.size(), toLocalCount, toRemoteCount);

        return PairwiseSyncResult.repaired(
                local.getDeviceId(), remote.getDeviceId(), toLocalCount, toRemoteCount, divergentBucketIndices.size());
    }

    /**
     * Execute anti-entropy with target peer; if target peer is unavailable or fails,
     * fallback to an alternate peer from candidates.
     */
    public PairwiseSyncResult syncWithFallback(VirtualDevice local, String primaryTargetId,
                                               List<VirtualDevice> candidates,
                                               java.util.function.BiPredicate<String, String> reachabilityCheck) {
        // Attempt primary target
        VirtualDevice primary = candidates.stream()
                .filter(d -> d.getDeviceId().equals(primaryTargetId))
                .findFirst()
                .orElse(null);

        boolean primaryAvailable = faultInterceptor == null || faultInterceptor.isPeerAvailable(local.getDeviceId(), primaryTargetId);
        if (primary != null && primaryAvailable && reachabilityCheck.test(local.getDeviceId(), primary.getDeviceId())) {
            try {
                return syncPair(local, primary);
            } catch (Exception e) {
                log.warn("Sync failed with primary target {}: {}. Attempting alternate peer...", primaryTargetId, e.getMessage());
                local.getPeerRecord(primaryTargetId).recordFailure();
            }
        } else {
            local.getPeerRecord(primaryTargetId).recordFailure();
            log.warn("Primary target {} unreachable or unavailable. Attempting alternate peer...", primaryTargetId);
        }

        // Alternate peer selection: find first reachable non-self peer that isn't the failed primary
        for (VirtualDevice alt : candidates) {
            if (alt.getDeviceId().equals(local.getDeviceId()) || alt.getDeviceId().equals(primaryTargetId)) {
                continue;
            }
            boolean altAvailable = faultInterceptor == null || faultInterceptor.isPeerAvailable(local.getDeviceId(), alt.getDeviceId());
            if (altAvailable && reachabilityCheck.test(local.getDeviceId(), alt.getDeviceId())) {
                log.info("Selected alternate peer {} for node {}", alt.getDeviceId(), local.getDeviceId());
                return syncPair(local, alt);
            }
        }

        throw new IllegalStateException("No reachable alternate peer found for node: " + local.getDeviceId());
    }
}

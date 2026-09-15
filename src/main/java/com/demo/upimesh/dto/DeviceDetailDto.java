package com.demo.upimesh.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deep diagnostic snapshot of an individual device in the mesh.
 * Returned only on-demand when a node is explicitly inspected.
 */
public record DeviceDetailDto(
        String deviceId,
        boolean hasInternet,
        String stateDigest,
        int packetCount,
        List<PacketSummary> heldPackets,
        List<String> bucketChecksums,
        Map<String, PeerSyncSummary> peerSyncSummary,
        WalletStateSummary walletState
) {
    public record PacketSummary(
            String packetId,
            String packetHash,
            int ttl,
            Instant createdAt
    ) {}

    public record PeerSyncSummary(
            String peerId,
            long lastSyncRound,
            boolean inSync,
            int consecutiveSuccesses
    ) {}

    public record WalletStateSummary(
            String walletId,
            Long walletEpoch,
            Long sequenceCounter,
            BigDecimal cumulativeSpend
    ) {}
}

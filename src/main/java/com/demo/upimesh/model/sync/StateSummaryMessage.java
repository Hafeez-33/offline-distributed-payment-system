package com.demo.upimesh.model.sync;

import java.util.List;

/**
 * State summary containing the root state digest and 16 prefix bucket checksums.
 * Used for O(1) comparison when peers are in sync.
 */
public record StateSummaryMessage(
        String senderNodeId,
        String protocolVersion,
        long timestamp,
        int packetCount,
        String stateDigest,
        List<String> bucketChecksums
) implements MeshSyncMessage {}

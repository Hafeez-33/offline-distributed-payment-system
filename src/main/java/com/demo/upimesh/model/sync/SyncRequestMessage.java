package com.demo.upimesh.model.sync;

import java.util.List;

/**
 * Targeted pull request for missing authoritative packet hashes.
 */
public record SyncRequestMessage(
        String senderNodeId,
        String targetNodeId,
        String requestId,
        long timestamp,
        List<String> requestedPacketHashes,
        int maxBatchSize
) implements MeshSyncMessage {}

package com.demo.upimesh.model.sync;

import java.util.List;

/**
 * Carries the full authoritative packet hashes for a specific divergent prefix bucket.
 */
public record BucketHashExchangeMessage(
        String senderNodeId,
        String targetNodeId,
        long timestamp,
        int bucketIndex,
        List<String> fullPacketHashes
) implements MeshSyncMessage {}

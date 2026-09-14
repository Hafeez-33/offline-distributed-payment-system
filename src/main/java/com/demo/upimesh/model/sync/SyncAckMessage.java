package com.demo.upimesh.model.sync;

import java.util.List;

/**
 * Acknowledgment indicating pairwise synchronization exchange between two peers
 * completed successfully.
 */
public record SyncAckMessage(
        String senderNodeId,
        String targetNodeId,
        String requestId,
        long timestamp,
        List<String> acknowledgedPacketHashes,
        String updatedStateDigest,
        boolean pairwiseCompleted
) implements MeshSyncMessage {}

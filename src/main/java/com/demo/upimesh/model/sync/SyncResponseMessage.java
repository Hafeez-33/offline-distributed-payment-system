package com.demo.upimesh.model.sync;

import com.demo.upimesh.model.MeshPacket;

import java.util.List;

/**
 * Payload delivery of requested MeshPacket objects.
 */
public record SyncResponseMessage(
        String senderNodeId,
        String targetNodeId,
        String requestId,
        long timestamp,
        List<MeshPacket> packets,
        boolean hasMore
) implements MeshSyncMessage {}

package com.demo.upimesh.model.sync;

/**
 * Peer discovery and heartbeat message.
 */
public record HelloMessage(
        String senderNodeId,
        String protocolVersion,
        long timestamp
) implements MeshSyncMessage {}

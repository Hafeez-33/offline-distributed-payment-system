package com.demo.upimesh.model.sync;

/**
 * Strongly typed message hierarchy for Phase 4 Anti-Entropy and
 * Distributed Synchronization Protocol.
 */
public sealed interface MeshSyncMessage permits
        HelloMessage,
        StateSummaryMessage,
        BucketHashExchangeMessage,
        SyncRequestMessage,
        SyncResponseMessage,
        SyncAckMessage {

    String senderNodeId();
    long timestamp();
}

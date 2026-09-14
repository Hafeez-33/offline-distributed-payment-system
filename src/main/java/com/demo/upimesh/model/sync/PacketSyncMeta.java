package com.demo.upimesh.model.sync;

/**
 * Synchronization metadata associated with a stored packet.
 */
public record PacketSyncMeta(
        String packetHash,
        String packetId,
        String originVpa,
        String walletId,
        Long sequenceCounter,
        long arrivalTimestamp,
        int hopCount
) {}

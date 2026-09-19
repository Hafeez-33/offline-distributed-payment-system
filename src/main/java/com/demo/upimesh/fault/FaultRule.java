package com.demo.upimesh.fault;

import java.util.Objects;

/**
 * Immutable configuration rule for deterministic fault injection.
 * All mutable state (such as activation counts) is stored centrally in FaultInjector.
 */
public record FaultRule(
        String faultId,
        FaultType faultType,
        String sourceNode,          // Exact node ID, or "*" for wildcard
        String destinationNode,     // Exact node ID, or "*" for wildcard
        Class<?> messageClass,      // e.g. MeshPacket.class, SyncRequestMessage.class, or null
        String targetPacketHash,    // Exact hash, or null for any
        int occurrenceLimit,        // Maximum number of times to trigger
        long delayMillis            // Bound for simulated step delay
) {
    public FaultRule {
        Objects.requireNonNull(faultId, "faultId must not be null");
        Objects.requireNonNull(faultType, "faultType must not be null");
        if (sourceNode == null) sourceNode = "*";
        if (destinationNode == null) destinationNode = "*";
    }

    public boolean matches(String src, String dst, Object message, String hash) {
        if (!"*".equals(sourceNode) && src != null && !sourceNode.equals(src)) {
            return false;
        }
        if (!"*".equals(destinationNode) && dst != null && !destinationNode.equals(dst)) {
            return false;
        }
        if (messageClass != null && message != null && !messageClass.isInstance(message)) {
            return false;
        }
        if (targetPacketHash != null && hash != null && !targetPacketHash.equals(hash)) {
            return false;
        }
        return true;
    }
}

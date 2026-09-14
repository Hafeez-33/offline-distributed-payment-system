package com.demo.upimesh.fault;

import com.demo.upimesh.model.MeshPacket;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central deterministic fault-injection engine for Phase 5 distributed reliability testing.
 *
 * Enforces:
 *   - Immutable FaultRule definitions with centralized mutable activation counters.
 *   - Zero overhead and transparent passthrough when disabled.
 *   - Clean, deterministic reset between test runs.
 *   - Strict financial safety: operates ONLY at transport/control/exception boundaries,
 *     never mutating financial balances, amounts, counters, or ledger entities directly.
 */
@Component
public class FaultInjector implements FaultInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    private final List<FaultRule> rules = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> activationCounters = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    @Autowired
    private ReliabilityMetrics metrics;

    public FaultInjector() {}

    public FaultInjector(ReliabilityMetrics metrics) {
        this.metrics = metrics;
    }

    public void enable() {
        enabled.set(true);
    }

    public void disable() {
        enabled.set(false);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void addRule(FaultRule rule) {
        rules.add(rule);
        activationCounters.put(rule.faultId(), new AtomicInteger(0));
        enabled.set(true);
        log.info("FaultRule registered: id={}, type={}, src={}, dst={}, limit={}",
                rule.faultId(), rule.faultType(), rule.sourceNode(), rule.destinationNode(), rule.occurrenceLimit());
    }

    public String failNext(FaultType type) {
        return failNext(type, "*", "*", null, null);
    }

    public String failNext(FaultType type, String src, String dst) {
        return failNext(type, src, dst, null, null);
    }

    public String failNext(FaultType type, String src, String dst, Class<?> msgClass) {
        return failNext(type, src, dst, msgClass, null);
    }

    public String failNext(FaultType type, String src, String dst, Class<?> msgClass, String packetHash) {
        String id = "rule-" + UUID.randomUUID().toString().substring(0, 8);
        FaultRule rule = new FaultRule(id, type, src, dst, msgClass, packetHash, 1, 0);
        addRule(rule);
        return id;
    }

    public String failN(FaultType type, int count) {
        return failN(type, "*", "*", count);
    }

    public String failN(FaultType type, String src, String dst, int count) {
        String id = "rule-" + UUID.randomUUID().toString().substring(0, 8);
        FaultRule rule = new FaultRule(id, type, src, dst, null, null, count, 0);
        addRule(rule);
        return id;
    }

    public String failPacket(FaultType type, String packetHash) {
        String id = "rule-" + UUID.randomUUID().toString().substring(0, 8);
        FaultRule rule = new FaultRule(id, type, "*", "*", null, packetHash, 1, 0);
        addRule(rule);
        return id;
    }

    public int getActivationCount(String ruleId) {
        AtomicInteger counter = activationCounters.get(ruleId);
        return counter != null ? counter.get() : 0;
    }

    public void reset() {
        rules.clear();
        activationCounters.clear();
        enabled.set(false);
        if (metrics != null) {
            metrics.reset();
        }
        log.debug("FaultInjector reset complete. All rules and counters cleared.");
    }

    // ---------------------------------------------------------------- FaultInterceptor Implementation

    @Override
    public boolean allowGossipPush(String src, String dst, MeshPacket packet) {
        if (!enabled.get()) return true;
        String hash = packet != null ? packet.getPacketHash() : null;

        for (FaultRule rule : rules) {
            if ((rule.faultType() == FaultType.DROP || rule.faultType() == FaultType.DELAY)
                    && rule.matches(src, dst, packet, hash)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) metrics.recordFault(rule.faultType());
                    log.warn("Fault applied [{}]: withheld/dropped gossip push from {} to {} for packet {}",
                            rule.faultType(), src, dst, hash != null ? hash.substring(0, Math.min(12, hash.length())) : "?");
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int getGossipDuplicateCount(String src, String dst, MeshPacket packet) {
        if (!enabled.get()) return 1;
        String hash = packet != null ? packet.getPacketHash() : null;

        for (FaultRule rule : rules) {
            if (rule.faultType() == FaultType.DUPLICATE && rule.matches(src, dst, packet, hash)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) metrics.recordFault(FaultType.DUPLICATE);
                    int dups = rule.occurrenceLimit() > 1 ? rule.occurrenceLimit() : 5;
                    log.warn("Fault applied [DUPLICATE]: duplicating gossip push from {} to {} (count={})",
                            src, dst, dups);
                    return dups;
                }
            }
        }
        return 1;
    }

    @Override
    public boolean allowSyncMessage(String src, String dst, Object message) {
        if (!enabled.get()) return true;

        for (FaultRule rule : rules) {
            if ((rule.faultType() == FaultType.DROP || rule.faultType() == FaultType.MALFORMED_SYNC_MESSAGE)
                    && rule.matches(src, dst, message, null)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) metrics.recordFault(rule.faultType());
                    log.warn("Fault applied [{}]: dropped/rejected sync message {} from {} to {}",
                            rule.faultType(), message.getClass().getSimpleName(), src, dst);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean isPeerAvailable(String src, String target) {
        if (!enabled.get()) return true;

        for (FaultRule rule : rules) {
            if (rule.faultType() == FaultType.PEER_UNAVAILABLE && rule.matches(src, target, null, null)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) metrics.recordFault(FaultType.PEER_UNAVAILABLE);
                    log.warn("Fault applied [PEER_UNAVAILABLE]: peer {} unavailable for sync from {}", target, src);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public MeshPacket interceptPacketPayload(String src, String dst, MeshPacket packet) {
        if (!enabled.get() || packet == null) return packet;
        String hash = packet.getPacketHash();

        for (FaultRule rule : rules) {
            if (rule.faultType() == FaultType.CORRUPTED_PACKET_PAYLOAD && rule.matches(src, dst, packet, hash)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) metrics.recordFault(FaultType.CORRUPTED_PACKET_PAYLOAD);
                    log.warn("Fault applied [CORRUPTED_PACKET_PAYLOAD]: corrupting ciphertext for packet {}",
                            hash != null ? hash.substring(0, Math.min(12, hash.length())) : "?");
                    MeshPacket corrupted = new MeshPacket();
                    corrupted.setPacketId(packet.getPacketId());
                    corrupted.setCreatedAt(packet.getCreatedAt());
                    corrupted.setTtl(packet.getTtl());
                    // Corrupt ciphertext payload so decryption fails
                    corrupted.setCiphertext(packet.getCiphertext() != null ? packet.getCiphertext() + "_CORRUPTED" : "INVALID_CIPHERTEXT");
                    return corrupted;
                }
            }
        }
        return packet;
    }

    @Override
    public boolean allowBridgeUpload(String bridgeNodeId, MeshPacket packet) {
        if (!enabled.get()) return true;
        String hash = packet != null ? packet.getPacketHash() : null;

        for (FaultRule rule : rules) {
            if (rule.faultType() == FaultType.BRIDGE_UNAVAILABLE && rule.matches(bridgeNodeId, "*", packet, hash)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) metrics.recordFault(FaultType.BRIDGE_UNAVAILABLE);
                    log.warn("Fault applied [BRIDGE_UNAVAILABLE]: bridge {} upload failed for packet {}",
                            bridgeNodeId, hash != null ? hash.substring(0, Math.min(12, hash.length())) : "?");
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void inspectSettlementAttempt(String packetHash, int attempt) throws Exception {
        if (!enabled.get()) return;

        for (FaultRule rule : rules) {
            if (rule.faultType() == FaultType.TRANSIENT_DATABASE_FAILURE && rule.matches("*", "*", null, packetHash)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) {
                        metrics.recordFault(FaultType.TRANSIENT_DATABASE_FAILURE);
                        metrics.recordRetry();
                    }
                    log.warn("Fault applied [TRANSIENT_DATABASE_FAILURE]: throwing OptimisticLockException for packet {} on attempt {}",
                            packetHash != null ? packetHash.substring(0, Math.min(12, packetHash.length())) : "?", attempt);
                    throw new OptimisticLockException("Simulated transient optimistic lock contention on attempt " + attempt);
                }
            }
        }
    }

    @Override
    public boolean allowPostCommitResponse(String packetHash) {
        if (!enabled.get()) return true;

        for (FaultRule rule : rules) {
            if (rule.faultType() == FaultType.STALE_RESPONSE && rule.matches("*", "*", null, packetHash)) {
                AtomicInteger counter = activationCounters.get(rule.faultId());
                if (counter != null && counter.incrementAndGet() <= rule.occurrenceLimit()) {
                    if (metrics != null) metrics.recordFault(FaultType.STALE_RESPONSE);
                    log.warn("Fault applied [STALE_RESPONSE]: dropping post-commit HTTP response for packet {}",
                            packetHash != null ? packetHash.substring(0, Math.min(12, packetHash.length())) : "?");
                    return false;
                }
            }
        }
        return true;
    }
}

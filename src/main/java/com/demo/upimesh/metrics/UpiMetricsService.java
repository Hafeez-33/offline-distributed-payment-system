package com.demo.upimesh.metrics;

import com.demo.upimesh.fault.FaultType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Centralized Micrometer metric registry and recording service for Phase 8.
 *
 * Enforces strict bounded cardinality:
 * - Labels are strictly restricted to bounded enums/constants (status, reason, fault_type, device_role, result).
 * - NEVER uses high-cardinality labels (packetHash, transactionId, walletId, ownerVpa, requestId, nonce).
 *
 * Financial Non-Authoritative Invariant:
 * - All gauges and metric values are purely observational telemetry.
 * - PostgreSQL remains the sole authoritative financial store.
 */
@Service
public class UpiMetricsService {

    private final MeterRegistry registry;

    // Transaction Counters
    private final Counter txAttemptedCounter;
    private final Counter txSettledOnlineCounter;
    private final Counter txSettledOfflineCounter;
    private final Counter txDuplicateCounter;
    private final Counter txPendingGapCounter;
    private final Counter txRetriesCounter;
    private final ConcurrentHashMap<String, Counter> txRejectedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> txConflictingCounters = new ConcurrentHashMap<>();

    // Latency Timers
    private final Timer settlementLatencyOnlineTimer;
    private final Timer settlementLatencyOfflineTimer;

    // Wallet Counters & Gauges
    private final Counter walletAllocatedCounter;
    private final Counter walletReconciledCounter;
    private final Counter walletDisputedCounter;
    private final Counter walletAuditRequiredCounter;
    private final Counter walletExpiredCounter;
    private final LongAdder cumulativeEscrowAllocated = new LongAdder();

    // Mesh Counters
    private final Counter meshPacketsReceivedNodeCounter;
    private final Counter meshPacketsReceivedBridgeCounter;
    private final Counter meshPacketsForwardedCounter;
    private final Counter meshGossipRoundsCounter;
    private final Counter meshSyncInSyncCounter;
    private final Counter meshSyncRepairedCounter;
    private final Counter meshSyncFailuresCounter;
    private final Counter meshConvergenceSuccessCounter;
    private final Counter meshConvergenceFailureCounter;
    private final Counter meshPartitionEventsCounter;
    private final Counter meshHealEventsCounter;
    private final Counter meshBridgeFlushesCounter;

    // Fault & Invariant Counters
    private final ConcurrentHashMap<String, Counter> faultInjectionCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> invariantViolationCounters = new ConcurrentHashMap<>();
    private final Counter faultDropsCounter;
    private final Counter faultDuplicatesCounter;
    private final Counter faultDelaysCounter;
    private final Counter faultReordersCounter;
    private final Counter faultPartitionsCounter;
    private final Counter faultRecoveriesCounter;

    // Observational In-Memory Tracking for Gauges
    private final AtomicLong currentDisputedWalletsCount = new AtomicLong(0);
    private final AtomicLong currentPendingGapsCount = new AtomicLong(0);

    @Autowired
    public UpiMetricsService(MeterRegistry registry) {
        this.registry = registry;

        // ---- Transaction Meters ----
        this.txAttemptedCounter = Counter.builder("upi.transactions.attempted")
                .description("Total number of transaction ingestion attempts")
                .register(registry);

        this.txSettledOnlineCounter = Counter.builder("upi.transactions.settled")
                .tag("type", "online")
                .description("Total number of online liquid transactions successfully settled")
                .register(registry);

        this.txSettledOfflineCounter = Counter.builder("upi.transactions.settled")
                .tag("type", "offline")
                .description("Total number of offline wallet transactions successfully settled")
                .register(registry);

        this.txDuplicateCounter = Counter.builder("upi.transactions.duplicate")
                .description("Total number of duplicate or in-flight duplicate transactions suppressed")
                .register(registry);

        this.txPendingGapCounter = Counter.builder("upi.transactions.pending.gap")
                .description("Total number of transactions staged due to missing prior sequence counters")
                .register(registry);

        this.txRetriesCounter = Counter.builder("upi.settlement.retries")
                .description("Total number of database settlement retries executed due to optimistic lock collisions")
                .register(registry);

        this.settlementLatencyOnlineTimer = Timer.builder("upi.settlement.latency")
                .tag("type", "online")
                .description("Latency distribution of online settlement transactions")
                .publishPercentileHistogram()
                .register(registry);

        this.settlementLatencyOfflineTimer = Timer.builder("upi.settlement.latency")
                .tag("type", "offline")
                .description("Latency distribution of offline wallet settlement transactions")
                .publishPercentileHistogram()
                .register(registry);

        // ---- Wallet Meters ----
        this.walletAllocatedCounter = Counter.builder("upi.wallets.allocated")
                .description("Total number of offline wallets allocated")
                .register(registry);

        this.walletReconciledCounter = Counter.builder("upi.wallets.reconciled")
                .description("Total number of offline wallets reconciled and closed")
                .register(registry);

        this.walletDisputedCounter = Counter.builder("upi.wallets.disputed")
                .description("Total number of offline wallets marked LOCKED_DISPUTED due to double-spend forks")
                .register(registry);

        this.walletAuditRequiredCounter = Counter.builder("upi.wallets.audit.required")
                .description("Total number of offline wallets marked AUDIT_REQUIRED due to unresolved sequence gap timeouts")
                .register(registry);

        this.walletExpiredCounter = Counter.builder("upi.wallets.expired")
                .description("Total number of offline wallet certificates detected as expired")
                .register(registry);

        Gauge.builder("upi.wallets.escrow.allocated.total", cumulativeEscrowAllocated, LongAdder::doubleValue)
                .description("Observational total amount of escrow allocated to offline wallets (rupees)")
                .register(registry);

        Gauge.builder("upi.wallets.disputed.current", currentDisputedWalletsCount, AtomicLong::doubleValue)
                .description("Observational current count of disputed offline wallets")
                .register(registry);

        Gauge.builder("upi.transactions.pending.gap.current", currentPendingGapsCount, AtomicLong::doubleValue)
                .description("Observational current count of transactions staged in sequence gap")
                .register(registry);

        // ---- Mesh Meters ----
        this.meshPacketsReceivedNodeCounter = Counter.builder("upi.mesh.packets.received")
                .tag("device_role", "node")
                .description("Total mesh packets received by regular virtual nodes")
                .register(registry);

        this.meshPacketsReceivedBridgeCounter = Counter.builder("upi.mesh.packets.received")
                .tag("device_role", "bridge")
                .description("Total mesh packets received by bridge nodes")
                .register(registry);

        this.meshPacketsForwardedCounter = Counter.builder("upi.mesh.packets.forwarded")
                .description("Total mesh packets successfully forwarded across peer hops")
                .register(registry);

        this.meshGossipRoundsCounter = Counter.builder("upi.mesh.gossip.rounds")
                .description("Total epidemic push gossip rounds executed")
                .register(registry);

        this.meshSyncInSyncCounter = Counter.builder("upi.mesh.sync.rounds")
                .tag("result", "in_sync")
                .description("Pairwise anti-entropy sync rounds where peers were already in sync")
                .register(registry);

        this.meshSyncRepairedCounter = Counter.builder("upi.mesh.sync.rounds")
                .tag("result", "repaired")
                .description("Pairwise anti-entropy sync rounds where missing packets were repaired")
                .register(registry);

        this.meshSyncFailuresCounter = Counter.builder("upi.mesh.sync.failures")
                .description("Pairwise anti-entropy sync failures or timeouts")
                .register(registry);

        this.meshConvergenceSuccessCounter = Counter.builder("upi.mesh.convergence.checks")
                .tag("converged", "true")
                .description("Mesh convergence checks where all reachable nodes shared identical state digests")
                .register(registry);

        this.meshConvergenceFailureCounter = Counter.builder("upi.mesh.convergence.checks")
                .tag("converged", "false")
                .description("Mesh convergence checks where reachable nodes exhibited divergent state digests")
                .register(registry);

        this.meshPartitionEventsCounter = Counter.builder("upi.mesh.partition.events")
                .description("Total network partition and link severing events triggered")
                .register(registry);

        this.meshHealEventsCounter = Counter.builder("upi.mesh.heal.events")
                .description("Total network heal and link restoration events triggered")
                .register(registry);

        this.meshBridgeFlushesCounter = Counter.builder("upi.mesh.bridge.flushes")
                .description("Total bridge upload batch flush operations")
                .register(registry);

        // ---- Fault & Reliability Meters ----
        this.faultDropsCounter = Counter.builder("upi.fault.drops")
                .description("Total packets dropped by fault rules")
                .register(registry);

        this.faultDuplicatesCounter = Counter.builder("upi.fault.duplicates")
                .description("Total packet duplicates injected by fault rules")
                .register(registry);

        this.faultDelaysCounter = Counter.builder("upi.fault.delays")
                .description("Total packet transmissions delayed by fault rules")
                .register(registry);

        this.faultReordersCounter = Counter.builder("upi.fault.reorders")
                .description("Total packet transmissions reordered by fault rules")
                .register(registry);

        this.faultPartitionsCounter = Counter.builder("upi.fault.partitions")
                .description("Total partition faults injected")
                .register(registry);

        this.faultRecoveriesCounter = Counter.builder("upi.fault.recoveries")
                .description("Total successful recoveries after fault injection")
                .register(registry);
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    // =========================================================================
    // Transaction Metric Recorders
    // =========================================================================

    public void recordTransactionAttempt() {
        txAttemptedCounter.increment();
    }

    public void recordTransactionSettled(boolean isOffline, long durationNanos) {
        if (isOffline) {
            txSettledOfflineCounter.increment();
            settlementLatencyOfflineTimer.record(durationNanos, TimeUnit.NANOSECONDS);
        } else {
            txSettledOnlineCounter.increment();
            settlementLatencyOnlineTimer.record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void recordTransactionDuplicate() {
        txDuplicateCounter.increment();
    }

    public void recordTransactionPendingGap() {
        txPendingGapCounter.increment();
        currentPendingGapsCount.incrementAndGet();
    }

    public void recordPendingGapResolved() {
        currentPendingGapsCount.updateAndGet(val -> Math.max(0, val - 1));
    }

    public void recordTransactionRejected(String rawReason) {
        String boundedReason = sanitizeReason(rawReason);
        txRejectedCounters.computeIfAbsent(boundedReason, r ->
                Counter.builder("upi.transactions.rejected")
                        .tag("reason", r)
                        .description("Total number of rejected transactions by failure reason")
                        .register(registry)
        ).increment();
    }

    public void recordTransactionConflicting(String rawReason) {
        String boundedReason = sanitizeReason(rawReason);
        txConflictingCounters.computeIfAbsent(boundedReason, r ->
                Counter.builder("upi.transactions.conflicting")
                        .tag("reason", r)
                        .description("Total number of conflicting/double-spend transactions detected")
                        .register(registry)
        ).increment();
    }

    public void recordSettlementRetry() {
        txRetriesCounter.increment();
    }

    // =========================================================================
    // Wallet Metric Recorders
    // =========================================================================

    public void recordWalletAllocated(BigDecimal amount) {
        walletAllocatedCounter.increment();
        if (amount != null && amount.signum() > 0) {
            cumulativeEscrowAllocated.add(amount.longValue());
        }
    }

    public void recordWalletReconciled() {
        walletReconciledCounter.increment();
    }

    public void recordWalletDisputed() {
        walletDisputedCounter.increment();
        currentDisputedWalletsCount.incrementAndGet();
    }

    public void recordWalletAuditRequired() {
        walletAuditRequiredCounter.increment();
    }

    public void recordWalletExpired() {
        walletExpiredCounter.increment();
    }

    // =========================================================================
    // Mesh Metric Recorders
    // =========================================================================

    public void recordMeshPacketReceived(boolean isBridge) {
        if (isBridge) {
            meshPacketsReceivedBridgeCounter.increment();
        } else {
            meshPacketsReceivedNodeCounter.increment();
        }
    }

    public void recordMeshPacketForwarded(int count) {
        if (count > 0) {
            meshPacketsForwardedCounter.increment(count);
        }
    }

    public void recordMeshGossipRound() {
        meshGossipRoundsCounter.increment();
    }

    public void recordMeshSyncRound(boolean wasInSync, int transfers) {
        if (wasInSync) {
            meshSyncInSyncCounter.increment();
        } else {
            meshSyncRepairedCounter.increment();
            if (transfers > 0) {
                meshPacketsForwardedCounter.increment(transfers);
            }
        }
    }

    public void recordMeshSyncFailure() {
        meshSyncFailuresCounter.increment();
    }

    public void recordMeshConvergenceCheck(boolean converged) {
        if (converged) {
            meshConvergenceSuccessCounter.increment();
        } else {
            meshConvergenceFailureCounter.increment();
        }
    }

    public void recordMeshPartitionEvent() {
        meshPartitionEventsCounter.increment();
    }

    public void recordMeshHealEvent() {
        meshHealEventsCounter.increment();
    }

    public void recordMeshBridgeFlush() {
        meshBridgeFlushesCounter.increment();
    }

    // =========================================================================
    // Fault & Reliability Metric Recorders
    // =========================================================================

    public void recordFaultInjection(FaultType type) {
        String faultTypeName = (type != null) ? type.name().toLowerCase() : "unknown";
        faultInjectionCounters.computeIfAbsent(faultTypeName, ft ->
                Counter.builder("upi.fault.injections")
                        .tag("fault_type", ft)
                        .description("Total fault injections by fault type")
                        .register(registry)
        ).increment();

        if (type != null) {
            switch (type) {
                case DROP -> faultDropsCounter.increment();
                case DUPLICATE -> faultDuplicatesCounter.increment();
                case DELAY -> faultDelaysCounter.increment();
                case REORDER -> faultReordersCounter.increment();
                case PARTITION -> faultPartitionsCounter.increment();
                default -> {}
            }
        }
    }

    public void recordFaultRecovery() {
        faultRecoveriesCounter.increment();
    }

    public void recordInvariantViolation(String invariantId) {
        String boundedId = (invariantId != null && !invariantId.isBlank()) ? invariantId.trim().toUpperCase() : "UNKNOWN";
        invariantViolationCounters.computeIfAbsent(boundedId, id ->
                Counter.builder("upi.invariant.violations")
                        .tag("invariant_id", id)
                        .description("Total invariant violations detected")
                        .register(registry)
        ).increment();
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        String cleaned = reason.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        // Limit max length to ensure strict bounded cardinality
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        return cleaned;
    }
}

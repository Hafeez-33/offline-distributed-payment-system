package com.demo.upimesh.fault;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory reliability metrics registry for Phase 5 distributed fault injection testing.
 * Provides machine-readable counters for test assertions and prepares for future Phase 8 metric exports.
 */
@Component
public class ReliabilityMetrics {

    private final LongAdder faultInjectionsTotal = new LongAdder();
    private final LongAdder faultDropsTotal = new LongAdder();
    private final LongAdder faultDuplicatesTotal = new LongAdder();
    private final LongAdder faultDelaysTotal = new LongAdder();
    private final LongAdder faultReordersTotal = new LongAdder();
    private final LongAdder faultPartitionsTotal = new LongAdder();
    private final LongAdder faultRecoveriesTotal = new LongAdder();
    private final LongAdder retryAttemptsTotal = new LongAdder();
    private final LongAdder reconciliationRecoveryTotal = new LongAdder();
    private final LongAdder invariantViolationsTotal = new LongAdder();

    public void recordFault(FaultType type) {
        faultInjectionsTotal.increment();
        if (type != null) {
            switch (type) {
                case DROP -> faultDropsTotal.increment();
                case DUPLICATE -> faultDuplicatesTotal.increment();
                case DELAY -> faultDelaysTotal.increment();
                case REORDER -> faultReordersTotal.increment();
                case PARTITION -> faultPartitionsTotal.increment();
                default -> {}
            }
        }
    }

    public void recordRetry() {
        retryAttemptsTotal.increment();
    }

    public void recordRecovery() {
        faultRecoveriesTotal.increment();
    }

    public void recordReconciliation() {
        reconciliationRecoveryTotal.increment();
    }

    public void recordViolation() {
        invariantViolationsTotal.increment();
    }

    public long getFaultInjectionsTotal() {
        return faultInjectionsTotal.sum();
    }

    public long getFaultDropsTotal() {
        return faultDropsTotal.sum();
    }

    public long getFaultDuplicatesTotal() {
        return faultDuplicatesTotal.sum();
    }

    public long getFaultDelaysTotal() {
        return faultDelaysTotal.sum();
    }

    public long getFaultReordersTotal() {
        return faultReordersTotal.sum();
    }

    public long getFaultPartitionsTotal() {
        return faultPartitionsTotal.sum();
    }

    public long getFaultRecoveriesTotal() {
        return faultRecoveriesTotal.sum();
    }

    public long getRetryAttemptsTotal() {
        return retryAttemptsTotal.sum();
    }

    public long getReconciliationRecoveryTotal() {
        return reconciliationRecoveryTotal.sum();
    }

    public long getInvariantViolationsTotal() {
        return invariantViolationsTotal.sum();
    }

    public void reset() {
        faultInjectionsTotal.reset();
        faultDropsTotal.reset();
        faultDuplicatesTotal.reset();
        faultDelaysTotal.reset();
        faultReordersTotal.reset();
        faultPartitionsTotal.reset();
        faultRecoveriesTotal.reset();
        retryAttemptsTotal.reset();
        reconciliationRecoveryTotal.reset();
        invariantViolationsTotal.reset();
    }
}

package com.demo.upimesh.dto;

import java.util.List;

/**
 * Authoritative reliability report containing Phase 5 metrics and server-evaluated
 * Machine-Checkable Invariants (I1-I12).
 */
public record ReliabilityReportDto(
        MetricsSummary metrics,
        List<InvariantResult> invariants
) {
    public record MetricsSummary(
            long faultInjectionsTotal,
            long faultDropsTotal,
            long faultDuplicatesTotal,
            long faultDelaysTotal,
            long faultReordersTotal,
            long faultPartitionsTotal,
            long faultRecoveriesTotal,
            long retryAttemptsTotal,
            long reconciliationRecoveryTotal,
            long invariantViolationsTotal
    ) {}

    public record InvariantResult(
            String id,
            String name,
            String status,
            String details
    ) {}
}

package com.demo.upimesh.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * High-level aggregate summary of system health, node counts, balance totals, and alerts.
 * Contains no large collections or unpaginated entity lists.
 */
public record DashboardOverviewDto(
        String systemStatus,
        int totalDevices,
        int onlineBridges,
        boolean meshConverged,
        int severedLinkCount,
        int totalHeldPackets,
        int totalAccounts,
        BigDecimal totalLiquidBalance,
        BigDecimal totalEscrowBalance,
        int activeWallets,
        int disputedWallets,
        long settledTxCount,
        long conflictingTxCount,
        long pendingGapCount,
        int activeFaultRules,
        long invariantViolations,
        Instant timestamp
) {}

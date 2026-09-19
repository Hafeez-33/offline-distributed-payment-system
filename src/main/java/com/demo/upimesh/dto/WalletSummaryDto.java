package com.demo.upimesh.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Authoritative ledger view of all offline wallet allocations alongside live account balances.
 */
public record WalletSummaryDto(
        List<WalletItem> wallets,
        List<AccountItem> accounts
) {
    public record WalletItem(
            String walletId,
            String ownerVpa,
            BigDecimal allocatedAmount,
            BigDecimal settledAmount,
            BigDecimal remainingAmount,
            Long walletEpoch,
            Long lastSettledCounter,
            Instant validUntil,
            String status
    ) {}

    public record AccountItem(
            String vpa,
            String holderName,
            BigDecimal liquidBalance,
            BigDecimal offlineLockedBalance,
            Long version
    ) {}
}

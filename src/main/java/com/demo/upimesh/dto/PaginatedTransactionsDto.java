package com.demo.upimesh.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Paginated response container for dashboard transaction ledger queries.
 */
public record PaginatedTransactionsDto(
        List<TransactionItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public record TransactionItem(
            Long id,
            String packetHash,
            String senderVpa,
            String receiverVpa,
            BigDecimal amount,
            Instant signedAt,
            Instant settledAt,
            String bridgeNodeId,
            int hopCount,
            String status,
            String walletId,
            Long sequenceCounter,
            String conflictReason,
            Long winningTransactionId,
            String receiptSignature
    ) {}
}

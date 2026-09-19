package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Authoritative record of an escrow-backed offline wallet allocation.
 *
 * Tracks the cumulative settled amount and monotonic sequence counter
 * for detecting forks and double spending.
 */
@Entity
@Table(name = "offline_wallets")
public class OfflineWallet {

    @Id
    private String walletId;

    @Column(nullable = false)
    private String ownerVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal settledAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingAmount;

    @Column(nullable = false)
    private Long walletEpoch;

    @Column(nullable = false)
    private Long lastSettledCounter;

    @Column(nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletStatus status;

    @Version
    private Long version;

    public enum WalletStatus {
        ACTIVE,
        EXPIRED,
        LOCKED_DISPUTED,
        AUDIT_REQUIRED,
        RECONCILED_CLOSED
    }

    public OfflineWallet() {}

    public OfflineWallet(String walletId, String ownerVpa, BigDecimal allocatedAmount,
                         Long walletEpoch, Instant validUntil) {
        this.walletId = walletId;
        this.ownerVpa = ownerVpa;
        this.allocatedAmount = allocatedAmount;
        this.settledAmount = BigDecimal.ZERO;
        this.remainingAmount = allocatedAmount;
        this.walletEpoch = walletEpoch;
        this.lastSettledCounter = 0L;
        this.validUntil = validUntil;
        this.status = WalletStatus.ACTIVE;
    }

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public String getOwnerVpa() { return ownerVpa; }
    public void setOwnerVpa(String ownerVpa) { this.ownerVpa = ownerVpa; }

    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }

    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }

    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public BigDecimal getRemainingEscrow() { return remainingAmount; }
    public void setRemainingAmount(BigDecimal remainingAmount) { this.remainingAmount = remainingAmount; }

    public Long getWalletEpoch() { return walletEpoch; }
    public void setWalletEpoch(Long walletEpoch) { this.walletEpoch = walletEpoch; }

    public Long getLastSettledCounter() { return lastSettledCounter; }
    public void setLastSettledCounter(Long lastSettledCounter) { this.lastSettledCounter = lastSettledCounter; }

    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }

    public WalletStatus getStatus() { return status; }
    public void setStatus(WalletStatus status) { this.status = status; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}

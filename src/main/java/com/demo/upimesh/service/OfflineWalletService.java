package com.demo.upimesh.service;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Service managing offline wallet lifecycle, escrow allocations, certificate issuance,
 * and reconciliation.
 *
 * Invariant:
 *   Account total funds = liquid available balance + offline locked balance
 */
@Service
public class OfflineWalletService {

    private static final Logger log = LoggerFactory.getLogger(OfflineWalletService.class);

    private final AccountRepository accountRepository;
    private final OfflineWalletRepository walletRepository;
    private final ServerKeyHolder serverKeyHolder;
    private final SignatureService signatureService;

    public OfflineWalletService(AccountRepository accountRepository,
                                  OfflineWalletRepository walletRepository,
                                  ServerKeyHolder serverKeyHolder,
                                  SignatureService signatureService) {
        this.accountRepository = accountRepository;
        this.walletRepository = walletRepository;
        this.serverKeyHolder = serverKeyHolder;
        this.signatureService = signatureService;
    }

    /**
     * Allocation record containing the wallet entity and the server-signed certificate.
     */
    public record AllocationResult(OfflineWallet wallet, OfflineWalletCertificate certificate) {}

    /**
     * Allocate ₹X from the account's liquid balance into offline escrow.
     * Creates an OfflineWallet in ACTIVE state and issues a signed OfflineWalletCertificate.
     *
     * @param ownerVpa Account VPA requesting offline allocation
     * @param amount Amount to allocate to offline wallet
     * @param durationHours How long the wallet certificate is valid (default e.g. 24h)
     */
    @Transactional
    public AllocationResult allocate(String ownerVpa, BigDecimal amount, long durationHours) {
        Objects.requireNonNull(ownerVpa, "ownerVpa must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Allocation amount must be strictly positive");
        }

        Account account = accountRepository.findById(ownerVpa.trim())
                .orElseThrow(() -> new IllegalArgumentException("Account not found for VPA: " + ownerVpa));

        if (account.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient liquid balance for offline allocation. Available: "
                    + account.getBalance() + ", Requested: " + amount);
        }

        if (account.getPublicKey() == null || account.getPublicKey().isBlank()) {
            throw new IllegalStateException("Account does not have a registered public key for offline signing");
        }

        // Determine epoch: check if prior wallets exist for this owner
        List<OfflineWallet> existingWallets = walletRepository.findByOwnerVpaOrderByWalletEpochDesc(account.getVpa());
        long nextEpoch = 1L;
        if (!existingWallets.isEmpty()) {
            OfflineWallet latest = existingWallets.get(0);
            if (latest.getStatus() == OfflineWallet.WalletStatus.ACTIVE) {
                throw new IllegalStateException("An active offline wallet already exists for this account. Reconcile it before allocating a new epoch.");
            }
            nextEpoch = latest.getWalletEpoch() + 1;
        }

        // Debit liquid balance, credit offline escrow
        account.setBalance(account.getBalance().subtract(amount));
        account.setOfflineLockedBalance(account.getOfflineLockedBalance().add(amount));
        accountRepository.save(account);

        Instant now = Instant.now();
        Instant validUntil = now.plus(durationHours, ChronoUnit.HOURS);
        String walletId = "WLT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        OfflineWallet wallet = new OfflineWallet(
                walletId,
                account.getVpa(),
                amount,
                nextEpoch,
                validUntil
        );
        wallet = walletRepository.save(wallet);

        // Build unsigned certificate
        OfflineWalletCertificate unsignedCert = new OfflineWalletCertificate(
                walletId,
                account.getVpa(),
                account.getPublicKey(),
                amount,
                nextEpoch,
                now.toEpochMilli(),
                validUntil.toEpochMilli(),
                0L,
                ""
        );

        // Sign certificate using server's Ed25519 issuer key
        try {
            String signature = signatureService.signCertificate(unsignedCert, serverKeyHolder.getIssuerPrivateKey());
            OfflineWalletCertificate signedCert = new OfflineWalletCertificate(
                    unsignedCert.walletId(),
                    unsignedCert.ownerVpa(),
                    unsignedCert.ownerPublicKey(),
                    unsignedCert.allocatedAmount(),
                    unsignedCert.walletEpoch(),
                    unsignedCert.validFrom(),
                    unsignedCert.validUntil(),
                    unsignedCert.initialCounter(),
                    signature
            );

            log.info("Allocated offline wallet: id={}, owner={}, amount={}, epoch={}, validUntil={}",
                    walletId, account.getVpa(), amount, nextEpoch, validUntil);

            return new AllocationResult(wallet, signedCert);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign offline wallet certificate", e);
        }
    }

    /**
     * Reconcile an offline wallet and return unused escrow to sender's liquid balance.
     *
     * Unused escrow = allocatedAmount - settledAmount.
     */
    @Transactional
    public OfflineWallet reconcileAndClose(String walletId) {
        OfflineWallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        if (wallet.getStatus() == OfflineWallet.WalletStatus.RECONCILED_CLOSED) {
            return wallet;
        }

        Account account = accountRepository.findById(wallet.getOwnerVpa())
                .orElseThrow(() -> new IllegalArgumentException("Account not found for VPA: " + wallet.getOwnerVpa()));

        BigDecimal unusedEscrow = wallet.getRemainingAmount();
        if (unusedEscrow.compareTo(BigDecimal.ZERO) > 0) {
            // Deduct unused escrow from locked balance and return to liquid balance
            account.setOfflineLockedBalance(account.getOfflineLockedBalance().subtract(unusedEscrow));
            account.setBalance(account.getBalance().add(unusedEscrow));
            accountRepository.save(account);
            wallet.setRemainingAmount(BigDecimal.ZERO);
        }

        wallet.setStatus(OfflineWallet.WalletStatus.RECONCILED_CLOSED);
        OfflineWallet closedWallet = walletRepository.save(wallet);

        log.info("Reconciled offline wallet: id={}, owner={}, unusedEscrowReturned={}",
                walletId, wallet.getOwnerVpa(), unusedEscrow);

        return closedWallet;
    }
}

package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where the actual financial ledger update happens.
 *
 * Implements bounded optimistic-locking retry:
 *   - Each attempt runs in a fresh independent database transaction (PROPAGATION_REQUIRES_NEW).
 *   - On OptimisticLockException or transient data access exceptions, retries up to 3 times
 *     with exponential backoff and jitter.
 *   - If all attempts are exhausted, throws TransientSettlementException so BridgeIngestionService
 *     can release in-flight locks and return TRANSIENT_FAILURE.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final int MAX_ATTEMPTS = 3;

    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private com.demo.upimesh.model.OfflineWalletRepository walletRepository;
    @Autowired private com.demo.upimesh.crypto.ServerKeyHolder serverKeyHolder;
    @Autowired private com.demo.upimesh.crypto.SignatureService signatureService;
    @Autowired private PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Value("${upi.mesh.sequence-gap-window-seconds:1800}")
    private long gapWindowSeconds;

    /**
     * Settle payment with bounded optimistic-lock retry.
     */
    public Transaction settle(PaymentInstruction instruction, String packetHash,
                              String bridgeNodeId, int hopCount) {
        Throwable lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executeInNewTransaction(instruction, packetHash, bridgeNodeId, hopCount);
            } catch (Exception e) {
                if (!isRetryable(e)) {
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException(e);
                }
                lastException = e;
                log.warn("Optimistic lock / transient conflict on attempt {}/{} for packet {}: {}",
                        attempt, MAX_ATTEMPTS, packetHash.substring(0, Math.min(12, packetHash.length())), e.getMessage());

                if (attempt < MAX_ATTEMPTS) {
                    applyBackoff(attempt);
                }
            }
        }

        throw new TransientSettlementException(
                "Settlement exhausted retries after " + MAX_ATTEMPTS + " attempts for packet " + packetHash,
                lastException);
    }

    private Transaction executeInNewTransaction(PaymentInstruction instruction, String packetHash,
                                                String bridgeNodeId, int hopCount) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return txTemplate.execute(status -> {
            // Check if already settled in DB (authoritative barrier)
            var existing = transactions.findByPacketHash(packetHash);
            if (existing.isPresent()) {
                log.info("Packet {} already committed in DB, returning existing record", packetHash);
                return existing.get();
            }

            // Phase 3: Offline Wallet transaction vs Phase 1/2 Online liquid transaction
            if (instruction.getWalletId() != null && !instruction.getWalletId().isBlank()) {
                return settleOfflineTransaction(instruction, packetHash, bridgeNodeId, hopCount);
            }

            return settleOnlineTransaction(instruction, packetHash, bridgeNodeId, hopCount);
        });
    }

    private Transaction settleOnlineTransaction(PaymentInstruction instruction, String packetHash,
                                                String bridgeNodeId, int hopCount) {
        Account sender = accounts.findById(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sender VPA: " + instruction.getSenderVpa()));

        Account receiver = accounts.findById(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown receiver VPA: " + instruction.getReceiverVpa()));

        BigDecimal amount = instruction.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        if (sender.getBalance().compareTo(amount) < 0) {
            log.warn("Insufficient balance: {} has ₹{}, tried to send ₹{}",
                    sender.getVpa(), sender.getBalance(), amount);
            return recordRejected(instruction, packetHash, bridgeNodeId, hopCount, "insufficient_balance");
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accounts.save(sender);
        accounts.save(receiver);

        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(amount);
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.SETTLED);
        Transaction saved = transactions.save(tx);
        signAndSetReceipt(saved, 0L);
        saved = transactions.save(saved);

        log.info("SETTLED ₹{} from {} to {} (packetHash={}, bridge={}, hops={})",
                amount, sender.getVpa(), receiver.getVpa(),
                packetHash.substring(0, Math.min(12, packetHash.length())) + "...", bridgeNodeId, hopCount);

        return saved;
    }

    /**
     * Phase 3 Sequence State Machine & Escrow Settlement.
     */
    private Transaction settleOfflineTransaction(PaymentInstruction instruction, String packetHash,
                                                 String bridgeNodeId, int hopCount) {
        String walletId = instruction.getWalletId().trim();
        long counter = instruction.getSequenceCounter() != null ? instruction.getSequenceCounter() : 1L;
        BigDecimal amount = instruction.getAmount();

        com.demo.upimesh.model.OfflineWallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown wallet ID: " + walletId));

        Account sender = accounts.findById(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException("Unknown sender VPA: " + instruction.getSenderVpa()));

        Account receiver = accounts.findById(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException("Unknown receiver VPA: " + instruction.getReceiverVpa()));

        // Check if wallet is already disputed
        if (wallet.getStatus() == com.demo.upimesh.model.OfflineWallet.WalletStatus.LOCKED_DISPUTED) {
            log.warn("Wallet {} is LOCKED_DISPUTED. Rejecting incoming transaction counter {}", walletId, counter);
            return recordRejected(instruction, packetHash, bridgeNodeId, hopCount, "wallet_locked_disputed");
        }

        long lastSettled = wallet.getLastSettledCounter();

        // -------------------------------------------------------------
        // FORK & DUPLICATE ANALYSIS: counter <= lastSettled
        // -------------------------------------------------------------
        if (counter <= lastSettled) {
            // Find existing transaction with this walletId and sequenceCounter
            var priorOpt = transactions.findByWalletIdAndSequenceCounter(walletId, counter);
            if (priorOpt.isPresent()) {
                Transaction priorTx = priorOpt.get();
                if (packetHash.equals(priorTx.getPacketHash())) {
                    log.info("Duplicate packet hash for settled counter {} in wallet {}", counter, walletId);
                    return priorTx;
                } else {
                    // Conflicting counter fork!
                    log.error("DOUBLE-SPEND DETECTED: Counter {} previously used by tx {} (hash {}), now re-used with new packet {}",
                            counter, priorTx.getId(), priorTx.getPacketHash(), packetHash);

                    wallet.setStatus(com.demo.upimesh.model.OfflineWallet.WalletStatus.LOCKED_DISPUTED);
                    walletRepository.save(wallet);

                    Transaction conflictTx = new Transaction();
                    conflictTx.setPacketHash(packetHash);
                    conflictTx.setWalletId(walletId);
                    conflictTx.setSequenceCounter(counter);
                    conflictTx.setSenderVpa(instruction.getSenderVpa());
                    conflictTx.setReceiverVpa(instruction.getReceiverVpa());
                    conflictTx.setAmount(amount);
                    conflictTx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
                    conflictTx.setSettledAt(Instant.now());
                    conflictTx.setBridgeNodeId(bridgeNodeId);
                    conflictTx.setHopCount(hopCount);
                    conflictTx.setStatus(Transaction.Status.CONFLICTING);
                    conflictTx.setConflictReason("double_spend_counter_collision");
                    conflictTx.setWinningTransactionId(priorTx.getId());
                    return transactions.save(conflictTx);
                }
            } else {
                // Should not happen unless DB corrupted, but treat as conflict
                wallet.setStatus(com.demo.upimesh.model.OfflineWallet.WalletStatus.LOCKED_DISPUTED);
                walletRepository.save(wallet);
                Transaction conflictTx = new Transaction();
                conflictTx.setPacketHash(packetHash);
                conflictTx.setWalletId(walletId);
                conflictTx.setSequenceCounter(counter);
                conflictTx.setSenderVpa(instruction.getSenderVpa());
                conflictTx.setReceiverVpa(instruction.getReceiverVpa());
                conflictTx.setAmount(amount);
                conflictTx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
                conflictTx.setSettledAt(Instant.now());
                conflictTx.setBridgeNodeId(bridgeNodeId);
                conflictTx.setHopCount(hopCount);
                conflictTx.setStatus(Transaction.Status.CONFLICTING);
                conflictTx.setConflictReason("double_spend_counter_collision");
                return transactions.save(conflictTx);
            }
        }

        // -------------------------------------------------------------
        // SEQUENCE GAP ANALYSIS: counter > lastSettled + 1
        // -------------------------------------------------------------
        if (counter > lastSettled + 1) {
            // Check if there is already a pending gap tx with this counter that has a different hash (fork in gap)
            var existingPendingOpt = transactions.findByWalletIdAndSequenceCounter(walletId, counter);
            if (existingPendingOpt.isPresent()) {
                Transaction existingPending = existingPendingOpt.get();
                if (!packetHash.equals(existingPending.getPacketHash())) {
                    log.error("CONFLICTING FORK IN GAP: wallet={}, counter={}, existingHash={}, newHash={}",
                            walletId, counter, existingPending.getPacketHash(), packetHash);
                    wallet.setStatus(com.demo.upimesh.model.OfflineWallet.WalletStatus.LOCKED_DISPUTED);
                    walletRepository.save(wallet);

                    Transaction conflictTx = new Transaction();
                    conflictTx.setPacketHash(packetHash);
                    conflictTx.setWalletId(walletId);
                    conflictTx.setSequenceCounter(counter);
                    conflictTx.setSenderVpa(instruction.getSenderVpa());
                    conflictTx.setReceiverVpa(instruction.getReceiverVpa());
                    conflictTx.setAmount(amount);
                    conflictTx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
                    conflictTx.setSettledAt(Instant.now());
                    conflictTx.setBridgeNodeId(bridgeNodeId);
                    conflictTx.setHopCount(hopCount);
                    conflictTx.setStatus(Transaction.Status.CONFLICTING);
                    conflictTx.setConflictReason("conflicting_fork_in_sequence_gap");
                    conflictTx.setWinningTransactionId(existingPending.getId());
                    return transactions.save(conflictTx);
                }
                return existingPending;
            }

            log.info("SEQUENCE GAP DETECTED: wallet={}, counter={}, expected={}. Staging transaction.",
                    walletId, counter, lastSettled + 1);

            Transaction pendingTx = new Transaction();
            pendingTx.setPacketHash(packetHash);
            pendingTx.setWalletId(walletId);
            pendingTx.setSequenceCounter(counter);
            pendingTx.setSenderVpa(instruction.getSenderVpa());
            pendingTx.setReceiverVpa(instruction.getReceiverVpa());
            pendingTx.setAmount(amount);
            pendingTx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
            pendingTx.setSettledAt(null); // not settled yet
            pendingTx.setBridgeNodeId(bridgeNodeId);
            pendingTx.setHopCount(hopCount);
            pendingTx.setStatus(Transaction.Status.PENDING_SEQUENCE_GAP);
            return transactions.save(pendingTx);
        }

        // -------------------------------------------------------------
        // IN-ORDER ARRIVAL: counter == lastSettled + 1
        // -------------------------------------------------------------
        return settleAndCascade(wallet, sender, receiver, instruction, packetHash, bridgeNodeId, hopCount);
    }

    private Transaction settleAndCascade(com.demo.upimesh.model.OfflineWallet wallet,
                                         Account sender,
                                         Account receiver,
                                         PaymentInstruction instruction,
                                         String packetHash,
                                         String bridgeNodeId,
                                         int hopCount) {
        BigDecimal amount = instruction.getAmount();
        long counter = instruction.getSequenceCounter() != null ? instruction.getSequenceCounter() : 1L;

        // Escrow solvency check: remaining amount in wallet
        if (wallet.getRemainingAmount().compareTo(amount) < 0) {
            log.warn("Offline wallet remaining escrow insufficient: wallet={}, remaining=₹{}, tried to spend ₹{}",
                    wallet.getWalletId(), wallet.getRemainingAmount(), amount);
            return recordRejected(instruction, packetHash, bridgeNodeId, hopCount, "insufficient_offline_escrow");
        }

        // Update Escrow accounting:
        // 1. subtract from sender offlineLockedBalance (DO NOT debit liquid balance again!)
        sender.setOfflineLockedBalance(sender.getOfflineLockedBalance().subtract(amount));
        // 2. credit receiver liquid balance
        receiver.setBalance(receiver.getBalance().add(amount));
        accounts.save(sender);
        accounts.save(receiver);

        // 3. update wallet: settledAmount += amount, remainingAmount -= amount, lastSettledCounter = counter
        wallet.setSettledAmount(wallet.getSettledAmount().add(amount));
        wallet.setRemainingAmount(wallet.getRemainingAmount().subtract(amount));
        wallet.setLastSettledCounter(counter);
        walletRepository.save(wallet);

        // 4. record transaction as SETTLED
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setWalletId(wallet.getWalletId());
        tx.setSequenceCounter(counter);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(amount);
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.SETTLED);
        Transaction saved = transactions.save(tx);
        signAndSetReceipt(saved, counter);
        saved = transactions.save(saved);

        log.info("OFFLINE SETTLED: wallet={}, counter={}, amount=₹{}, remainingEscrow=₹{}",
                wallet.getWalletId(), counter, amount, wallet.getRemainingAmount());

        // 5. Cascade any pending transactions waiting for sequence gap resolution
        cascadePendingGaps(wallet, sender, receiver);

        return saved;
    }

    private void cascadePendingGaps(com.demo.upimesh.model.OfflineWallet wallet,
                                    Account sender,
                                    Account receiver) {
        long nextExpected = wallet.getLastSettledCounter() + 1;
        while (true) {
            var nextPendingOpt = transactions.findByWalletIdAndSequenceCounter(wallet.getWalletId(), nextExpected);
            if (nextPendingOpt.isEmpty()) {
                break;
            }

            Transaction pendingTx = nextPendingOpt.get();
            if (pendingTx.getStatus() != Transaction.Status.PENDING_SEQUENCE_GAP) {
                break;
            }

            // Check if escrow is still sufficient
            if (wallet.getRemainingAmount().compareTo(pendingTx.getAmount()) < 0) {
                log.warn("Escrow exhausted during gap cascade for wallet {}, counter {}",
                        wallet.getWalletId(), pendingTx.getSequenceCounter());
                pendingTx.setStatus(Transaction.Status.REJECTED);
                pendingTx.setConflictReason("insufficient_offline_escrow");
                transactions.save(pendingTx);
                break;
            }

            // Debit sender offline locked balance, credit recipient
            Account pendReceiver = accounts.findById(pendingTx.getReceiverVpa())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown receiver: " + pendingTx.getReceiverVpa()));

            sender.setOfflineLockedBalance(sender.getOfflineLockedBalance().subtract(pendingTx.getAmount()));
            pendReceiver.setBalance(pendReceiver.getBalance().add(pendingTx.getAmount()));
            accounts.save(sender);
            accounts.save(pendReceiver);

            wallet.setSettledAmount(wallet.getSettledAmount().add(pendingTx.getAmount()));
            wallet.setRemainingAmount(wallet.getRemainingAmount().subtract(pendingTx.getAmount()));
            wallet.setLastSettledCounter(nextExpected);
            walletRepository.save(wallet);

            pendingTx.setStatus(Transaction.Status.SETTLED);
            pendingTx.setSettledAt(Instant.now());
            signAndSetReceipt(pendingTx, nextExpected);
            transactions.save(pendingTx);

            log.info("CASCADED GAP RESOLVED: wallet={}, counter={}, amount=₹{}, remaining=₹{}",
                    wallet.getWalletId(), nextExpected, pendingTx.getAmount(), wallet.getRemainingAmount());

            nextExpected++;
        }
    }

    /**
     * Check and expire pending sequence gap transactions that exceeded gapWindowSeconds.
     */
    @org.springframework.transaction.annotation.Transactional
    public void resolveExpiredGaps(String walletId) {
        var walletOpt = walletRepository.findByWalletId(walletId);
        if (walletOpt.isEmpty()) return;
        var wallet = walletOpt.get();

        var pendingTxs = transactions.findByWalletIdAndStatus(walletId, Transaction.Status.PENDING_SEQUENCE_GAP);
        Instant cutoff = Instant.now().minusSeconds(gapWindowSeconds);

        boolean expiredAny = false;
        for (Transaction p : pendingTxs) {
            if (p.getSignedAt().isBefore(cutoff)) {
                p.setStatus(Transaction.Status.REJECTED);
                p.setConflictReason("REJECTED_UNRESOLVED_SEQUENCE_GAP");
                transactions.save(p);
                expiredAny = true;
                log.warn("Gap window expired for tx id={}, wallet={}, counter={}", p.getId(), walletId, p.getSequenceCounter());
            }
        }

        if (expiredAny) {
            wallet.setStatus(com.demo.upimesh.model.OfflineWallet.WalletStatus.AUDIT_REQUIRED);
            walletRepository.save(wallet);
            log.warn("Wallet {} marked AUDIT_REQUIRED due to unresolved sequence gap expiry", walletId);
        }
    }

    private void signAndSetReceipt(Transaction tx, long counter) {
        try {
            var receipt = new com.demo.upimesh.model.SettlementReceipt(
                    tx.getId() != null ? tx.getId() : 0L,
                    tx.getPacketHash(),
                    counter,
                    tx.getStatus().name(),
                    tx.getSettledAt() != null ? tx.getSettledAt().toEpochMilli() : Instant.now().toEpochMilli(),
                    ""
            );
            String sig = signatureService.signReceipt(receipt, serverKeyHolder.getIssuerPrivateKey());
            tx.setReceiptSignature(sig);
        } catch (Exception e) {
            log.error("Failed to generate settlement receipt signature for tx: {}", e.getMessage());
        }
    }

    private Transaction recordRejected(PaymentInstruction instruction, String packetHash,
                                       String bridgeNodeId, int hopCount, String reason) {
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setWalletId(instruction.getWalletId());
        tx.setSequenceCounter(instruction.getSequenceCounter());
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.REJECTED);
        tx.setConflictReason(reason);
        return transactions.save(tx);
    }

    private boolean isRetryable(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof OptimisticLockException
                    || curr instanceof ObjectOptimisticLockingFailureException
                    || curr instanceof TransientDataAccessException
                    || curr.getClass().getName().contains("StaleObjectState")) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    private void applyBackoff(int attempt) {
        try {
            // attempt 1 -> ~25ms + [0,10]ms jitter; attempt 2 -> ~50ms + [0,15]ms jitter
            long baseMs = attempt * 25L;
            long jitter = ThreadLocalRandom.current().nextLong(5L * attempt);
            Thread.sleep(baseMs + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

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
    @Autowired private PlatformTransactionManager transactionManager;

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
                return recordRejected(instruction, packetHash, bridgeNodeId, hopCount);
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

            log.info("SETTLED ₹{} from {} to {} (packetHash={}, bridge={}, hops={})",
                    amount, sender.getVpa(), receiver.getVpa(),
                    packetHash.substring(0, Math.min(12, packetHash.length())) + "...", bridgeNodeId, hopCount);

            return saved;
        });
    }

    private Transaction recordRejected(PaymentInstruction instruction, String packetHash,
                                       String bridgeNodeId, int hopCount) {
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.REJECTED);
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

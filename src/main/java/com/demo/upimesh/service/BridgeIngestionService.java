package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Optional;

/**
 * Orchestrates the full server-side pipeline for one inbound packet from a
 * bridge node:
 *
 *   1. Compute SHA-256(ciphertext) packet hash.
 *   2. Fast DB Lookup: Check TransactionRepository.findByPacketHash(hash)
 *      - If already committed in DB, return original settled/rejected outcome.
 *        (Lost HTTP response recovery / bridge retry after commit).
 *   3. In-flight Gate: idempotency.tryAcquire(packetHash)
 *      - If in-flight gate is already held: reject as duplicate/in-flight.
 *   4. Decrypt ciphertext with server's private key.
 *      - On failure: release in-flight claim, reject as invalid (decryption_failed).
 *   5. Check freshness (replay protection).
 *      - On failure: release in-flight claim, reject as invalid (stale_packet / future_dated).
 *   6. Resolve sender account & registered public key.
 *      - On failure: release in-flight claim, reject as invalid (unknown_sender / missing_sender_public_key / corrupted_sender_public_key).
 *   7. Verify Ed25519 digital signature.
 *      - On failure: release in-flight claim, reject as invalid (missing_signature / unsupported_signature_algorithm / invalid_signature).
 *   8. Execute settlement via SettlementService with bounded optimistic-lock retry.
 *      - If SETTLED: markCompleted in cache, return settled.
 *      - If REJECTED (e.g. insufficient balance): markCompleted in cache, return rejected.
 *      - On TransientSettlementException: release in-flight claim, return TRANSIENT_FAILURE.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private SignatureService signatureService;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private IdempotencyService idempotency;
    @Autowired private SettlementService settlement;
    @Autowired private com.demo.upimesh.model.OfflineWalletRepository walletRepository;
    @Autowired private com.demo.upimesh.crypto.ServerKeyHolder serverKeyHolder;
    @Autowired(required = false) private com.demo.upimesh.fault.FaultInterceptor faultInterceptor;
    @Autowired(required = false) private com.demo.upimesh.metrics.UpiMetricsService metricsService;

    public void setFaultInterceptor(com.demo.upimesh.fault.FaultInterceptor faultInterceptor) {
        this.faultInterceptor = faultInterceptor;
    }

    public void setMetricsService(com.demo.upimesh.metrics.UpiMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        if (metricsService != null) {
            metricsService.recordTransactionAttempt();
        }
        String packetHash = null;
        try {
            packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- 1. Fast DB check: Authoritative recovery for committed transactions ----
            Optional<Transaction> existingTx = transactions.findByPacketHash(packetHash);
            if (existingTx.isPresent()) {
                Transaction tx = existingTx.get();
                log.info("Packet {} previously committed in DB as {} — returning cached result",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", tx.getStatus());
                idempotency.markCompleted(packetHash);
                if (tx.getStatus() == Transaction.Status.SETTLED) {
                    return returnDuplicateResult(IngestResult.settled(packetHash, tx));
                } else if (tx.getStatus() == Transaction.Status.CONFLICTING) {
                    return returnDuplicateResult(IngestResult.conflicting(packetHash, tx, tx.getConflictReason()));
                } else if (tx.getStatus() == Transaction.Status.PENDING_SEQUENCE_GAP) {
                    return returnDuplicateResult(IngestResult.pendingGap(packetHash, tx));
                } else {
                    return returnDuplicateResult(IngestResult.rejected(packetHash, tx, tx.getConflictReason() != null ? tx.getConflictReason() : "previously_rejected"));
                }
            }

            // ---- 2. In-flight idempotency concurrency gate ----
            if (!idempotency.tryAcquire(packetHash)) {
                log.info("DUPLICATE/IN-FLIGHT packet {} from bridge {} — dropped",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", bridgeNodeId);
                return returnDuplicate(packetHash);
            }

            // ---- 3. Decrypt ----
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                idempotency.release(packetHash);
                log.warn("Decryption failed for packet {}: {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", e.getMessage());
                return returnInvalid(packetHash, "decryption_failed");
            }

            // ---- 4. Freshness check (replay protection) ----
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                idempotency.release(packetHash);
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", ageSeconds);
                return returnInvalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                idempotency.release(packetHash);
                return returnInvalid(packetHash, "future_dated");
            }

            // ---- 5. Resolve sender account & registered public key ----
            Optional<Account> senderOpt = accounts.findById(instruction.getSenderVpa());
            if (senderOpt.isEmpty()) {
                idempotency.release(packetHash);
                log.warn("Unknown sender {} for packet {}",
                        instruction.getSenderVpa(), packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return returnInvalid(packetHash, "unknown_sender");
            }

            Account sender = senderOpt.get();
            if (sender.getPublicKey() == null || sender.getPublicKey().isBlank()) {
                idempotency.release(packetHash);
                log.warn("Sender {} has no registered public key for packet {}",
                        sender.getVpa(), packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return returnInvalid(packetHash, "missing_sender_public_key");
            }

            // ---- 6. Verify Ed25519 digital signature ----
            if (instruction.getSignature() == null || instruction.getSignature().isBlank()) {
                idempotency.release(packetHash);
                log.warn("Missing signature in payment instruction for packet {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return returnInvalid(packetHash, "missing_signature");
            }

            if (instruction.getSignatureAlgorithm() == null
                    || instruction.getSignatureAlgorithm().isBlank()
                    || !SignatureService.ALGORITHM.equalsIgnoreCase(instruction.getSignatureAlgorithm().trim())) {
                idempotency.release(packetHash);
                log.warn("Unsupported or missing signature algorithm {} in packet {}",
                        instruction.getSignatureAlgorithm(), packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return returnInvalid(packetHash, "unsupported_signature_algorithm");
            }

            PublicKey senderPublicKey;
            try {
                senderPublicKey = signatureService.decodePublicKey(sender.getPublicKey());
            } catch (Exception e) {
                idempotency.release(packetHash);
                log.error("Failed to decode public key for sender {}: {}", sender.getVpa(), e.getMessage());
                return returnInvalid(packetHash, "corrupted_sender_public_key");
            }

            boolean signatureValid = signatureService.verify(
                    instruction, instruction.getSignature(), senderPublicKey);

            if (!signatureValid) {
                idempotency.release(packetHash);
                log.warn("INVALID signature on packet {} from sender {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", sender.getVpa());
                return returnInvalid(packetHash, "invalid_signature");
            }

            // ---- 7. Phase 3: Offline Wallet Certificate & Epoch Validation ----
            if (instruction.getWalletId() != null && !instruction.getWalletId().isBlank()) {
                com.demo.upimesh.model.OfflineWalletCertificate cert = instruction.getWalletCertificate();
                if (cert == null) {
                    idempotency.release(packetHash);
                    log.warn("Missing wallet certificate for offline payment in packet {}", packetHash);
                    return returnInvalid(packetHash, "missing_wallet_certificate");
                }

                // 7.1 Verify certificate issuer signature
                boolean certSigOk = signatureService.verifyCertificate(cert, serverKeyHolder.getIssuerPublicKey());
                if (!certSigOk) {
                    idempotency.release(packetHash);
                    log.warn("Forged or invalid wallet certificate signature in packet {}", packetHash);
                    return returnInvalid(packetHash, "forged_wallet_certificate");
                }

                // 7.2 Verify certificate validity window
                long nowMs = Instant.now().toEpochMilli();
                if (nowMs > cert.validUntil()) {
                    idempotency.release(packetHash);
                    log.warn("Expired wallet certificate in packet {}: expired at {}", packetHash, cert.validUntil());
                    if (metricsService != null) {
                        metricsService.recordWalletExpired();
                    }
                    return returnInvalid(packetHash, "expired_wallet_certificate");
                }

                // 7.3 Verify wallet ownership & public key binding
                if (!cert.ownerVpa().equalsIgnoreCase(sender.getVpa())) {
                    idempotency.release(packetHash);
                    return returnInvalid(packetHash, "certificate_owner_mismatch");
                }
                if (!cert.ownerPublicKey().equals(sender.getPublicKey())) {
                    idempotency.release(packetHash);
                    return returnInvalid(packetHash, "certificate_key_mismatch");
                }

                // 7.4 Verify wallet entity in DB
                var walletOpt = walletRepository.findByWalletId(instruction.getWalletId().trim());
                if (walletOpt.isEmpty()) {
                    idempotency.release(packetHash);
                    return returnInvalid(packetHash, "unknown_wallet_id");
                }
                var wallet = walletOpt.get();

                // 7.5 Verify wallet epoch
                if (instruction.getWalletEpoch() == null || !instruction.getWalletEpoch().equals(wallet.getWalletEpoch())) {
                    idempotency.release(packetHash);
                    log.warn("Obsolete wallet epoch {} (active is {}) in packet {}",
                            instruction.getWalletEpoch(), wallet.getWalletEpoch(), packetHash);
                    return returnInvalid(packetHash, "obsolete_wallet_epoch");
                }

                // 7.6 Terminal Transfer Policy Enforcement:
                // Sender must be wallet owner; payee cannot re-spend offline wallet funds
                if (!instruction.getSenderVpa().equalsIgnoreCase(wallet.getOwnerVpa())) {
                    idempotency.release(packetHash);
                    log.warn("Terminal transfer violation: sender {} is not wallet owner {}",
                            instruction.getSenderVpa(), wallet.getOwnerVpa());
                    return returnInvalid(packetHash, "terminal_transfer_violation");
                }

                // 7.7 Verify cumulative amount bounds
                if (instruction.getCumulativeAmount() != null
                        && instruction.getCumulativeAmount().compareTo(wallet.getAllocatedAmount()) > 0) {
                    idempotency.release(packetHash);
                    log.warn("Cumulative spend ₹{} exceeds wallet allocation ₹{}",
                            instruction.getCumulativeAmount(), wallet.getAllocatedAmount());
                    return returnInvalid(packetHash, "allocation_exceeded");
                }
            }

            // ---- 8. Settle with retry ----
            Transaction tx;
            try {
                tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            } catch (TransientSettlementException tse) {
                idempotency.release(packetHash);
                log.warn("Transient settlement failure for packet {}: {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", tse.getMessage());
                return returnTransientFailure(packetHash, "transient_settlement_failure");
            } catch (Exception e) {
                idempotency.release(packetHash);
                log.error("Settlement error for packet {}: {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", e.getMessage(), e);
                return returnInvalid(packetHash, "settlement_error: " + e.getMessage());
            }

            // Transaction committed to DB
            idempotency.markCompleted(packetHash);

            if (faultInterceptor != null && !faultInterceptor.allowPostCommitResponse(packetHash)) {
                log.warn("Post-commit response dropped by fault rule for packet {}", packetHash);
                throw new IllegalStateException("Simulated lost HTTP response after database commit");
            }

            if (tx.getStatus() == Transaction.Status.SETTLED) {
                return IngestResult.settled(packetHash, tx);
            } else if (tx.getStatus() == Transaction.Status.PENDING_SEQUENCE_GAP) {
                return returnPendingGap(packetHash, tx);
            } else if (tx.getStatus() == Transaction.Status.CONFLICTING) {
                return returnConflicting(packetHash, tx, tx.getConflictReason());
            } else {
                return returnRejected(packetHash, tx, tx.getConflictReason() != null ? tx.getConflictReason() : "insufficient_balance");
            }

        } catch (Exception e) {
            if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().contains("Simulated lost HTTP response")) {
                throw (IllegalStateException) e;
            }
            if (packetHash != null && transactions.findByPacketHash(packetHash).isEmpty()) {
                idempotency.release(packetHash);
            }
            log.error("Ingestion error: {}", e.getMessage(), e);
            return returnInvalid(packetHash != null ? packetHash : "?", "internal_error: " + e.getMessage());
        }
    }

    private IngestResult returnDuplicateResult(IngestResult result) {
        if (metricsService != null) {
            metricsService.recordTransactionDuplicate();
        }
        return result;
    }

    private IngestResult returnDuplicate(String hash) {
        if (metricsService != null) {
            metricsService.recordTransactionDuplicate();
        }
        return IngestResult.duplicate(hash);
    }

    private IngestResult returnInvalid(String hash, String reason) {
        if (metricsService != null) {
            metricsService.recordTransactionRejected(reason);
        }
        return IngestResult.invalid(hash, reason);
    }

    private IngestResult returnRejected(String hash, Transaction tx, String reason) {
        if (metricsService != null) {
            metricsService.recordTransactionRejected(reason);
        }
        return IngestResult.rejected(hash, tx, reason);
    }

    private IngestResult returnConflicting(String hash, Transaction tx, String reason) {
        if (metricsService != null) {
            metricsService.recordTransactionConflicting(reason);
        }
        return IngestResult.conflicting(hash, tx, reason);
    }

    private IngestResult returnPendingGap(String hash, Transaction tx) {
        if (metricsService != null) {
            metricsService.recordTransactionPendingGap();
        }
        return IngestResult.pendingGap(hash, tx);
    }

    private IngestResult returnTransientFailure(String hash, String reason) {
        if (metricsService != null) {
            metricsService.recordTransactionRejected(reason);
        }
        return IngestResult.transientFailure(hash, reason);
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId, String receiptSignature) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId(), tx.getReceiptSignature());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null, null);
        }
        public static IngestResult rejected(String hash, Transaction tx, String reason) {
            return new IngestResult("REJECTED", hash, reason, tx != null ? tx.getId() : null, null);
        }
        public static IngestResult conflicting(String hash, Transaction tx, String reason) {
            return new IngestResult("CONFLICTING", hash, reason, tx != null ? tx.getId() : null, null);
        }
        public static IngestResult pendingGap(String hash, Transaction tx) {
            return new IngestResult("PENDING_SEQUENCE_GAP", hash, "missing_prior_sequence_counter", tx != null ? tx.getId() : null, null);
        }
        public static IngestResult transientFailure(String hash, String reason) {
            return new IngestResult("TRANSIENT_FAILURE", hash, reason, null, null);
        }
    }
}

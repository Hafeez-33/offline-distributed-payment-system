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

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
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
                    return IngestResult.settled(packetHash, tx);
                } else {
                    return IngestResult.rejected(packetHash, tx, "previously_rejected");
                }
            }

            // ---- 2. In-flight idempotency concurrency gate ----
            if (!idempotency.tryAcquire(packetHash)) {
                log.info("DUPLICATE/IN-FLIGHT packet {} from bridge {} — dropped",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }

            // ---- 3. Decrypt ----
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                idempotency.release(packetHash);
                log.warn("Decryption failed for packet {}: {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", e.getMessage());
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            // ---- 4. Freshness check (replay protection) ----
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                idempotency.release(packetHash);
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", ageSeconds);
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                idempotency.release(packetHash);
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ---- 5. Resolve sender account & registered public key ----
            Optional<Account> senderOpt = accounts.findById(instruction.getSenderVpa());
            if (senderOpt.isEmpty()) {
                idempotency.release(packetHash);
                log.warn("Unknown sender {} for packet {}",
                        instruction.getSenderVpa(), packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return IngestResult.invalid(packetHash, "unknown_sender");
            }

            Account sender = senderOpt.get();
            if (sender.getPublicKey() == null || sender.getPublicKey().isBlank()) {
                idempotency.release(packetHash);
                log.warn("Sender {} has no registered public key for packet {}",
                        sender.getVpa(), packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return IngestResult.invalid(packetHash, "missing_sender_public_key");
            }

            // ---- 6. Verify Ed25519 digital signature ----
            if (instruction.getSignature() == null || instruction.getSignature().isBlank()) {
                idempotency.release(packetHash);
                log.warn("Missing signature in payment instruction for packet {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return IngestResult.invalid(packetHash, "missing_signature");
            }

            if (instruction.getSignatureAlgorithm() == null
                    || instruction.getSignatureAlgorithm().isBlank()
                    || !SignatureService.ALGORITHM.equalsIgnoreCase(instruction.getSignatureAlgorithm().trim())) {
                idempotency.release(packetHash);
                log.warn("Unsupported or missing signature algorithm {} in packet {}",
                        instruction.getSignatureAlgorithm(), packetHash.substring(0, Math.min(12, packetHash.length())) + "...");
                return IngestResult.invalid(packetHash, "unsupported_signature_algorithm");
            }

            PublicKey senderPublicKey;
            try {
                senderPublicKey = signatureService.decodePublicKey(sender.getPublicKey());
            } catch (Exception e) {
                idempotency.release(packetHash);
                log.error("Failed to decode public key for sender {}: {}", sender.getVpa(), e.getMessage());
                return IngestResult.invalid(packetHash, "corrupted_sender_public_key");
            }

            boolean signatureValid = signatureService.verify(
                    instruction, instruction.getSignature(), senderPublicKey);

            if (!signatureValid) {
                idempotency.release(packetHash);
                log.warn("INVALID signature on packet {} from sender {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", sender.getVpa());
                return IngestResult.invalid(packetHash, "invalid_signature");
            }

            // ---- 7. Settle with retry ----
            Transaction tx;
            try {
                tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            } catch (TransientSettlementException tse) {
                idempotency.release(packetHash);
                log.warn("Transient settlement failure for packet {}: {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", tse.getMessage());
                return IngestResult.transientFailure(packetHash, "transient_settlement_failure");
            } catch (Exception e) {
                idempotency.release(packetHash);
                log.error("Settlement error for packet {}: {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())) + "...", e.getMessage(), e);
                return IngestResult.invalid(packetHash, "settlement_error: " + e.getMessage());
            }

            // Transaction committed to DB
            idempotency.markCompleted(packetHash);
            if (tx.getStatus() == Transaction.Status.SETTLED) {
                return IngestResult.settled(packetHash, tx);
            } else {
                return IngestResult.rejected(packetHash, tx, "insufficient_balance");
            }

        } catch (Exception e) {
            if (packetHash != null) {
                idempotency.release(packetHash);
            }
            log.error("Ingestion error: {}", e.getMessage(), e);
            return IngestResult.invalid(packetHash != null ? packetHash : "?", "internal_error: " + e.getMessage());
        }
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
        public static IngestResult rejected(String hash, Transaction tx, String reason) {
            return new IngestResult("REJECTED", hash, reason, tx != null ? tx.getId() : null);
        }
        public static IngestResult transientFailure(String hash, String reason) {
            return new IngestResult("TRANSIENT_FAILURE", hash, reason, null);
        }
    }
}

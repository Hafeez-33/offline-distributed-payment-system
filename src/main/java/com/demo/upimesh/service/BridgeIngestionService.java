package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
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
 *   1. Hash the ciphertext.
 *   2. Try to claim that hash via the idempotency cache.
 *      - If already claimed: this is a duplicate. Drop it.
 *   3. Decrypt the ciphertext with the server's private key.
 *      - If decryption fails: tampered or junk. Reject.
 *   4. Check freshness — reject if signedAt is too old or future-dated (replay protection).
 *   5. Resolve sender account in authoritative repository.
 *   6. Retrieve registered Ed25519 public key.
 *   7. Verify Ed25519 signature over canonical payment representation.
 *      - If verification fails or signature missing: Reject.
 *   8. Hand off to SettlementService for the actual debit/credit.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private SignatureService signatureService;
    @Autowired private AccountRepository accounts;
    @Autowired private IdempotencyService idempotency;
    @Autowired private SettlementService settlement;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        try {
            String packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- 1. Idempotency gate ----
            if (!idempotency.claim(packetHash)) {
                log.info("DUPLICATE packet {} from bridge {} — dropped",
                        packetHash.substring(0, 12) + "...", bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }

            // ---- 2. Decrypt ----
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                log.warn("Decryption failed for packet {}: {}",
                        packetHash.substring(0, 12) + "...", e.getMessage());
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            // ---- 3. Freshness check (replay protection) ----
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, 12) + "...", ageSeconds);
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ---- 4. Resolve sender account & registered public key ----
            Optional<Account> senderOpt = accounts.findById(instruction.getSenderVpa());
            if (senderOpt.isEmpty()) {
                log.warn("Unknown sender {} for packet {}",
                        instruction.getSenderVpa(), packetHash.substring(0, 12) + "...");
                return IngestResult.invalid(packetHash, "unknown_sender");
            }

            Account sender = senderOpt.get();
            if (sender.getPublicKey() == null || sender.getPublicKey().isBlank()) {
                log.warn("Sender {} has no registered public key for packet {}",
                        sender.getVpa(), packetHash.substring(0, 12) + "...");
                return IngestResult.invalid(packetHash, "missing_sender_public_key");
            }

            // ---- 5. Verify Ed25519 digital signature ----
            if (instruction.getSignature() == null || instruction.getSignature().isBlank()) {
                log.warn("Missing signature in payment instruction for packet {}",
                        packetHash.substring(0, 12) + "...");
                return IngestResult.invalid(packetHash, "missing_signature");
            }

            if (instruction.getSignatureAlgorithm() == null
                    || instruction.getSignatureAlgorithm().isBlank()
                    || !SignatureService.ALGORITHM.equalsIgnoreCase(instruction.getSignatureAlgorithm().trim())) {
                log.warn("Unsupported or missing signature algorithm {} in packet {}",
                        instruction.getSignatureAlgorithm(), packetHash.substring(0, 12) + "...");
                return IngestResult.invalid(packetHash, "unsupported_signature_algorithm");
            }

            PublicKey senderPublicKey;
            try {
                senderPublicKey = signatureService.decodePublicKey(sender.getPublicKey());
            } catch (Exception e) {
                log.error("Failed to decode public key for sender {}: {}", sender.getVpa(), e.getMessage());
                return IngestResult.invalid(packetHash, "corrupted_sender_public_key");
            }

            boolean signatureValid = signatureService.verify(
                    instruction, instruction.getSignature(), senderPublicKey);

            if (!signatureValid) {
                log.warn("INVALID signature on packet {} from sender {}",
                        packetHash.substring(0, 12) + "...", sender.getVpa());
                return IngestResult.invalid(packetHash, "invalid_signature");
            }

            // ---- 6. Settle ----
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            return IngestResult.settled(packetHash, tx);

        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            return IngestResult.invalid("?", "internal_error: " + e.getMessage());
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
    }
}

package com.demo.upimesh.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Structured logging helper providing consistent, secure log formatting across domain events.
 *
 * CRITICAL SECURITY INVARIANT:
 * Never logs:
 *   - Private keys or key material
 *   - Passwords or credentials
 *   - Decrypted payment plaintext
 *   - Full raw ciphertexts
 */
public final class StructuredLogHelper {

    private static final Logger log = LoggerFactory.getLogger("EVENT_LOGGER");

    private StructuredLogHelper() {}

    public static void logTransactionSettled(String packetHash, String senderVpa, String receiverVpa,
                                            BigDecimal amount, boolean isOffline, String walletId, Long counter) {
        String shortHash = sanitizeHash(packetHash);
        log.info("[EVENT=TX_SETTLED] type={} packetHash={} sender={} receiver={} amount={} walletId={} counter={}",
                isOffline ? "OFFLINE" : "ONLINE", shortHash, senderVpa, receiverVpa, amount,
                walletId != null ? walletId : "NONE", counter != null ? counter : 0);
    }

    public static void logTransactionRejected(String packetHash, String senderVpa, String reason) {
        String shortHash = sanitizeHash(packetHash);
        log.warn("[EVENT=TX_REJECTED] packetHash={} sender={} reason={}",
                shortHash, senderVpa != null ? senderVpa : "UNKNOWN", reason);
    }

    public static void logTransactionConflict(String packetHash, String walletId, Long counter, String reason, Long winningTxId) {
        String shortHash = sanitizeHash(packetHash);
        log.error("[EVENT=TX_CONFLICT] packetHash={} walletId={} counter={} reason={} winningTxId={}",
                shortHash, walletId, counter, reason, winningTxId);
    }

    public static void logWalletAllocated(String walletId, String ownerVpa, BigDecimal amount, long epoch) {
        log.info("[EVENT=WALLET_ALLOCATED] walletId={} owner={} amount={} epoch={}",
                walletId, ownerVpa, amount, epoch);
    }

    public static void logWalletReconciled(String walletId, String ownerVpa, BigDecimal unusedReturned) {
        log.info("[EVENT=WALLET_RECONCILED] walletId={} owner={} unusedReturned={}",
                walletId, ownerVpa, unusedReturned);
    }

    public static void logMeshPartition(String nodeA, String nodeB) {
        log.warn("[EVENT=MESH_PARTITION] link={} <-> {}", nodeA, nodeB);
    }

    public static void logMeshHeal(String nodeA, String nodeB) {
        log.info("[EVENT=MESH_HEAL] link={} <-> {}", nodeA, nodeB);
    }

    public static void logGossipRound(int transfers) {
        log.info("[EVENT=GOSSIP_ROUND] transfers={}", transfers);
    }

    public static void logAntiEntropySync(String local, String remote, boolean wasInSync, int transfers) {
        log.info("[EVENT=ANTI_ENTROPY_SYNC] peer1={} peer2={} inSync={} transfers={}",
                local, remote, wasInSync, transfers);
    }

    public static void logFaultInjected(String faultType, String ruleId) {
        log.warn("[EVENT=FAULT_INJECTED] type={} ruleId={}", faultType, ruleId);
    }

    public static void logRedisFallback(String operation, String reason) {
        log.warn("[EVENT=REDIS_FALLBACK] op={} reason={}", operation, reason);
    }

    public static void logDatabaseRetry(int attempt, int maxAttempts, String packetHash) {
        log.warn("[EVENT=DB_RETRY] attempt={}/{} packetHash={}", attempt, maxAttempts, sanitizeHash(packetHash));
    }

    public static void logInvariantViolation(String invariantId, String description) {
        log.error("[EVENT=INVARIANT_VIOLATION] id={} description={}", invariantId, description);
    }

    private static String sanitizeHash(String hash) {
        if (hash == null || hash.isBlank()) return "UNKNOWN";
        return hash.length() > 16 ? hash.substring(0, 16) + "..." : hash;
    }
}

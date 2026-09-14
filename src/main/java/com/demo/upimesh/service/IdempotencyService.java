package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory in-flight concurrency and idempotency gate.
 *
 * Provides fast-path deduplication and in-flight lock management:
 *   - tryAcquire(hash): atomically acquires the in-flight processing gate. Returns true
 *     if this thread acquired the gate, false if another thread is currently processing
 *     or has already completed this packet.
 *   - release(hash): releases the in-flight claim on transient failures or validation errors,
 *     allowing subsequent legitimate retries.
 *   - markCompleted(hash): retains the hash in the cache upon committed settlement.
 *
 * NOTE ON CORRECTNESS:
 * The authoritative deduplication barrier is the database UNIQUE constraint on
 * Transaction.packetHash. This in-memory structure acts as a fast concurrency gate
 * to prevent duplicate work across threads.
 */
@Service
public class IdempotencyService {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    /**
     * Atomically try to acquire the in-flight processing gate for a packet hash.
     * Returns true if successfully acquired; false if duplicate or currently in-flight.
     */
    public boolean tryAcquire(String packetHash) {
        Instant now = Instant.now();
        Instant prev = seen.putIfAbsent(packetHash, now);
        return prev == null;
    }

    /**
     * Legacy alias for tryAcquire.
     */
    public boolean claim(String packetHash) {
        return tryAcquire(packetHash);
    }

    /**
     * Release the in-flight claim for a packet hash (e.g., after a transient failure or rollback).
     * This allows subsequent deliveries of the same packet to be retried safely.
     */
    public void release(String packetHash) {
        if (packetHash != null) {
            seen.remove(packetHash);
        }
    }

    /**
     * Mark a packet hash as permanently completed in the fast-path cache.
     */
    public void markCompleted(String packetHash) {
        if (packetHash != null) {
            seen.put(packetHash, Instant.now());
        }
    }

    /**
     * Check if a hash is currently tracked.
     */
    public boolean isTracked(String packetHash) {
        return seen.containsKey(packetHash);
    }

    public int size() {
        return seen.size();
    }

    /** Periodically evict entries past their TTL so the map doesn't grow forever. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    /** Test/demo helper. */
    public void clear() {
        seen.clear();
    }
}

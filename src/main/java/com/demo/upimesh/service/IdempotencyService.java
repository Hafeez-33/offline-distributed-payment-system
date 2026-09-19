package com.demo.upimesh.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed in-flight concurrency and idempotency gate (Phase 7).
 *
 * Provides:
 *   - Redis-backed distributed processing lock: upi:lock:<packetHash> with explicit TTL.
 *   - Automatic fallback to process-local ConcurrentHashMap if Redis is unavailable.
 *   - Post-settlement idempotency completion cache to fast-drop duplicates across instances.
 *
 * NOTE ON CORRECTNESS:
 * The authoritative deduplication barrier is the database UNIQUE constraint on
 * Transaction.packetHash. Redis is non-authoritative coordination/cache. If Redis fails,
 * packets still reach the PostgreSQL barrier without risk of double-debit or financial corruption.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    public static final String KEY_PREFIX = "upi:lock:";
    public static final Duration IN_FLIGHT_LOCK_TTL = Duration.ofSeconds(60);

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private InfrastructureMetrics metrics;

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds = 86400L;

    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void setMetrics(InfrastructureMetrics metrics) {
        this.metrics = metrics;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Atomically try to acquire the in-flight processing gate for a packet hash.
     * Checks Redis first if available; falls back to local ConcurrentHashMap if Redis is down.
     * Returns true if successfully acquired; false if duplicate or currently in-flight.
     */
    public boolean tryAcquire(String packetHash) {
        if (packetHash == null) {
            return false;
        }

        // Try Redis distributed lock coordination first
        if (redisTemplate != null) {
            try {
                if (metrics != null) metrics.recordRedisOperation();
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(KEY_PREFIX + packetHash, "LOCKED", IN_FLIGHT_LOCK_TTL);

                if (acquired != null) {
                    if (acquired) {
                        seen.put(packetHash, Instant.now());
                        return true;
                    } else {
                        return false; // Already locked or marked completed in Redis
                    }
                }
            } catch (Exception e) {
                if (metrics != null) metrics.recordRedisFallback();
                log.warn("Redis coordination unavailable for packet {}, falling back to local gate + PostgreSQL barrier: {}",
                        packetHash.substring(0, Math.min(12, packetHash.length())), e.getMessage());
            }
        }

        // Local in-memory gate fallback
        Instant prev = seen.putIfAbsent(packetHash, Instant.now());
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

            if (redisTemplate != null) {
                try {
                    if (metrics != null) metrics.recordRedisOperation();
                    redisTemplate.delete(KEY_PREFIX + packetHash);
                } catch (Exception e) {
                    if (metrics != null) metrics.recordRedisFailure();
                    log.warn("Failed to release Redis lock for key {}{}: {}", KEY_PREFIX, packetHash, e.getMessage());
                }
            }
        }
    }

    /**
     * Mark a packet hash as permanently completed in the fast-path cache.
     */
    public void markCompleted(String packetHash) {
        if (packetHash != null) {
            seen.put(packetHash, Instant.now());

            if (redisTemplate != null) {
                try {
                    if (metrics != null) metrics.recordRedisOperation();
                    redisTemplate.opsForValue().set(KEY_PREFIX + packetHash, "COMPLETED", Duration.ofSeconds(ttlSeconds));
                } catch (Exception e) {
                    if (metrics != null) metrics.recordRedisFailure();
                    log.warn("Failed to mark Redis key completed for {}{}: {}", KEY_PREFIX, packetHash, e.getMessage());
                }
            }
        }
    }

    /**
     * Check if a hash is currently tracked.
     */
    public boolean isTracked(String packetHash) {
        if (packetHash == null) return false;
        if (seen.containsKey(packetHash)) {
            return true;
        }

        if (redisTemplate != null) {
            try {
                if (metrics != null) metrics.recordRedisOperation();
                return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + packetHash));
            } catch (Exception e) {
                if (metrics != null) metrics.recordRedisFailure();
            }
        }

        return false;
    }

    public int size() {
        return seen.size();
    }

    /** Periodically evict entries past their TTL so the local map doesn't grow forever. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    /** Test/demo helper. */
    public void clear() {
        seen.clear();
        if (redisTemplate != null) {
            try {
                var keys = redisTemplate.keys(KEY_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {}
        }
    }
}

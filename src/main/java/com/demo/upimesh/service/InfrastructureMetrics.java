package com.demo.upimesh.service;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.LongAdder;

/**
 * Registry for infrastructure-level observability metrics (Phase 7).
 * Tracks Redis operations, failures, fallbacks, database retries, and cache hits/misses.
 */
@Component
public class InfrastructureMetrics {

    private final LongAdder redisOperationsTotal = new LongAdder();
    private final LongAdder redisFailuresTotal = new LongAdder();
    private final LongAdder redisFallbackTotal = new LongAdder();
    private final LongAdder cacheHitsTotal = new LongAdder();
    private final LongAdder cacheMissesTotal = new LongAdder();
    private final LongAdder dbRetriesTotal = new LongAdder();

    public void recordRedisOperation() {
        redisOperationsTotal.increment();
    }

    public void recordRedisFailure() {
        redisFailuresTotal.increment();
    }

    public void recordRedisFallback() {
        redisFallbackTotal.increment();
        redisFailuresTotal.increment();
    }

    public void recordCacheHit() {
        cacheHitsTotal.increment();
    }

    public void recordCacheMiss() {
        cacheMissesTotal.increment();
    }

    public void recordDbRetry() {
        dbRetriesTotal.increment();
    }

    public long getRedisOperationsTotal() {
        return redisOperationsTotal.sum();
    }

    public long getRedisFailuresTotal() {
        return redisFailuresTotal.sum();
    }

    public long getRedisFallbackTotal() {
        return redisFallbackTotal.sum();
    }

    public long getCacheHitsTotal() {
        return cacheHitsTotal.sum();
    }

    public long getCacheMissesTotal() {
        return cacheMissesTotal.sum();
    }

    public long getDbRetriesTotal() {
        return dbRetriesTotal.sum();
    }

    public void reset() {
        redisOperationsTotal.reset();
        redisFailuresTotal.reset();
        redisFallbackTotal.reset();
        cacheHitsTotal.reset();
        cacheMissesTotal.reset();
        dbRetriesTotal.reset();
    }
}

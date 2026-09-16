package com.demo.upimesh.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.LongAdder;

/**
 * Registry for infrastructure-level observability metrics (Phase 7 & Phase 8).
 * Tracks Redis operations, failures, fallbacks, database retries, and cache hits/misses.
 * Unifies in-memory counters with Micrometer Prometheus meters.
 */
@Component
public class InfrastructureMetrics {

    private final LongAdder redisOperationsTotal = new LongAdder();
    private final LongAdder redisFailuresTotal = new LongAdder();
    private final LongAdder redisFallbackTotal = new LongAdder();
    private final LongAdder cacheHitsTotal = new LongAdder();
    private final LongAdder cacheMissesTotal = new LongAdder();
    private final LongAdder dbRetriesTotal = new LongAdder();

    private final Counter micrometerRedisOps;
    private final Counter micrometerRedisFailures;
    private final Counter micrometerRedisFallback;
    private final Counter micrometerCacheHits;
    private final Counter micrometerCacheMisses;
    private final Counter micrometerDbRetries;

    @Autowired
    public InfrastructureMetrics(MeterRegistry registry) {
        this.micrometerRedisOps = Counter.builder("upi.infra.redis.operations")
                .description("Total operations executed against Redis")
                .register(registry);

        this.micrometerRedisFailures = Counter.builder("upi.infra.redis.failures")
                .description("Total failures/exceptions encountered when accessing Redis")
                .register(registry);

        this.micrometerRedisFallback = Counter.builder("upi.infra.redis.fallbacks")
                .description("Total non-authoritative fallbacks from Redis to local concurrency gates")
                .register(registry);

        this.micrometerCacheHits = Counter.builder("upi.infra.cache.hits")
                .description("Total dashboard cache hits")
                .register(registry);

        this.micrometerCacheMisses = Counter.builder("upi.infra.cache.misses")
                .description("Total dashboard cache misses")
                .register(registry);

        this.micrometerDbRetries = Counter.builder("upi.infra.db.retries")
                .description("Total database transaction optimistic lock retries")
                .register(registry);
    }

    // Default constructor for tests that don't inject MeterRegistry
    public InfrastructureMetrics() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry simpleRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        this.micrometerRedisOps = Counter.builder("upi.infra.redis.operations").register(simpleRegistry);
        this.micrometerRedisFailures = Counter.builder("upi.infra.redis.failures").register(simpleRegistry);
        this.micrometerRedisFallback = Counter.builder("upi.infra.redis.fallbacks").register(simpleRegistry);
        this.micrometerCacheHits = Counter.builder("upi.infra.cache.hits").register(simpleRegistry);
        this.micrometerCacheMisses = Counter.builder("upi.infra.cache.misses").register(simpleRegistry);
        this.micrometerDbRetries = Counter.builder("upi.infra.db.retries").register(simpleRegistry);
    }

    public void recordRedisOperation() {
        redisOperationsTotal.increment();
        micrometerRedisOps.increment();
    }

    public void recordRedisFailure() {
        redisFailuresTotal.increment();
        micrometerRedisFailures.increment();
    }

    public void recordRedisFallback() {
        redisFallbackTotal.increment();
        redisFailuresTotal.increment();
        micrometerRedisFallback.increment();
        micrometerRedisFailures.increment();
    }

    public void recordCacheHit() {
        cacheHitsTotal.increment();
        micrometerCacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMissesTotal.increment();
        micrometerCacheMisses.increment();
    }

    public void recordDbRetry() {
        dbRetriesTotal.increment();
        micrometerDbRetries.increment();
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

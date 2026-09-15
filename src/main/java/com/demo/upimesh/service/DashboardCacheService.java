package com.demo.upimesh.service;

import com.demo.upimesh.dto.DashboardOverviewDto;
import com.demo.upimesh.dto.MeshSummaryDto;
import com.demo.upimesh.dto.ReliabilityReportDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Cache-aside layer for expensive dashboard reads using Redis (Phase 7).
 *
 * Invariants:
 *   - Redis is strictly non-authoritative cache.
 *   - Explicit 10-second TTL prevents stale views indefinitely.
 *   - Relevant cache keys are immediately invalidated upon financial mutations,
 *     mesh topology changes, and fault injections.
 *   - If Redis is unavailable, transparently falls back to direct authoritative reads.
 */
@Service
public class DashboardCacheService {

    private static final Logger log = LoggerFactory.getLogger(DashboardCacheService.class);

    public static final String KEY_OVERVIEW = "upi:cache:dashboard:overview";
    public static final String KEY_MESH = "upi:cache:dashboard:mesh";
    public static final String KEY_RELIABILITY = "upi:cache:dashboard:reliability";
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(10);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InfrastructureMetrics metrics;

    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public DashboardOverviewDto getOverview(Supplier<DashboardOverviewDto> loader) {
        return getOrLoad(KEY_OVERVIEW, DashboardOverviewDto.class, loader);
    }

    public MeshSummaryDto getMeshSummary(Supplier<MeshSummaryDto> loader) {
        return getOrLoad(KEY_MESH, MeshSummaryDto.class, loader);
    }

    public ReliabilityReportDto getReliability(Supplier<ReliabilityReportDto> loader) {
        return getOrLoad(KEY_RELIABILITY, ReliabilityReportDto.class, loader);
    }

    private <T> T getOrLoad(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        if (redisTemplate != null) {
            try {
                metrics.recordRedisOperation();
                String cachedJson = redisTemplate.opsForValue().get(cacheKey);
                if (cachedJson != null && !cachedJson.isBlank()) {
                    metrics.recordCacheHit();
                    log.debug("Cache hit for key {}", cacheKey);
                    return objectMapper.readValue(cachedJson, clazz);
                }
            } catch (Exception e) {
                metrics.recordRedisFallback();
                log.warn("Redis read failed for key {}, falling back to authoritative source: {}", cacheKey, e.getMessage());
            }
        }

        metrics.recordCacheMiss();
        T freshValue = loader.get();

        if (redisTemplate != null && freshValue != null) {
            try {
                metrics.recordRedisOperation();
                String json = objectMapper.writeValueAsString(freshValue);
                redisTemplate.opsForValue().set(cacheKey, json, DEFAULT_TTL);
                log.debug("Populated cache for key {} with TTL {}s", cacheKey, DEFAULT_TTL.toSeconds());
            } catch (Exception e) {
                metrics.recordRedisFailure();
                log.warn("Failed to write cache for key {}: {}", cacheKey, e.getMessage());
            }
        }

        return freshValue;
    }

    public void invalidateOverview() {
        evictKey(KEY_OVERVIEW);
    }

    public void invalidateMesh() {
        evictKey(KEY_MESH);
    }

    public void invalidateReliability() {
        evictKey(KEY_RELIABILITY);
    }

    public void invalidateAll() {
        invalidateOverview();
        invalidateMesh();
        invalidateReliability();
    }

    private void evictKey(String key) {
        if (redisTemplate != null) {
            try {
                metrics.recordRedisOperation();
                redisTemplate.delete(key);
                log.debug("Evicted cache key {}", key);
            } catch (Exception e) {
                metrics.recordRedisFailure();
                log.warn("Failed to evict cache key {}: {}", key, e.getMessage());
            }
        }
    }
}

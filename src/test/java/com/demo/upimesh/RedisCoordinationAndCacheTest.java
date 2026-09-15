package com.demo.upimesh;

import com.demo.upimesh.dto.DashboardOverviewDto;
import com.demo.upimesh.service.DashboardCacheService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.InfrastructureMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class RedisCoordinationAndCacheTest {

    @Autowired private DashboardCacheService cacheService;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private InfrastructureMetrics metrics;
    @Autowired private ObjectMapper objectMapper;

    private StringRedisTemplate mockRedisTemplate;
    private ValueOperations<String, String> mockValueOps;

    @BeforeEach
    void setUp() {
        metrics.reset();
        mockRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        mockValueOps = ops;
        when(mockRedisTemplate.opsForValue()).thenReturn(mockValueOps);

        cacheService.setRedisTemplate(mockRedisTemplate);
        idempotencyService.setRedisTemplate(mockRedisTemplate);
    }

    @Test
    @DisplayName("IdempotencyService acquires Redis distributed lock and rejects concurrent duplicate claims")
    void testRedisDistributedLockAcquisition() {
        String packetHash = "hash-coord-" + Instant.now().toEpochMilli();

        // First attempt: Redis setIfAbsent returns true (lock acquired)
        when(mockValueOps.setIfAbsent(eq(IdempotencyService.KEY_PREFIX + packetHash), eq("LOCKED"), any(Duration.class)))
                .thenReturn(true);

        boolean firstAcquired = idempotencyService.tryAcquire(packetHash);
        assertTrue(firstAcquired, "First thread must acquire Redis lock");
        verify(mockValueOps, times(1)).setIfAbsent(eq(IdempotencyService.KEY_PREFIX + packetHash), eq("LOCKED"), any(Duration.class));

        // Second attempt: Redis setIfAbsent returns false (lock collision/already held)
        when(mockValueOps.setIfAbsent(eq(IdempotencyService.KEY_PREFIX + packetHash), eq("LOCKED"), any(Duration.class)))
                .thenReturn(false);

        boolean secondAcquired = idempotencyService.tryAcquire(packetHash);
        assertFalse(secondAcquired, "Concurrent duplicate attempt must be rejected by Redis gate");
    }

    @Test
    @DisplayName("IdempotencyService releases lock on failure and marks completed on success")
    void testRedisLockReleaseAndComplete() {
        String packetHash = "hash-lifecycle-" + Instant.now().toEpochMilli();

        // Release lock
        idempotencyService.release(packetHash);
        verify(mockRedisTemplate, times(1)).delete(IdempotencyService.KEY_PREFIX + packetHash);

        // Mark completed with TTL
        idempotencyService.markCompleted(packetHash);
        verify(mockValueOps, times(1)).set(eq(IdempotencyService.KEY_PREFIX + packetHash), eq("COMPLETED"), any(Duration.class));
    }

    @Test
    @DisplayName("Dashboard cache-aside serves cache hits without executing supplier, records metrics, and invalidates on mutation")
    void testDashboardCacheHitMissAndInvalidation() throws Exception {
        AtomicInteger supplierCallCount = new AtomicInteger(0);

        DashboardOverviewDto dummyDto = new DashboardOverviewDto(
                "HEALTHY", 5, 1, true, 0, 10, 4,
                new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                1, 0, 5L, 0L, 0L, 0, 0L, Instant.now()
        );
        String dtoJson = objectMapper.writeValueAsString(dummyDto);

        // 1. Cache Miss scenario
        when(mockValueOps.get(DashboardCacheService.KEY_OVERVIEW)).thenReturn(null);

        DashboardOverviewDto result1 = cacheService.getOverview(() -> {
            supplierCallCount.incrementAndGet();
            return dummyDto;
        });

        assertEquals("HEALTHY", result1.systemStatus());
        assertEquals(1, supplierCallCount.get(), "Supplier must be called on cache miss");
        assertEquals(1, metrics.getCacheMissesTotal(), "Cache miss metric must increment");
        verify(mockValueOps, times(1)).set(eq(DashboardCacheService.KEY_OVERVIEW), anyString(), eq(DashboardCacheService.DEFAULT_TTL));

        // 2. Cache Hit scenario
        when(mockValueOps.get(DashboardCacheService.KEY_OVERVIEW)).thenReturn(dtoJson);

        DashboardOverviewDto result2 = cacheService.getOverview(() -> {
            supplierCallCount.incrementAndGet();
            return dummyDto;
        });

        assertEquals("HEALTHY", result2.systemStatus());
        assertEquals(1, supplierCallCount.get(), "Supplier must NOT be called on cache hit!");
        assertEquals(1, metrics.getCacheHitsTotal(), "Cache hit metric must increment");

        // 3. Mutation Invalidation scenario
        cacheService.invalidateOverview();
        verify(mockRedisTemplate, times(1)).delete(DashboardCacheService.KEY_OVERVIEW);
    }
}

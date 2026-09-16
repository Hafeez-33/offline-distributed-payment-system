package com.demo.upimesh.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Custom Spring Boot Actuator HealthIndicator for the Offline UPI Mesh System.
 *
 * Explicit Health Semantics:
 *   - HEALTHY (UP):
 *       PostgreSQL is reachable, and Redis is reachable.
 *   - DEGRADED:
 *       Redis is unreachable/down. Since Redis is non-authoritative coordination/cache,
 *       the system continues to operate safely via local concurrency gates and the PostgreSQL
 *       UNIQUE(packet_hash) barrier.
 *   - DOWN (FAILED):
 *       PostgreSQL is unreachable. The authoritative ledger cannot commit transactions.
 */
@Component("upiSystem")
public class UpiSystemHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED", "Non-authoritative Redis service is unavailable; fallback active");

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    @Autowired
    public UpiSystemHealthIndicator(DataSource dataSource,
                                    @Autowired(required = false) RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public Health health() {
        boolean dbHealthy = checkDatabase();
        if (!dbHealthy) {
            return Health.down()
                    .withDetail("database", "DOWN")
                    .withDetail("message", "Authoritative PostgreSQL ledger is unreachable")
                    .build();
        }

        boolean redisHealthy = checkRedis();
        if (!redisHealthy) {
            return Health.status(DEGRADED)
                    .withDetail("database", "UP")
                    .withDetail("redis", "DEGRADED")
                    .withDetail("message", "Non-authoritative Redis is unavailable; local concurrency fallback active")
                    .build();
        }

        return Health.up()
                .withDetail("database", "UP")
                .withDetail("redis", "UP")
                .withDetail("status", "HEALTHY")
                .build();
    }

    public boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.execute("SELECT 1");
        } catch (Exception e) {
            return false;
        }
    }

    public boolean checkRedis() {
        if (redisConnectionFactory == null) {
            return false;
        }
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            String pong = conn.ping();
            return pong != null && pong.equalsIgnoreCase("PONG");
        } catch (Exception e) {
            return false;
        }
    }
}

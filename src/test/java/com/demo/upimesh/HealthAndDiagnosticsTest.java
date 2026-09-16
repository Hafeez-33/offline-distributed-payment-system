package com.demo.upimesh;

import com.demo.upimesh.config.UpiSystemHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class HealthAndDiagnosticsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;
    @Autowired(required = false) private RedisConnectionFactory redisConnectionFactory;
    @Autowired private UpiSystemHealthIndicator healthIndicator;

    @Test
    @DisplayName("/actuator/health endpoint is accessible and returns UP or DEGRADED status")
    void testActuatorHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("Health indicator reports UP when database is healthy")
    void testHealthIndicatorUpWhenDbHealthy() {
        Health health = healthIndicator.health();
        assertNotNull(health);
        assertTrue(health.getStatus() == Status.UP || health.getStatus().getCode().equals("DEGRADED"));
    }

    @Test
    @DisplayName("Health indicator reports DEGRADED when Redis is unavailable (non-authoritative fallback)")
    void testHealthIndicatorDegradedWhenRedisFails() {
        // Construct indicator with null RedisConnectionFactory
        UpiSystemHealthIndicator degradedIndicator = new UpiSystemHealthIndicator(dataSource, null);
        Health health = degradedIndicator.health();

        assertEquals("DEGRADED", health.getStatus().getCode());
        assertEquals("UP", health.getDetails().get("database"));
        assertEquals("DEGRADED", health.getDetails().get("redis"));
        assertTrue(health.getDetails().get("message").toString().contains("Non-authoritative Redis is unavailable"));
    }

    @Test
    @DisplayName("Health indicator reports DOWN when PostgreSQL database is unreachable")
    void testHealthIndicatorDownWhenDbFails() {
        // Construct indicator with a failing/mock DataSource
        DataSource failingDataSource = org.mockito.Mockito.mock(DataSource.class);
        try {
            org.mockito.Mockito.when(failingDataSource.getConnection()).thenThrow(new java.sql.SQLException("Connection refused"));
        } catch (Exception ignored) {}

        UpiSystemHealthIndicator downIndicator = new UpiSystemHealthIndicator(failingDataSource, redisConnectionFactory);
        Health health = downIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN", health.getDetails().get("database"));
    }

    @Test
    @DisplayName("/actuator/prometheus endpoint is accessible and exports upi_* metrics")
    void testActuatorPrometheusEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;version=0.0.4;charset=utf-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("upi_transactions_attempted_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("upi_mesh_gossip_rounds_total")));
    }

    @Test
    @DisplayName("Unauthenticated /actuator/health does not leak sensitive datasource details")
    void testHealthDoesNotLeakCredentials() throws Exception {
        String response = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.toLowerCase().contains("password"), "Health response must not contain password");
        assertFalse(response.toLowerCase().contains("jdbc:postgresql://"), "Health response must not leak full JDBC URL");
        assertFalse(response.toLowerCase().contains("secret"), "Health response must not contain secrets");
    }
}

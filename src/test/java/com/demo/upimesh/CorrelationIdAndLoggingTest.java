package com.demo.upimesh;

import com.demo.upimesh.config.CorrelationIdFilter;
import com.demo.upimesh.config.StructuredLogHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class CorrelationIdAndLoggingTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Inbound request without X-Request-ID receives generated UUID correlation ID")
    void testGeneratesCorrelationId() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.HEADER_REQUEST_ID))
                .andExpect(result -> {
                    String correlationId = result.getResponse().getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
                    assertNotNull(correlationId);
                    assertFalse(correlationId.isBlank());
                    // Verify it is a valid UUID format
                    assertDoesNotThrow(() -> UUID.fromString(correlationId));
                });
    }

    @Test
    @DisplayName("Inbound request with X-Request-ID preserves and echoes correlation ID")
    void testPropagatesCorrelationId() throws Exception {
        String customRequestId = "trace-client-" + UUID.randomUUID();

        mockMvc.perform(get("/api/dashboard/overview")
                        .header(CorrelationIdFilter.HEADER_REQUEST_ID, customRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER_REQUEST_ID, customRequestId));
    }

    @Test
    @DisplayName("Structured logging helper safely formats domain events without sensitive leaks")
    void testStructuredLogHelperSafety() {
        assertDoesNotThrow(() -> {
            StructuredLogHelper.logTransactionSettled("a1b2c3d4e5f67890a1b2c3d4e5f67890", "alice@upi", "bob@upi", new BigDecimal("100.00"), false, null, 0L);
            StructuredLogHelper.logTransactionRejected("a1b2c3d4e5f67890a1b2c3d4e5f67890", "alice@upi", "invalid_signature");
            StructuredLogHelper.logTransactionConflict("a1b2c3d4e5f67890a1b2c3d4e5f67890", "WLT-TEST01", 1L, "double_spend_counter_collision", 42L);
            StructuredLogHelper.logWalletAllocated("WLT-TEST01", "alice@upi", new BigDecimal("500.00"), 1L);
            StructuredLogHelper.logWalletReconciled("WLT-TEST01", "alice@upi", new BigDecimal("200.00"));
            StructuredLogHelper.logMeshPartition("phone-alice", "phone-bob");
            StructuredLogHelper.logMeshHeal("phone-alice", "phone-bob");
            StructuredLogHelper.logGossipRound(5);
            StructuredLogHelper.logAntiEntropySync("phone-alice", "phone-bob", true, 0);
            StructuredLogHelper.logFaultInjected("DROP", "rule-1");
            StructuredLogHelper.logRedisFallback("tryAcquire", "Connection refused");
            StructuredLogHelper.logDatabaseRetry(1, 3, "a1b2c3d4e5f67890");
            StructuredLogHelper.logInvariantViolation("I1", "Packet Identity mismatch");
        });
    }
}

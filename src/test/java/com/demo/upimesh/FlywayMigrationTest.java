package com.demo.upimesh;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Flyway V1 migration executes cleanly and applies schema version 1")
    void testFlywayMigrationApplied() {
        assertNotNull(flyway, "Flyway bean must be present in application context");

        MigrationInfo current = flyway.info().current();
        assertNotNull(current, "Current migration info must not be null");
        assertEquals("1", current.getVersion().getVersion(), "Migration version must be 1");
        assertEquals("initial schema", current.getDescription(), "Migration description must match");
        assertTrue(current.getState().isApplied(), "Migration must be in an applied state");
    }

    @Test
    @DisplayName("Authoritative tables exist with correct schema after Flyway migration")
    void testAuthoritativeTablesExist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            Set<String> tableNames = new HashSet<>();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tableNames.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }

            assertTrue(tableNames.contains("accounts"), "Table 'accounts' must exist");
            assertTrue(tableNames.contains("offline_wallets"), "Table 'offline_wallets' must exist");
            assertTrue(tableNames.contains("transactions"), "Table 'transactions' must exist");
        }
    }

    @Test
    @DisplayName("Transactions table enforces unique packet_hash constraint")
    void testPacketHashUniqueConstraint() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            boolean foundUniquePacketHash = false;
            try (ResultSet rs = metaData.getIndexInfo(null, null, "TRANSACTIONS", true, false)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null && col.equalsIgnoreCase("packet_hash")) {
                        foundUniquePacketHash = true;
                        break;
                    }
                }
            }

            // Also check lowercase table name if uppercase didn't match (H2 vs Postgres case handling)
            if (!foundUniquePacketHash) {
                try (ResultSet rs = metaData.getIndexInfo(null, null, "transactions", true, false)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        if (col != null && col.equalsIgnoreCase("packet_hash")) {
                            foundUniquePacketHash = true;
                            break;
                        }
                    }
                }
            }

            assertTrue(foundUniquePacketHash, "Unique index/constraint on packet_hash must be present");
        }
    }
}

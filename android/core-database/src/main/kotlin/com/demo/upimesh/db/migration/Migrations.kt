package com.demo.upimesh.db.migration

import java.sql.Connection

/**
 * Migration contract for schema evolution.
 * Destructive migrations are strictly disallowed.
 */
abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(connection: Connection)
}

/**
 * Migration 1 -> 2:
 * Adds index on received_packets(received_at) for efficient cleanup/querying,
 * and adds an optional metadata column `notes` to offline_wallets without altering existing data.
 */
class Migration1To2 : Migration(1, 2) {
    override fun migrate(connection: Connection) {
        connection.createStatement().use { stmt ->
            // Add index on received_at
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_received_packets_received_at ON received_packets(received_at)")
            // Add non-destructive audit column to offline_wallets if not present
            try {
                stmt.execute("ALTER TABLE offline_wallets ADD COLUMN notes TEXT DEFAULT ''")
            } catch (ignored: Exception) {
                // Already present or handled
            }
        }
    }
}

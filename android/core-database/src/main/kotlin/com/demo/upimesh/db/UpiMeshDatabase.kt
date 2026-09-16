package com.demo.upimesh.db

import com.demo.upimesh.db.dao.*
import com.demo.upimesh.db.entity.*
import com.demo.upimesh.db.migration.Migration
import java.io.File
import java.sql.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * UpiMesh durable local database interface.
 * Matches AndroidX RoomDatabase architecture and provides DAO accessors and transaction scoping.
 */
interface UpiMeshDatabase : AutoCloseable {
    val deviceIdentityDao: DeviceIdentityDao
    val offlineWalletDao: OfflineWalletDao
    val outboundPaymentDao: OutboundPaymentDao
    val receivedPacketDao: ReceivedPacketDao
    val packetFragmentDao: PacketFragmentDao
    val settlementReceiptDao: SettlementReceiptDao

    /**
     * Executes a block within a single atomic database transaction.
     * If an exception occurs, the transaction is rolled back.
     */
    fun <T> withTransaction(block: () -> T): T

    /**
     * Returns the current schema version.
     */
    fun getSchemaVersion(): Int

    companion object {
        const val CURRENT_VERSION = 1

        fun inMemory(
            targetVersion: Int = CURRENT_VERSION,
            migrations: List<Migration> = emptyList()
        ): UpiMeshDatabase {
            return SqliteUpiMeshDatabase("jdbc:sqlite::memory:", targetVersion, migrations)
        }

        fun open(
            dbFile: File,
            targetVersion: Int = CURRENT_VERSION,
            migrations: List<Migration> = emptyList()
        ): UpiMeshDatabase {
            dbFile.parentFile?.mkdirs()
            return SqliteUpiMeshDatabase("jdbc:sqlite:${dbFile.absolutePath}", targetVersion, migrations)
        }
    }
}

/**
 * Production-grade SQLite implementation of UpiMeshDatabase.
 * Implements genuine SQLite ACID transactions, WAL mode, foreign keys, and migration-safe versioning.
 */
class SqliteUpiMeshDatabase(
    private val jdbcUrl: String,
    private val targetVersion: Int = 1,
    private val migrations: List<Migration> = emptyList()
) : UpiMeshDatabase {

    private val lock = ReentrantLock()
    private val connection: Connection = DriverManager.getConnection(jdbcUrl)
    private val transactionDepth = ThreadLocal.withInitial { 0 }

    override val deviceIdentityDao: DeviceIdentityDao = SqliteDeviceIdentityDao(this)
    override val offlineWalletDao: OfflineWalletDao = SqliteOfflineWalletDao(this)
    override val outboundPaymentDao: OutboundPaymentDao = SqliteOutboundPaymentDao(this)
    override val receivedPacketDao: ReceivedPacketDao = SqliteReceivedPacketDao(this)
    override val packetFragmentDao: PacketFragmentDao = SqlitePacketFragmentDao(this)
    override val settlementReceiptDao: SettlementReceiptDao = SqliteSettlementReceiptDao(this)

    init {
        try {
            lock.withLock {
                connection.createStatement().use { stmt ->
                    stmt.execute("PRAGMA foreign_keys = ON")
                    if (!jdbcUrl.contains(":memory:")) {
                        stmt.execute("PRAGMA journal_mode = WAL")
                        stmt.execute("PRAGMA synchronous = NORMAL")
                    }
                }
                initSchemaAndMigrations()
            }
        } catch (e: Throwable) {
            try {
                connection.close()
            } catch (ignored: Throwable) {}
            throw e
        }
    }

    internal fun <R> execute(block: (Connection) -> R): R {
        lock.withLock {
            return block(connection)
        }
    }

    override fun <T> withTransaction(block: () -> T): T {
        lock.withLock {
            val depth = transactionDepth.get()
            if (depth == 0) {
                connection.autoCommit = false
                transactionDepth.set(1)
                var success = false
                try {
                    val result = block()
                    connection.commit()
                    success = true
                    return result
                } finally {
                    try {
                        if (!success) {
                            connection.rollback()
                        }
                    } finally {
                        connection.autoCommit = true
                        transactionDepth.set(0)
                    }
                }
            } else {
                // Nested transaction within the same thread
                transactionDepth.set(depth + 1)
                try {
                    return block()
                } finally {
                    transactionDepth.set(depth)
                }
            }
        }
    }

    override fun getSchemaVersion(): Int {
        return execute { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT version FROM room_master_table WHERE id = 42 LIMIT 1")
                if (rs.next()) rs.getInt("version") else 0
            }
        }
    }

    private fun initSchemaAndMigrations() {
        connection.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS room_master_table (
                    id INTEGER PRIMARY KEY,
                    identity_hash TEXT,
                    version INTEGER
                )
            """.trimIndent())
        }

        val currentVersion = getSchemaVersion()

        if (currentVersion == 0) {
            // Fresh database: create Version 1 schema
            createVersion1Schema(connection)
            setSchemaVersion(connection, 1)

            // If targetVersion > 1, apply migrations sequentially
            if (targetVersion > 1) {
                applyMigrations(1, targetVersion)
            }
        } else if (currentVersion < targetVersion) {
            // Existing database needing migration
            applyMigrations(currentVersion, targetVersion)
        } else if (currentVersion > targetVersion) {
            throw IllegalStateException(
                "Can't downgrade database from version $currentVersion to $targetVersion"
            )
        }
    }

    private fun applyMigrations(fromVersion: Int, toVersion: Int) {
        var current = fromVersion
        while (current < toVersion) {
            val next = current + 1
            val migration = migrations.find { it.startVersion == current && it.endVersion == next }
                ?: throw IllegalStateException(
                    "A migration from $current to $next was required but not found. Destructive migrations are disallowed."
                )
            migration.migrate(connection)
            setSchemaVersion(connection, next)
            current = next
        }
    }

    private fun setSchemaVersion(conn: Connection, version: Int) {
        conn.prepareStatement("""
            INSERT INTO room_master_table (id, identity_hash, version)
            VALUES (42, 'upi_mesh_hash_v$version', ?)
            ON CONFLICT(id) DO UPDATE SET version = excluded.version, identity_hash = excluded.identity_hash
        """.trimIndent()).use { ps ->
            ps.setInt(1, version)
            ps.executeUpdate()
        }
    }

    private fun createVersion1Schema(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS device_identities (
                    device_id TEXT PRIMARY KEY NOT NULL,
                    owner_vpa TEXT NOT NULL,
                    public_key TEXT NOT NULL,
                    encrypted_private_key TEXT NOT NULL,
                    encryption_iv TEXT NOT NULL,
                    enrollment_state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_device_identities_owner_vpa ON device_identities(owner_vpa)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS offline_wallets (
                    wallet_id TEXT PRIMARY KEY NOT NULL,
                    owner_vpa TEXT NOT NULL,
                    owner_public_key TEXT NOT NULL,
                    allocated_amount_paisa INTEGER NOT NULL,
                    local_spent_amount_paisa INTEGER NOT NULL DEFAULT 0,
                    settled_amount_paisa INTEGER NOT NULL DEFAULT 0,
                    remaining_amount_paisa INTEGER NOT NULL,
                    sequence_counter INTEGER NOT NULL DEFAULT 0,
                    wallet_epoch INTEGER NOT NULL DEFAULT 1,
                    valid_from INTEGER NOT NULL,
                    valid_until INTEGER NOT NULL,
                    certificate_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_offline_wallets_owner_vpa ON offline_wallets(owner_vpa)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS outbound_payments (
                    payment_id TEXT PRIMARY KEY NOT NULL,
                    wallet_id TEXT NOT NULL,
                    sequence_counter INTEGER NOT NULL,
                    amount_paisa INTEGER NOT NULL,
                    cumulative_amount_paisa INTEGER NOT NULL,
                    receiver_vpa TEXT NOT NULL,
                    nonce TEXT NOT NULL,
                    packet_hash TEXT,
                    ciphertext TEXT,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    retry_count INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbound_payments_wallet_id ON outbound_payments(wallet_id)")
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_outbound_payments_wallet_counter ON outbound_payments(wallet_id, sequence_counter)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbound_payments_packet_hash ON outbound_payments(packet_hash)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS received_packets (
                    packet_hash TEXT PRIMARY KEY NOT NULL,
                    packet_id TEXT NOT NULL,
                    ciphertext TEXT NOT NULL,
                    ttl INTEGER NOT NULL,
                    hop_count INTEGER NOT NULL,
                    received_at INTEGER NOT NULL,
                    uploaded_to_bridge INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS packet_fragments (
                    packet_hash TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    total_chunks INTEGER NOT NULL,
                    data BLOB NOT NULL,
                    received_at INTEGER NOT NULL,
                    PRIMARY KEY (packet_hash, chunk_index)
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_packet_fragments_packet_hash ON packet_fragments(packet_hash)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS settlement_receipts (
                    transaction_id INTEGER PRIMARY KEY NOT NULL,
                    packet_hash TEXT NOT NULL,
                    counter INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    settled_at INTEGER NOT NULL,
                    server_signature TEXT NOT NULL
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_receipts_packet_hash ON settlement_receipts(packet_hash)")
        }
    }

    override fun close() {
        lock.withLock {
            if (!connection.isClosed) {
                connection.close()
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// DAO Implementations
// -------------------------------------------------------------------------------------------------

internal class SqliteDeviceIdentityDao(private val db: SqliteUpiMeshDatabase) : DeviceIdentityDao {
    override fun insert(identity: DeviceIdentity): Long {
        return db.execute { conn ->
            val sql = """
                INSERT INTO device_identities 
                (device_id, owner_vpa, public_key, encrypted_private_key, encryption_iv, enrollment_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, identity.deviceId)
                ps.setString(2, identity.ownerVpa)
                ps.setString(3, identity.publicKey)
                ps.setString(4, identity.encryptedPrivateKey)
                ps.setString(5, identity.encryptionIv)
                ps.setString(6, identity.enrollmentState.name)
                ps.setLong(7, identity.createdAt)
                ps.setLong(8, identity.updatedAt)
                ps.executeUpdate().toLong()
            }
        }
    }

    override fun getIdentity(deviceId: String): DeviceIdentity? {
        return db.execute { conn ->
            val sql = "SELECT * FROM device_identities WHERE device_id = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, deviceId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getActiveIdentity(): DeviceIdentity? {
        return db.execute { conn ->
            val sql = "SELECT * FROM device_identities WHERE enrollment_state = 'ENROLLED' ORDER BY created_at DESC LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun update(identity: DeviceIdentity) {
        db.execute { conn ->
            val sql = """
                UPDATE device_identities 
                SET owner_vpa = ?, public_key = ?, encrypted_private_key = ?, encryption_iv = ?, 
                    enrollment_state = ?, updated_at = ?
                WHERE device_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, identity.ownerVpa)
                ps.setString(2, identity.publicKey)
                ps.setString(3, identity.encryptedPrivateKey)
                ps.setString(4, identity.encryptionIv)
                ps.setString(5, identity.enrollmentState.name)
                ps.setLong(6, identity.updatedAt)
                ps.setString(7, identity.deviceId)
                ps.executeUpdate()
            }
        }
    }

    override fun delete(deviceId: String): Int {
        return db.execute { conn ->
            conn.prepareStatement("DELETE FROM device_identities WHERE device_id = ?").use { ps ->
                ps.setString(1, deviceId)
                ps.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: ResultSet): DeviceIdentity {
        return DeviceIdentity(
            deviceId = rs.getString("device_id"),
            ownerVpa = rs.getString("owner_vpa"),
            publicKey = rs.getString("public_key"),
            encryptedPrivateKey = rs.getString("encrypted_private_key"),
            encryptionIv = rs.getString("encryption_iv"),
            enrollmentState = EnrollmentState.valueOf(rs.getString("enrollment_state")),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at")
        )
    }
}

internal class SqliteOfflineWalletDao(private val db: SqliteUpiMeshDatabase) : OfflineWalletDao {
    override fun insert(wallet: OfflineWallet): Long {
        return db.execute { conn ->
            val sql = """
                INSERT INTO offline_wallets 
                (wallet_id, owner_vpa, owner_public_key, allocated_amount_paisa, local_spent_amount_paisa, 
                 settled_amount_paisa, remaining_amount_paisa, sequence_counter, wallet_epoch, 
                 valid_from, valid_until, certificate_json, status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, wallet.walletId)
                ps.setString(2, wallet.ownerVpa)
                ps.setString(3, wallet.ownerPublicKey)
                ps.setLong(4, wallet.allocatedAmountPaisa)
                ps.setLong(5, wallet.localSpentAmountPaisa)
                ps.setLong(6, wallet.settledAmountPaisa)
                ps.setLong(7, wallet.remainingAmountPaisa)
                ps.setLong(8, wallet.sequenceCounter)
                ps.setLong(9, wallet.walletEpoch)
                ps.setLong(10, wallet.validFrom)
                ps.setLong(11, wallet.validUntil)
                ps.setString(12, wallet.certificateJson)
                ps.setString(13, wallet.status.name)
                ps.setLong(14, wallet.updatedAt)
                ps.executeUpdate().toLong()
            }
        }
    }

    override fun getWallet(walletId: String): OfflineWallet? {
        return db.execute { conn ->
            val sql = "SELECT * FROM offline_wallets WHERE wallet_id = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, walletId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getActiveWalletForOwner(ownerVpa: String): OfflineWallet? {
        return db.execute { conn ->
            val sql = "SELECT * FROM offline_wallets WHERE owner_vpa = ? AND status = 'ACTIVE' LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, ownerVpa)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getAllWallets(): List<OfflineWallet> {
        return db.execute { conn ->
            val sql = "SELECT * FROM offline_wallets ORDER BY valid_until DESC"
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<OfflineWallet>()
                    while (rs.next()) {
                        list.add(mapRow(rs))
                    }
                    list
                }
            }
        }
    }

    override fun update(wallet: OfflineWallet) {
        db.execute { conn ->
            val sql = """
                UPDATE offline_wallets 
                SET owner_vpa = ?, owner_public_key = ?, allocated_amount_paisa = ?, 
                    local_spent_amount_paisa = ?, settled_amount_paisa = ?, remaining_amount_paisa = ?, 
                    sequence_counter = ?, wallet_epoch = ?, valid_from = ?, valid_until = ?, 
                    certificate_json = ?, status = ?, updated_at = ?
                WHERE wallet_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, wallet.ownerVpa)
                ps.setString(2, wallet.ownerPublicKey)
                ps.setLong(3, wallet.allocatedAmountPaisa)
                ps.setLong(4, wallet.localSpentAmountPaisa)
                ps.setLong(5, wallet.settledAmountPaisa)
                ps.setLong(6, wallet.remainingAmountPaisa)
                ps.setLong(7, wallet.sequenceCounter)
                ps.setLong(8, wallet.walletEpoch)
                ps.setLong(9, wallet.validFrom)
                ps.setLong(10, wallet.validUntil)
                ps.setString(11, wallet.certificateJson)
                ps.setString(12, wallet.status.name)
                ps.setLong(13, wallet.updatedAt)
                ps.setString(14, wallet.walletId)
                ps.executeUpdate()
            }
        }
    }

    override fun updateStatus(walletId: String, status: WalletStatus, updatedAt: Long): Int {
        return db.execute { conn ->
            val sql = "UPDATE offline_wallets SET status = ?, updated_at = ? WHERE wallet_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, status.name)
                ps.setLong(2, updatedAt)
                ps.setString(3, walletId)
                ps.executeUpdate()
            }
        }
    }

    override fun updateCounterAndBalances(
        walletId: String,
        nextCounter: Long,
        newRemainingPaisa: Long,
        newLocalSpentPaisa: Long,
        updatedAt: Long
    ): Int {
        return db.execute { conn ->
            val sql = """
                UPDATE offline_wallets 
                SET sequence_counter = ?, remaining_amount_paisa = ?, local_spent_amount_paisa = ?, updated_at = ? 
                WHERE wallet_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, nextCounter)
                ps.setLong(2, newRemainingPaisa)
                ps.setLong(3, newLocalSpentPaisa)
                ps.setLong(4, updatedAt)
                ps.setString(5, walletId)
                ps.executeUpdate()
            }
        }
    }

    override fun updateSettledAmount(walletId: String, settledAmountPaisa: Long, updatedAt: Long): Int {
        return db.execute { conn ->
            val sql = "UPDATE offline_wallets SET settled_amount_paisa = ?, updated_at = ? WHERE wallet_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, settledAmountPaisa)
                ps.setLong(2, updatedAt)
                ps.setString(3, walletId)
                ps.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: ResultSet): OfflineWallet {
        return OfflineWallet(
            walletId = rs.getString("wallet_id"),
            ownerVpa = rs.getString("owner_vpa"),
            ownerPublicKey = rs.getString("owner_public_key"),
            allocatedAmountPaisa = rs.getLong("allocated_amount_paisa"),
            localSpentAmountPaisa = rs.getLong("local_spent_amount_paisa"),
            settledAmountPaisa = rs.getLong("settled_amount_paisa"),
            remainingAmountPaisa = rs.getLong("remaining_amount_paisa"),
            sequenceCounter = rs.getLong("sequence_counter"),
            walletEpoch = rs.getLong("wallet_epoch"),
            validFrom = rs.getLong("valid_from"),
            validUntil = rs.getLong("valid_until"),
            certificateJson = rs.getString("certificate_json"),
            status = WalletStatus.valueOf(rs.getString("status")),
            updatedAt = rs.getLong("updated_at")
        )
    }
}

internal class SqliteOutboundPaymentDao(private val db: SqliteUpiMeshDatabase) : OutboundPaymentDao {
    override fun insert(payment: OutboundPayment): Long {
        return db.execute { conn ->
            val sql = """
                INSERT INTO outbound_payments 
                (payment_id, wallet_id, sequence_counter, amount_paisa, cumulative_amount_paisa, 
                 receiver_vpa, nonce, packet_hash, ciphertext, state, created_at, updated_at, retry_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, payment.paymentId)
                ps.setString(2, payment.walletId)
                ps.setLong(3, payment.sequenceCounter)
                ps.setLong(4, payment.amountPaisa)
                ps.setLong(5, payment.cumulativeAmountPaisa)
                ps.setString(6, payment.receiverVpa)
                ps.setString(7, payment.nonce)
                ps.setString(8, payment.packetHash)
                ps.setString(9, payment.ciphertext)
                ps.setString(10, payment.state.name)
                ps.setLong(11, payment.createdAt)
                ps.setLong(12, payment.updatedAt)
                ps.setInt(13, payment.retryCount)
                ps.executeUpdate().toLong()
            }
        }
    }

    override fun getPayment(paymentId: String): OutboundPayment? {
        return db.execute { conn ->
            val sql = "SELECT * FROM outbound_payments WHERE payment_id = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, paymentId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getPaymentsForWallet(walletId: String): List<OutboundPayment> {
        return db.execute { conn ->
            val sql = "SELECT * FROM outbound_payments WHERE wallet_id = ? ORDER BY sequence_counter ASC"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, walletId)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<OutboundPayment>()
                    while (rs.next()) list.add(mapRow(rs))
                    list
                }
            }
        }
    }

    override fun getPaymentsByState(state: OutboundPaymentState): List<OutboundPayment> {
        return db.execute { conn ->
            val sql = "SELECT * FROM outbound_payments WHERE state = ? ORDER BY created_at ASC"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, state.name)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<OutboundPayment>()
                    while (rs.next()) list.add(mapRow(rs))
                    list
                }
            }
        }
    }

    override fun getPaymentByWalletAndCounter(walletId: String, sequenceCounter: Long): OutboundPayment? {
        return db.execute { conn ->
            val sql = "SELECT * FROM outbound_payments WHERE wallet_id = ? AND sequence_counter = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, walletId)
                ps.setLong(2, sequenceCounter)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getPaymentByPacketHash(packetHash: String): OutboundPayment? {
        return db.execute { conn ->
            val sql = "SELECT * FROM outbound_payments WHERE packet_hash = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packetHash)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun update(payment: OutboundPayment) {
        db.execute { conn ->
            val sql = """
                UPDATE outbound_payments 
                SET wallet_id = ?, sequence_counter = ?, amount_paisa = ?, cumulative_amount_paisa = ?, 
                    receiver_vpa = ?, nonce = ?, packet_hash = ?, ciphertext = ?, state = ?, 
                    updated_at = ?, retry_count = ?
                WHERE payment_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, payment.walletId)
                ps.setLong(2, payment.sequenceCounter)
                ps.setLong(3, payment.amountPaisa)
                ps.setLong(4, payment.cumulativeAmountPaisa)
                ps.setString(5, payment.receiverVpa)
                ps.setString(6, payment.nonce)
                ps.setString(7, payment.packetHash)
                ps.setString(8, payment.ciphertext)
                ps.setString(9, payment.state.name)
                ps.setLong(10, payment.updatedAt)
                ps.setInt(11, payment.retryCount)
                ps.setString(12, payment.paymentId)
                ps.executeUpdate()
            }
        }
    }

    override fun updateState(paymentId: String, state: OutboundPaymentState, updatedAt: Long): Int {
        return db.execute { conn ->
            val sql = "UPDATE outbound_payments SET state = ?, updated_at = ? WHERE payment_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, state.name)
                ps.setLong(2, updatedAt)
                ps.setString(3, paymentId)
                ps.executeUpdate()
            }
        }
    }

    override fun updateCryptoPayload(
        paymentId: String,
        ciphertext: String,
        packetHash: String,
        state: OutboundPaymentState,
        updatedAt: Long
    ): Int {
        return db.execute { conn ->
            val sql = """
                UPDATE outbound_payments 
                SET ciphertext = ?, packet_hash = ?, state = ?, updated_at = ? 
                WHERE payment_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, ciphertext)
                ps.setString(2, packetHash)
                ps.setString(3, state.name)
                ps.setLong(4, updatedAt)
                ps.setString(5, paymentId)
                ps.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: ResultSet): OutboundPayment {
        return OutboundPayment(
            paymentId = rs.getString("payment_id"),
            walletId = rs.getString("wallet_id"),
            sequenceCounter = rs.getLong("sequence_counter"),
            amountPaisa = rs.getLong("amount_paisa"),
            cumulativeAmountPaisa = rs.getLong("cumulative_amount_paisa"),
            receiverVpa = rs.getString("receiver_vpa"),
            nonce = rs.getString("nonce"),
            packetHash = rs.getString("packet_hash"),
            ciphertext = rs.getString("ciphertext"),
            state = OutboundPaymentState.valueOf(rs.getString("state")),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            retryCount = rs.getInt("retry_count")
        )
    }
}

internal class SqliteReceivedPacketDao(private val db: SqliteUpiMeshDatabase) : ReceivedPacketDao {
    override fun insert(packet: ReceivedPacket): Long {
        return db.execute { conn ->
            val sql = """
                INSERT OR IGNORE INTO received_packets 
                (packet_hash, packet_id, ciphertext, ttl, hop_count, received_at, uploaded_to_bridge, status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packet.packetHash)
                ps.setString(2, packet.packetId)
                ps.setString(3, packet.ciphertext)
                ps.setInt(4, packet.ttl)
                ps.setInt(5, packet.hopCount)
                ps.setLong(6, packet.receivedAt)
                ps.setInt(7, if (packet.uploadedToBridge) 1 else 0)
                ps.setString(8, packet.status.name)
                ps.setLong(9, packet.updatedAt)
                val rows = ps.executeUpdate()
                if (rows > 0) 1L else -1L
            }
        }
    }

    override fun getPacket(packetHash: String): ReceivedPacket? {
        return db.execute { conn ->
            val sql = "SELECT * FROM received_packets WHERE packet_hash = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packetHash)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getAllPackets(): List<ReceivedPacket> {
        return db.execute { conn ->
            val sql = "SELECT * FROM received_packets ORDER BY received_at DESC"
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<ReceivedPacket>()
                    while (rs.next()) list.add(mapRow(rs))
                    list
                }
            }
        }
    }

    override fun getPendingBridgePackets(): List<ReceivedPacket> {
        return db.execute { conn ->
            val sql = "SELECT * FROM received_packets WHERE uploaded_to_bridge = 0 ORDER BY received_at ASC"
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<ReceivedPacket>()
                    while (rs.next()) list.add(mapRow(rs))
                    list
                }
            }
        }
    }

    override fun update(packet: ReceivedPacket) {
        db.execute { conn ->
            val sql = """
                UPDATE received_packets 
                SET packet_id = ?, ciphertext = ?, ttl = ?, hop_count = ?, 
                    uploaded_to_bridge = ?, status = ?, updated_at = ?
                WHERE packet_hash = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packet.packetId)
                ps.setString(2, packet.ciphertext)
                ps.setInt(3, packet.ttl)
                ps.setInt(4, packet.hopCount)
                ps.setInt(5, if (packet.uploadedToBridge) 1 else 0)
                ps.setString(6, packet.status.name)
                ps.setLong(7, packet.updatedAt)
                ps.setString(8, packet.packetHash)
                ps.executeUpdate()
            }
        }
    }

    override fun markUploadedToBridge(packetHash: String, updatedAt: Long): Int {
        return db.execute { conn ->
            val sql = "UPDATE received_packets SET uploaded_to_bridge = 1, updated_at = ? WHERE packet_hash = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, updatedAt)
                ps.setString(2, packetHash)
                ps.executeUpdate()
            }
        }
    }

    override fun exists(packetHash: String): Boolean {
        return db.execute { conn ->
            val sql = "SELECT COUNT(*) FROM received_packets WHERE packet_hash = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packetHash)
                ps.executeQuery().use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): ReceivedPacket {
        return ReceivedPacket(
            packetHash = rs.getString("packet_hash"),
            packetId = rs.getString("packet_id"),
            ciphertext = rs.getString("ciphertext"),
            ttl = rs.getInt("ttl"),
            hopCount = rs.getInt("hop_count"),
            receivedAt = rs.getLong("received_at"),
            uploadedToBridge = rs.getInt("uploaded_to_bridge") == 1,
            status = ReceivedPacketStatus.valueOf(rs.getString("status")),
            updatedAt = rs.getLong("updated_at")
        )
    }
}

internal class SqlitePacketFragmentDao(private val db: SqliteUpiMeshDatabase) : PacketFragmentDao {
    override fun saveFragment(fragment: PacketFragment): Long {
        return db.execute { conn ->
            val sql = """
                INSERT OR REPLACE INTO packet_fragments 
                (packet_hash, chunk_index, total_chunks, data, received_at)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, fragment.packetHash)
                ps.setInt(2, fragment.chunkIndex)
                ps.setInt(3, fragment.totalChunks)
                ps.setBytes(4, fragment.data)
                ps.setLong(5, fragment.receivedAt)
                ps.executeUpdate().toLong()
            }
        }
    }

    override fun queryFragments(packetHash: String): List<PacketFragment> {
        return db.execute { conn ->
            val sql = "SELECT * FROM packet_fragments WHERE packet_hash = ? ORDER BY chunk_index ASC"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packetHash)
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<PacketFragment>()
                    while (rs.next()) list.add(mapRow(rs))
                    list
                }
            }
        }
    }

    override fun countFragments(packetHash: String): Int {
        return db.execute { conn ->
            val sql = "SELECT COUNT(*) FROM packet_fragments WHERE packet_hash = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packetHash)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    override fun deleteFragments(packetHash: String): Int {
        return db.execute { conn ->
            val sql = "DELETE FROM packet_fragments WHERE packet_hash = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packetHash)
                ps.executeUpdate()
            }
        }
    }

    override fun clearPacketReassemblyState(packetHash: String): Int {
        return deleteFragments(packetHash)
    }

    override fun deleteFragmentsOlderThan(cutoffTime: Long): Int {
        return db.execute { conn ->
            val sql = "DELETE FROM packet_fragments WHERE received_at < ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, cutoffTime)
                ps.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: ResultSet): PacketFragment {
        return PacketFragment(
            packetHash = rs.getString("packet_hash"),
            chunkIndex = rs.getInt("chunk_index"),
            totalChunks = rs.getInt("total_chunks"),
            data = rs.getBytes("data"),
            receivedAt = rs.getLong("received_at")
        )
    }
}

internal class SqliteSettlementReceiptDao(private val db: SqliteUpiMeshDatabase) : SettlementReceiptDao {
    override fun insert(receipt: SettlementReceipt): Long {
        return db.execute { conn ->
            val sql = """
                INSERT INTO settlement_receipts 
                (transaction_id, packet_hash, counter, status, settled_at, server_signature)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, receipt.transactionId)
                ps.setString(2, receipt.packetHash)
                ps.setLong(3, receipt.counter)
                ps.setString(4, receipt.status)
                ps.setLong(5, receipt.settledAt)
                ps.setString(6, receipt.serverSignature)
                ps.executeUpdate().toLong()
            }
        }
    }

    override fun getReceipt(transactionId: Long): SettlementReceipt? {
        return db.execute { conn ->
            val sql = "SELECT * FROM settlement_receipts WHERE transaction_id = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, transactionId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getReceiptByPacketHash(packetHash: String): SettlementReceipt? {
        return db.execute { conn ->
            val sql = "SELECT * FROM settlement_receipts WHERE packet_hash = ? LIMIT 1"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, packetHash)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun getAllReceipts(): List<SettlementReceipt> {
        return db.execute { conn ->
            val sql = "SELECT * FROM settlement_receipts ORDER BY settled_at DESC"
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<SettlementReceipt>()
                    while (rs.next()) list.add(mapRow(rs))
                    list
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): SettlementReceipt {
        return SettlementReceipt(
            transactionId = rs.getLong("transaction_id"),
            packetHash = rs.getString("packet_hash"),
            counter = rs.getLong("counter"),
            status = rs.getString("status"),
            settledAt = rs.getLong("settled_at"),
            serverSignature = rs.getString("server_signature")
        )
    }
}

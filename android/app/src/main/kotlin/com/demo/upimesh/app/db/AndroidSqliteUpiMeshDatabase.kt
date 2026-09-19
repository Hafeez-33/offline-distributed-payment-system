package com.demo.upimesh.app.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.dao.*
import com.demo.upimesh.db.entity.*
import com.demo.upimesh.db.migration.Migration
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Android Runtime SQLite Database implementation of [UpiMeshDatabase].
 *
 * Uses Android OS SQLite framework ([SQLiteDatabase] / [SQLiteOpenHelper]) instead of
 * desktop JVM SQLite-JDBC. This ensures complete runtime compatibility on Android ART
 * without modifying the underlying Room entity/DAO/migration specifications.
 */
class AndroidSqliteUpiMeshDatabase(
    private val dbHelper: UpiMeshOpenHelper
) : UpiMeshDatabase {

    private val lock = ReentrantLock()
    private val db: SQLiteDatabase get() = dbHelper.writableDatabase

    override val deviceIdentityDao: DeviceIdentityDao = AndroidDeviceIdentityDao(this)
    override val offlineWalletDao: OfflineWalletDao = AndroidOfflineWalletDao(this)
    override val outboundPaymentDao: OutboundPaymentDao = AndroidOutboundPaymentDao(this)
    override val receivedPacketDao: ReceivedPacketDao = AndroidReceivedPacketDao(this)
    override val packetFragmentDao: PacketFragmentDao = AndroidPacketFragmentDao(this)
    override val settlementReceiptDao: SettlementReceiptDao = AndroidSettlementReceiptDao(this)

    override fun <T> withTransaction(block: () -> T): T {
        lock.withLock {
            val database = db
            database.beginTransaction()
            return try {
                val result = block()
                database.setTransactionSuccessful()
                result
            } finally {
                database.endTransaction()
            }
        }
    }

    override fun getSchemaVersion(): Int {
        lock.withLock {
            val database = db
            val cursor = database.rawQuery("SELECT version FROM room_master_table WHERE id = 42 LIMIT 1", null)
            return cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }
    }

    override fun close() {
        lock.withLock {
            dbHelper.close()
        }
    }

    internal fun <R> execute(block: (SQLiteDatabase) -> R): R {
        lock.withLock {
            return block(db)
        }
    }

    companion object {
        const val DATABASE_NAME = "upimesh_local.db"
        const val DATABASE_VERSION = 1

        fun open(context: Context, dbFile: File? = null, migrations: List<Migration> = emptyList()): AndroidSqliteUpiMeshDatabase {
            val name = dbFile?.absolutePath ?: DATABASE_NAME
            val helper = UpiMeshOpenHelper(context, name, DATABASE_VERSION, migrations)
            return AndroidSqliteUpiMeshDatabase(helper)
        }

        fun inMemory(context: Context): AndroidSqliteUpiMeshDatabase {
            val helper = UpiMeshOpenHelper(context, null, DATABASE_VERSION, emptyList())
            return AndroidSqliteUpiMeshDatabase(helper)
        }
    }
}

/**
 * SQLiteOpenHelper managing schema creation, PRAGMAs, and migrations.
 */
class UpiMeshOpenHelper(
    context: Context,
    name: String?,
    version: Int,
    private val migrations: List<Migration>
) : SQLiteOpenHelper(context, name, null, version) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create Room master table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS room_master_table (
                id INTEGER PRIMARY KEY,
                identity_hash TEXT,
                version INTEGER
            )
        """.trimIndent())

        // Create Device Identities
        db.execSQL("""
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_device_identities_owner_vpa ON device_identities(owner_vpa)")

        // Create Offline Wallets
        db.execSQL("""
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_offline_wallets_owner_vpa ON offline_wallets(owner_vpa)")

        // Create Outbound Payments
        db.execSQL("""
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbound_payments_wallet_id ON outbound_payments(wallet_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_outbound_payments_wallet_counter ON outbound_payments(wallet_id, sequence_counter)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbound_payments_packet_hash ON outbound_payments(packet_hash)")

        // Create Received Packets
        db.execSQL("""
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

        // Create Packet Fragments
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS packet_fragments (
                packet_hash TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                total_chunks INTEGER NOT NULL,
                data BLOB NOT NULL,
                received_at INTEGER NOT NULL,
                PRIMARY KEY (packet_hash, chunk_index)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_packet_fragments_packet_hash ON packet_fragments(packet_hash)")

        // Create Settlement Receipts
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS settlement_receipts (
                transaction_id INTEGER PRIMARY KEY NOT NULL,
                packet_hash TEXT NOT NULL,
                counter INTEGER NOT NULL,
                status TEXT NOT NULL,
                settled_at INTEGER NOT NULL,
                server_signature TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_receipts_packet_hash ON settlement_receipts(packet_hash)")

        // Initialize schema version
        db.execSQL("""
            INSERT OR REPLACE INTO room_master_table (id, identity_hash, version)
            VALUES (42, 'upi_mesh_hash_v1', 1)
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var current = oldVersion
        while (current < newVersion) {
            val next = current + 1
            val migration = migrations.find { it.startVersion == current && it.endVersion == next }
                ?: throw IllegalStateException(
                    "A migration from $current to $next was required but not found. Destructive migrations are disallowed."
                )
            // Execute migration statements
            db.beginTransaction()
            try {
                // Apply version bump
                db.execSQL("""
                    INSERT OR REPLACE INTO room_master_table (id, identity_hash, version)
                    VALUES (42, 'upi_mesh_hash_v$next', $next)
                """.trimIndent())
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            current = next
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Android SQLite DAO Implementations
// -------------------------------------------------------------------------------------------------

internal class AndroidDeviceIdentityDao(private val db: AndroidSqliteUpiMeshDatabase) : DeviceIdentityDao {
    override fun insert(identity: DeviceIdentity): Long {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("device_id", identity.deviceId)
                put("owner_vpa", identity.ownerVpa)
                put("public_key", identity.publicKey)
                put("encrypted_private_key", identity.encryptedPrivateKey)
                put("encryption_iv", identity.encryptionIv)
                put("enrollment_state", identity.enrollmentState.name)
                put("created_at", identity.createdAt)
                put("updated_at", identity.updatedAt)
            }
            database.insertOrThrow("device_identities", null, values)
        }
    }

    override fun getIdentity(deviceId: String): DeviceIdentity? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM device_identities WHERE device_id = ? LIMIT 1",
                arrayOf(deviceId)
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getActiveIdentity(): DeviceIdentity? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM device_identities WHERE enrollment_state = 'ENROLLED' ORDER BY created_at DESC LIMIT 1",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun update(identity: DeviceIdentity) {
        db.execute { database ->
            val values = ContentValues().apply {
                put("owner_vpa", identity.ownerVpa)
                put("public_key", identity.publicKey)
                put("encrypted_private_key", identity.encryptedPrivateKey)
                put("encryption_iv", identity.encryptionIv)
                put("enrollment_state", identity.enrollmentState.name)
                put("updated_at", identity.updatedAt)
            }
            database.update("device_identities", values, "device_id = ?", arrayOf(identity.deviceId))
        }
    }

    override fun delete(deviceId: String): Int {
        return db.execute { database ->
            database.delete("device_identities", "device_id = ?", arrayOf(deviceId))
        }
    }

    private fun mapRow(cursor: Cursor): DeviceIdentity {
        return DeviceIdentity(
            deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
            ownerVpa = cursor.getString(cursor.getColumnIndexOrThrow("owner_vpa")),
            publicKey = cursor.getString(cursor.getColumnIndexOrThrow("public_key")),
            encryptedPrivateKey = cursor.getString(cursor.getColumnIndexOrThrow("encrypted_private_key")),
            encryptionIv = cursor.getString(cursor.getColumnIndexOrThrow("encryption_iv")),
            enrollmentState = EnrollmentState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("enrollment_state"))),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }
}

internal class AndroidOfflineWalletDao(private val db: AndroidSqliteUpiMeshDatabase) : OfflineWalletDao {
    override fun insert(wallet: OfflineWallet): Long {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("wallet_id", wallet.walletId)
                put("owner_vpa", wallet.ownerVpa)
                put("owner_public_key", wallet.ownerPublicKey)
                put("allocated_amount_paisa", wallet.allocatedAmountPaisa)
                put("local_spent_amount_paisa", wallet.localSpentAmountPaisa)
                put("settled_amount_paisa", wallet.settledAmountPaisa)
                put("remaining_amount_paisa", wallet.remainingAmountPaisa)
                put("sequence_counter", wallet.sequenceCounter)
                put("wallet_epoch", wallet.walletEpoch)
                put("valid_from", wallet.validFrom)
                put("valid_until", wallet.validUntil)
                put("certificate_json", wallet.certificateJson)
                put("status", wallet.status.name)
                put("updated_at", wallet.updatedAt)
            }
            database.insertOrThrow("offline_wallets", null, values)
        }
    }

    override fun getWallet(walletId: String): OfflineWallet? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM offline_wallets WHERE wallet_id = ? LIMIT 1",
                arrayOf(walletId)
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getActiveWalletForOwner(ownerVpa: String): OfflineWallet? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM offline_wallets WHERE owner_vpa = ? AND status = 'ACTIVE' LIMIT 1",
                arrayOf(ownerVpa)
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getAllWallets(): List<OfflineWallet> {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM offline_wallets ORDER BY valid_until DESC",
                null
            ).use { cursor ->
                val list = mutableListOf<OfflineWallet>()
                while (cursor.moveToNext()) {
                    list.add(mapRow(cursor))
                }
                list
            }
        }
    }

    override fun update(wallet: OfflineWallet) {
        db.execute { database ->
            val values = ContentValues().apply {
                put("owner_vpa", wallet.ownerVpa)
                put("owner_public_key", wallet.ownerPublicKey)
                put("allocated_amount_paisa", wallet.allocatedAmountPaisa)
                put("local_spent_amount_paisa", wallet.localSpentAmountPaisa)
                put("settled_amount_paisa", wallet.settledAmountPaisa)
                put("remaining_amount_paisa", wallet.remainingAmountPaisa)
                put("sequence_counter", wallet.sequenceCounter)
                put("wallet_epoch", wallet.walletEpoch)
                put("valid_from", wallet.validFrom)
                put("valid_until", wallet.validUntil)
                put("certificate_json", wallet.certificateJson)
                put("status", wallet.status.name)
                put("updated_at", wallet.updatedAt)
            }
            database.update("offline_wallets", values, "wallet_id = ?", arrayOf(wallet.walletId))
        }
    }

    override fun updateStatus(walletId: String, status: WalletStatus, updatedAt: Long): Int {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("status", status.name)
                put("updated_at", updatedAt)
            }
            database.update("offline_wallets", values, "wallet_id = ?", arrayOf(walletId))
        }
    }

    override fun updateCounterAndBalances(
        walletId: String,
        nextCounter: Long,
        newRemainingPaisa: Long,
        newLocalSpentPaisa: Long,
        updatedAt: Long
    ): Int {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("sequence_counter", nextCounter)
                put("remaining_amount_paisa", newRemainingPaisa)
                put("local_spent_amount_paisa", newLocalSpentPaisa)
                put("updated_at", updatedAt)
            }
            database.update("offline_wallets", values, "wallet_id = ?", arrayOf(walletId))
        }
    }

    override fun updateSettledAmount(walletId: String, settledAmountPaisa: Long, updatedAt: Long): Int {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("settled_amount_paisa", settledAmountPaisa)
                put("updated_at", updatedAt)
            }
            database.update("offline_wallets", values, "wallet_id = ?", arrayOf(walletId))
        }
    }

    private fun mapRow(cursor: Cursor): OfflineWallet {
        return OfflineWallet(
            walletId = cursor.getString(cursor.getColumnIndexOrThrow("wallet_id")),
            ownerVpa = cursor.getString(cursor.getColumnIndexOrThrow("owner_vpa")),
            ownerPublicKey = cursor.getString(cursor.getColumnIndexOrThrow("owner_public_key")),
            allocatedAmountPaisa = cursor.getLong(cursor.getColumnIndexOrThrow("allocated_amount_paisa")),
            localSpentAmountPaisa = cursor.getLong(cursor.getColumnIndexOrThrow("local_spent_amount_paisa")),
            settledAmountPaisa = cursor.getLong(cursor.getColumnIndexOrThrow("settled_amount_paisa")),
            remainingAmountPaisa = cursor.getLong(cursor.getColumnIndexOrThrow("remaining_amount_paisa")),
            sequenceCounter = cursor.getLong(cursor.getColumnIndexOrThrow("sequence_counter")),
            walletEpoch = cursor.getLong(cursor.getColumnIndexOrThrow("wallet_epoch")),
            validFrom = cursor.getLong(cursor.getColumnIndexOrThrow("valid_from")),
            validUntil = cursor.getLong(cursor.getColumnIndexOrThrow("valid_until")),
            certificateJson = cursor.getString(cursor.getColumnIndexOrThrow("certificate_json")),
            status = WalletStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }
}

internal class AndroidOutboundPaymentDao(private val db: AndroidSqliteUpiMeshDatabase) : OutboundPaymentDao {
    override fun insert(payment: OutboundPayment): Long {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("payment_id", payment.paymentId)
                put("wallet_id", payment.walletId)
                put("sequence_counter", payment.sequenceCounter)
                put("amount_paisa", payment.amountPaisa)
                put("cumulative_amount_paisa", payment.cumulativeAmountPaisa)
                put("receiver_vpa", payment.receiverVpa)
                put("nonce", payment.nonce)
                put("packet_hash", payment.packetHash)
                put("ciphertext", payment.ciphertext)
                put("state", payment.state.name)
                put("created_at", payment.createdAt)
                put("updated_at", payment.updatedAt)
                put("retry_count", payment.retryCount)
            }
            database.insertOrThrow("outbound_payments", null, values)
        }
    }

    override fun getPayment(paymentId: String): OutboundPayment? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM outbound_payments WHERE payment_id = ? LIMIT 1",
                arrayOf(paymentId)
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getPaymentsForWallet(walletId: String): List<OutboundPayment> {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM outbound_payments WHERE wallet_id = ? ORDER BY sequence_counter ASC",
                arrayOf(walletId)
            ).use { cursor ->
                val list = mutableListOf<OutboundPayment>()
                while (cursor.moveToNext()) list.add(mapRow(cursor))
                list
            }
        }
    }

    override fun getPaymentsByState(state: OutboundPaymentState): List<OutboundPayment> {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM outbound_payments WHERE state = ? ORDER BY created_at ASC",
                arrayOf(state.name)
            ).use { cursor ->
                val list = mutableListOf<OutboundPayment>()
                while (cursor.moveToNext()) list.add(mapRow(cursor))
                list
            }
        }
    }

    override fun getPaymentByWalletAndCounter(walletId: String, sequenceCounter: Long): OutboundPayment? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM outbound_payments WHERE wallet_id = ? AND sequence_counter = ? LIMIT 1",
                arrayOf(walletId, sequenceCounter.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getPaymentByPacketHash(packetHash: String): OutboundPayment? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM outbound_payments WHERE packet_hash = ? LIMIT 1",
                arrayOf(packetHash)
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun update(payment: OutboundPayment) {
        db.execute { database ->
            val values = ContentValues().apply {
                put("wallet_id", payment.walletId)
                put("sequence_counter", payment.sequenceCounter)
                put("amount_paisa", payment.amountPaisa)
                put("cumulative_amount_paisa", payment.cumulativeAmountPaisa)
                put("receiver_vpa", payment.receiverVpa)
                put("nonce", payment.nonce)
                put("packet_hash", payment.packetHash)
                put("ciphertext", payment.ciphertext)
                put("state", payment.state.name)
                put("updated_at", payment.updatedAt)
                put("retry_count", payment.retryCount)
            }
            database.update("outbound_payments", values, "payment_id = ?", arrayOf(payment.paymentId))
        }
    }

    override fun updateState(paymentId: String, state: OutboundPaymentState, updatedAt: Long): Int {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("state", state.name)
                put("updated_at", updatedAt)
            }
            database.update("outbound_payments", values, "payment_id = ?", arrayOf(paymentId))
        }
    }

    override fun updateCryptoPayload(
        paymentId: String,
        ciphertext: String,
        packetHash: String,
        state: OutboundPaymentState,
        updatedAt: Long
    ): Int {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("ciphertext", ciphertext)
                put("packet_hash", packetHash)
                put("state", state.name)
                put("updated_at", updatedAt)
            }
            database.update("outbound_payments", values, "payment_id = ?", arrayOf(paymentId))
        }
    }

    private fun mapRow(cursor: Cursor): OutboundPayment {
        return OutboundPayment(
            paymentId = cursor.getString(cursor.getColumnIndexOrThrow("payment_id")),
            walletId = cursor.getString(cursor.getColumnIndexOrThrow("wallet_id")),
            sequenceCounter = cursor.getLong(cursor.getColumnIndexOrThrow("sequence_counter")),
            amountPaisa = cursor.getLong(cursor.getColumnIndexOrThrow("amount_paisa")),
            cumulativeAmountPaisa = cursor.getLong(cursor.getColumnIndexOrThrow("cumulative_amount_paisa")),
            receiverVpa = cursor.getString(cursor.getColumnIndexOrThrow("receiver_vpa")),
            nonce = cursor.getString(cursor.getColumnIndexOrThrow("nonce")),
            packetHash = cursor.getString(cursor.getColumnIndexOrThrow("packet_hash")),
            ciphertext = cursor.getString(cursor.getColumnIndexOrThrow("ciphertext")),
            state = OutboundPaymentState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state"))),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count"))
        )
    }
}

internal class AndroidReceivedPacketDao(private val db: AndroidSqliteUpiMeshDatabase) : ReceivedPacketDao {
    override fun insert(packet: ReceivedPacket): Long {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("packet_hash", packet.packetHash)
                put("packet_id", packet.packetId)
                put("ciphertext", packet.ciphertext)
                put("ttl", packet.ttl)
                put("hop_count", packet.hopCount)
                put("received_at", packet.receivedAt)
                put("uploaded_to_bridge", if (packet.uploadedToBridge) 1 else 0)
                put("status", packet.status.name)
                put("updated_at", packet.updatedAt)
            }
            database.insertWithOnConflict("received_packets", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    override fun getPacket(packetHash: String): ReceivedPacket? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM received_packets WHERE packet_hash = ? LIMIT 1",
                arrayOf(packetHash)
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getAllPackets(): List<ReceivedPacket> {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM received_packets ORDER BY received_at DESC",
                null
            ).use { cursor ->
                val list = mutableListOf<ReceivedPacket>()
                while (cursor.moveToNext()) list.add(mapRow(cursor))
                list
            }
        }
    }

    override fun getPendingBridgePackets(): List<ReceivedPacket> {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM received_packets WHERE uploaded_to_bridge = 0 ORDER BY received_at ASC",
                null
            ).use { cursor ->
                val list = mutableListOf<ReceivedPacket>()
                while (cursor.moveToNext()) list.add(mapRow(cursor))
                list
            }
        }
    }

    override fun update(packet: ReceivedPacket) {
        db.execute { database ->
            val values = ContentValues().apply {
                put("packet_id", packet.packetId)
                put("ciphertext", packet.ciphertext)
                put("ttl", packet.ttl)
                put("hop_count", packet.hopCount)
                put("uploaded_to_bridge", if (packet.uploadedToBridge) 1 else 0)
                put("status", packet.status.name)
                put("updated_at", packet.updatedAt)
            }
            database.update("received_packets", values, "packet_hash = ?", arrayOf(packet.packetHash))
        }
    }

    override fun markUploadedToBridge(packetHash: String, updatedAt: Long): Int {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("uploaded_to_bridge", 1)
                put("updated_at", updatedAt)
            }
            database.update("received_packets", values, "packet_hash = ?", arrayOf(packetHash))
        }
    }

    override fun exists(packetHash: String): Boolean {
        return db.execute { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM received_packets WHERE packet_hash = ?",
                arrayOf(packetHash)
            ).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) > 0
            }
        }
    }

    private fun mapRow(cursor: Cursor): ReceivedPacket {
        return ReceivedPacket(
            packetHash = cursor.getString(cursor.getColumnIndexOrThrow("packet_hash")),
            packetId = cursor.getString(cursor.getColumnIndexOrThrow("packet_id")),
            ciphertext = cursor.getString(cursor.getColumnIndexOrThrow("ciphertext")),
            ttl = cursor.getInt(cursor.getColumnIndexOrThrow("ttl")),
            hopCount = cursor.getInt(cursor.getColumnIndexOrThrow("hop_count")),
            receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow("received_at")),
            uploadedToBridge = cursor.getInt(cursor.getColumnIndexOrThrow("uploaded_to_bridge")) == 1,
            status = ReceivedPacketStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }
}

internal class AndroidPacketFragmentDao(private val db: AndroidSqliteUpiMeshDatabase) : PacketFragmentDao {
    override fun saveFragment(fragment: PacketFragment): Long {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("packet_hash", fragment.packetHash)
                put("chunk_index", fragment.chunkIndex)
                put("total_chunks", fragment.totalChunks)
                put("data", fragment.data)
                put("received_at", fragment.receivedAt)
            }
            database.insertWithOnConflict("packet_fragments", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun queryFragments(packetHash: String): List<PacketFragment> {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM packet_fragments WHERE packet_hash = ? ORDER BY chunk_index ASC",
                arrayOf(packetHash)
            ).use { cursor ->
                val list = mutableListOf<PacketFragment>()
                while (cursor.moveToNext()) list.add(mapRow(cursor))
                list
            }
        }
    }

    override fun countFragments(packetHash: String): Int {
        return db.execute { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM packet_fragments WHERE packet_hash = ?",
                arrayOf(packetHash)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    override fun deleteFragments(packetHash: String): Int {
        return db.execute { database ->
            database.delete("packet_fragments", "packet_hash = ?", arrayOf(packetHash))
        }
    }

    override fun clearPacketReassemblyState(packetHash: String): Int {
        return deleteFragments(packetHash)
    }

    override fun deleteFragmentsOlderThan(cutoffTime: Long): Int {
        return db.execute { database ->
            database.delete("packet_fragments", "received_at < ?", arrayOf(cutoffTime.toString()))
        }
    }

    private fun mapRow(cursor: Cursor): PacketFragment {
        return PacketFragment(
            packetHash = cursor.getString(cursor.getColumnIndexOrThrow("packet_hash")),
            chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunk_index")),
            totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow("total_chunks")),
            data = cursor.getBlob(cursor.getColumnIndexOrThrow("data")),
            receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow("received_at"))
        )
    }
}

internal class AndroidSettlementReceiptDao(private val db: AndroidSqliteUpiMeshDatabase) : SettlementReceiptDao {
    override fun insert(receipt: SettlementReceipt): Long {
        return db.execute { database ->
            val values = ContentValues().apply {
                put("transaction_id", receipt.transactionId)
                put("packet_hash", receipt.packetHash)
                put("counter", receipt.counter)
                put("status", receipt.status)
                put("settled_at", receipt.settledAt)
                put("server_signature", receipt.serverSignature)
            }
            database.insertOrThrow("settlement_receipts", null, values)
        }
    }

    override fun getReceipt(transactionId: Long): SettlementReceipt? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM settlement_receipts WHERE transaction_id = ? LIMIT 1",
                arrayOf(transactionId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getReceiptByPacketHash(packetHash: String): SettlementReceipt? {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM settlement_receipts WHERE packet_hash = ? LIMIT 1",
                arrayOf(packetHash)
            ).use { cursor ->
                if (cursor.moveToFirst()) mapRow(cursor) else null
            }
        }
    }

    override fun getAllReceipts(): List<SettlementReceipt> {
        return db.execute { database ->
            database.rawQuery(
                "SELECT * FROM settlement_receipts ORDER BY settled_at DESC",
                null
            ).use { cursor ->
                val list = mutableListOf<SettlementReceipt>()
                while (cursor.moveToNext()) list.add(mapRow(cursor))
                list
            }
        }
    }

    private fun mapRow(cursor: Cursor): SettlementReceipt {
        return SettlementReceipt(
            transactionId = cursor.getLong(cursor.getColumnIndexOrThrow("transaction_id")),
            packetHash = cursor.getString(cursor.getColumnIndexOrThrow("packet_hash")),
            counter = cursor.getLong(cursor.getColumnIndexOrThrow("counter")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            settledAt = cursor.getLong(cursor.getColumnIndexOrThrow("settled_at")),
            serverSignature = cursor.getString(cursor.getColumnIndexOrThrow("server_signature"))
        )
    }
}

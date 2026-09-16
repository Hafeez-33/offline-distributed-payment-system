package com.demo.upimesh.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Enrollment state for a physical Android device.
 */
enum class EnrollmentState {
    ENROLLED,
    SUSPENDED,
    REVOKED
}

/**
 * Lifecycle states for an offline wallet.
 * Local safety rules govern transitions.
 */
enum class WalletStatus {
    ACTIVE,
    EXPIRED,
    LOCKED_DISPUTED,
    AUDIT_REQUIRED,
    RECONCILED_CLOSED,
    PENDING_RECONCILE
}

/**
 * Controlled lifecycle states for an outbound payment created locally.
 */
enum class OutboundPaymentState {
    CREATED,
    ENCRYPTED,
    READY_FOR_TRANSPORT,
    PENDING_BRIDGE,
    SETTLEMENT_CONFIRMED,
    REJECTED,
    CONFLICTING,
    EXPIRED
}

/**
 * Ingestion state for a mesh packet received locally.
 */
enum class ReceivedPacketStatus {
    STORED,
    FORWARDED,
    SETTLED,
    DUPLICATE
}

/**
 * Durable local storage for device identity.
 * Private key is NEVER stored plaintext; it is encrypted using the Android Keystore master key.
 */
@Entity(
    tableName = "device_identities",
    indices = [Index(value = ["owner_vpa"])]
)
data class DeviceIdentity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "owner_vpa")
    val ownerVpa: String,

    @ColumnInfo(name = "public_key")
    val publicKey: String,

    @ColumnInfo(name = "encrypted_private_key")
    val encryptedPrivateKey: String,

    @ColumnInfo(name = "encryption_iv")
    val encryptionIv: String,

    @ColumnInfo(name = "enrollment_state")
    val enrollmentState: EnrollmentState = EnrollmentState.ENROLLED,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Durable local state for an offline spending wallet.
 *
 * MONETARY UNITS:
 * All monetary values use Long integer paisa (₹1.00 = 100).
 * Floating-point representation is strictly prohibited.
 *
 * LOCAL SETTLEMENT BOUNDARY:
 * `localSpentAmountPaisa` records locally committed offline spends.
 * `settledAmountPaisa` is ONLY updated upon receiving an authoritative SettlementReceipt from the backend.
 * Local payment creation NEVER increments settledAmountPaisa.
 */
@Entity(
    tableName = "offline_wallets",
    indices = [Index(value = ["owner_vpa"])]
)
data class OfflineWallet(
    @PrimaryKey
    @ColumnInfo(name = "wallet_id")
    val walletId: String,

    @ColumnInfo(name = "owner_vpa")
    val ownerVpa: String,

    @ColumnInfo(name = "owner_public_key")
    val ownerPublicKey: String,

    @ColumnInfo(name = "allocated_amount_paisa")
    val allocatedAmountPaisa: Long,

    @ColumnInfo(name = "local_spent_amount_paisa")
    val localSpentAmountPaisa: Long = 0L,

    @ColumnInfo(name = "settled_amount_paisa")
    val settledAmountPaisa: Long = 0L,

    @ColumnInfo(name = "remaining_amount_paisa")
    val remainingAmountPaisa: Long,

    @ColumnInfo(name = "sequence_counter")
    val sequenceCounter: Long = 0L,

    @ColumnInfo(name = "wallet_epoch")
    val walletEpoch: Long = 1L,

    @ColumnInfo(name = "valid_from")
    val validFrom: Long,

    @ColumnInfo(name = "valid_until")
    val validUntil: Long,

    @ColumnInfo(name = "certificate_json")
    val certificateJson: String,

    @ColumnInfo(name = "status")
    val status: WalletStatus = WalletStatus.ACTIVE,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Durable local record of an outbound offline payment initiated by this device.
 * Persists immutable intent first before transport.
 */
@Entity(
    tableName = "outbound_payments",
    indices = [
        Index(value = ["wallet_id"]),
        Index(value = ["wallet_id", "sequence_counter"], unique = true),
        Index(value = ["packet_hash"])
    ]
)
data class OutboundPayment(
    @PrimaryKey
    @ColumnInfo(name = "payment_id")
    val paymentId: String,

    @ColumnInfo(name = "wallet_id")
    val walletId: String,

    @ColumnInfo(name = "sequence_counter")
    val sequenceCounter: Long,

    @ColumnInfo(name = "amount_paisa")
    val amountPaisa: Long,

    @ColumnInfo(name = "cumulative_amount_paisa")
    val cumulativeAmountPaisa: Long,

    @ColumnInfo(name = "receiver_vpa")
    val receiverVpa: String,

    @ColumnInfo(name = "nonce")
    val nonce: String,

    @ColumnInfo(name = "packet_hash")
    val packetHash: String? = null,

    @ColumnInfo(name = "ciphertext")
    val ciphertext: String? = null,

    @ColumnInfo(name = "state")
    val state: OutboundPaymentState = OutboundPaymentState.CREATED,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0
)

/**
 * Durable local record of an incoming mesh packet received from peer devices.
 * Local deduplication is an optimization; backend PostgreSQL UNIQUE(packet_hash) remains authoritative.
 */
@Entity(tableName = "received_packets")
data class ReceivedPacket(
    @PrimaryKey
    @ColumnInfo(name = "packet_hash")
    val packetHash: String,

    @ColumnInfo(name = "packet_id")
    val packetId: String,

    @ColumnInfo(name = "ciphertext")
    val ciphertext: String,

    @ColumnInfo(name = "ttl")
    val ttl: Int,

    @ColumnInfo(name = "hop_count")
    val hopCount: Int,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "uploaded_to_bridge")
    val uploadedToBridge: Boolean = false,

    @ColumnInfo(name = "status")
    val status: ReceivedPacketStatus = ReceivedPacketStatus.STORED,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Durable local storage for chunks of a packet being assembled over low-MTU links.
 * Uses composite primary key (packetHash, chunkIndex).
 */
@Entity(
    tableName = "packet_fragments",
    primaryKeys = ["packet_hash", "chunk_index"],
    indices = [Index(value = ["packet_hash"])]
)
data class PacketFragment(
    @ColumnInfo(name = "packet_hash")
    val packetHash: String,

    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,

    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int,

    @ColumnInfo(name = "data")
    val data: ByteArray,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PacketFragment
        if (packetHash != other.packetHash) return false
        if (chunkIndex != other.chunkIndex) return false
        if (totalChunks != other.totalChunks) return false
        if (!data.contentEquals(other.data)) return false
        if (receivedAt != other.receivedAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = packetHash.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + totalChunks
        result = 31 * result + data.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        return result
    }
}

/**
 * Authoritative settlement receipt issued by backend and verified on device.
 * Stored only after cryptographic signature verification passes.
 */
@Entity(
    tableName = "settlement_receipts",
    indices = [Index(value = ["packet_hash"])]
)
data class SettlementReceipt(
    @PrimaryKey
    @ColumnInfo(name = "transaction_id")
    val transactionId: Long,

    @ColumnInfo(name = "packet_hash")
    val packetHash: String,

    @ColumnInfo(name = "counter")
    val counter: Long,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "settled_at")
    val settledAt: Long,

    @ColumnInfo(name = "server_signature")
    val serverSignature: String
)

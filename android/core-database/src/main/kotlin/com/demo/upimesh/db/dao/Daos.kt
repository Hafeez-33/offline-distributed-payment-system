package com.demo.upimesh.db.dao

import androidx.room.*
import com.demo.upimesh.db.entity.*

@Dao
interface DeviceIdentityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(identity: DeviceIdentity): Long

    @Query("SELECT * FROM device_identities WHERE device_id = :deviceId LIMIT 1")
    fun getIdentity(deviceId: String): DeviceIdentity?

    @Query("SELECT * FROM device_identities WHERE enrollment_state = 'ENROLLED' ORDER BY created_at DESC LIMIT 1")
    fun getActiveIdentity(): DeviceIdentity?

    @Update
    fun update(identity: DeviceIdentity)

    @Query("DELETE FROM device_identities WHERE device_id = :deviceId")
    fun delete(deviceId: String): Int
}

@Dao
interface OfflineWalletDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(wallet: OfflineWallet): Long

    @Query("SELECT * FROM offline_wallets WHERE wallet_id = :walletId LIMIT 1")
    fun getWallet(walletId: String): OfflineWallet?

    @Query("SELECT * FROM offline_wallets WHERE owner_vpa = :ownerVpa AND status = 'ACTIVE' LIMIT 1")
    fun getActiveWalletForOwner(ownerVpa: String): OfflineWallet?

    @Query("SELECT * FROM offline_wallets ORDER BY valid_until DESC")
    fun getAllWallets(): List<OfflineWallet>

    @Update
    fun update(wallet: OfflineWallet)

    @Query("UPDATE offline_wallets SET status = :status, updated_at = :updatedAt WHERE wallet_id = :walletId")
    fun updateStatus(walletId: String, status: WalletStatus, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE offline_wallets 
        SET sequence_counter = :nextCounter,
            remaining_amount_paisa = :newRemainingPaisa,
            local_spent_amount_paisa = :newLocalSpentPaisa,
            updated_at = :updatedAt 
        WHERE wallet_id = :walletId
    """)
    fun updateCounterAndBalances(
        walletId: String,
        nextCounter: Long,
        newRemainingPaisa: Long,
        newLocalSpentPaisa: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE offline_wallets SET settled_amount_paisa = :settledAmountPaisa, updated_at = :updatedAt WHERE wallet_id = :walletId")
    fun updateSettledAmount(
        walletId: String,
        settledAmountPaisa: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
}

@Dao
interface OutboundPaymentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(payment: OutboundPayment): Long

    @Query("SELECT * FROM outbound_payments WHERE payment_id = :paymentId LIMIT 1")
    fun getPayment(paymentId: String): OutboundPayment?

    @Query("SELECT * FROM outbound_payments WHERE wallet_id = :walletId ORDER BY sequence_counter ASC")
    fun getPaymentsForWallet(walletId: String): List<OutboundPayment>

    @Query("SELECT * FROM outbound_payments WHERE state = :state ORDER BY created_at ASC")
    fun getPaymentsByState(state: OutboundPaymentState): List<OutboundPayment>

    @Query("SELECT * FROM outbound_payments WHERE wallet_id = :walletId AND sequence_counter = :sequenceCounter LIMIT 1")
    fun getPaymentByWalletAndCounter(walletId: String, sequenceCounter: Long): OutboundPayment?

    @Query("SELECT * FROM outbound_payments WHERE packet_hash = :packetHash LIMIT 1")
    fun getPaymentByPacketHash(packetHash: String): OutboundPayment?

    @Update
    fun update(payment: OutboundPayment)

    @Query("UPDATE outbound_payments SET state = :state, updated_at = :updatedAt WHERE payment_id = :paymentId")
    fun updateState(
        paymentId: String,
        state: OutboundPaymentState,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE outbound_payments 
        SET ciphertext = :ciphertext,
            packet_hash = :packetHash,
            state = :state,
            updated_at = :updatedAt 
        WHERE payment_id = :paymentId
    """)
    fun updateCryptoPayload(
        paymentId: String,
        ciphertext: String,
        packetHash: String,
        state: OutboundPaymentState,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
}

@Dao
interface ReceivedPacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(packet: ReceivedPacket): Long

    @Query("SELECT * FROM received_packets WHERE packet_hash = :packetHash LIMIT 1")
    fun getPacket(packetHash: String): ReceivedPacket?

    @Query("SELECT * FROM received_packets ORDER BY received_at DESC")
    fun getAllPackets(): List<ReceivedPacket>

    @Query("SELECT * FROM received_packets WHERE uploaded_to_bridge = 0 ORDER BY received_at ASC")
    fun getPendingBridgePackets(): List<ReceivedPacket>

    @Update
    fun update(packet: ReceivedPacket)

    @Query("UPDATE received_packets SET uploaded_to_bridge = 1, updated_at = :updatedAt WHERE packet_hash = :packetHash")
    fun markUploadedToBridge(packetHash: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) > 0 FROM received_packets WHERE packet_hash = :packetHash")
    fun exists(packetHash: String): Boolean
}

@Dao
interface PacketFragmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveFragment(fragment: PacketFragment): Long

    @Query("SELECT * FROM packet_fragments WHERE packet_hash = :packetHash ORDER BY chunk_index ASC")
    fun queryFragments(packetHash: String): List<PacketFragment>

    @Query("SELECT COUNT(*) FROM packet_fragments WHERE packet_hash = :packetHash")
    fun countFragments(packetHash: String): Int

    @Query("DELETE FROM packet_fragments WHERE packet_hash = :packetHash")
    fun deleteFragments(packetHash: String): Int

    @Query("DELETE FROM packet_fragments WHERE packet_hash = :packetHash")
    fun clearPacketReassemblyState(packetHash: String): Int

    @Query("DELETE FROM packet_fragments WHERE received_at < :cutoffTime")
    fun deleteFragmentsOlderThan(cutoffTime: Long): Int
}

@Dao
interface SettlementReceiptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(receipt: SettlementReceipt): Long

    @Query("SELECT * FROM settlement_receipts WHERE transaction_id = :transactionId LIMIT 1")
    fun getReceipt(transactionId: Long): SettlementReceipt?

    @Query("SELECT * FROM settlement_receipts WHERE packet_hash = :packetHash LIMIT 1")
    fun getReceiptByPacketHash(packetHash: String): SettlementReceipt?

    @Query("SELECT * FROM settlement_receipts ORDER BY settled_at DESC")
    fun getAllReceipts(): List<SettlementReceipt>
}

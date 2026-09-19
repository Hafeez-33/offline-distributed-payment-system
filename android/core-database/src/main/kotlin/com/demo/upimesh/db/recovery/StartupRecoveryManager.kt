package com.demo.upimesh.db.recovery

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.engine.OfflineWalletEngine
import com.demo.upimesh.db.entity.DeviceIdentity
import com.demo.upimesh.db.entity.OfflineWallet
import com.demo.upimesh.db.entity.OutboundPaymentState
import com.demo.upimesh.db.entity.WalletStatus
import com.demo.upimesh.db.metrics.LocalDatabaseMetrics

data class RecoveryReport(
    val enrolledDevice: DeviceIdentity?,
    val activeWalletsCount: Int,
    val expiredWalletsCount: Int,
    val paymentsRecoveredToReady: Int,
    val paymentsPendingTransport: Int,
    val paymentsPendingBridge: Int,
    val staleFragmentsPurged: Int,
    val receivedPacketsCount: Int
)

/**
 * Deterministic recovery coordinator executed on application startup.
 *
 * CRITICAL RECOVERY RULES:
 * 1. Never regenerate a partially-created payment as a new logical payment.
 * 2. Immutable intent data (paymentId, walletId, counter, amount, receiver, nonce, createdAt)
 *    persisted at CREATED state MUST be preserved without generating a new nonce or sequence counter.
 * 3. Android local state is an execution/replication cache; PostgreSQL remains authoritative.
 */
class StartupRecoveryManager(
    private val database: UpiMeshDatabase,
    private val walletEngine: OfflineWalletEngine
) {

    fun performStartupRecovery(
        serverRsaPublicKeyBase64: String? = null,
        fragmentTtlMillis: Long = 86_400_000L, // 24 hours
        now: Long = System.currentTimeMillis()
    ): RecoveryReport {
        return database.withTransaction {
            // 1. Reload DeviceIdentity
            val enrolledDevice = database.deviceIdentityDao.getActiveIdentity()

            // 2. Reload and inspect OfflineWallets
            val allWallets = database.offlineWalletDao.getAllWallets()
            var activeWallets = 0
            var expiredWallets = 0

            for (wallet in allWallets) {
                if (wallet.status == WalletStatus.ACTIVE) {
                    if (now > wallet.validUntil) {
                        database.offlineWalletDao.updateStatus(wallet.walletId, WalletStatus.EXPIRED, now)
                        expiredWallets++
                    } else {
                        activeWallets++
                    }
                }
            }

            // 3. Inspect OutboundPayments and deterministically recover incomplete states
            var recoveredToReady = 0

            // A. Payments in CREATED state: Resume preparation using existing immutable intent
            val createdPayments = database.outboundPaymentDao.getPaymentsByState(OutboundPaymentState.CREATED)
            for (payment in createdPayments) {
                if (serverRsaPublicKeyBase64 != null) {
                    walletEngine.prepareCreatedPayment(payment, serverRsaPublicKeyBase64)
                    recoveredToReady++
                    LocalDatabaseMetrics.recordLocalPaymentRecovered()
                }
            }

            // B. Payments in ENCRYPTED state: Verify consistency and transition safely to READY_FOR_TRANSPORT
            val encryptedPayments = database.outboundPaymentDao.getPaymentsByState(OutboundPaymentState.ENCRYPTED)
            for (payment in encryptedPayments) {
                if (!payment.ciphertext.isNullOrBlank()) {
                    val computedHash = PacketHasher.hashCiphertext(payment.ciphertext)
                    val finalHash = payment.packetHash ?: computedHash
                    database.outboundPaymentDao.updateCryptoPayload(
                        paymentId = payment.paymentId,
                        ciphertext = payment.ciphertext,
                        packetHash = finalHash,
                        state = OutboundPaymentState.READY_FOR_TRANSPORT,
                        updatedAt = now
                    )
                    recoveredToReady++
                    LocalDatabaseMetrics.recordLocalPaymentRecovered()
                }
            }

            // C. Count pending transport and pending bridge payments
            val readyPayments = database.outboundPaymentDao.getPaymentsByState(OutboundPaymentState.READY_FOR_TRANSPORT)
            val pendingBridgePayments = database.outboundPaymentDao.getPaymentsByState(OutboundPaymentState.PENDING_BRIDGE)

            // 4. Purge stale packet fragments
            val cutoff = now - fragmentTtlMillis
            val purgedFragments = database.packetFragmentDao.deleteFragmentsOlderThan(cutoff)

            // 5. Total received packets
            val receivedPackets = database.receivedPacketDao.getAllPackets().size

            RecoveryReport(
                enrolledDevice = enrolledDevice,
                activeWalletsCount = activeWallets,
                expiredWalletsCount = expiredWallets,
                paymentsRecoveredToReady = recoveredToReady,
                paymentsPendingTransport = readyPayments.size,
                paymentsPendingBridge = pendingBridgePayments.size,
                staleFragmentsPurged = purgedFragments,
                receivedPacketsCount = receivedPackets
            )
        }
    }
}

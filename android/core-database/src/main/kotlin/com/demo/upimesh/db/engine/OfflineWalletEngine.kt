package com.demo.upimesh.db.engine

import com.demo.upimesh.crypto.*
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.entity.*
import com.demo.upimesh.db.keystore.EncryptedKeyEnvelope
import com.demo.upimesh.db.keystore.KeyStoreManager
import com.demo.upimesh.db.metrics.LocalDatabaseMetrics
import com.demo.upimesh.model.OfflineWalletCertificate
import com.demo.upimesh.model.PaymentInstruction
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID

sealed class WalletEngineException(message: String) : RuntimeException(message)
class WalletValidationException(message: String) : WalletEngineException(message)
class InsufficientEscrowException(message: String) : WalletEngineException(message)
class InvalidSequenceException(message: String) : WalletEngineException(message)
class InvalidWalletStateException(message: String) : WalletEngineException(message)
class PaymentCreationException(message: String) : WalletEngineException(message)

/**
 * Dedicated domain engine for local offline wallet state and atomic spend execution.
 *
 * CRITICAL ARCHITECTURAL BOUNDARY:
 * Android Room local persistence is strictly an execution/replication cache.
 * Local payment creation records pending intent; authoritative settlement finality occurs ONLY at PostgreSQL.
 *
 * `settledAmountPaisa` is NEVER incremented by local spend.
 * It is updated ONLY when an authoritative, cryptographically verified SettlementReceipt arrives from backend.
 */
class OfflineWalletEngine(
    private val database: UpiMeshDatabase,
    private val keyStoreManager: KeyStoreManager,
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
) {

    /**
     * Initializes and persists an offline wallet granted by the backend.
     */
    fun createWallet(wallet: OfflineWallet): OfflineWallet {
        require(wallet.allocatedAmountPaisa > 0) { "Allocated amount must be positive" }
        require(wallet.remainingAmountPaisa == wallet.allocatedAmountPaisa) {
            "Initial remaining amount must equal allocated amount"
        }
        require(wallet.localSpentAmountPaisa == 0L) { "Initial local spent amount must be 0" }
        require(wallet.settledAmountPaisa == 0L) { "Initial settled amount must be 0" }

        database.offlineWalletDao.insert(wallet)
        LocalDatabaseMetrics.recordWalletOperation("create", "success")
        return wallet
    }

    /**
     * Validates local wallet state against business rules and expiration.
     */
    fun validateWallet(
        wallet: OfflineWallet,
        ownerVpa: String,
        now: Long = System.currentTimeMillis()
    ) {
        if (!wallet.ownerVpa.equals(ownerVpa.trim(), ignoreCase = true)) {
            LocalDatabaseMetrics.recordWalletOperation("validate", "unauthorized_owner")
            throw WalletValidationException("Wallet owner mismatch: ${wallet.ownerVpa} vs $ownerVpa")
        }

        if (wallet.status != WalletStatus.ACTIVE) {
            LocalDatabaseMetrics.recordWalletOperation("validate", "invalid_status")
            throw InvalidWalletStateException("Wallet is not active: status is ${wallet.status}")
        }

        if (now < wallet.validFrom || now > wallet.validUntil) {
            LocalDatabaseMetrics.recordWalletOperation("validate", "expired")
            throw WalletValidationException(
                "Wallet validity expired: validFrom=${wallet.validFrom}, validUntil=${wallet.validUntil}, now=$now"
            )
        }
    }

    /**
     * Creates an outbound offline payment atomically within a single database transaction.
     *
     * ATOMICITY GUARANTEE:
     * One single Room/SQLite transaction executes:
     * 1. Validate wallet status, owner, expiration
     * 2. Validate counter (nextCounter == currentCounter + 1, no rollback)
     * 3. Validate remaining allowance (remaining >= spendAmount)
     * 4. Increment sequenceCounter, decrement remainingAmountPaisa, increment localSpentAmountPaisa
     *    (settledAmountPaisa is untouched!)
     * 5. Persist immutable OutboundPayment intent with state CREATED
     * 6. Construct canonical v3_tx, sign with device Ed25519 key, hybrid-encrypt with server RSA key
     * 7. Calculate packetHash = SHA-256(ciphertext)
     * 8. Update OutboundPayment with ciphertext, packetHash, and state READY_FOR_TRANSPORT
     * 9. Commit
     *
     * Only after the Room transaction commits is later BLE/mesh transport allowed to transmit the payment.
     */
    fun createOfflineSpend(
        walletId: String,
        ownerVpa: String,
        receiverVpa: String,
        amountPaisa: Long,
        serverRsaPublicKeyBase64: String,
        now: Long = System.currentTimeMillis()
    ): OutboundPayment {
        if (amountPaisa <= 0) {
            throw WalletValidationException("Payment amount must be positive: $amountPaisa")
        }
        if (ownerVpa.trim().equals(receiverVpa.trim(), ignoreCase = true)) {
            throw WalletValidationException("Cannot spend to self: owner=$ownerVpa, receiver=$receiverVpa")
        }

        return database.withTransaction {
            val wallet = database.offlineWalletDao.getWallet(walletId)
                ?: throw WalletValidationException("Wallet not found: $walletId")

            // 1. Validate wallet rules
            validateWallet(wallet, ownerVpa, now)

            // 2. Validate remaining allowance
            if (wallet.remainingAmountPaisa < amountPaisa) {
                LocalDatabaseMetrics.recordWalletOperation("spend", "insufficient_escrow")
                throw InsufficientEscrowException(
                    "Insufficient local offline allowance: remaining=${wallet.remainingAmountPaisa}, requested=$amountPaisa"
                )
            }

            // 3. Monotonic counter calculation
            val nextCounter = wallet.sequenceCounter + 1
            val newRemainingPaisa = wallet.remainingAmountPaisa - amountPaisa
            val newLocalSpentPaisa = wallet.localSpentAmountPaisa + amountPaisa

            // Invariant check: cumulative amount cannot exceed allocated amount
            if (newLocalSpentPaisa > wallet.allocatedAmountPaisa) {
                throw InsufficientEscrowException("Cumulative spend exceeds allocated escrow limit")
            }

            // 4. Update wallet row: local allowance & counter updated, settledAmountPaisa strictly untouched!
            database.offlineWalletDao.updateCounterAndBalances(
                walletId = walletId,
                nextCounter = nextCounter,
                newRemainingPaisa = newRemainingPaisa,
                newLocalSpentPaisa = newLocalSpentPaisa,
                updatedAt = now
            )

            // 5. Create and persist immutable intent with state CREATED
            val paymentId = UUID.randomUUID().toString()
            val nonce = UUID.randomUUID().toString()

            val initialPayment = OutboundPayment(
                paymentId = paymentId,
                walletId = walletId,
                sequenceCounter = nextCounter,
                amountPaisa = amountPaisa,
                cumulativeAmountPaisa = newLocalSpentPaisa,
                receiverVpa = receiverVpa.trim().lowercase(),
                nonce = nonce,
                packetHash = null,
                ciphertext = null,
                state = OutboundPaymentState.CREATED,
                createdAt = now,
                updatedAt = now,
                retryCount = 0
            )
            database.outboundPaymentDao.insert(initialPayment)

            // 6. Cryptographic preparation
            val activeDevice = database.deviceIdentityDao.getActiveIdentity()
                ?: throw PaymentCreationException("No active enrolled device identity found to sign payment")

            val privateKeyBytes = keyStoreManager.decryptPrivateKey(
                EncryptedKeyEnvelope(
                    ciphertextBase64 = activeDevice.encryptedPrivateKey,
                    ivBase64 = activeDevice.encryptionIv
                )
            )

            val privateKey: PrivateKey = try {
                Ed25519Crypto.decodePrivateKey(java.util.Base64.getEncoder().encodeToString(privateKeyBytes))
            } finally {
                // Best-effort memory hygiene
                keyStoreManager.bestEffortZeroize(privateKeyBytes)
            }

            val cert: OfflineWalletCertificate = try {
                objectMapper.readValue(wallet.certificateJson, OfflineWalletCertificate::class.java)
            } catch (e: Exception) {
                throw PaymentCreationException("Failed to deserialize wallet certificate: ${e.message}")
            }

            val amountBigDecimal = BigDecimal(amountPaisa).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            val cumBigDecimal = BigDecimal(newLocalSpentPaisa).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

            val instruction = PaymentInstruction(
                senderVpa = wallet.ownerVpa.trim().lowercase(),
                receiverVpa = receiverVpa.trim().lowercase(),
                amount = amountBigDecimal,
                pinHash = "pin",
                nonce = nonce,
                signedAt = now,
                walletId = wallet.walletId,
                walletEpoch = wallet.walletEpoch,
                sequenceCounter = nextCounter,
                cumulativeAmount = cumBigDecimal,
                walletCertificate = cert
            )

            val canonicalBytes = CanonicalSerializer.toCanonicalBytes(instruction)
            val signature = Ed25519Crypto.sign(canonicalBytes, privateKey)
            instruction.signature = signature

            val serverRsaKey: PublicKey = HybridCrypto.decodeRsaPublicKey(serverRsaPublicKeyBase64)
            val instructionBytes = objectMapper.writeValueAsBytes(instruction)
            val ciphertext = HybridCrypto.encrypt(instructionBytes, serverRsaKey)
            val packetHash = PacketHasher.hashCiphertext(ciphertext)

            // 7. Update OutboundPayment with ciphertext + hash and transition to READY_FOR_TRANSPORT
            database.outboundPaymentDao.updateCryptoPayload(
                paymentId = paymentId,
                ciphertext = ciphertext,
                packetHash = packetHash,
                state = OutboundPaymentState.READY_FOR_TRANSPORT,
                updatedAt = now
            )

            LocalDatabaseMetrics.recordLocalPaymentCreated()
            LocalDatabaseMetrics.recordWalletOperation("spend", "success")

            database.outboundPaymentDao.getPayment(paymentId)!!
        }
    }

    /**
     * Resumes preparation of an existing CREATED payment without regenerating nonce or sequence number.
     */
    fun prepareCreatedPayment(
        payment: OutboundPayment,
        serverRsaPublicKeyBase64: String
    ): OutboundPayment {
        require(payment.state == OutboundPaymentState.CREATED) { "Payment must be in CREATED state" }

        return database.withTransaction {
            val wallet = database.offlineWalletDao.getWallet(payment.walletId)
                ?: throw WalletValidationException("Wallet not found for payment: ${payment.walletId}")

            val activeDevice = database.deviceIdentityDao.getActiveIdentity()
                ?: throw PaymentCreationException("No active enrolled device identity found to sign payment")

            val privateKeyBytes = keyStoreManager.decryptPrivateKey(
                EncryptedKeyEnvelope(
                    ciphertextBase64 = activeDevice.encryptedPrivateKey,
                    ivBase64 = activeDevice.encryptionIv
                )
            )

            val privateKey: PrivateKey = try {
                Ed25519Crypto.decodePrivateKey(java.util.Base64.getEncoder().encodeToString(privateKeyBytes))
            } finally {
                keyStoreManager.bestEffortZeroize(privateKeyBytes)
            }

            val cert: OfflineWalletCertificate = objectMapper.readValue(
                wallet.certificateJson,
                OfflineWalletCertificate::class.java
            )

            val amountBigDecimal = BigDecimal(payment.amountPaisa).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            val cumBigDecimal = BigDecimal(payment.cumulativeAmountPaisa).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

            // Reconstruct exact instruction using persisted immutable fields
            val instruction = PaymentInstruction(
                senderVpa = wallet.ownerVpa.trim().lowercase(),
                receiverVpa = payment.receiverVpa.trim().lowercase(),
                amount = amountBigDecimal,
                pinHash = "pin",
                nonce = payment.nonce,
                signedAt = payment.createdAt,
                walletId = payment.walletId,
                walletEpoch = wallet.walletEpoch,
                sequenceCounter = payment.sequenceCounter,
                cumulativeAmount = cumBigDecimal,
                walletCertificate = cert
            )

            val canonicalBytes = CanonicalSerializer.toCanonicalBytes(instruction)
            val signature = Ed25519Crypto.sign(canonicalBytes, privateKey)
            instruction.signature = signature

            val serverRsaKey: PublicKey = HybridCrypto.decodeRsaPublicKey(serverRsaPublicKeyBase64)
            val instructionBytes = objectMapper.writeValueAsBytes(instruction)
            val ciphertext = HybridCrypto.encrypt(instructionBytes, serverRsaKey)
            val packetHash = PacketHasher.hashCiphertext(ciphertext)

            database.outboundPaymentDao.updateCryptoPayload(
                paymentId = payment.paymentId,
                ciphertext = ciphertext,
                packetHash = packetHash,
                state = OutboundPaymentState.READY_FOR_TRANSPORT,
                updatedAt = System.currentTimeMillis()
            )

            database.outboundPaymentDao.getPayment(payment.paymentId)!!
        }
    }

    /**
     * Advances the sequence counter monotonically. Rejects counter rollback.
     */
    fun advanceSequence(walletId: String, expectedCounter: Long): OfflineWallet {
        return database.withTransaction {
            val wallet = database.offlineWalletDao.getWallet(walletId)
                ?: throw WalletValidationException("Wallet not found: $walletId")

            if (expectedCounter != wallet.sequenceCounter + 1) {
                LocalDatabaseMetrics.recordWalletOperation("advance_seq", "rejected")
                throw InvalidSequenceException(
                    "Sequence mismatch: expected ${wallet.sequenceCounter + 1}, got $expectedCounter (rollback rejected)"
                )
            }

            database.offlineWalletDao.updateCounterAndBalances(
                walletId = walletId,
                nextCounter = expectedCounter,
                newRemainingPaisa = wallet.remainingAmountPaisa,
                newLocalSpentPaisa = wallet.localSpentAmountPaisa,
                updatedAt = System.currentTimeMillis()
            )

            database.offlineWalletDao.getWallet(walletId)!!
        }
    }

    fun markPendingReconcile(walletId: String) {
        transitionWalletStatus(walletId, WalletStatus.PENDING_RECONCILE)
    }

    fun markConflicting(walletId: String, reason: String) {
        LocalDatabaseMetrics.recordWalletOperation("conflict", reason.take(20).replace(" ", "_"))
        transitionWalletStatus(walletId, WalletStatus.LOCKED_DISPUTED)
    }

    fun markExpired(walletId: String) {
        transitionWalletStatus(walletId, WalletStatus.EXPIRED)
    }

    fun lockDisputed(walletId: String, disputeReason: String) {
        LocalDatabaseMetrics.recordWalletOperation("dispute", disputeReason.take(20).replace(" ", "_"))
        transitionWalletStatus(walletId, WalletStatus.LOCKED_DISPUTED)
    }

    private fun transitionWalletStatus(walletId: String, newStatus: WalletStatus) {
        database.withTransaction {
            val wallet = database.offlineWalletDao.getWallet(walletId)
                ?: throw WalletValidationException("Wallet not found: $walletId")

            // Terminal status checks
            if (wallet.status == WalletStatus.RECONCILED_CLOSED) {
                throw InvalidWalletStateException("Cannot transition a RECONCILED_CLOSED wallet")
            }

            database.offlineWalletDao.updateStatus(walletId, newStatus, System.currentTimeMillis())
            LocalDatabaseMetrics.recordWalletOperation("transition_${newStatus.name.lowercase()}", "success")
        }
    }

    /**
     * Verifies and records an authoritative backend SettlementReceipt.
     * THIS IS THE ONLY TRANSITION THAT INCREASES settledAmountPaisa.
     */
    fun applySettlementReceipt(
        receipt: com.demo.upimesh.model.SettlementReceipt,
        serverIssuerPublicKeyBase64: String
    ): SettlementReceipt {
        // 1. Verify receipt signature before touching local DB
        val canonicalBytes = CanonicalSerializer.toCanonicalBytes(receipt)
        val issuerPublicKey: PublicKey = Ed25519Crypto.decodePublicKey(serverIssuerPublicKeyBase64)
        val isValid = Ed25519Crypto.verify(canonicalBytes, receipt.serverSignature, issuerPublicKey)

        if (!isValid) {
            LocalDatabaseMetrics.recordReceiptVerificationFailure()
            throw SecurityException("Invalid settlement receipt signature: rejected by device")
        }

        val txId = receipt.transactionId ?: throw IllegalArgumentException("Receipt transactionId must not be null")
        val packetHash = receipt.packetHash ?: throw IllegalArgumentException("Receipt packetHash must not be null")

        return database.withTransaction {
            // Store receipt
            val entity = SettlementReceipt(
                transactionId = txId,
                packetHash = packetHash,
                counter = receipt.counter ?: 0L,
                status = receipt.status ?: "CONFIRMED",
                settledAt = receipt.settledAt,
                serverSignature = receipt.serverSignature ?: ""
            )
            database.settlementReceiptDao.insert(entity)

            // If an OutboundPayment matches, mark SETTLEMENT_CONFIRMED and update wallet settledAmountPaisa
            val payment = database.outboundPaymentDao.getPaymentByPacketHash(packetHash)
            if (payment != null) {
                database.outboundPaymentDao.updateState(
                    payment.paymentId,
                    OutboundPaymentState.SETTLEMENT_CONFIRMED,
                    System.currentTimeMillis()
                )

                val wallet = database.offlineWalletDao.getWallet(payment.walletId)
                if (wallet != null) {
                    val updatedSettled = wallet.settledAmountPaisa + payment.amountPaisa
                    database.offlineWalletDao.updateSettledAmount(
                        wallet.walletId,
                        updatedSettled,
                        System.currentTimeMillis()
                    )
                }
            }

            LocalDatabaseMetrics.recordWalletOperation("apply_receipt", "success")
            entity
        }
    }
}

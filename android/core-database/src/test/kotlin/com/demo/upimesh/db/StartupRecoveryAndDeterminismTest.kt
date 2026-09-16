package com.demo.upimesh.db

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.engine.OfflineWalletEngine
import com.demo.upimesh.db.entity.OutboundPayment
import com.demo.upimesh.db.entity.OutboundPaymentState
import com.demo.upimesh.db.entity.PacketFragment
import com.demo.upimesh.db.entity.WalletStatus
import com.demo.upimesh.db.recovery.StartupRecoveryManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.util.Base64
import java.util.UUID

class StartupRecoveryAndDeterminismTest {

    private lateinit var database: UpiMeshDatabase
    private val keyStoreManager = TestFixtures.createTestMasterKeyManager()
    private lateinit var engine: OfflineWalletEngine
    private lateinit var recoveryManager: StartupRecoveryManager
    private lateinit var rsaServerKeyPair: KeyPair
    private lateinit var serverRsaPublicKeyBase64: String

    @BeforeEach
    fun setUp() {
        database = UpiMeshDatabase.inMemory()
        engine = OfflineWalletEngine(database, keyStoreManager)
        recoveryManager = StartupRecoveryManager(database, engine)
        rsaServerKeyPair = TestFixtures.generateRsaKeyPair()
        serverRsaPublicKeyBase64 = Base64.getEncoder().encodeToString(rsaServerKeyPair.public.encoded)
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    /**
     * Test C: Restart of a CREATED payment preserves the same nonce/counter/receiver/amount.
     */
    @Test
    fun testRestartOfCreatedPaymentPreservesExactIntentFields() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-REC-01",
            deviceKeyPair = deviceKeyPair
        )

        // Insert payment manually interrupted in CREATED state (before crypto finish)
        val fixedPaymentId = UUID.randomUUID().toString()
        val fixedNonce = "immutable-nonce-12345"
        val fixedCounter = 1L
        val fixedAmount = 45_000L
        val fixedReceiver = "merchant@upi"
        val fixedCreatedAt = 123456789L

        val interruptedPayment = OutboundPayment(
            paymentId = fixedPaymentId,
            walletId = wallet.walletId,
            sequenceCounter = fixedCounter,
            amountPaisa = fixedAmount,
            cumulativeAmountPaisa = fixedAmount,
            receiverVpa = fixedReceiver,
            nonce = fixedNonce,
            packetHash = null,
            ciphertext = null,
            state = OutboundPaymentState.CREATED,
            createdAt = fixedCreatedAt,
            updatedAt = fixedCreatedAt
        )
        database.outboundPaymentDao.insert(interruptedPayment)

        // Perform startup recovery
        val report = recoveryManager.performStartupRecovery(serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64)
        assertEquals(1, report.paymentsRecoveredToReady)

        // Verify recovered payment
        val recovered = database.outboundPaymentDao.getPayment(fixedPaymentId)
        assertNotNull(recovered)
        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, recovered!!.state)

        // TEST C: Exact identity and intent preserved
        assertEquals(fixedPaymentId, recovered.paymentId)
        assertEquals(fixedNonce, recovered.nonce, "Nonce MUST NOT change during recovery")
        assertEquals(fixedCounter, recovered.sequenceCounter, "Sequence counter MUST NOT change during recovery")
        assertEquals(fixedAmount, recovered.amountPaisa, "Amount MUST NOT change during recovery")
        assertEquals(fixedReceiver, recovered.receiverVpa, "Receiver MUST NOT change during recovery")
        assertNotNull(recovered.ciphertext)
        assertNotNull(recovered.packetHash)
    }

    /**
     * Test D: Restart of an ENCRYPTED payment preserves the same ciphertext and packetHash.
     */
    @Test
    fun testRestartOfEncryptedPaymentPreservesExactCiphertextAndHash() {
        val wallet = TestFixtures.setupActiveWallet(database = database, walletId = "WLT-REC-02")
        val fixedPaymentId = UUID.randomUUID().toString()
        val existingCiphertext = "test-ciphertext-envelope-bytes"
        val expectedHash = PacketHasher.hashCiphertext(existingCiphertext)

        val payment = OutboundPayment(
            paymentId = fixedPaymentId,
            walletId = wallet.walletId,
            sequenceCounter = 1L,
            amountPaisa = 10_000L,
            cumulativeAmountPaisa = 10_000L,
            receiverVpa = "merchant@upi",
            nonce = "nonce-enc-01",
            packetHash = expectedHash,
            ciphertext = existingCiphertext,
            state = OutboundPaymentState.ENCRYPTED,
            createdAt = 1000L
        )
        database.outboundPaymentDao.insert(payment)

        val report = recoveryManager.performStartupRecovery(serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64)
        assertEquals(1, report.paymentsRecoveredToReady)

        val recovered = database.outboundPaymentDao.getPayment(fixedPaymentId)!!
        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, recovered.state)

        // TEST D: Preserves exact ciphertext and packetHash
        assertEquals(existingCiphertext, recovered.ciphertext)
        assertEquals(expectedHash, recovered.packetHash)
    }

    /**
     * Test E: No recovery path creates a second logical payment.
     */
    @Test
    fun testNoRecoveryPathCreatesSecondLogicalPayment() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            walletId = "WLT-REC-03",
            deviceKeyPair = deviceKeyPair
        )

        // Single payment in CREATED state
        val paymentId = UUID.randomUUID().toString()
        database.outboundPaymentDao.insert(
            OutboundPayment(
                paymentId = paymentId,
                walletId = wallet.walletId,
                sequenceCounter = 1L,
                amountPaisa = 20_000L,
                cumulativeAmountPaisa = 20_000L,
                receiverVpa = "bob@upi",
                nonce = "single-nonce",
                state = OutboundPaymentState.CREATED
            )
        )

        assertEquals(1, database.outboundPaymentDao.getPaymentsForWallet(wallet.walletId).size)

        // Run recovery twice
        recoveryManager.performStartupRecovery(serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64)
        recoveryManager.performStartupRecovery(serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64)

        // TEST E: Total payment rows remains exactly 1! No second payment created.
        val paymentsAfter = database.outboundPaymentDao.getPaymentsForWallet(wallet.walletId)
        assertEquals(1, paymentsAfter.size, "No duplicate logical payments must ever be created by recovery")
        assertEquals(paymentId, paymentsAfter[0].paymentId)
    }

    @Test
    fun testSimulatedCrashBeforeTransportKeepsPaymentReadyForTransport() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            walletId = "WLT-CRASH-01",
            deviceKeyPair = deviceKeyPair
        )

        val created = engine.createOfflineSpend(
            walletId = wallet.walletId,
            ownerVpa = "alice@upi",
            receiverVpa = "bob@upi",
            amountPaisa = 15_000L,
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
        )

        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, created.state)

        // App crashes here before transport runs!
        // Application restarts:
        val report = recoveryManager.performStartupRecovery(serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64)
        assertEquals(1, report.paymentsPendingTransport)

        val persisted = database.outboundPaymentDao.getPayment(created.paymentId)!!
        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, persisted.state)
        assertEquals(created.nonce, persisted.nonce)
        assertEquals(created.packetHash, persisted.packetHash)
    }

    @Test
    fun testStalePacketFragmentsPurgedOnStartup() {
        val now = System.currentTimeMillis()
        val oldTime = now - 90_000_000L // 25 hours ago (> 24h TTL)
        val freshTime = now - 3600_000L // 1 hour ago (< 24h TTL)

        database.packetFragmentDao.saveFragment(PacketFragment("hash-old", 0, 2, byteArrayOf(1), oldTime))
        database.packetFragmentDao.saveFragment(PacketFragment("hash-fresh", 0, 2, byteArrayOf(2), freshTime))

        val report = recoveryManager.performStartupRecovery(
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64,
            fragmentTtlMillis = 86_400_000L,
            now = now
        )

        assertEquals(1, report.staleFragmentsPurged)
        assertEquals(0, database.packetFragmentDao.countFragments("hash-old"))
        assertEquals(1, database.packetFragmentDao.countFragments("hash-fresh"))
    }

    @Test
    fun testExpiredWalletMarkedOnStartup() {
        val now = System.currentTimeMillis()
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            walletId = "WLT-STARTUP-EXP",
            validUntil = now - 5000L // expired 5s ago
        )

        assertEquals(WalletStatus.ACTIVE, wallet.status)

        val report = recoveryManager.performStartupRecovery(now = now)
        assertEquals(1, report.expiredWalletsCount)

        val reloaded = database.offlineWalletDao.getWallet("WLT-STARTUP-EXP")!!
        assertEquals(WalletStatus.EXPIRED, reloaded.status)
    }
}

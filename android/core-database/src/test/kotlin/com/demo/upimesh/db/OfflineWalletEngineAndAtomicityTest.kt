package com.demo.upimesh.db

import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.db.engine.*
import com.demo.upimesh.db.entity.OutboundPaymentState
import com.demo.upimesh.db.entity.WalletStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.util.Base64

class OfflineWalletEngineAndAtomicityTest {

    private lateinit var database: UpiMeshDatabase
    private val keyStoreManager = TestFixtures.createTestMasterKeyManager()
    private lateinit var engine: OfflineWalletEngine
    private lateinit var rsaServerKeyPair: KeyPair
    private lateinit var serverRsaPublicKeyBase64: String

    @BeforeEach
    fun setUp() {
        database = UpiMeshDatabase.inMemory()
        engine = OfflineWalletEngine(database, keyStoreManager)
        rsaServerKeyPair = TestFixtures.generateRsaKeyPair()
        serverRsaPublicKeyBase64 = Base64.getEncoder().encodeToString(rsaServerKeyPair.public.encoded)
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    /**
     * Test A: Local payment creation does NOT increase settledAmountPaisa.
     * Test B: Local payment creation decreases only remainingAmountPaisa and increases localSpentAmountPaisa.
     */
    @Test
    fun testLocalPaymentCreationDoesNotIncreaseSettledAmountAndDecreasesRemainingAllowance() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-ATOM-01",
            allocatedPaisa = 100_000L, // ₹1,000.00
            deviceKeyPair = deviceKeyPair
        )

        val spendAmount = 25_000L // ₹250.00
        val payment = engine.createOfflineSpend(
            walletId = wallet.walletId,
            ownerVpa = "alice@upi",
            receiverVpa = "bob@upi",
            amountPaisa = spendAmount,
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
        )

        // Verify committed OutboundPayment
        assertNotNull(payment)
        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, payment.state)
        assertEquals(1L, payment.sequenceCounter)
        assertEquals(spendAmount, payment.amountPaisa)
        assertEquals(spendAmount, payment.cumulativeAmountPaisa)
        assertNotNull(payment.ciphertext)
        assertNotNull(payment.packetHash)

        // Reload wallet from DB
        val reloadedWallet = database.offlineWalletDao.getWallet(wallet.walletId)!!

        // TEST A: Local payment creation does NOT increase settledAmount
        assertEquals(0L, reloadedWallet.settledAmountPaisa, "Local spend must NEVER increase settledAmountPaisa")

        // TEST B: Local payment creation decreases only remainingAmountPaisa and increases localSpentAmountPaisa
        assertEquals(75_000L, reloadedWallet.remainingAmountPaisa, "Remaining allowance must decrease by spend amount")
        assertEquals(25_000L, reloadedWallet.localSpentAmountPaisa, "Local spent amount must equal cumulative spend")
        assertEquals(1L, reloadedWallet.sequenceCounter, "Sequence counter must advance to 1")
    }

    @Test
    fun testSubsequentSpendIncrementsSequenceAndAccumulatesLocalSpend() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-ATOM-02",
            allocatedPaisa = 100_000L,
            deviceKeyPair = deviceKeyPair
        )

        // Spend 1: ₹300.00
        val p1 = engine.createOfflineSpend(
            walletId = wallet.walletId,
            ownerVpa = "alice@upi",
            receiverVpa = "merchant@upi",
            amountPaisa = 30_000L,
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
        )
        assertEquals(1L, p1.sequenceCounter)
        assertEquals(30_000L, p1.cumulativeAmountPaisa)

        // Spend 2: ₹200.00
        val p2 = engine.createOfflineSpend(
            walletId = wallet.walletId,
            ownerVpa = "alice@upi",
            receiverVpa = "grocer@upi",
            amountPaisa = 20_000L,
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
        )
        assertEquals(2L, p2.sequenceCounter)
        assertEquals(50_000L, p2.cumulativeAmountPaisa)

        val finalWallet = database.offlineWalletDao.getWallet(wallet.walletId)!!
        assertEquals(2L, finalWallet.sequenceCounter)
        assertEquals(50_000L, finalWallet.remainingAmountPaisa)
        assertEquals(50_000L, finalWallet.localSpentAmountPaisa)
        assertEquals(0L, finalWallet.settledAmountPaisa) // Still zero until backend settlement!
    }

    @Test
    fun testCounterRollbackRejection() {
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-SEQ-01",
            allocatedPaisa = 50_000L
        )

        // Valid advance to 1
        engine.advanceSequence(wallet.walletId, 1L)

        // Attempt rollback to 0
        val ex1 = assertThrows(InvalidSequenceException::class.java) {
            engine.advanceSequence(wallet.walletId, 0L)
        }
        assertTrue(ex1.message!!.contains("rollback rejected"))

        // Attempt non-monotonic skip (e.g. from 1 directly to 3)
        val ex2 = assertThrows(InvalidSequenceException::class.java) {
            engine.advanceSequence(wallet.walletId, 3L)
        }
        assertTrue(ex2.message!!.contains("Sequence mismatch"))
    }

    @Test
    fun testEscrowExhaustionRejection() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-EXHAUST",
            allocatedPaisa = 10_000L, // ₹100.00
            deviceKeyPair = deviceKeyPair
        )

        // Attempt spend of ₹150.00
        assertThrows(InsufficientEscrowException::class.java) {
            engine.createOfflineSpend(
                walletId = wallet.walletId,
                ownerVpa = "alice@upi",
                receiverVpa = "bob@upi",
                amountPaisa = 15_000L,
                serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
            )
        }

        // Wallet balances untouched
        val reloaded = database.offlineWalletDao.getWallet(wallet.walletId)!!
        assertEquals(10_000L, reloaded.remainingAmountPaisa)
        assertEquals(0L, reloaded.sequenceCounter)
    }

    @Test
    fun testExpiredWalletPreventsLocalSpend() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val now = System.currentTimeMillis()
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-EXPIRED",
            allocatedPaisa = 50_000L,
            validFrom = now - 100_000L,
            validUntil = now - 10_000L, // Expired 10s ago
            deviceKeyPair = deviceKeyPair
        )

        assertThrows(WalletValidationException::class.java) {
            engine.createOfflineSpend(
                walletId = wallet.walletId,
                ownerVpa = "alice@upi",
                receiverVpa = "bob@upi",
                amountPaisa = 1000L,
                serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64,
                now = now
            )
        }
    }

    @Test
    fun testDisputedWalletRejectsLocalSpend() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-DISPUTED",
            allocatedPaisa = 50_000L,
            deviceKeyPair = deviceKeyPair
        )

        // Lock wallet in dispute
        engine.lockDisputed(wallet.walletId, "dispute_detected_at_bridge")
        assertEquals(WalletStatus.LOCKED_DISPUTED, database.offlineWalletDao.getWallet(wallet.walletId)!!.status)

        assertThrows(InvalidWalletStateException::class.java) {
            engine.createOfflineSpend(
                walletId = wallet.walletId,
                ownerVpa = "alice@upi",
                receiverVpa = "bob@upi",
                amountPaisa = 1000L,
                serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
            )
        }
    }

    @Test
    fun testSelfSpendAndUnauthorizedSpenderRejected() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-AUTH-CHECK",
            allocatedPaisa = 50_000L,
            deviceKeyPair = deviceKeyPair
        )

        // Self spend rejected
        assertThrows(WalletValidationException::class.java) {
            engine.createOfflineSpend(
                walletId = wallet.walletId,
                ownerVpa = "alice@upi",
                receiverVpa = "alice@upi",
                amountPaisa = 1000L,
                serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
            )
        }

        // Impersonator / unauthorized spender rejected
        assertThrows(WalletValidationException::class.java) {
            engine.createOfflineSpend(
                walletId = wallet.walletId,
                ownerVpa = "eve@upi",
                receiverVpa = "bob@upi",
                amountPaisa = 1000L,
                serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
            )
        }
    }
}

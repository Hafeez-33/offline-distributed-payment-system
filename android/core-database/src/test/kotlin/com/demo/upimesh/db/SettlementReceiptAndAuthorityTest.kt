package com.demo.upimesh.db

import com.demo.upimesh.crypto.CanonicalSerializer
import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.db.engine.OfflineWalletEngine
import com.demo.upimesh.db.engine.WalletValidationException
import com.demo.upimesh.db.entity.OutboundPaymentState
import com.demo.upimesh.model.SettlementReceipt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.util.Base64

class SettlementReceiptAndAuthorityTest {

    private lateinit var database: UpiMeshDatabase
    private val keyStoreManager = TestFixtures.createTestMasterKeyManager()
    private lateinit var engine: OfflineWalletEngine
    private lateinit var serverIssuerKeyPair: KeyPair
    private lateinit var serverIssuerPublicKeyBase64: String
    private lateinit var rsaServerKeyPair: KeyPair
    private lateinit var serverRsaPublicKeyBase64: String

    @BeforeEach
    fun setUp() {
        database = UpiMeshDatabase.inMemory()
        engine = OfflineWalletEngine(database, keyStoreManager)
        serverIssuerKeyPair = Ed25519Crypto.generateKeyPair()
        serverIssuerPublicKeyBase64 = Ed25519Crypto.encodePublicKey(serverIssuerKeyPair.public)
        rsaServerKeyPair = TestFixtures.generateRsaKeyPair()
        serverRsaPublicKeyBase64 = Base64.getEncoder().encodeToString(rsaServerKeyPair.public.encoded)
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    /**
     * Test F: Backend settlement is the only transition that makes the transaction authoritative
     * and updates settledAmountPaisa.
     */
    @Test
    fun testBackendSettlementReceiptIsTheOnlyTransitionThatUpdatesSettledAmount() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            ownerVpa = "alice@upi",
            walletId = "WLT-RECEIPT-01",
            allocatedPaisa = 100_000L,
            deviceKeyPair = deviceKeyPair
        )

        // 1. Create offline payment locally
        val payment = engine.createOfflineSpend(
            walletId = wallet.walletId,
            ownerVpa = "alice@upi",
            receiverVpa = "bob@upi",
            amountPaisa = 30_000L, // ₹300.00
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
        )

        // Before receipt: settledAmount is 0
        var reloadedWallet = database.offlineWalletDao.getWallet(wallet.walletId)!!
        assertEquals(0L, reloadedWallet.settledAmountPaisa)
        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, payment.state)

        // 2. Server settles transaction and issues authoritative signed SettlementReceipt
        val receiptModel = SettlementReceipt(
            transactionId = 98765L,
            packetHash = payment.packetHash,
            counter = payment.sequenceCounter,
            status = "CONFIRMED",
            settledAt = System.currentTimeMillis()
        )
        val canonicalReceiptBytes = CanonicalSerializer.toCanonicalBytes(receiptModel)
        val serverSig = Ed25519Crypto.sign(canonicalReceiptBytes, serverIssuerKeyPair.private)
        val signedReceipt = SettlementReceipt(
            transactionId = receiptModel.transactionId,
            packetHash = receiptModel.packetHash,
            counter = receiptModel.counter,
            status = receiptModel.status,
            settledAt = receiptModel.settledAt,
            serverSignature = serverSig
        )

        // 3. Apply receipt via engine
        val storedReceipt = engine.applySettlementReceipt(signedReceipt, serverIssuerPublicKeyBase64)
        assertNotNull(storedReceipt)
        assertEquals(98765L, storedReceipt.transactionId)

        // 4. TEST F Verification:
        // Payment state transitioned to SETTLEMENT_CONFIRMED
        val confirmedPayment = database.outboundPaymentDao.getPayment(payment.paymentId)!!
        assertEquals(OutboundPaymentState.SETTLEMENT_CONFIRMED, confirmedPayment.state)

        // Wallet settledAmountPaisa is now updated to the authoritative amount!
        reloadedWallet = database.offlineWalletDao.getWallet(wallet.walletId)!!
        assertEquals(30_000L, reloadedWallet.settledAmountPaisa, "Only verified receipt updates settledAmountPaisa")
    }

    @Test
    fun testInvalidOrForgedReceiptSignatureIsRejectedWithoutPersisting() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            walletId = "WLT-RECEIPT-02",
            deviceKeyPair = deviceKeyPair
        )

        val payment = engine.createOfflineSpend(
            walletId = wallet.walletId,
            ownerVpa = "alice@upi",
            receiverVpa = "bob@upi",
            amountPaisa = 15_000L,
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
        )

        // Attacker creates forged receipt signed by an unknown key
        val attackerKeyPair = Ed25519Crypto.generateKeyPair()
        val forgedReceiptModel = SettlementReceipt(
            transactionId = 11111L,
            packetHash = payment.packetHash,
            counter = payment.sequenceCounter,
            status = "CONFIRMED",
            settledAt = System.currentTimeMillis()
        )
        val canonicalBytes = CanonicalSerializer.toCanonicalBytes(forgedReceiptModel)
        val forgedSig = Ed25519Crypto.sign(canonicalBytes, attackerKeyPair.private)
        val forgedReceipt = SettlementReceipt(
            transactionId = forgedReceiptModel.transactionId,
            packetHash = forgedReceiptModel.packetHash,
            counter = forgedReceiptModel.counter,
            status = forgedReceiptModel.status,
            settledAt = forgedReceiptModel.settledAt,
            serverSignature = forgedSig
        )

        // Attempting to apply forged receipt MUST fail with SecurityException
        assertThrows(SecurityException::class.java) {
            engine.applySettlementReceipt(forgedReceipt, serverIssuerPublicKeyBase64)
        }

        // Receipt is NOT persisted
        assertNull(database.settlementReceiptDao.getReceipt(11111L))

        // Payment remains READY_FOR_TRANSPORT (NOT confirmed)
        val unchangedPayment = database.outboundPaymentDao.getPayment(payment.paymentId)!!
        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, unchangedPayment.state)

        // Wallet settled amount remains 0
        val unchangedWallet = database.offlineWalletDao.getWallet(wallet.walletId)!!
        assertEquals(0L, unchangedWallet.settledAmountPaisa)
    }

    /**
     * Requirement 17: No False Authority Test.
     * Proves Android database does NOT establish authoritative balance, final settlement,
     * global idempotency, or dispute arbitration.
     */
    @Test
    fun testNoFalseAuthorityGuarantees() {
        val deviceKeyPair = TestFixtures.setupEnrolledDevice(database, keyStoreManager, "alice@upi")
        val wallet = TestFixtures.setupActiveWallet(
            database = database,
            walletId = "WLT-NO-AUTHORITY",
            allocatedPaisa = 50_000L,
            deviceKeyPair = deviceKeyPair
        )

        val payment = engine.createOfflineSpend(
            walletId = wallet.walletId,
            ownerVpa = "alice@upi",
            receiverVpa = "bob@upi",
            amountPaisa = 10_000L,
            serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
        )

        // 1. Android local payment is in READY_FOR_TRANSPORT (pending intent, NOT settled)
        assertNotEquals(OutboundPaymentState.SETTLEMENT_CONFIRMED, payment.state)

        // 2. Android local wallet does NOT claim settled amount
        val localWallet = database.offlineWalletDao.getWallet(wallet.walletId)!!
        assertEquals(0L, localWallet.settledAmountPaisa, "Android local DB cannot establish settled funds")

        // 3. Payees cannot treat received packets as their own offline spending allowance
        val receivedPacket = com.demo.upimesh.db.entity.ReceivedPacket(
            packetHash = "foreign-packet-hash",
            packetId = "pkt-foreign",
            ciphertext = "foreign-ciphertext",
            ttl = 3,
            hopCount = 1,
            receivedAt = System.currentTimeMillis()
        )
        database.receivedPacketDao.insert(receivedPacket)

        // Attempting to spend foreign received funds fails because they do not form an issued OfflineWallet
        assertNull(database.offlineWalletDao.getWallet("foreign-packet-hash"))
        assertThrows(WalletValidationException::class.java) {
            engine.createOfflineSpend(
                walletId = "foreign-packet-hash",
                ownerVpa = "bob@upi",
                receiverVpa = "charlie@upi",
                amountPaisa = 5000L,
                serverRsaPublicKeyBase64 = serverRsaPublicKeyBase64
            )
        }
    }
}

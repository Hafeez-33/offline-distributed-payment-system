package com.demo.upimesh.bridge.sync

import com.demo.upimesh.bridge.client.FakeBackendApiClient
import com.demo.upimesh.bridge.client.WanIngestResponse
import com.demo.upimesh.bridge.metrics.WanBridgeMetrics
import com.demo.upimesh.bridge.network.FakeNetworkConnectivityProvider
import com.demo.upimesh.bridge.role.BridgeCapabilityManager
import com.demo.upimesh.bridge.role.BridgeConfig
import com.demo.upimesh.bridge.role.DeviceRole
import com.demo.upimesh.crypto.CanonicalSerializer
import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.engine.OfflineWalletEngine
import com.demo.upimesh.db.entity.*
import com.demo.upimesh.db.keystore.KeyStoreManager
import com.demo.upimesh.model.SettlementReceipt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class WanBridgeSyncEngineIntegrationTest {

    private lateinit var db: UpiMeshDatabase
    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var walletEngine: OfflineWalletEngine
    private lateinit var connectivityProvider: FakeNetworkConnectivityProvider
    private lateinit var capabilityManager: BridgeCapabilityManager
    private lateinit var fakeApiClient: FakeBackendApiClient
    private lateinit var syncEngine: WanBridgeSyncEngine

    private val serverKeyPair = Ed25519Crypto.generateKeyPair()
    private val serverIssuerPublicKeyBase64 = Ed25519Crypto.encodePublicKey(serverKeyPair.public)
    private val serverIssuerPrivateKey = serverKeyPair.private

    private val testWalletId = "WLT-BRIDGE-01"
    private val testOwnerVpa = "alice@demo"

    @BeforeEach
    fun setUp() {
        WanBridgeMetrics.reset()
        db = UpiMeshDatabase.inMemory()
        keyStoreManager = KeyStoreManager()
        walletEngine = OfflineWalletEngine(db, keyStoreManager)
        connectivityProvider = FakeNetworkConnectivityProvider(connected = true)
        capabilityManager = BridgeCapabilityManager(BridgeConfig(isBridgeCapable = true, bridgeNodeId = "test-bridge"))
        fakeApiClient = FakeBackendApiClient()

        syncEngine = WanBridgeSyncEngine(
            database = db,
            apiClient = fakeApiClient,
            serverIssuerPublicKeyBase64 = serverIssuerPublicKeyBase64,
            capabilityManager = capabilityManager,
            connectivityProvider = connectivityProvider,
            walletEngine = walletEngine
        )

        // Initialize wallet with 1000.00 INR allocation (100000 paisa)
        val wallet = OfflineWallet(
            walletId = testWalletId,
            ownerVpa = testOwnerVpa,
            ownerPublicKey = "dummy_owner_key",
            allocatedAmountPaisa = 100000L,
            localSpentAmountPaisa = 0L,
            settledAmountPaisa = 0L,
            remainingAmountPaisa = 100000L,
            sequenceCounter = 0L,
            validFrom = System.currentTimeMillis() - 1000,
            validUntil = System.currentTimeMillis() + 86400000L,
            certificateJson = "{}"
        )
        db.offlineWalletDao.insert(wallet)
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    private fun createSignedReceipt(
        txId: Long,
        packetHash: String,
        counter: Long,
        settledAt: Long
    ): String {
        val receipt = SettlementReceipt(
            transactionId = txId,
            packetHash = packetHash,
            counter = counter,
            status = "SETTLED",
            settledAt = settledAt,
            serverSignature = ""
        )
        val canonicalBytes = CanonicalSerializer.toCanonicalBytes(receipt)
        return Ed25519Crypto.sign(canonicalBytes, serverIssuerPrivateKey)
    }

    @Test
    fun testOfflineNodeReturnsNoNetworkWithoutAlteringLocalBalances() = runBlocking {
        connectivityProvider.goOffline()

        val ciphertext = "c_offline_01"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)
        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-1",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.NO_NETWORK, result.status)
        assertEquals(0, result.processedCount)

        val wallet = db.offlineWalletDao.getWallet(testWalletId)!!
        assertEquals(0L, wallet.settledAmountPaisa)

        val packet = db.receivedPacketDao.getPacket(packetHash)!!
        assertFalse(packet.uploadedToBridge)
    }

    @Test
    fun testNonBridgeDeviceSkipsUpload() = runBlocking {
        capabilityManager.setRole(DeviceRole.RELAY)

        val ciphertext = "c_relay_01"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)
        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-relay",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.NOT_BRIDGE_CAPABLE, result.status)
        assertEquals(0, result.processedCount)
    }

    @Test
    fun testOfflineThenOnlineFullSettlementFlowWithReceiptVerification() = runBlocking {
        val ciphertext = "valid_ciphertext_bytes"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)
        val paymentId = "pmt-001"
        val spendPaisa = 25000L // 250.00 INR
        val counter = 1L
        val txId = 101L
        val settledAt = 1700000000000L

        // 1. Local payment created
        db.outboundPaymentDao.insert(
            OutboundPayment(
                paymentId = paymentId,
                walletId = testWalletId,
                sequenceCounter = counter,
                amountPaisa = spendPaisa,
                cumulativeAmountPaisa = spendPaisa,
                receiverVpa = "bob@demo",
                nonce = "nonce-1",
                packetHash = packetHash,
                ciphertext = ciphertext,
                state = OutboundPaymentState.READY_FOR_TRANSPORT
            )
        )

        // 2. Local packet buffered in mesh store
        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-001",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        // 3. Start offline
        connectivityProvider.goOffline()
        val offlineResult = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.NO_NETWORK, offlineResult.status)

        // Check local state remains untouched
        var wallet = db.offlineWalletDao.getWallet(testWalletId)!!
        assertEquals(0L, wallet.settledAmountPaisa)

        // 4. Device walks outside and goes online
        connectivityProvider.goOnline()

        // Configure backend mock with valid server-signed receipt
        val validSig = createSignedReceipt(txId, packetHash, counter, settledAt)
        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "SETTLED",
                packetHash = packetHash,
                transactionId = txId,
                receiptSignature = validSig,
                counter = counter,
                settledAt = settledAt,
                httpStatusCode = 200
            )
        )

        // 5. Run sync
        val onlineResult = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.SUCCESS, onlineResult.status)
        assertEquals(1, onlineResult.processedCount)
        assertEquals(1, onlineResult.successCount)
        assertEquals(0, onlineResult.retryCount)

        // 6. Verify local database state updated ONLY via verified receipt
        wallet = db.offlineWalletDao.getWallet(testWalletId)!!
        assertEquals(spendPaisa, wallet.settledAmountPaisa) // Settled amount updated!

        val updatedPayment = db.outboundPaymentDao.getPayment(paymentId)!!
        assertEquals(OutboundPaymentState.SETTLEMENT_CONFIRMED, updatedPayment.state)

        val updatedPacket = db.receivedPacketDao.getPacket(packetHash)!!
        assertTrue(updatedPacket.uploadedToBridge)

        val storedReceipt = db.settlementReceiptDao.getReceipt(txId)
        assertNotNull(storedReceipt)
        assertEquals(packetHash, storedReceipt!!.packetHash)
        assertEquals(counter, storedReceipt.counter)
        assertEquals(validSig, storedReceipt.serverSignature)

        // Metrics verification
        assertEquals(1L, WanBridgeMetrics.getUploadAttemptsTotal())
        assertEquals(1L, WanBridgeMetrics.getUploadSuccessTotal())
        assertEquals(1L, WanBridgeMetrics.getReceiptsVerifiedTotal())
        assertEquals(0L, WanBridgeMetrics.getReceiptsRejectedTotal())
    }

    @Test
    fun testForgedReceiptRejectionPreservesZeroSettledAmount() = runBlocking {
        val ciphertext = "ciphertext_forged"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)
        val paymentId = "pmt-forged"
        val spendPaisa = 15000L

        db.outboundPaymentDao.insert(
            OutboundPayment(
                paymentId = paymentId,
                walletId = testWalletId,
                sequenceCounter = 1L,
                amountPaisa = spendPaisa,
                cumulativeAmountPaisa = spendPaisa,
                receiverVpa = "bob@demo",
                nonce = "nonce-forged",
                packetHash = packetHash,
                ciphertext = ciphertext,
                state = OutboundPaymentState.READY_FOR_TRANSPORT
            )
        )

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-forged",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        // Forged signature from unknown key
        val attackerKey = Ed25519Crypto.generateKeyPair()
        val receipt = SettlementReceipt(
            transactionId = 999L,
            packetHash = packetHash,
            counter = 1L,
            status = "SETTLED",
            settledAt = 1700000000000L,
            serverSignature = ""
        )
        val forgedSig = Ed25519Crypto.sign(CanonicalSerializer.toCanonicalBytes(receipt), attackerKey.private)

        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "SETTLED",
                packetHash = packetHash,
                transactionId = 999L,
                receiptSignature = forgedSig, // FORGED!
                counter = 1L,
                settledAt = 1700000000000L,
                httpStatusCode = 200
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.FAILED, result.status)
        assertEquals(1, result.failureCount)

        // STRICT INVARIANT CHECK: settledAmountPaisa must NOT be modified
        val wallet = db.offlineWalletDao.getWallet(testWalletId)!!
        assertEquals(0L, wallet.settledAmountPaisa)

        val payment = db.outboundPaymentDao.getPayment(paymentId)!!
        assertEquals(OutboundPaymentState.READY_FOR_TRANSPORT, payment.state)

        assertEquals(1L, WanBridgeMetrics.getReceiptsRejectedTotal())
    }

    @Test
    fun testMissingReceiptFieldsRejectionPreservesZeroSettledAmount() = runBlocking {
        val ciphertext = "c_missing"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-missing",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        // Backend says SETTLED but omits counter and settledAt
        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "SETTLED",
                packetHash = packetHash,
                transactionId = 55L,
                receiptSignature = "some_sig",
                counter = null, // MISSING!
                settledAt = null, // MISSING!
                httpStatusCode = 200
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.FAILED, result.status)

        // STRICT INVARIANT CHECK: settledAmountPaisa must remain 0
        val wallet = db.offlineWalletDao.getWallet(testWalletId)!!
        assertEquals(0L, wallet.settledAmountPaisa)

        assertEquals(1L, WanBridgeMetrics.getReceiptsRejectedTotal())
    }

    @Test
    fun testDuplicateUploadIdempotentlyAcceptedWithoutDoubleCredit() = runBlocking {
        val ciphertext = "c_dup"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-dup",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "DUPLICATE_DROPPED",
                packetHash = packetHash,
                httpStatusCode = 200
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.SUCCESS, result.status)
        assertEquals(1, result.successCount)

        val packet = db.receivedPacketDao.getPacket(packetHash)!!
        assertTrue(packet.uploadedToBridge)

        val wallet = db.offlineWalletDao.getWallet(testWalletId)!!
        assertEquals(0L, wallet.settledAmountPaisa) // No extra settlement
    }

    @Test
    fun testPendingSequenceGapStagedOnBackendMarksUploadedAtBridge() = runBlocking {
        val ciphertext = "c_gap"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-gap",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "PENDING_SEQUENCE_GAP",
                packetHash = packetHash,
                reason = "missing_prior_sequence_counter",
                httpStatusCode = 200
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.SUCCESS, result.status)
        assertEquals(1, result.successCount)

        val packet = db.receivedPacketDao.getPacket(packetHash)!!
        assertTrue(packet.uploadedToBridge)
    }

    @Test
    fun testConflictingDoubleSpendMarksLocalPaymentConflicting() = runBlocking {
        val ciphertext = "c_conflict"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)
        val paymentId = "pmt-conflict"

        db.outboundPaymentDao.insert(
            OutboundPayment(
                paymentId = paymentId,
                walletId = testWalletId,
                sequenceCounter = 1L,
                amountPaisa = 10000L,
                cumulativeAmountPaisa = 10000L,
                receiverVpa = "charlie@demo",
                nonce = "nonce-conflict",
                packetHash = packetHash,
                ciphertext = ciphertext,
                state = OutboundPaymentState.READY_FOR_TRANSPORT
            )
        )

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-conflict",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "CONFLICTING",
                packetHash = packetHash,
                reason = "double_spend_counter_collision",
                httpStatusCode = 200
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.FAILED, result.status)
        assertEquals(1, result.failureCount)

        val payment = db.outboundPaymentDao.getPayment(paymentId)!!
        assertEquals(OutboundPaymentState.CONFLICTING, payment.state)

        val packet = db.receivedPacketDao.getPacket(packetHash)!!
        assertTrue(packet.uploadedToBridge) // Marked uploaded so not retried
    }

    @Test
    fun testPermanentRejectionMarksLocalPaymentRejected() = runBlocking {
        val ciphertext = "c_reject"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)
        val paymentId = "pmt-reject"

        db.outboundPaymentDao.insert(
            OutboundPayment(
                paymentId = paymentId,
                walletId = testWalletId,
                sequenceCounter = 1L,
                amountPaisa = 10000L,
                cumulativeAmountPaisa = 10000L,
                receiverVpa = "bob@demo",
                nonce = "nonce-reject",
                packetHash = packetHash,
                ciphertext = ciphertext,
                state = OutboundPaymentState.READY_FOR_TRANSPORT
            )
        )

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-reject",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "REJECTED",
                packetHash = packetHash,
                reason = "insufficient_balance",
                httpStatusCode = 200
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.FAILED, result.status)

        val payment = db.outboundPaymentDao.getPayment(paymentId)!!
        assertEquals(OutboundPaymentState.REJECTED, payment.state)

        val packet = db.receivedPacketDao.getPacket(packetHash)!!
        assertTrue(packet.uploadedToBridge)
    }

    @Test
    fun testHttp408TimeoutRetriesWithoutLosingPacketFromQueue() = runBlocking {
        val ciphertext = "c_timeout"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-timeout",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        fakeApiClient.shouldTimeout = true

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.RETRY_NEEDED, result.status)
        assertEquals(1, result.retryCount)

        // Packet must remain un-uploaded in Room
        val packet = db.receivedPacketDao.getPacket(packetHash)!!
        assertFalse(packet.uploadedToBridge)

        // In-flight lease must be released
        val pending = db.receivedPacketDao.getPendingBridgePackets()
        assertEquals(1, pending.size)

        assertEquals(1L, WanBridgeMetrics.getUploadRetryTotal())
    }

    @Test
    fun testHttp503ServiceUnavailableRetries() = runBlocking {
        val ciphertext = "c_503"
        val packetHash = PacketHasher.hashCiphertext(ciphertext)

        db.receivedPacketDao.insert(
            ReceivedPacket(
                packetHash = packetHash,
                packetId = "pkt-503",
                ciphertext = ciphertext,
                ttl = 3,
                hopCount = 1,
                receivedAt = 1000L,
                uploadedToBridge = false
            )
        )

        fakeApiClient.setResponseForPacket(
            packetHash,
            WanIngestResponse(
                outcome = "TRANSIENT_FAILURE",
                packetHash = packetHash,
                reason = "server_overloaded",
                httpStatusCode = 503
            )
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.RETRY_NEEDED, result.status)
        assertEquals(1, result.retryCount)

        val packet = db.receivedPacketDao.getPacket(packetHash)!!
        assertFalse(packet.uploadedToBridge)
    }

    @Test
    fun testPartialBatchSuccessAndFailurePreservesRemainingWork() = runBlocking {
        val c1 = "c_batch_01"
        val c2 = "c_batch_02"
        val h1 = PacketHasher.hashCiphertext(c1)
        val h2 = PacketHasher.hashCiphertext(c2)

        db.receivedPacketDao.insert(
            ReceivedPacket(packetHash = h1, packetId = "p1", ciphertext = c1, ttl = 3, hopCount = 1, receivedAt = 100L, uploadedToBridge = false)
        )
        db.receivedPacketDao.insert(
            ReceivedPacket(packetHash = h2, packetId = "p2", ciphertext = c2, ttl = 3, hopCount = 1, receivedAt = 200L, uploadedToBridge = false)
        )

        val sig1 = createSignedReceipt(201L, h1, 1L, 1700000000000L)
        fakeApiClient.setResponseForPacket(
            h1,
            WanIngestResponse(outcome = "SETTLED", packetHash = h1, transactionId = 201L, receiptSignature = sig1, counter = 1L, settledAt = 1700000000000L)
        )
        fakeApiClient.setResponseForPacket(
            h2,
            WanIngestResponse(outcome = "TRANSIENT_FAILURE", packetHash = h2, httpStatusCode = 503)
        )

        val result = syncEngine.runSyncBatch()
        assertEquals(WanSyncStatus.PARTIAL_SUCCESS, result.status)
        assertEquals(2, result.processedCount)
        assertEquals(1, result.successCount)
        assertEquals(1, result.retryCount)

        // h1 is uploaded, h2 is preserved in queue
        assertTrue(db.receivedPacketDao.getPacket(h1)!!.uploadedToBridge)
        assertFalse(db.receivedPacketDao.getPacket(h2)!!.uploadedToBridge)

        val pending = db.receivedPacketDao.getPendingBridgePackets()
        assertEquals(1, pending.size)
        assertEquals(h2, pending[0].packetHash)
    }

    @Test
    fun testCrashRestartRecoveryResumesUnuploadedPacketsFromRoom() = runBlocking {
        val dbFile = File.createTempFile("upi_mesh_bridge_test", ".db")
        dbFile.deleteOnExit()

        // Phase A: Create DB on disk and populate items
        var fileDb = UpiMeshDatabase.open(dbFile)
        val c1 = "c_persisted_01"
        val h1 = PacketHasher.hashCiphertext(c1)
        fileDb.receivedPacketDao.insert(
            ReceivedPacket(packetHash = h1, packetId = "p1", ciphertext = c1, ttl = 3, hopCount = 1, receivedAt = 100L, uploadedToBridge = false)
        )
        fileDb.close() // Simulate process death

        // Phase B: Reopen after crash
        fileDb = UpiMeshDatabase.open(dbFile)
        val restartedEngine = WanBridgeSyncEngine(
            database = fileDb,
            apiClient = fakeApiClient,
            serverIssuerPublicKeyBase64 = serverIssuerPublicKeyBase64,
            connectivityProvider = connectivityProvider
        )

        fakeApiClient.setResponseForPacket(
            h1,
            WanIngestResponse(outcome = "DUPLICATE_DROPPED", packetHash = h1)
        )

        val result = restartedEngine.runSyncBatch()
        assertEquals(WanSyncStatus.SUCCESS, result.status)
        assertEquals(1, result.successCount)

        assertTrue(fileDb.receivedPacketDao.getPacket(h1)!!.uploadedToBridge)
        fileDb.close()
    }
}

package com.demo.upimesh.bridge.work

import com.demo.upimesh.bridge.client.FakeBackendApiClient
import com.demo.upimesh.bridge.client.WanIngestResponse
import com.demo.upimesh.bridge.network.FakeNetworkConnectivityProvider
import com.demo.upimesh.bridge.role.BridgeCapabilityManager
import com.demo.upimesh.bridge.sync.WanBridgeSyncEngine
import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.entity.ReceivedPacket
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WanUploadWorkerTest {

    private lateinit var db: UpiMeshDatabase
    private lateinit var connectivityProvider: FakeNetworkConnectivityProvider
    private lateinit var fakeApiClient: FakeBackendApiClient
    private lateinit var syncEngine: WanBridgeSyncEngine
    private lateinit var worker: WanUploadWorker

    private val serverKeyPair = Ed25519Crypto.generateKeyPair()
    private val serverIssuerPublicKeyBase64 = Ed25519Crypto.encodePublicKey(serverKeyPair.public)

    @BeforeEach
    fun setUp() {
        db = UpiMeshDatabase.inMemory()
        connectivityProvider = FakeNetworkConnectivityProvider(connected = true)
        fakeApiClient = FakeBackendApiClient()

        syncEngine = WanBridgeSyncEngine(
            database = db,
            apiClient = fakeApiClient,
            serverIssuerPublicKeyBase64 = serverIssuerPublicKeyBase64,
            connectivityProvider = connectivityProvider
        )

        worker = WanUploadWorker(syncEngine)
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun testWorkerEmptyQueueReturnsSuccess() = runBlocking {
        val result = worker.doWork()
        assertEquals(WanWorkerResult.SUCCESS, result)
    }

    @Test
    fun testWorkerNoNetworkReturnsRetry() = runBlocking {
        connectivityProvider.goOffline()
        val c1 = "c_offline"
        val h1 = PacketHasher.hashCiphertext(c1)
        db.receivedPacketDao.insert(
            ReceivedPacket(packetHash = h1, packetId = "p1", ciphertext = c1, ttl = 3, hopCount = 1, receivedAt = 100L, uploadedToBridge = false)
        )

        val result = worker.doWork()
        assertEquals(WanWorkerResult.RETRY, result)
    }

    @Test
    fun testWorkerSuccessfulBatchReturnsSuccess() = runBlocking {
        val c1 = "c_success"
        val h1 = PacketHasher.hashCiphertext(c1)
        db.receivedPacketDao.insert(
            ReceivedPacket(packetHash = h1, packetId = "p1", ciphertext = c1, ttl = 3, hopCount = 1, receivedAt = 100L, uploadedToBridge = false)
        )

        fakeApiClient.setResponseForPacket(
            h1,
            WanIngestResponse(outcome = "DUPLICATE_DROPPED", packetHash = h1)
        )

        val result = worker.doWork()
        assertEquals(WanWorkerResult.SUCCESS, result)
        assertTrue(db.receivedPacketDao.getPacket(h1)!!.uploadedToBridge)
    }

    @Test
    fun testWorkerTransientErrorReturnsRetry() = runBlocking {
        val c1 = "c_transient"
        val h1 = PacketHasher.hashCiphertext(c1)
        db.receivedPacketDao.insert(
            ReceivedPacket(packetHash = h1, packetId = "p1", ciphertext = c1, ttl = 3, hopCount = 1, receivedAt = 100L, uploadedToBridge = false)
        )

        fakeApiClient.setResponseForPacket(
            h1,
            WanIngestResponse(outcome = "TRANSIENT_FAILURE", packetHash = h1, httpStatusCode = 503)
        )

        val result = worker.doWork()
        assertEquals(WanWorkerResult.RETRY, result)
        assertFalse(db.receivedPacketDao.getPacket(h1)!!.uploadedToBridge)
    }
}

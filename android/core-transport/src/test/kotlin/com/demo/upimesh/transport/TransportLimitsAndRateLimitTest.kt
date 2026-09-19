package com.demo.upimesh.transport

import com.demo.upimesh.transport.abstraction.FakeTimeProvider
import com.demo.upimesh.transport.android.AndroidBleTransport
import com.demo.upimesh.transport.limits.TransportLimits
import com.demo.upimesh.transport.ratelimit.TransportRateLimiter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TransportLimitsAndRateLimitTest {

    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var rateLimiter: TransportRateLimiter

    @BeforeEach
    fun setUp() {
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
        rateLimiter = TransportRateLimiter(
            timeProvider = timeProvider,
            maxHelloRatePerSec = 5,
            maxControlRatePerSec = 20,
            maxChunkRatePerSec = 100,
            maxConnAttemptsPerMin = 10
        )
    }

    @Test
    fun testHelloRateLimitExceeded() {
        val peerId = "peer_device_1"
        // 5 HELLO requests in 1 second are allowed
        for (i in 1..5) {
            rateLimiter.checkHello(peerId)
        }

        // 6th request in same second throws RATE_LIMIT_EXCEEDED
        val ex = assertThrows(BleProtocolException::class.java) {
            rateLimiter.checkHello(peerId)
        }
        assertEquals(BleProtocolError.RATE_LIMIT_EXCEEDED, ex.error)

        // Advance time by 1001ms -> rate limit clears
        timeProvider.advanceTime(1001L)
        rateLimiter.checkHello(peerId) // Should succeed
    }

    @Test
    fun testControlMessageRateLimitExceeded() {
        val peerId = "peer_device_2"
        for (i in 1..20) {
            rateLimiter.checkControl(peerId)
        }

        val ex = assertThrows(BleProtocolException::class.java) {
            rateLimiter.checkControl(peerId)
        }
        assertEquals(BleProtocolError.RATE_LIMIT_EXCEEDED, ex.error)

        timeProvider.advanceTime(1001L)
        rateLimiter.checkControl(peerId) // Cleared
    }

    @Test
    fun testChunkRateLimitExceeded() {
        val peerId = "peer_device_3"
        for (i in 1..100) {
            rateLimiter.checkChunk(peerId)
        }

        val ex = assertThrows(BleProtocolException::class.java) {
            rateLimiter.checkChunk(peerId)
        }
        assertEquals(BleProtocolError.RATE_LIMIT_EXCEEDED, ex.error)

        timeProvider.advanceTime(1001L)
        rateLimiter.checkChunk(peerId) // Cleared
    }

    @Test
    fun testConnectionAttemptsRateLimitExceeded() {
        val peerId = "peer_device_4"
        for (i in 1..10) {
            rateLimiter.checkConnectionAttempt(peerId)
        }

        val ex = assertThrows(BleProtocolException::class.java) {
            rateLimiter.checkConnectionAttempt(peerId)
        }
        assertEquals(BleProtocolError.RATE_LIMIT_EXCEEDED, ex.error)

        // Must wait 60 seconds
        timeProvider.advanceTime(60_001L)
        rateLimiter.checkConnectionAttempt(peerId) // Cleared
    }

    @Test
    fun testMaxConnectedPeersEnforcement() = runBlocking {
        val transport = AndroidBleTransport(
            role = BleRole.MERCHANT,
            rateLimiter = rateLimiter
        )

        val listener = object : com.demo.upimesh.transport.abstraction.BleTransportListener {
            override fun onPeerConnected(connection: com.demo.upimesh.transport.abstraction.BleConnection) {}
            override fun onPeerDisconnected(connection: com.demo.upimesh.transport.abstraction.BleConnection) {}
            override fun onFrameReceived(connection: com.demo.upimesh.transport.abstraction.BleConnection, frame: com.demo.upimesh.transport.frame.BleFrame) {}
            override fun onMtuChanged(connection: com.demo.upimesh.transport.abstraction.BleConnection, mtu: Int) {}
            override fun onError(connection: com.demo.upimesh.transport.abstraction.BleConnection?, error: Throwable) {}
        }

        // Connect 4 peers (TransportLimits.MAX_CONNECTED_PEERS = 4)
        for (i in 1..4) {
            transport.gattClient.connect("peer_0$i", listener)
        }

        // 5th connection attempt must fail
        val ex = assertThrows(BleProtocolException::class.java) {
            runBlocking {
                transport.gattClient.connect("peer_05", listener)
            }
        }
        assertEquals(BleProtocolError.RATE_LIMIT_EXCEEDED, ex.error)
    }

    @Test
    fun testTransportLimitsConstants() {
        assertEquals(4, TransportLimits.MAX_CONNECTED_PEERS)
        assertEquals(2L * 1024 * 1024, TransportLimits.MAX_REASSEMBLY_MEMORY_BYTES)
        assertEquals(8, TransportLimits.MAX_ACTIVE_REASSEMBLY_BUFFERS)
        assertEquals(1024, TransportLimits.MAX_CONTROL_MESSAGE_BYTES)
        assertEquals(64 * 1024, TransportLimits.MAX_PACKET_TRANSFER_BYTES)
        assertEquals(10L * 1024 * 1024, TransportLimits.MAX_LOCAL_MESH_STORAGE_BYTES)
    }
}

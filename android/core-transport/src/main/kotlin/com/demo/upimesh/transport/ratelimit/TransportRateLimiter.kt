package com.demo.upimesh.transport.ratelimit

import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import com.demo.upimesh.transport.abstraction.SystemTimeProvider
import com.demo.upimesh.transport.abstraction.TimeProvider
import com.demo.upimesh.transport.limits.TransportLimits
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window rate limiter per peer preventing BLE message flooding.
 */
class TransportRateLimiter(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val maxHelloRatePerSec: Int = TransportLimits.MAX_HELLO_RATE_PER_SEC,
    private val maxControlRatePerSec: Int = TransportLimits.MAX_CONTROL_RATE_PER_SEC,
    private val maxChunkRatePerSec: Int = TransportLimits.MAX_CHUNK_RATE_PER_SEC,
    private val maxConnAttemptsPerMin: Int = TransportLimits.MAX_CONN_ATTEMPTS_PER_MIN
) {

    private val peerHelloTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val peerControlTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val peerChunkTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val peerConnTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /**
     * Checks whether a HELLO message from the given peer exceeds the rate limit.
     */
    fun checkHello(peerId: String) {
        checkRate(peerHelloTimestamps, peerId, 1000L, maxHelloRatePerSec, "HELLO")
    }

    /**
     * Checks whether a control message from the given peer exceeds the rate limit.
     */
    fun checkControl(peerId: String) {
        checkRate(peerControlTimestamps, peerId, 1000L, maxControlRatePerSec, "Control message")
    }

    /**
     * Checks whether a packet chunk frame from the given peer exceeds the rate limit.
     */
    fun checkChunk(peerId: String) {
        checkRate(peerChunkTimestamps, peerId, 1000L, maxChunkRatePerSec, "Packet chunk")
    }

    /**
     * Checks whether a connection attempt from the given peer exceeds the rate limit.
     */
    fun checkConnectionAttempt(peerId: String) {
        checkRate(peerConnTimestamps, peerId, 60_000L, maxConnAttemptsPerMin, "Connection attempt")
    }

    /**
     * Resets rate tracking for a peer when disconnected.
     */
    fun resetPeer(peerId: String) {
        peerHelloTimestamps.remove(peerId)
        peerControlTimestamps.remove(peerId)
        peerChunkTimestamps.remove(peerId)
        peerConnTimestamps.remove(peerId)
    }

    fun clearAll() {
        peerHelloTimestamps.clear()
        peerControlTimestamps.clear()
        peerChunkTimestamps.clear()
        peerConnTimestamps.clear()
    }

    private fun checkRate(
        map: ConcurrentHashMap<String, ArrayDeque<Long>>,
        peerId: String,
        windowMs: Long,
        maxEvents: Int,
        eventType: String
    ) {
        val now = timeProvider.currentTimeMillis()
        val timestamps = map.computeIfAbsent(peerId) { ArrayDeque() }

        synchronized(timestamps) {
            // Evict expired timestamps outside window
            val cutoff = now - windowMs
            while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
                timestamps.removeFirst()
            }

            if (timestamps.size >= maxEvents) {
                throw BleProtocolException(
                    BleProtocolError.RATE_LIMIT_EXCEEDED,
                    "$eventType rate limit exceeded for peer $peerId (max $maxEvents per ${windowMs}ms)"
                )
            }

            timestamps.addLast(now)
        }
    }
}

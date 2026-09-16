package com.demo.upimesh.transport.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded observability metrics for the BLE GATT transport layer.
 *
 * All metrics use atomic counters with bounded cardinality labels (e.g. role, error code).
 * High-cardinality values (packetHash, device public key, transferId) are never used as labels.
 */
class BleTransportMetrics {

    val connectionsTotal = AtomicLong(0)
    val disconnectsTotal = AtomicLong(0)
    val framesReceivedTotal = AtomicLong(0)
    val framesRejectedTotal = AtomicLong(0)
    val crcFailuresTotal = AtomicLong(0)
    val reassemblyStartedTotal = AtomicLong(0)
    val reassemblyCompletedTotal = AtomicLong(0)
    val reassemblyTimeoutTotal = AtomicLong(0)
    val bytesReceivedTotal = AtomicLong(0)

    fun recordConnection() = connectionsTotal.incrementAndGet()
    fun recordDisconnect() = disconnectsTotal.incrementAndGet()
    fun recordFrameReceived(bytes: Int) {
        framesReceivedTotal.incrementAndGet()
        bytesReceivedTotal.addAndGet(bytes.toLong())
    }
    fun recordFrameRejected() = framesRejectedTotal.incrementAndGet()
    fun recordCrcFailure() = crcFailuresTotal.incrementAndGet()
    fun recordReassemblyStarted() = reassemblyStartedTotal.incrementAndGet()
    fun recordReassemblyCompleted() = reassemblyCompletedTotal.incrementAndGet()
    fun recordReassemblyTimeout() = reassemblyTimeoutTotal.incrementAndGet()

    fun reset() {
        connectionsTotal.set(0)
        disconnectsTotal.set(0)
        framesReceivedTotal.set(0)
        framesRejectedTotal.set(0)
        crcFailuresTotal.set(0)
        reassemblyStartedTotal.set(0)
        reassemblyCompletedTotal.set(0)
        reassemblyTimeoutTotal.set(0)
        bytesReceivedTotal.set(0)
    }
}

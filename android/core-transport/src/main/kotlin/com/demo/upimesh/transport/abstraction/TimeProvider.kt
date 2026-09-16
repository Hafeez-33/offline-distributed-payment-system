package com.demo.upimesh.transport.abstraction

/**
 * Clock abstraction for deterministic time testing across rate limiters,
 * timeouts, and retry loops.
 */
interface TimeProvider {
    fun currentTimeMillis(): Long
}

/**
 * Default wall-clock implementation using System.currentTimeMillis().
 */
class SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/**
 * Deterministic controllable fake clock for unit tests.
 */
class FakeTimeProvider(private var currentTime: Long = 1_700_000_000_000L) : TimeProvider {
    override fun currentTimeMillis(): Long = currentTime

    fun advanceTime(millis: Long) {
        require(millis >= 0) { "Cannot advance time backwards: $millis" }
        currentTime += millis
    }

    fun setTime(millis: Long) {
        currentTime = millis
    }
}

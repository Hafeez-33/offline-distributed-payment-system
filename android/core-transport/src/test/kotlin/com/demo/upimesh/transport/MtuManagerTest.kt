package com.demo.upimesh.transport

import com.demo.upimesh.transport.mtu.MtuManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MtuManagerTest {

    @Test
    fun testPreferredMtuCalculation() {
        // Preferred MTU 517: 517 - 3 (ATT) - 16 (Header) = 498
        val payloadSize = MtuManager.calculateEffectivePayloadSize(517)
        assertEquals(498, payloadSize)
    }

    @Test
    fun testMinimumMtuCalculation() {
        // Minimum MTU 64: 64 - 3 (ATT) - 16 (Header) = 45
        val payloadSize = MtuManager.calculateEffectivePayloadSize(64)
        assertEquals(45, payloadSize)
    }

    @Test
    fun testIntermediateMtuCalculation() {
        // Typical Android MTU 247: 247 - 19 = 228
        val payloadSize = MtuManager.calculateEffectivePayloadSize(247)
        assertEquals(228, payloadSize)

        // MTU 128: 128 - 19 = 109
        assertEquals(109, MtuManager.calculateEffectivePayloadSize(128))
    }

    @Test
    fun testMtuBelowMinimumThrowsMtuTooSmall() {
        val ex1 = assertThrows(BleProtocolException::class.java) {
            MtuManager.calculateEffectivePayloadSize(63)
        }
        assertEquals(BleProtocolError.MTU_TOO_SMALL, ex1.error)

        // Default un-negotiated BLE MTU (23 bytes)
        val ex2 = assertThrows(BleProtocolException::class.java) {
            MtuManager.calculateEffectivePayloadSize(23)
        }
        assertEquals(BleProtocolError.MTU_TOO_SMALL, ex2.error)
    }

    @Test
    fun testIsMtuAcceptable() {
        assertTrue(MtuManager.isMtuAcceptable(517))
        assertTrue(MtuManager.isMtuAcceptable(247))
        assertTrue(MtuManager.isMtuAcceptable(64))
        assertFalse(MtuManager.isMtuAcceptable(63))
        assertFalse(MtuManager.isMtuAcceptable(23))
    }
}

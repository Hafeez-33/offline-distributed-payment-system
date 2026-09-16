package com.demo.upimesh.transport

import com.demo.upimesh.transport.fragment.FragmentationEngine
import com.demo.upimesh.transport.frame.BleFrameConstants
import com.demo.upimesh.transport.mtu.MtuManager
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FragmentationEngineTest {

    @Test
    fun testSingleFragmentUnderPreferredMtu() {
        val payload = "Small payment payload".toByteArray(StandardCharsets.UTF_8)
        val frames = FragmentationEngine.fragment(
            payload = payload,
            messageType = BleMessageType.PACKET_OFFER,
            transferId = 100,
            packetHash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            negotiatedMtu = 517
        )

        assertEquals(1, frames.size)
        val frame = frames[0]
        assertEquals(0, frame.header.fragmentIndex)
        assertEquals(1, frame.header.totalFragments)
        assertEquals(100, frame.header.transferId)
        assertEquals(BleMessageType.PACKET_OFFER, frame.header.messageType)
        assertTrue(frame.isSingleFragment)
        assertTrue(frame.isLastFragment)
        assertArrayEquals(payload, frame.payload)

        val prefix = frame.header.packetHashPrefix
        assertEquals(0xAB.toByte(), prefix[0])
        assertEquals(0xCD.toByte(), prefix[1])
        assertEquals(0xEF.toByte(), prefix[2])
    }

    @Test
    fun testMultiFragmentSlicing() {
        // Under MTU 64, effective chunk size = 64 - 19 = 45 bytes.
        // Payload size 100 -> chunks: 45, 45, 10 -> total 3 fragments
        val payload = ByteArray(100) { it.toByte() }
        val frames = FragmentationEngine.fragment(
            payload = payload,
            messageType = BleMessageType.PACKET_OFFER,
            transferId = 200,
            packetHash = "1122334455667788112233445566778811223344556677881122334455667788",
            negotiatedMtu = 64
        )

        assertEquals(3, frames.size)

        // Frame 0
        assertEquals(0, frames[0].header.fragmentIndex)
        assertEquals(3, frames[0].header.totalFragments)
        assertEquals(45, frames[0].payload.size)
        assertFalse(frames[0].isLastFragment)

        // Frame 1
        assertEquals(1, frames[1].header.fragmentIndex)
        assertEquals(3, frames[1].header.totalFragments)
        assertEquals(45, frames[1].payload.size)
        assertFalse(frames[1].isLastFragment)

        // Frame 2 (Last)
        assertEquals(2, frames[2].header.fragmentIndex)
        assertEquals(3, frames[2].header.totalFragments)
        assertEquals(10, frames[2].payload.size)
        assertTrue(frames[2].isLastFragment)

        // Verify combined reconstructed payload matches original
        val combined = frames[0].payload + frames[1].payload + frames[2].payload
        assertArrayEquals(payload, combined)
    }

    @Test
    fun testMax64FragmentsAllowed() {
        // Under MTU 64, chunk size = 45.
        // 64 fragments * 45 bytes = 2880 bytes
        val payload = ByteArray(64 * 45) { (it % 128).toByte() }
        val frames = FragmentationEngine.fragment(
            payload = payload,
            negotiatedMtu = 64
        )
        assertEquals(64, frames.size)
        assertEquals(64, frames[0].header.totalFragments)
        assertEquals(63, frames[63].header.fragmentIndex)
        assertTrue(frames[63].isLastFragment)
    }

    @Test
    fun testExceeding64FragmentsThrowsExcessiveFragments() {
        // Under MTU 64, chunk size = 45.
        // 65 * 45 = 2925 bytes -> requires 65 fragments -> must be rejected!
        val payload = ByteArray(65 * 45)
        val ex = assertThrows(BleProtocolException::class.java) {
            FragmentationEngine.fragment(
                payload = payload,
                negotiatedMtu = 64
            )
        }
        assertEquals(BleProtocolError.EXCESSIVE_FRAGMENTS, ex.error)
    }

    @Test
    fun testEmptyPayloadProducesSingleEmptyFrame() {
        val frames = FragmentationEngine.fragment(
            payload = ByteArray(0),
            negotiatedMtu = 517
        )
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].payload.size)
        assertEquals(1, frames[0].header.totalFragments)
        assertEquals(0, frames[0].header.fragmentIndex)
        assertTrue(frames[0].isSingleFragment)
    }

    @Test
    fun testExtractPrefixFromShortOrNullHash() {
        val p1 = FragmentationEngine.extractPrefix(null)
        assertArrayEquals(ByteArray(3), p1)

        val p2 = FragmentationEngine.extractPrefix("12")
        assertArrayEquals(ByteArray(3), p2)

        val p3 = FragmentationEngine.extractPrefix("aabbccddeeff")
        assertEquals(0xAA.toByte(), p3[0])
        assertEquals(0xBB.toByte(), p3[1])
        assertEquals(0xCC.toByte(), p3[2])
    }
}

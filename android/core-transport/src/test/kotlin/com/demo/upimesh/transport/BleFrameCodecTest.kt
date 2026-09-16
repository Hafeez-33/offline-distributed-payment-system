package com.demo.upimesh.transport

import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.frame.BleFrameCodec
import com.demo.upimesh.transport.frame.BleFrameConstants
import com.demo.upimesh.transport.frame.BleFrameHeader
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BleFrameCodecTest {

    @Test
    fun testEncodeAndDecodeRoundtrip() {
        val payload = "Hello UPI Mesh BLE Transport!".toByteArray(StandardCharsets.UTF_8)
        val prefix = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte())

        val header = BleFrameHeader(
            messageType = BleMessageType.PACKET_OFFER,
            flags = BleFrameConstants.FLAG_LAST_FRAGMENT,
            fragmentIndex = 0,
            totalFragments = 1,
            transferId = 1042,
            packetHashPrefix = prefix,
            payloadLength = payload.size
        )
        val originalFrame = BleFrame(header, payload)

        val encoded = BleFrameCodec.encode(originalFrame)
        assertEquals(16 + payload.size, encoded.size, "Encoded frame size must be header (16) + payload length")

        val decoded = BleFrameCodec.decode(encoded)
        assertEquals(BleFrameConstants.MAGIC, decoded.header.magic)
        assertEquals(BleFrameConstants.CURRENT_VERSION, decoded.header.version)
        assertEquals(BleMessageType.PACKET_OFFER, decoded.header.messageType)
        assertEquals(BleFrameConstants.FLAG_LAST_FRAGMENT, decoded.header.flags)
        assertEquals(0, decoded.header.fragmentIndex)
        assertEquals(1, decoded.header.totalFragments)
        assertEquals(1042, decoded.header.transferId)
        assertArrayEquals(prefix, decoded.header.packetHashPrefix)
        assertEquals(payload.size, decoded.header.payloadLength)
        assertArrayEquals(payload, decoded.payload)
        assertTrue(decoded.isSingleFragment)
        assertTrue(decoded.isLastFragment)
    }

    @Test
    fun testEmptyPayloadFrame() {
        val header = BleFrameHeader(
            messageType = BleMessageType.SYNC_ACK,
            flags = 0,
            fragmentIndex = 0,
            totalFragments = 1,
            transferId = 42,
            packetHashPrefix = ByteArray(3),
            payloadLength = 0
        )
        val frame = BleFrame(header, ByteArray(0))

        val encoded = BleFrameCodec.encode(frame)
        assertEquals(16, encoded.size)

        val decoded = BleFrameCodec.decode(encoded)
        assertEquals(0, decoded.payload.size)
        assertEquals(BleMessageType.SYNC_ACK, decoded.header.messageType)
    }

    @Test
    fun testBadMagicThrowsDeterministicError() {
        val payload = byteArrayOf(0x01, 0x02)
        val header = BleFrameHeader(messageType = BleMessageType.HELLO, totalFragments = 1, payloadLength = 2)
        val encoded = BleFrameCodec.encode(BleFrame(header, payload))

        // Corrupt magic
        encoded[0] = 0x00
        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.decode(encoded)
        }
        assertEquals(BleProtocolError.BAD_MAGIC, ex.error)
    }

    @Test
    fun testUnsupportedVersionThrowsDeterministicError() {
        val payload = byteArrayOf(0x01)
        val header = BleFrameHeader(messageType = BleMessageType.HELLO, totalFragments = 1, payloadLength = 1)
        val encoded = BleFrameCodec.encode(BleFrame(header, payload))

        // Corrupt version to 0x02
        encoded[2] = 0x02
        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.decode(encoded)
        }
        assertEquals(BleProtocolError.UNSUPPORTED_VERSION, ex.error)
    }

    @Test
    fun testInvalidMessageTypeThrowsDeterministicError() {
        val payload = byteArrayOf(0x01)
        val header = BleFrameHeader(messageType = BleMessageType.HELLO, totalFragments = 1, payloadLength = 1)
        val encoded = BleFrameCodec.encode(BleFrame(header, payload))

        // Corrupt message type to 0xFF
        encoded[3] = 0xFF.toByte()
        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.decode(encoded)
        }
        assertEquals(BleProtocolError.INVALID_MSG_TYPE, ex.error)
    }

    @Test
    fun testCrcFailureThrowsDeterministicError() {
        val payload = "Important Payment Packet Data".toByteArray(StandardCharsets.UTF_8)
        val header = BleFrameHeader(messageType = BleMessageType.PACKET_OFFER, totalFragments = 1, payloadLength = payload.size)
        val encoded = BleFrameCodec.encode(BleFrame(header, payload))

        // Corrupt 1 byte in payload
        encoded[16] = (encoded[16].toInt() xor 0x01).toByte()

        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.decode(encoded)
        }
        assertEquals(BleProtocolError.CRC_FAILURE, ex.error)
    }

    @Test
    fun testHeaderCrcTamperThrowsCrcFailure() {
        val payload = "Sample Data".toByteArray(StandardCharsets.UTF_8)
        val header = BleFrameHeader(messageType = BleMessageType.PACKET_OFFER, totalFragments = 1, payloadLength = payload.size)
        val encoded = BleFrameCodec.encode(BleFrame(header, payload))

        // Corrupt transferId byte in header (byte 7)
        encoded[7] = (encoded[7].toInt() xor 0x01).toByte()

        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.decode(encoded)
        }
        assertEquals(BleProtocolError.CRC_FAILURE, ex.error)
    }

    @Test
    fun testInvalidFragmentIndexThrowsDeterministicError() {
        // fragmentIndex = 5, totalFragments = 3 -> invalid!
        val payload = byteArrayOf(0x10)
        val header = BleFrameHeader(
            messageType = BleMessageType.PACKET_OFFER,
            fragmentIndex = 5,
            totalFragments = 3,
            payloadLength = 1
        )
        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.encode(BleFrame(header, payload))
        }
        assertEquals(BleProtocolError.INVALID_FRAG_INDEX, ex.error)
    }

    @Test
    fun testExcessiveFragmentsThrowsDeterministicError() {
        val payload = byteArrayOf(0x10)
        // totalFragments = 65 (> 64)
        val header = BleFrameHeader(
            messageType = BleMessageType.PACKET_OFFER,
            fragmentIndex = 0,
            totalFragments = 65,
            payloadLength = 1
        )
        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.encode(BleFrame(header, payload))
        }
        assertEquals(BleProtocolError.EXCESSIVE_FRAGMENTS, ex.error)
    }

    @Test
    fun testZeroTotalFragmentsThrowsDeterministicError() {
        val payload = byteArrayOf(0x10)
        val header = BleFrameHeader(
            messageType = BleMessageType.PACKET_OFFER,
            fragmentIndex = 0,
            totalFragments = 0,
            payloadLength = 1
        )
        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.encode(BleFrame(header, payload))
        }
        assertEquals(BleProtocolError.EXCESSIVE_FRAGMENTS, ex.error)
    }

    @Test
    fun testOversizedPayloadExceedingLimitThrowsError() {
        val payload = ByteArray(200)
        val header = BleFrameHeader(messageType = BleMessageType.PACKET_OFFER, totalFragments = 1, payloadLength = 200)
        val encoded = BleFrameCodec.encode(BleFrame(header, payload))

        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.decode(encoded, maxAllowedPayload = 100)
        }
        assertEquals(BleProtocolError.OVERSIZED_PAYLOAD, ex.error)
    }

    @Test
    fun testTruncatedBufferBelow16BytesThrowsError() {
        val truncated = ByteArray(10)
        val ex = assertThrows(BleProtocolException::class.java) {
            BleFrameCodec.decode(truncated)
        }
        assertEquals(BleProtocolError.OVERSIZED_PAYLOAD, ex.error)
    }
}

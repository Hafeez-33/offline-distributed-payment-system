package com.demo.upimesh.transport

import com.demo.upimesh.transport.crc.Crc16Ccitt
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class Crc16CcittTest {

    @Test
    fun testStandardGoldenVector123456789() {
        // Standard CRC-16/CCITT-FALSE vector:
        // Input: "123456789" (ASCII bytes 0x31..0x39)
        // Poly: 0x1021, Init: 0xFFFF, RefIn: false, RefOut: false, XorOut: 0x0000
        // Expected: 0x29B1
        val input = "123456789".toByteArray(StandardCharsets.US_ASCII)
        val crc = Crc16Ccitt.compute(input)
        assertEquals(0x29B1, crc, "CRC-16 for '123456789' must be 0x29B1")
    }

    @Test
    fun testEmptyArrayProducesInitialValueFfff() {
        val input = ByteArray(0)
        val crc = Crc16Ccitt.compute(input)
        assertEquals(0xFFFF, crc, "CRC-16 for empty input must be 0xFFFF")
    }

    @Test
    fun testSingleByteVectors() {
        val b1 = byteArrayOf(0x00)
        val crc1 = Crc16Ccitt.compute(b1)
        // Init: 0xFFFF, byte: 0x00 -> ((0xFFFF ushr 8) xor 0x00) = 0xFF -> Table[0xFF]
        assertNotEquals(0, crc1)

        val b2 = byteArrayOf(0x41) // 'A'
        val crc2 = Crc16Ccitt.compute(b2)
        assertNotEquals(crc1, crc2)
    }

    @Test
    fun testBitFlipAltersCrc() {
        val original = "UPI_OFFLINE_MESH_PACKET_TEST_PAYLOAD".toByteArray(StandardCharsets.UTF_8)
        val originalCrc = Crc16Ccitt.compute(original)

        for (i in original.indices) {
            val tampered = original.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte() // flip 1 bit
            val tamperedCrc = Crc16Ccitt.compute(tampered)
            assertNotEquals(originalCrc, tamperedCrc, "Flipping bit at index $i must alter CRC")
        }
    }

    @Test
    fun testSliceCalculationMatchesWholeArray() {
        val fullData = "PREFIX_HEADER_MIDDLE_SUFFIX".toByteArray(StandardCharsets.UTF_8)
        val offset = 7
        val length = 13
        val sliceCrc = Crc16Ccitt.compute(fullData, offset, length)

        val extracted = fullData.copyOfRange(offset, offset + length)
        val directCrc = Crc16Ccitt.compute(extracted)

        assertEquals(directCrc, sliceCrc)
    }

    @Test
    fun testIncrementalUpdateMatchesDirectCompute() {
        val part1 = "HELLO_WORLD_".toByteArray(StandardCharsets.UTF_8)
        val part2 = "UPI_BLE_TRANSPORT".toByteArray(StandardCharsets.UTF_8)

        val combined = part1 + part2
        val directCrc = Crc16Ccitt.compute(combined)

        val crcPart1 = Crc16Ccitt.compute(part1)
        val updatedCrc = Crc16Ccitt.update(crcPart1, part2)

        assertEquals(directCrc, updatedCrc)
    }
}

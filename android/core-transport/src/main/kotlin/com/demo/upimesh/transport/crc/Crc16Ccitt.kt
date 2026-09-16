package com.demo.upimesh.transport.crc

/**
 * Deterministic CRC-16-CCITT implementation for UPI Mesh BLE transport frames.
 *
 * Algorithm Parameters:
 * - Polynomial: 0x1021 (x^16 + x^12 + x^5 + 1)
 * - Initial Value: 0xFFFF
 * - RefIn: false
 * - RefOut: false
 * - XorOut: 0x0000
 * - Byte Order: Big-Endian (UInt16)
 *
 * Coverage Range for BLE Frames:
 * - Fixed Header bytes 0..13 (excluding bytes 14..15 which store the CRC itself)
 * - Plus Payload bytes 16..(16 + payloadLength - 1).
 */
object Crc16Ccitt {

    private const val POLYNOMIAL = 0x1021
    private const val INITIAL_VALUE = 0xFFFF

    private val TABLE = IntArray(256) { i ->
        var temp = i shl 8
        for (j in 0 until 8) {
            temp = if ((temp and 0x8000) != 0) {
                ((temp shl 1) xor POLYNOMIAL) and 0xFFFF
            } else {
                (temp shl 1) and 0xFFFF
            }
        }
        temp
    }

    /**
     * Computes the 16-bit CRC-CCITT value for the given byte array slice.
     *
     * @param data Byte array containing the data to checksum
     * @param offset Start offset in the array
     * @param length Number of bytes to process
     * @return 16-bit unsigned integer CRC (0..65535)
     */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "Invalid offset $offset or length $length for array of size ${data.size}"
        }

        var crc = INITIAL_VALUE
        for (i in offset until (offset + length)) {
            val byteVal = data[i].toInt() and 0xFF
            val tableIndex = ((crc ushr 8) xor byteVal) and 0xFF
            crc = ((crc shl 8) xor TABLE[tableIndex]) and 0xFFFF
        }
        return crc
    }

    /**
     * Updates an ongoing CRC calculation with an additional byte array slice.
     */
    fun update(currentCrc: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "Invalid offset $offset or length $length for array of size ${data.size}"
        }

        var crc = currentCrc and 0xFFFF
        for (i in offset until (offset + length)) {
            val byteVal = data[i].toInt() and 0xFF
            val tableIndex = ((crc ushr 8) xor byteVal) and 0xFF
            crc = ((crc shl 8) xor TABLE[tableIndex]) and 0xFFFF
        }
        return crc
    }
}

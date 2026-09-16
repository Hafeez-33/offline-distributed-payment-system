package com.demo.upimesh.transport.frame

import com.demo.upimesh.transport.BleMessageType
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import com.demo.upimesh.transport.crc.Crc16Ccitt

/**
 * Deterministic binary encoder and decoder for UPI Mesh BLE transport frames.
 */
object BleFrameCodec {

    /**
     * Encodes a BleFrame into a byte array for transmission over BLE GATT.
     * Automatically computes CRC-16 over header bytes 0..13 and payload.
     *
     * @param frame Frame to encode
     * @return Raw byte array ready for GATT write
     */
    fun encode(frame: BleFrame): ByteArray {
        val payload = frame.payload
        val payloadLength = payload.size

        if (frame.header.totalFragments < BleFrameConstants.MIN_FRAGMENTS ||
            frame.header.totalFragments > BleFrameConstants.MAX_FRAGMENTS
        ) {
            throw BleProtocolException(
                BleProtocolError.EXCESSIVE_FRAGMENTS,
                "totalFragments ${frame.header.totalFragments} must be in 1..64"
            )
        }

        if (frame.header.fragmentIndex < 0 || frame.header.fragmentIndex >= frame.header.totalFragments) {
            throw BleProtocolException(
                BleProtocolError.INVALID_FRAG_INDEX,
                "fragmentIndex ${frame.header.fragmentIndex} must be in 0 until ${frame.header.totalFragments}"
            )
        }

        val totalSize = BleFrameConstants.HEADER_SIZE_BYTES + payloadLength
        val buffer = ByteArray(totalSize)

        // 0..1: Magic (0x55, 0x50)
        buffer[0] = BleFrameConstants.MAGIC_BYTE_0
        buffer[1] = BleFrameConstants.MAGIC_BYTE_1

        // 2: Version
        buffer[2] = frame.header.version

        // 3: Message Type
        buffer[3] = frame.header.messageType.code

        // 4: Flags
        buffer[4] = frame.header.flags

        // 5: Fragment Index
        buffer[5] = (frame.header.fragmentIndex and 0xFF).toByte()

        // 6: Total Fragments
        buffer[6] = (frame.header.totalFragments and 0xFF).toByte()

        // 7..8: Transfer ID (Big-Endian)
        val transferId = frame.header.transferId and 0xFFFF
        buffer[7] = ((transferId ushr 8) and 0xFF).toByte()
        buffer[8] = (transferId and 0xFF).toByte()

        // 9..11: Packet Hash Prefix (3 bytes)
        val hashPrefix = frame.header.packetHashPrefix
        buffer[9] = if (hashPrefix.isNotEmpty()) hashPrefix[0] else 0
        buffer[10] = if (hashPrefix.size > 1) hashPrefix[1] else 0
        buffer[11] = if (hashPrefix.size > 2) hashPrefix[2] else 0

        // 12..13: Payload Length (Big-Endian)
        buffer[12] = ((payloadLength ushr 8) and 0xFF).toByte()
        buffer[13] = (payloadLength and 0xFF).toByte()

        // Copy payload to bytes 16..end
        System.arraycopy(payload, 0, buffer, BleFrameConstants.HEADER_SIZE_BYTES, payloadLength)

        // Compute CRC-16 over bytes 0..13 + payload
        val headerCrc = Crc16Ccitt.compute(buffer, 0, 14)
        val finalCrc = if (payloadLength > 0) {
            Crc16Ccitt.update(headerCrc, buffer, BleFrameConstants.HEADER_SIZE_BYTES, payloadLength)
        } else {
            headerCrc
        }

        // 14..15: CRC-16 (Big-Endian)
        buffer[14] = ((finalCrc ushr 8) and 0xFF).toByte()
        buffer[15] = (finalCrc and 0xFF).toByte()

        return buffer
    }

    /**
     * Decodes and validates a raw byte array received from BLE GATT.
     *
     * @param rawBytes Raw incoming bytes
     * @param maxAllowedPayload Optional upper bound for payload length
     * @return Decoded and verified BleFrame
     * @throws BleProtocolException on any malformed or invalid frame
     */
    fun decode(rawBytes: ByteArray, maxAllowedPayload: Int = 65535): BleFrame {
        // 1. Validate minimum size
        if (rawBytes.size < BleFrameConstants.HEADER_SIZE_BYTES) {
            throw BleProtocolException(
                BleProtocolError.OVERSIZED_PAYLOAD,
                "Frame size ${rawBytes.size} is less than header size 16"
            )
        }

        // 2. Validate Magic
        if (rawBytes[0] != BleFrameConstants.MAGIC_BYTE_0 || rawBytes[1] != BleFrameConstants.MAGIC_BYTE_1) {
            throw BleProtocolException(
                BleProtocolError.BAD_MAGIC,
                "Invalid frame magic: 0x${String.format("%02X%02X", rawBytes[0], rawBytes[1])}"
            )
        }

        // 3. Validate Version
        val version = rawBytes[2]
        if (version != BleFrameConstants.CURRENT_VERSION) {
            throw BleProtocolException(
                BleProtocolError.UNSUPPORTED_VERSION,
                "Unsupported frame version: $version (expected ${BleFrameConstants.CURRENT_VERSION})"
            )
        }

        // 4. Validate Message Type
        val msgTypeCode = rawBytes[3]
        val messageType = try {
            BleMessageType.fromCode(msgTypeCode)
        } catch (e: Exception) {
            throw BleProtocolException(
                BleProtocolError.INVALID_MSG_TYPE,
                "Invalid message type code: 0x${String.format("%02X", msgTypeCode)}"
            )
        }

        val flags = rawBytes[4]
        val fragmentIndex = rawBytes[5].toInt() and 0xFF
        val totalFragments = rawBytes[6].toInt() and 0xFF

        // 5. Validate Total Fragments (1..64)
        if (totalFragments < BleFrameConstants.MIN_FRAGMENTS || totalFragments > BleFrameConstants.MAX_FRAGMENTS) {
            throw BleProtocolException(
                BleProtocolError.EXCESSIVE_FRAGMENTS,
                "Total fragments $totalFragments out of bounds (1..64)"
            )
        }

        // 6. Validate Fragment Index (0 until totalFragments)
        if (fragmentIndex >= totalFragments) {
            throw BleProtocolException(
                BleProtocolError.INVALID_FRAG_INDEX,
                "Fragment index $fragmentIndex is >= totalFragments $totalFragments"
            )
        }

        val transferId = ((rawBytes[7].toInt() and 0xFF) shl 8) or (rawBytes[8].toInt() and 0xFF)
        val packetHashPrefix = byteArrayOf(rawBytes[9], rawBytes[10], rawBytes[11])
        val payloadLength = ((rawBytes[12].toInt() and 0xFF) shl 8) or (rawBytes[13].toInt() and 0xFF)

        // 7. Validate Payload Length against buffer and limits
        val actualPayloadSize = rawBytes.size - BleFrameConstants.HEADER_SIZE_BYTES
        if (actualPayloadSize != payloadLength) {
            throw BleProtocolException(
                BleProtocolError.OVERSIZED_PAYLOAD,
                "Payload length in header ($payloadLength) does not match buffer size ($actualPayloadSize)"
            )
        }
        if (payloadLength > maxAllowedPayload) {
            throw BleProtocolException(
                BleProtocolError.OVERSIZED_PAYLOAD,
                "Payload length $payloadLength exceeds maximum allowed $maxAllowedPayload"
            )
        }

        // 8. Validate CRC-16
        val expectedCrc = ((rawBytes[14].toInt() and 0xFF) shl 8) or (rawBytes[15].toInt() and 0xFF)
        val headerCrc = Crc16Ccitt.compute(rawBytes, 0, 14)
        val calculatedCrc = if (payloadLength > 0) {
            Crc16Ccitt.update(headerCrc, rawBytes, BleFrameConstants.HEADER_SIZE_BYTES, payloadLength)
        } else {
            headerCrc
        }

        if (calculatedCrc != expectedCrc) {
            throw BleProtocolException(
                BleProtocolError.CRC_FAILURE,
                "CRC mismatch: expected 0x${String.format("%04X", expectedCrc)}, calculated 0x${String.format("%04X", calculatedCrc)}"
            )
        }

        // Extract Payload
        val payload = ByteArray(payloadLength)
        if (payloadLength > 0) {
            System.arraycopy(rawBytes, BleFrameConstants.HEADER_SIZE_BYTES, payload, 0, payloadLength)
        }

        val header = BleFrameHeader(
            magic = BleFrameConstants.MAGIC,
            version = version,
            messageType = messageType,
            flags = flags,
            fragmentIndex = fragmentIndex,
            totalFragments = totalFragments,
            transferId = transferId,
            packetHashPrefix = packetHashPrefix,
            payloadLength = payloadLength,
            crc16 = expectedCrc
        )

        return BleFrame(header, payload)
    }
}

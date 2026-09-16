package com.demo.upimesh.transport.frame

import com.demo.upimesh.transport.BleMessageType

/**
 * In-memory representation of a framed BLE transport message or chunk.
 *
 * Header Layout (Fixed 16 Bytes, Big-Endian):
 * - Bytes 0..1:   Magic (0x55, 0x50 -> 'U', 'P')
 * - Byte 2:       Version (0x01)
 * - Byte 3:       MessageType (0x01..0x0A)
 * - Byte 4:       Flags (bitmask)
 * - Byte 5:       FragmentIndex (0..63)
 * - Byte 6:       TotalFragments (1..64)
 * - Bytes 7..8:   TransferId (0..65535)
 * - Bytes 9..11:  PacketHashPrefix (3 bytes)
 * - Bytes 12..13: PayloadLength (0..65535)
 * - Bytes 14..15: CRC16-CCITT (Checksum of bytes 0..13 + Payload)
 */
data class BleFrameHeader(
    val magic: Short = BleFrameConstants.MAGIC,
    val version: Byte = BleFrameConstants.CURRENT_VERSION,
    val messageType: BleMessageType,
    val flags: Byte = 0,
    val fragmentIndex: Int = 0,
    val totalFragments: Int = 1,
    val transferId: Int = 0,
    val packetHashPrefix: ByteArray = ByteArray(3),
    val payloadLength: Int = 0,
    val crc16: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleFrameHeader
        if (magic != other.magic) return false
        if (version != other.version) return false
        if (messageType != other.messageType) return false
        if (flags != other.flags) return false
        if (fragmentIndex != other.fragmentIndex) return false
        if (totalFragments != other.totalFragments) return false
        if (transferId != other.transferId) return false
        if (!packetHashPrefix.contentEquals(other.packetHashPrefix)) return false
        if (payloadLength != other.payloadLength) return false
        if (crc16 != other.crc16) return false
        return true
    }

    override fun hashCode(): Int {
        var result = magic.toInt()
        result = 31 * result + version
        result = 31 * result + messageType.hashCode()
        result = 31 * result + flags
        result = 31 * result + fragmentIndex
        result = 31 * result + totalFragments
        result = 31 * result + transferId
        result = 31 * result + packetHashPrefix.contentHashCode()
        result = 31 * result + payloadLength
        result = 31 * result + crc16
        return result
    }
}

/**
 * Complete BLE Frame containing the 16-byte header and payload chunk.
 */
data class BleFrame(
    val header: BleFrameHeader,
    val payload: ByteArray
) {
    val isSingleFragment: Boolean
        get() = header.totalFragments == 1

    val isLastFragment: Boolean
        get() = header.fragmentIndex == header.totalFragments - 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleFrame
        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object BleFrameConstants {
    const val MAGIC: Short = 0x5550.toShort() // 'U', 'P'
    const val MAGIC_BYTE_0: Byte = 0x55
    const val MAGIC_BYTE_1: Byte = 0x50
    const val CURRENT_VERSION: Byte = 0x01
    const val HEADER_SIZE_BYTES: Int = 16
    const val MAX_FRAGMENTS: Int = 64
    const val MIN_FRAGMENTS: Int = 1
    const val MAX_TRANSFER_ID: Int = 65535

    /** Flag bit: Final fragment of multi-fragment transfer */
    const val FLAG_LAST_FRAGMENT: Byte = 0x01

    /** Flag bit: Payload is compressed (future extension) */
    const val FLAG_COMPRESSED: Byte = 0x02

    /** Flag bit: High-priority control packet */
    const val FLAG_PRIORITY: Byte = 0x04
}

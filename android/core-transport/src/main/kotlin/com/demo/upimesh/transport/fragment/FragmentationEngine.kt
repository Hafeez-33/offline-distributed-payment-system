package com.demo.upimesh.transport.fragment

import com.demo.upimesh.transport.BleMessageType
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.frame.BleFrameConstants
import com.demo.upimesh.transport.frame.BleFrameHeader
import com.demo.upimesh.transport.mtu.MtuManager

/**
 * Splits application payloads (such as ciphertext packets or sync messages)
 * into deterministic BLE frames based on the dynamically negotiated MTU.
 */
object FragmentationEngine {

    /**
     * Fragments a payload into a list of BleFrames.
     *
     * @param payload Raw payload bytes to transmit
     * @param messageType The BleMessageType for the frames
     * @param transferId Identifier for this multi-frame transfer (0..65535)
     * @param packetHash Optional 64-char hex packetHash string (first 3 bytes used as prefix)
     * @param negotiatedMtu The negotiated ATT MTU for this connection
     * @return Ordered list of BleFrame ready for encoding and transmission
     */
    fun fragment(
        payload: ByteArray,
        messageType: BleMessageType = BleMessageType.PACKET_OFFER,
        transferId: Int = 0,
        packetHash: String? = null,
        negotiatedMtu: Int = MtuManager.PREFERRED_MTU
    ): List<BleFrame> {
        val effectiveChunkSize = MtuManager.calculateEffectivePayloadSize(negotiatedMtu)

        val prefixBytes = extractPrefix(packetHash)

        if (payload.isEmpty()) {
            val header = BleFrameHeader(
                version = BleFrameConstants.CURRENT_VERSION,
                messageType = messageType,
                flags = BleFrameConstants.FLAG_LAST_FRAGMENT,
                fragmentIndex = 0,
                totalFragments = 1,
                transferId = transferId,
                packetHashPrefix = prefixBytes,
                payloadLength = 0
            )
            return listOf(BleFrame(header, ByteArray(0)))
        }

        val totalFragments = (payload.size + effectiveChunkSize - 1) / effectiveChunkSize

        if (totalFragments > BleFrameConstants.MAX_FRAGMENTS) {
            throw BleProtocolException(
                BleProtocolError.EXCESSIVE_FRAGMENTS,
                "Payload size ${payload.size} requires $totalFragments fragments (max ${BleFrameConstants.MAX_FRAGMENTS})"
            )
        }

        val frames = ArrayList<BleFrame>(totalFragments)

        for (i in 0 until totalFragments) {
            val offset = i * effectiveChunkSize
            val length = minOf(effectiveChunkSize, payload.size - offset)
            val chunk = ByteArray(length)
            System.arraycopy(payload, offset, chunk, 0, length)

            val isLast = (i == totalFragments - 1)
            val flags = if (isLast) BleFrameConstants.FLAG_LAST_FRAGMENT else 0

            val header = BleFrameHeader(
                version = BleFrameConstants.CURRENT_VERSION,
                messageType = messageType,
                flags = flags.toByte(),
                fragmentIndex = i,
                totalFragments = totalFragments,
                transferId = transferId,
                packetHashPrefix = prefixBytes,
                payloadLength = length
            )

            frames.add(BleFrame(header, chunk))
        }

        return frames
    }

    /**
     * Extracts first 3 bytes from a 64-char hexadecimal packetHash string.
     */
    fun extractPrefix(packetHash: String?): ByteArray {
        if (packetHash.isNullOrEmpty() || packetHash.length < 6) {
            return ByteArray(3)
        }
        val prefix = ByteArray(3)
        try {
            for (i in 0 until 3) {
                val hexByte = packetHash.substring(i * 2, i * 2 + 2)
                prefix[i] = hexByte.toInt(16).toByte()
            }
        } catch (_: Exception) {
            // Fallback to zeros if invalid hex
        }
        return prefix
    }
}

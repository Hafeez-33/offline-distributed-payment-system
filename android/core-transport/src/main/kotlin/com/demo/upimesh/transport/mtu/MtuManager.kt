package com.demo.upimesh.transport.mtu

import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import com.demo.upimesh.transport.frame.BleFrameConstants

/**
 * Handles dynamic ATT MTU validation, negotiation parameters, and payload size calculations.
 */
object MtuManager {

    /** Minimum allowable negotiated ATT MTU for the UPI Mesh protocol */
    const val MIN_NEGOTIATED_MTU: Int = 64

    /** Preferred ATT MTU requested by Central */
    const val PREFERRED_MTU: Int = 517

    /** Standard ATT opcode + attribute handle overhead (3 bytes) */
    const val ATT_HEADER_OVERHEAD_BYTES: Int = 3

    /** Total framing overhead per BLE characteristic packet write (3 ATT + 16 Frame Header = 19 bytes) */
    const val TOTAL_FRAME_OVERHEAD_BYTES: Int = ATT_HEADER_OVERHEAD_BYTES + BleFrameConstants.HEADER_SIZE_BYTES

    /**
     * Calculates the maximum application payload bytes that can fit in a single BLE frame
     * under the given negotiated ATT MTU.
     *
     * Formula:
     * effective payload size = negotiatedMtu - 3 (ATT overhead) - 16 (UPI frame header)
     *
     * @param negotiatedMtu The negotiated ATT MTU (e.g. 64..517)
     * @return Maximum payload chunk size in bytes
     * @throws BleProtocolException with MTU_TOO_SMALL if negotiatedMtu < 64
     */
    fun calculateEffectivePayloadSize(negotiatedMtu: Int): Int {
        if (negotiatedMtu < MIN_NEGOTIATED_MTU) {
            throw BleProtocolException(
                BleProtocolError.MTU_TOO_SMALL,
                "Negotiated ATT MTU $negotiatedMtu is below minimum $MIN_NEGOTIATED_MTU"
            )
        }
        val effectiveSize = negotiatedMtu - TOTAL_FRAME_OVERHEAD_BYTES
        if (effectiveSize <= 0) {
            throw BleProtocolException(
                BleProtocolError.MTU_TOO_SMALL,
                "Negotiated ATT MTU $negotiatedMtu cannot produce a valid payload (effective size $effectiveSize <= 0)"
            )
        }
        return effectiveSize
    }

    /**
     * Validates whether an MTU is acceptable for session establishment.
     */
    fun isMtuAcceptable(negotiatedMtu: Int): Boolean {
        return negotiatedMtu >= MIN_NEGOTIATED_MTU
    }
}

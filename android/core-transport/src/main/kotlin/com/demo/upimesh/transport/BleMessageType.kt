package com.demo.upimesh.transport

/**
 * Stable message types for the UPI Mesh BLE transport protocol vocabulary.
 *
 * NOTE: Phase 9.3 establishes the framing and transport vocabulary;
 * higher-level anti-entropy / reconciliation algorithms are deferred to Phase 9.4.
 */
enum class BleMessageType(val code: Byte) {
    /** Initial capability and MTU exchange upon GATT connection */
    HELLO(0x01),

    /** State digest and summary advertisement */
    STATE_SUMMARY(0x02),

    /** 16-bucket prefix checksums for divergence isolation */
    BUCKET_CHECKSUMS(0x03),

    /** Batch request for missing packet hashes */
    SYNC_REQUEST(0x04),

    /** Peer notification of available packet hashes */
    PACKET_OFFER(0x05),

    /** Request to stream a specific packet by hash */
    PACKET_REQUEST(0x06),

    /** Selective fragment acknowledgement / retransmission query */
    CHUNK_ACK(0x07),

    /** Synchronization completion acknowledgement */
    SYNC_ACK(0x08),

    /** Delivery receipt notification */
    RECEIPT_NOTIFY(0x09),

    /** Deterministic protocol error response */
    ERROR(0x0A);

    companion object {
        fun fromCode(code: Byte): BleMessageType =
            entries.firstOrNull { it.code == code }
                ?: throw BleProtocolException(
                    BleProtocolError.INVALID_MSG_TYPE,
                    "Unknown BleMessageType code: 0x${String.format("%02X", code)}"
                )
    }
}

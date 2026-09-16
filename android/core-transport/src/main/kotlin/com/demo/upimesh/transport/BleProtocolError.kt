package com.demo.upimesh.transport

/**
 * Deterministic protocol errors returned by the BLE transport layer.
 */
enum class BleProtocolError(val code: Byte, val description: String) {
    /** Magic header bytes do not match expected 0x55 0x50 ('UP') */
    BAD_MAGIC(0x01, "Invalid frame magic bytes"),

    /** Protocol major version is not supported */
    UNSUPPORTED_VERSION(0x02, "Unsupported protocol version"),

    /** Message type code is not recognized */
    INVALID_MSG_TYPE(0x03, "Invalid message type"),

    /** Chunk or payload length exceeds negotiated MTU or protocol limits */
    OVERSIZED_PAYLOAD(0x04, "Payload exceeds size limits"),

    /** CRC-16 checksum mismatch */
    CRC_FAILURE(0x05, "CRC-16 validation failure"),

    /** Fragment index is out of bounds (negative, or >= totalFragments) */
    INVALID_FRAG_INDEX(0x06, "Invalid fragment index"),

    /** Total fragment count is <= 0 or > 64 */
    EXCESSIVE_FRAGMENTS(0x07, "Total fragments out of bounds (1..64)"),

    /** Incomplete reassembly buffer timed out */
    REASSEMBLY_TIMEOUT(0x08, "Reassembly timed out before completion"),

    /** Conflicting payload data received for an already received fragment index */
    DUPLICATE_FRAME(0x09, "Conflicting duplicate frame received"),

    /** Reassembled payload SHA-256 hash does not match expected packetHash */
    UNKNOWN_PACKET_HASH(0x0A, "Reassembled packet hash integrity mismatch"),

    /** Peer exceeded transport rate limit thresholds */
    RATE_LIMIT_EXCEEDED(0x0B, "Transport rate limit exceeded"),

    /** Negotiated ATT MTU is below minimum (64 bytes) or cannot produce valid payload */
    MTU_TOO_SMALL(0x0C, "Negotiated ATT MTU is too small");

    companion object {
        fun fromCode(code: Byte): BleProtocolError =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown BleProtocolError code: $code")
    }
}

/**
 * Typed exception thrown when a deterministic BLE transport protocol violation occurs.
 */
class BleProtocolException(
    val error: BleProtocolError,
    message: String = error.description,
    cause: Throwable? = null
) : RuntimeException("[$error] $message", cause)

package com.demo.upimesh.transport.limits

/**
 * Explicit bounded resource limits for the BLE transport layer.
 */
object TransportLimits {
    /** Maximum number of concurrent connected BLE GATT peers */
    const val MAX_CONNECTED_PEERS: Int = 4

    /** Maximum total in-memory buffer allocated across all active reassemblies (2 MB) */
    const val MAX_REASSEMBLY_MEMORY_BYTES: Long = 2L * 1024 * 1024

    /** Maximum number of concurrent active reassembly transfer buffers */
    const val MAX_ACTIVE_REASSEMBLY_BUFFERS: Int = 8

    /** Maximum size for a single control message (HELLO, SYNC_REQUEST, etc.) (1 KB) */
    const val MAX_CONTROL_MESSAGE_BYTES: Int = 1024

    /** Maximum size for a complete single payment packet ciphertext transfer (64 KB) */
    const val MAX_PACKET_TRANSFER_BYTES: Int = 64 * 1024

    /** Target maximum storage budget for mesh packets in local Room database (10 MB) */
    const val MAX_LOCAL_MESH_STORAGE_BYTES: Long = 10L * 1024 * 1024

    /** Default transfer reassembly timeout in milliseconds (60 seconds) */
    const val DEFAULT_REASSEMBLY_TIMEOUT_MS: Long = 60_000L

    /** Maximum allowed HELLO messages per second per peer */
    const val MAX_HELLO_RATE_PER_SEC: Int = 5

    /** Maximum allowed control messages per second per peer */
    const val MAX_CONTROL_RATE_PER_SEC: Int = 20

    /** Maximum allowed packet chunk frames per second per peer */
    const val MAX_CHUNK_RATE_PER_SEC: Int = 100

    /** Maximum connection attempts per minute per peer */
    const val MAX_CONN_ATTEMPTS_PER_MIN: Int = 10
}

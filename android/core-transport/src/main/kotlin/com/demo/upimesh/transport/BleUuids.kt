package com.demo.upimesh.transport

import java.util.UUID

/**
 * Approved 128-bit UUID constants for the UPI Offline Mesh BLE GATT transport layer.
 *
 * All UUIDs share the common custom 128-bit namespace:
 * e8a3xxxx-7c2b-4e6a-a83d-3b9e8a9f24c0
 */
object BleUuids {
    /** Primary UPI Mesh BLE GATT Service UUID */
    val SERVICE_UUID: UUID = UUID.fromString("e8a30001-7c2b-4e6a-a83d-3b9e8a9f24c0")

    /** Control point characteristic for HELLO, capability exchange, sync commands, and ACKs */
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("e8a30002-7c2b-4e6a-a83d-3b9e8a9f24c0")

    /** Fragmented packet transfer characteristic (high-throughput framed chunks) */
    val PACKET_CHAR_UUID: UUID = UUID.fromString("e8a30003-7c2b-4e6a-a83d-3b9e8a9f24c0")

    /** State summary and device status characteristic (state digest / 16 bucket checksums) */
    val STATE_CHAR_UUID: UUID = UUID.fromString("e8a30004-7c2b-4e6a-a83d-3b9e8a9f24c0")

    const val SERVICE_UUID_STRING = "e8a30001-7c2b-4e6a-a83d-3b9e8a9f24c0"
    const val CONTROL_CHAR_UUID_STRING = "e8a30002-7c2b-4e6a-a83d-3b9e8a9f24c0"
    const val PACKET_CHAR_UUID_STRING = "e8a30003-7c2b-4e6a-a83d-3b9e8a9f24c0"
    const val STATE_CHAR_UUID_STRING = "e8a30004-7c2b-4e6a-a83d-3b9e8a9f24c0"
}

package com.demo.upimesh.transport.hello

import com.demo.upimesh.transport.BleRole
import com.demo.upimesh.transport.frame.BleFrameConstants
import com.demo.upimesh.transport.limits.TransportLimits
import com.demo.upimesh.transport.mtu.MtuManager
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Transport-level capability exchange message sent during GATT connection initialization.
 *
 * SECURITY INVARIANT:
 * This payload must NEVER contain private keys, seed phrases, or financial authority assertions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HelloMessage(
    @JsonProperty("protocolVersion")
    val protocolVersion: Int = BleFrameConstants.CURRENT_VERSION.toInt(),

    @JsonProperty("deviceIdentifier")
    val deviceIdentifier: String,

    @JsonProperty("role")
    val role: BleRole,

    @JsonProperty("negotiatedMtu")
    val negotiatedMtu: Int = MtuManager.PREFERRED_MTU,

    @JsonProperty("maxFragments")
    val maxFragments: Int = BleFrameConstants.MAX_FRAGMENTS,

    @JsonProperty("maxTransferBytes")
    val maxTransferBytes: Int = TransportLimits.MAX_PACKET_TRANSFER_BYTES,

    @JsonProperty("capabilities")
    val capabilities: List<String> = listOf("GATT_V1", "ROOM_PERSISTENCE", "HYBRID_ENCRYPTION")
) {

    fun serialize(): ByteArray {
        return MAPPER.writeValueAsBytes(this)
    }

    companion object {
        private val MAPPER: ObjectMapper = jacksonObjectMapper()

        fun deserialize(bytes: ByteArray): HelloMessage {
            return MAPPER.readValue(bytes, HelloMessage::class.java)
        }
    }
}

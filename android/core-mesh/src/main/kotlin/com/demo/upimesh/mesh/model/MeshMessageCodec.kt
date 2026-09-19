package com.demo.upimesh.mesh.model

import com.demo.upimesh.transport.BleMessageType
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Deterministic JSON serializer/deserializer for Mesh protocol messages.
 * Enforces maximum payload size limits to fit safely within Phase 9.3 BLE framing limits.
 */
object MeshMessageCodec {

    const val MAX_CONTROL_PAYLOAD_SIZE = 1024

    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * Serializes any MeshProtocolPayload to UTF-8 JSON bytes.
     * Enforces size check for control messages (all except large bulk transfers).
     */
    fun serialize(payload: MeshProtocolPayload): ByteArray {
        val bytes = mapper.writeValueAsBytes(payload)
        if (payload.messageType != BleMessageType.PACKET_OFFER && bytes.size > MAX_CONTROL_PAYLOAD_SIZE) {
            throw BleProtocolException(
                BleProtocolError.OVERSIZED_PAYLOAD,
                "Serialized ${payload.messageType} payload size (${bytes.size} bytes) exceeds limit ($MAX_CONTROL_PAYLOAD_SIZE bytes)"
            )
        }
        return bytes
    }

    /**
     * Deserializes bytes into the typed MeshProtocolPayload based on the provided BleMessageType.
     */
    fun deserialize(messageType: BleMessageType, bytes: ByteArray): MeshProtocolPayload {
        if (bytes.isEmpty()) {
            throw BleProtocolException(
                BleProtocolError.INVALID_MSG_TYPE,
                "Cannot deserialize empty payload for $messageType"
            )
        }
        return try {
            when (messageType) {
                BleMessageType.HELLO -> mapper.readValue(bytes, HelloPayload::class.java)
                BleMessageType.STATE_SUMMARY -> mapper.readValue(bytes, StateSummaryPayload::class.java)
                BleMessageType.BUCKET_CHECKSUMS -> mapper.readValue(bytes, BucketChecksumsPayload::class.java)
                BleMessageType.SYNC_REQUEST -> mapper.readValue(bytes, SyncRequestPayload::class.java)
                BleMessageType.PACKET_OFFER -> mapper.readValue(bytes, PacketOfferPayload::class.java)
                BleMessageType.PACKET_REQUEST -> mapper.readValue(bytes, PacketRequestPayload::class.java)
                BleMessageType.CHUNK_ACK -> mapper.readValue(bytes, ChunkAckPayload::class.java)
                BleMessageType.SYNC_ACK -> mapper.readValue(bytes, SyncAckPayload::class.java)
                BleMessageType.RECEIPT_NOTIFY -> mapper.readValue(bytes, ReceiptNotifyPayload::class.java)
                BleMessageType.ERROR -> mapper.readValue(bytes, MeshErrorPayload::class.java)
            }
        } catch (e: BleProtocolException) {
            throw e
        } catch (e: Exception) {
            throw BleProtocolException(
                BleProtocolError.INVALID_MSG_TYPE,
                "Failed to deserialize payload for $messageType: ${e.message}",
                e
            )
        }
    }
}

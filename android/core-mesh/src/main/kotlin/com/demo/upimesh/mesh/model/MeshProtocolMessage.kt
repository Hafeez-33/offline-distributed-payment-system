package com.demo.upimesh.mesh.model

import com.demo.upimesh.transport.BleMessageType
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Common base interface for all mesh anti-entropy and gossip protocol payloads.
 */
sealed interface MeshProtocolPayload {
    val messageType: BleMessageType
}

/**
 * 1. HELLO message (BleMessageType.HELLO = 0x01)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HelloPayload(
    @JsonProperty("peerId") val peerId: String,
    @JsonProperty("protocolVersion") val protocolVersion: Int = 1,
    @JsonProperty("maxBatchSize") val maxBatchSize: Int = 50,
    @JsonProperty("timestamp") val timestamp: Long = System.currentTimeMillis()
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.HELLO
}

/**
 * 2. STATE_SUMMARY message (BleMessageType.STATE_SUMMARY = 0x02)
 *
 * Deterministic digest of all known local packet hashes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StateSummaryPayload(
    @JsonProperty("protocolVersion") val protocolVersion: Int = 1,
    @JsonProperty("packetCount") val packetCount: Int,
    @JsonProperty("stateDigestHex") val stateDigestHex: String,
    @JsonProperty("bucketAlgorithm") val bucketAlgorithm: String = "PREFIX_4BIT",
    @JsonProperty("syncEpoch") val syncEpoch: Long = 1L
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.STATE_SUMMARY
}

/**
 * Individual entry for one prefix bucket checksum.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BucketEntry(
    @JsonProperty("bucketIndex") val bucketIndex: Int,
    @JsonProperty("packetCount") val packetCount: Int,
    @JsonProperty("bucketChecksumHex") val bucketChecksumHex: String
)

/**
 * 3. BUCKET_CHECKSUMS message (BleMessageType.BUCKET_CHECKSUMS = 0x03)
 *
 * Contains exactly 16 bucket checksum entries (0x0..0xF).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BucketChecksumsPayload(
    @JsonProperty("buckets") val buckets: List<BucketEntry>
) : MeshProtocolPayload {
    init {
        require(buckets.size == 16) { "BucketChecksumsPayload must contain exactly 16 buckets, got ${buckets.size}" }
    }
    override val messageType: BleMessageType get() = BleMessageType.BUCKET_CHECKSUMS
}

/**
 * 4. SYNC_REQUEST message (BleMessageType.SYNC_REQUEST = 0x04)
 *
 * Requests a batch of missing packet hashes (bounded to at most 50 per batch).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncRequestPayload(
    @JsonProperty("sessionId") val sessionId: String,
    @JsonProperty("requestedPacketHashes") val requestedPacketHashes: List<String>
) : MeshProtocolPayload {
    init {
        require(requestedPacketHashes.size <= 50) {
            "SyncRequestPayload cannot request more than 50 packets per batch, got ${requestedPacketHashes.size}"
        }
    }
    override val messageType: BleMessageType get() = BleMessageType.SYNC_REQUEST
}

/**
 * 5. PACKET_OFFER message (BleMessageType.PACKET_OFFER = 0x05)
 *
 * Communicates availability of packet hashes for epidemic push or bucket hash exchange.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PacketOfferPayload(
    @JsonProperty("bucketIndex") val bucketIndex: Int? = null,
    @JsonProperty("offeredPacketHashes") val offeredPacketHashes: List<String>,
    @JsonProperty("ttl") val ttl: Int = 3,
    @JsonProperty("hopCount") val hopCount: Int = 0
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.PACKET_OFFER
}

/**
 * 6. PACKET_REQUEST message (BleMessageType.PACKET_REQUEST = 0x06)
 *
 * Requests streaming transmission of a specific packet hash.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PacketRequestPayload(
    @JsonProperty("packetHash") val packetHash: String
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.PACKET_REQUEST
}

/**
 * 7. CHUNK_ACK message (BleMessageType.CHUNK_ACK = 0x07)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChunkAckPayload(
    @JsonProperty("packetHash") val packetHash: String,
    @JsonProperty("receivedChunks") val receivedChunks: List<Int>
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.CHUNK_ACK
}

/**
 * 8. SYNC_ACK message (BleMessageType.SYNC_ACK = 0x08)
 *
 * Acknowledges batch or session synchronization status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncAckPayload(
    @JsonProperty("sessionId") val sessionId: String,
    @JsonProperty("syncedPacketHashes") val syncedPacketHashes: List<String>,
    @JsonProperty("status") val status: String = "SUCCESS"
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.SYNC_ACK
}

/**
 * 9. RECEIPT_NOTIFY message (BleMessageType.RECEIPT_NOTIFY = 0x09)
 *
 * Conveys an authoritative backend settlement receipt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReceiptNotifyPayload(
    @JsonProperty("transactionId") val transactionId: Long,
    @JsonProperty("packetHash") val packetHash: String,
    @JsonProperty("counter") val counter: Long,
    @JsonProperty("status") val status: String,
    @JsonProperty("settledAt") val settledAt: Long,
    @JsonProperty("serverSignature") val serverSignature: String
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.RECEIPT_NOTIFY
}

/**
 * 10. ERROR message (BleMessageType.ERROR = 0x0A)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MeshErrorPayload(
    @JsonProperty("errorCode") val errorCode: String,
    @JsonProperty("errorMessage") val errorMessage: String
) : MeshProtocolPayload {
    override val messageType: BleMessageType get() = BleMessageType.ERROR
}

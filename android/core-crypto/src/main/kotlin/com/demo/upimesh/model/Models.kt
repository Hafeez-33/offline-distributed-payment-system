package com.demo.upimesh.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaymentInstruction(
    var senderVpa: String? = null,
    var receiverVpa: String? = null,
    var amount: BigDecimal? = null,
    var pinHash: String? = null,
    var nonce: String? = null,
    var signedAt: Long? = null,
    var signature: String? = null,
    var signatureAlgorithm: String? = "Ed25519",
    var walletId: String? = null,
    var walletEpoch: Long? = null,
    var sequenceCounter: Long? = null,
    var cumulativeAmount: BigDecimal? = null,
    var walletCertificate: OfflineWalletCertificate? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OfflineWalletCertificate(
    val walletId: String? = null,
    val ownerVpa: String? = null,
    val ownerPublicKey: String? = null,
    val allocatedAmount: BigDecimal? = null,
    val walletEpoch: Long? = null,
    val validFrom: Long = 0L,
    val validUntil: Long = 0L,
    val initialCounter: Long? = null,
    val issuerSignature: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SettlementReceipt(
    val transactionId: Long? = null,
    val packetHash: String? = null,
    val counter: Long? = null,
    val status: String? = null,
    val settledAt: Long = 0L,
    val serverSignature: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MeshPacket(
    var packetId: String? = null,
    var ttl: Int = 0,
    var createdAt: Long? = null,
    var ciphertext: String? = null
)

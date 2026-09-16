package com.demo.upimesh.crypto

import com.demo.upimesh.model.OfflineWalletCertificate
import com.demo.upimesh.model.PaymentInstruction
import com.demo.upimesh.model.SettlementReceipt
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets

object CanonicalSerializer {

    const val CANONICAL_VERSION_V1 = "v1"
    const val CANONICAL_VERSION_V3 = "v3_tx"
    const val CANONICAL_VERSION_CERT = "v1_cert"
    const val CANONICAL_VERSION_RECEIPT = "v1_receipt"

    fun toCanonicalString(instruction: PaymentInstruction): String {
        requireNotNull(instruction.senderVpa) { "senderVpa must not be null" }
        requireNotNull(instruction.receiverVpa) { "receiverVpa must not be null" }
        requireNotNull(instruction.amount) { "amount must not be null" }
        requireNotNull(instruction.nonce) { "nonce must not be null" }
        requireNotNull(instruction.signedAt) { "signedAt must not be null" }

        val sender = instruction.senderVpa!!.trim().lowercase()
        val receiver = instruction.receiverVpa!!.trim().lowercase()
        val amount = instruction.amount!!.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val nonce = instruction.nonce!!.trim().lowercase()
        val signedAt = instruction.signedAt.toString()

        if (instruction.walletId != null) {
            val walletId = instruction.walletId!!.trim()
            val epoch = instruction.walletEpoch ?: 1L
            val counter = instruction.sequenceCounter ?: 1L
            val cum = instruction.cumulativeAmount ?: instruction.amount!!
            val cumAmount = cum.setScale(2, RoundingMode.HALF_UP).toPlainString()

            return "$CANONICAL_VERSION_V3" +
                    "|walletId=$walletId" +
                    "|epoch=$epoch" +
                    "|counter=$counter" +
                    "|cumAmount=$cumAmount" +
                    "|sender=$sender" +
                    "|receiver=$receiver" +
                    "|amount=$amount" +
                    "|nonce=$nonce" +
                    "|signedAt=$signedAt"
        }

        return "$CANONICAL_VERSION_V1" +
                "|sender=$sender" +
                "|receiver=$receiver" +
                "|amount=$amount" +
                "|nonce=$nonce" +
                "|signedAt=$signedAt"
    }

    fun toCanonicalBytes(instruction: PaymentInstruction): ByteArray {
        return toCanonicalString(instruction).toByteArray(StandardCharsets.UTF_8)
    }

    fun toCanonicalString(cert: OfflineWalletCertificate): String {
        val allocStr = (cert.allocatedAmount ?: BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP).toPlainString()
        val walletId = cert.walletId?.trim() ?: ""
        val ownerVpa = cert.ownerVpa?.trim()?.lowercase() ?: ""
        val ownerPublicKey = cert.ownerPublicKey?.trim() ?: ""
        val walletEpoch = cert.walletEpoch ?: 1L
        val initialCounter = cert.initialCounter ?: 0L

        return "$CANONICAL_VERSION_CERT" +
                "|walletId=$walletId" +
                "|ownerVpa=$ownerVpa" +
                "|ownerPublicKey=$ownerPublicKey" +
                "|allocatedAmount=$allocStr" +
                "|walletEpoch=$walletEpoch" +
                "|validFrom=${cert.validFrom}" +
                "|validUntil=${cert.validUntil}" +
                "|initialCounter=$initialCounter"
    }

    fun toCanonicalBytes(cert: OfflineWalletCertificate): ByteArray {
        return toCanonicalString(cert).toByteArray(StandardCharsets.UTF_8)
    }

    fun toCanonicalString(receipt: SettlementReceipt): String {
        val hash = receipt.packetHash?.trim() ?: ""
        val counter = receipt.counter ?: 0L
        val status = receipt.status?.trim() ?: ""

        return "$CANONICAL_VERSION_RECEIPT" +
                "|txId=${receipt.transactionId}" +
                "|hash=$hash" +
                "|counter=$counter" +
                "|status=$status" +
                "|settledAt=${receipt.settledAt}"
    }

    fun toCanonicalBytes(receipt: SettlementReceipt): ByteArray {
        return toCanonicalString(receipt).toByteArray(StandardCharsets.UTF_8)
    }
}

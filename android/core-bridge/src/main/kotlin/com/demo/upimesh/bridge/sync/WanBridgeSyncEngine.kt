package com.demo.upimesh.bridge.sync

import com.demo.upimesh.bridge.client.BackendApiClient
import com.demo.upimesh.bridge.client.WanIngestResponse
import com.demo.upimesh.bridge.metrics.WanBridgeMetrics
import com.demo.upimesh.bridge.network.NetworkConnectivityProvider
import com.demo.upimesh.bridge.queue.WanQueueManager
import com.demo.upimesh.bridge.receipt.BridgeReceiptValidator
import com.demo.upimesh.bridge.receipt.ReceiptValidationResult
import com.demo.upimesh.bridge.retry.ErrorClassification
import com.demo.upimesh.bridge.retry.WanErrorClassifier
import com.demo.upimesh.bridge.role.BridgeCapabilityManager
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.engine.OfflineWalletEngine
import com.demo.upimesh.db.entity.OutboundPaymentState
import com.demo.upimesh.model.MeshPacket
import java.nio.charset.StandardCharsets

/**
 * Status of a WAN sync execution.
 */
enum class WanSyncStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    NO_NETWORK,
    NOT_BRIDGE_CAPABLE,
    EMPTY_QUEUE,
    RETRY_NEEDED,
    FAILED
}

/**
 * Outcome of a single packet sync within a batch.
 */
data class WanPacketSyncResult(
    val packetHash: String,
    val classification: ErrorClassification,
    val outcome: String,
    val reason: String? = null,
    val receiptVerified: Boolean = false
)

/**
 * Summary result of a WAN synchronization batch.
 */
data class WanSyncResult(
    val status: WanSyncStatus,
    val processedCount: Int = 0,
    val successCount: Int = 0,
    val retryCount: Int = 0,
    val failureCount: Int = 0,
    val packetResults: List<WanPacketSyncResult> = emptyList()
)

/**
 * Pure Kotlin WAN Bridge Synchronization Engine.
 *
 * Coordinates durable queue retrieval, HTTPS upload, error classification,
 * strict receipt cryptographic verification, and local execution state updates.
 *
 * NON-AUTHORITATIVE INVARIANT:
 * Receiving HTTP 200 alone never mutates settledAmountPaisa.
 * Local settledAmountPaisa is updated ONLY when a verified backend SettlementReceipt arrives.
 */
class WanBridgeSyncEngine(
    private val database: UpiMeshDatabase,
    private val apiClient: BackendApiClient,
    private val serverIssuerPublicKeyBase64: String,
    private val capabilityManager: BridgeCapabilityManager = BridgeCapabilityManager(),
    private val connectivityProvider: NetworkConnectivityProvider,
    private val queueManager: WanQueueManager = WanQueueManager(database),
    private val receiptValidator: BridgeReceiptValidator = BridgeReceiptValidator(),
    private val errorClassifier: WanErrorClassifier = WanErrorClassifier(),
    private val walletEngine: OfflineWalletEngine = OfflineWalletEngine(database, com.demo.upimesh.db.keystore.KeyStoreManager())
) {

    /**
     * Executes a single synchronization batch of up to [batchLimit] un-uploaded packets.
     */
    suspend fun runSyncBatch(batchLimit: Int = 50): WanSyncResult {
        // 1. Check bridge eligibility
        if (!capabilityManager.canActAsBridge()) {
            return WanSyncResult(status = WanSyncStatus.NOT_BRIDGE_CAPABLE)
        }

        // 2. Check WAN network connectivity
        if (!connectivityProvider.isConnected()) {
            return WanSyncResult(status = WanSyncStatus.NO_NETWORK)
        }

        // 3. Retrieve bounded batch from Room
        val batch = queueManager.getNextBatch(batchLimit)
        if (batch.isEmpty()) {
            return WanSyncResult(status = WanSyncStatus.EMPTY_QUEUE)
        }

        WanBridgeMetrics.recordWorkerRun()

        val results = mutableListOf<WanPacketSyncResult>()
        var successCount = 0
        var retryCount = 0
        var failureCount = 0

        for (packet in batch) {
            // Check connectivity before each packet upload
            if (!connectivityProvider.isConnected()) {
                queueManager.releaseInFlight(packet.packetHash)
                retryCount++
                results.add(
                    WanPacketSyncResult(
                        packetHash = packet.packetHash,
                        classification = ErrorClassification.RETRYABLE,
                        outcome = "NETWORK_LOST",
                        reason = "Network connectivity lost during batch"
                    )
                )
                continue
            }

            if (!queueManager.tryAcquireInFlight(packet.packetHash)) {
                continue
            }

            val meshPacket = MeshPacket(
                packetId = packet.packetId,
                ttl = packet.ttl,
                createdAt = packet.receivedAt,
                ciphertext = packet.ciphertext
            )

            WanBridgeMetrics.recordUploadAttempt()
            val payloadBytes = packet.ciphertext.toByteArray(StandardCharsets.UTF_8).size.toLong()
            WanBridgeMetrics.recordBytesUploaded(payloadBytes)

            val response: WanIngestResponse = try {
                apiClient.ingestPacket(
                    packet = meshPacket,
                    bridgeNodeId = capabilityManager.config.bridgeNodeId,
                    hopCount = packet.hopCount
                )
            } catch (e: Exception) {
                WanIngestResponse(
                    outcome = "TRANSIENT_FAILURE",
                    packetHash = packet.packetHash,
                    reason = "client_exception: ${e.message}",
                    httpStatusCode = 0
                )
            }

            val classification = errorClassifier.classify(response)

            when (classification) {
                ErrorClassification.SUCCESS -> {
                    // Settlement receipt verification
                    val receiptResult = receiptValidator.validateAndExtractReceipt(
                        response = response,
                        expectedPacketHash = packet.packetHash,
                        serverIssuerPublicKeyBase64 = serverIssuerPublicKeyBase64
                    )

                    when (receiptResult) {
                        is ReceiptValidationResult.Valid -> {
                            // Verified backend receipt: apply to wallet engine and update settledAmountPaisa
                            try {
                                walletEngine.applySettlementReceipt(
                                    receiptResult.receipt,
                                    serverIssuerPublicKeyBase64
                                )
                                queueManager.markUploaded(packet.packetHash)
                                WanBridgeMetrics.recordUploadSuccess()
                                WanBridgeMetrics.recordReceiptVerified()
                                successCount++
                                results.add(
                                    WanPacketSyncResult(
                                        packetHash = packet.packetHash,
                                        classification = classification,
                                        outcome = response.outcome,
                                        receiptVerified = true
                                    )
                                )
                            } catch (e: Exception) {
                                // Persistence or wallet state conflict
                                queueManager.releaseInFlight(packet.packetHash)
                                failureCount++
                                results.add(
                                    WanPacketSyncResult(
                                        packetHash = packet.packetHash,
                                        classification = ErrorClassification.PERMANENT,
                                        outcome = response.outcome,
                                        reason = "apply_receipt_failure: ${e.message}"
                                    )
                                )
                            }
                        }
                        is ReceiptValidationResult.Invalid -> {
                            // Strict rule: Invalid or incomplete receipt MUST NOT update settledAmountPaisa
                            queueManager.releaseInFlight(packet.packetHash)
                            WanBridgeMetrics.recordReceiptRejected()
                            WanBridgeMetrics.recordUploadPermanentFailure()
                            failureCount++
                            results.add(
                                WanPacketSyncResult(
                                    packetHash = packet.packetHash,
                                    classification = ErrorClassification.PERMANENT,
                                    outcome = "INVALID_RECEIPT",
                                    reason = receiptResult.reason
                                )
                            )
                        }
                    }
                }

                ErrorClassification.IGNORED_DUPLICATE -> {
                    // Backend already holds this packet idempotently
                    queueManager.markUploaded(packet.packetHash)
                    WanBridgeMetrics.recordUploadSuccess()
                    successCount++
                    results.add(
                        WanPacketSyncResult(
                            packetHash = packet.packetHash,
                            classification = classification,
                            outcome = response.outcome
                        )
                    )
                }

                ErrorClassification.IN_FLIGHT_GAP -> {
                    // Staged on backend pending sequence gap resolution
                    queueManager.markUploaded(packet.packetHash)
                    WanBridgeMetrics.recordUploadSuccess()
                    successCount++
                    results.add(
                        WanPacketSyncResult(
                            packetHash = packet.packetHash,
                            classification = classification,
                            outcome = response.outcome,
                            reason = response.reason
                        )
                    )
                }

                ErrorClassification.TERMINAL_CONFLICT -> {
                    // Double-spend collision on backend
                    val payment = database.outboundPaymentDao.getPaymentByPacketHash(packet.packetHash)
                    if (payment != null) {
                        database.outboundPaymentDao.updateState(
                            payment.paymentId,
                            OutboundPaymentState.CONFLICTING,
                            System.currentTimeMillis()
                        )
                    }
                    queueManager.markUploaded(packet.packetHash)
                    WanBridgeMetrics.recordUploadPermanentFailure()
                    failureCount++
                    results.add(
                        WanPacketSyncResult(
                            packetHash = packet.packetHash,
                            classification = classification,
                            outcome = response.outcome,
                            reason = response.reason
                        )
                    )
                }

                ErrorClassification.PERMANENT -> {
                    // Business rejection (e.g. insufficient funds, invalid signature)
                    val payment = database.outboundPaymentDao.getPaymentByPacketHash(packet.packetHash)
                    if (payment != null) {
                        database.outboundPaymentDao.updateState(
                            payment.paymentId,
                            OutboundPaymentState.REJECTED,
                            System.currentTimeMillis()
                        )
                    }
                    queueManager.markUploaded(packet.packetHash)
                    WanBridgeMetrics.recordUploadPermanentFailure()
                    failureCount++
                    results.add(
                        WanPacketSyncResult(
                            packetHash = packet.packetHash,
                            classification = classification,
                            outcome = response.outcome,
                            reason = response.reason
                        )
                    )
                }

                ErrorClassification.RETRYABLE -> {
                    // Transient error: release in-flight lease for subsequent retry
                    queueManager.releaseInFlight(packet.packetHash)
                    WanBridgeMetrics.recordUploadRetry()
                    retryCount++
                    results.add(
                        WanPacketSyncResult(
                            packetHash = packet.packetHash,
                            classification = classification,
                            outcome = response.outcome,
                            reason = response.reason
                        )
                    )
                }
            }
        }

        val finalStatus = when {
            successCount > 0 && retryCount == 0 && failureCount == 0 -> WanSyncStatus.SUCCESS
            successCount > 0 -> WanSyncStatus.PARTIAL_SUCCESS
            retryCount > 0 -> WanSyncStatus.RETRY_NEEDED
            else -> WanSyncStatus.FAILED
        }

        return WanSyncResult(
            status = finalStatus,
            processedCount = results.size,
            successCount = successCount,
            retryCount = retryCount,
            failureCount = failureCount,
            packetResults = results
        )
    }
}

package com.demo.upimesh.bridge.work

import com.demo.upimesh.bridge.sync.WanBridgeSyncEngine
import com.demo.upimesh.bridge.sync.WanSyncStatus

/**
 * Result representation for Android WorkManager execution.
 */
enum class WanWorkerResult {
    SUCCESS,
    RETRY,
    FAILURE
}

/**
 * WorkManager-compatible background worker for scheduled WAN bridge batch uploads.
 *
 * Implements safe idempotency, bounded execution, and retry semantics.
 */
class WanUploadWorker(
    private val syncEngine: WanBridgeSyncEngine
) {

    /**
     * Executes the background bridge synchronization unit of work.
     */
    suspend fun doWork(batchLimit: Int = 50): WanWorkerResult {
        val syncResult = syncEngine.runSyncBatch(batchLimit)
        return when (syncResult.status) {
            WanSyncStatus.SUCCESS,
            WanSyncStatus.EMPTY_QUEUE,
            WanSyncStatus.NOT_BRIDGE_CAPABLE -> WanWorkerResult.SUCCESS
            WanSyncStatus.PARTIAL_SUCCESS -> {
                // If some items in the batch need retry, return RETRY so WorkManager reschedules
                if (syncResult.retryCount > 0) WanWorkerResult.RETRY else WanWorkerResult.SUCCESS
            }
            WanSyncStatus.NO_NETWORK,
            WanSyncStatus.RETRY_NEEDED -> WanWorkerResult.RETRY
            WanSyncStatus.FAILED -> WanWorkerResult.FAILURE
        }
    }
}

package com.demo.upimesh.mesh.sync

/**
 * Plans bounded synchronization batches to ensure memory limits and message constraints are strictly respected.
 */
object SyncBatchPlanner {

    const val DEFAULT_MAX_BATCH_SIZE = 50

    /**
     * Splits a list of requested packet hashes into bounded sub-lists of at most maxBatchSize.
     */
    fun planBatches(
        hashes: List<String>,
        maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
    ): List<List<String>> {
        require(maxBatchSize in 1..50) {
            "maxBatchSize must be between 1 and 50, got $maxBatchSize"
        }
        if (hashes.isEmpty()) return emptyList()

        val cleanSorted = hashes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
        return cleanSorted.chunked(maxBatchSize)
    }
}

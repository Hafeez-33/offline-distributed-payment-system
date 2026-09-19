package com.demo.upimesh.mesh.sync

/**
 * Result of a pairwise set difference calculation.
 */
data class SetDifferenceResult(
    /** Hashes that exist on remote peer but are missing locally */
    val missingLocally: List<String>,
    /** Hashes that exist locally but are missing on remote peer */
    val missingRemotely: List<String>
)

/**
 * Calculates symmetric set difference between local and remote hash collections.
 * All results are canonicalized and deterministically sorted.
 */
object SetDifferenceCalculator {

    fun calculate(
        localHashes: Collection<String>,
        remoteHashes: Collection<String>
    ): SetDifferenceResult {
        val localSet = localHashes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val remoteSet = remoteHashes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        val missingLocally = (remoteSet - localSet).sorted()
        val missingRemotely = (localSet - remoteSet).sorted()

        return SetDifferenceResult(
            missingLocally = missingLocally,
            missingRemotely = missingRemotely
        )
    }
}

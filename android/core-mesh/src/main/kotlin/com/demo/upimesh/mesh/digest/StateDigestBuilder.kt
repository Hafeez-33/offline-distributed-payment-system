package com.demo.upimesh.mesh.digest

import com.demo.upimesh.crypto.PacketHasher

/**
 * Deterministic State Digest generator.
 *
 * Algorithm:
 * 1. Takes an arbitrary collection of packet hashes (64-char hex strings).
 * 2. Normalizes to lowercase and deduplicates.
 * 3. If collection is empty, returns SHA-256("EMPTY").
 * 4. Sorts packet hashes in canonical lexicographical order (byte-order / string compare).
 * 5. Concatenates sorted hashes with newline '\n' delimiters.
 * 6. Returns SHA-256 hash of the canonical UTF-8 string as a 64-char lowercase hex string.
 *
 * Independence properties:
 * - Independent of Room row order / insertion order.
 * - Independent of JVM HashMap iteration order.
 * - Produces identical digest across all Android devices holding the same set of packets.
 */
object StateDigestBuilder {

    private const val EMPTY_REPRESENTATION = "EMPTY"

    fun calculateDigest(packetHashes: Collection<String>): String {
        if (packetHashes.isEmpty()) {
            return PacketHasher.hashCiphertext(EMPTY_REPRESENTATION).lowercase()
        }
        val canonicalSorted = packetHashes
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        if (canonicalSorted.isEmpty()) {
            return PacketHasher.hashCiphertext(EMPTY_REPRESENTATION).lowercase()
        }

        val canonicalText = canonicalSorted.joinToString("\n")
        return PacketHasher.hashCiphertext(canonicalText).lowercase()
    }
}

package com.demo.upimesh.mesh.digest

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.mesh.model.BucketChecksumsPayload
import com.demo.upimesh.mesh.model.BucketEntry

/**
 * 16 Prefix-Bucket Checksum generator.
 *
 * Algorithm:
 * 1. Partitions packet hashes into exactly 16 buckets based on the first 4 bits
 *    (i.e. the first hex character 0..f) of the lowercase packetHash.
 * 2. For each bucket i (0..15):
 *    a. If bucket is empty, checksum = SHA-256("EMPTY"), count = 0.
 *    b. If bucket is non-empty, sort hashes lexicographically, concatenate with '\n',
 *       and compute checksum = SHA-256(concatenated string).
 *    c. Construct BucketEntry(bucketIndex = i, packetCount = count, bucketChecksumHex = checksum).
 * 3. Returns exactly 16 BucketEntries sorted from bucket 0 to 15.
 */
object BucketChecksumBuilder {

    private const val EMPTY_REPRESENTATION = "EMPTY"

    fun getBucketIndex(packetHash: String): Int {
        val clean = packetHash.trim().lowercase()
        require(clean.isNotEmpty()) { "Packet hash cannot be empty" }
        val firstChar = clean[0]
        val digit = Character.digit(firstChar, 16)
        require(digit in 0..15) { "Invalid hex character for bucket selection: $firstChar" }
        return digit
    }

    fun buildBuckets(packetHashes: Collection<String>): List<BucketEntry> {
        val cleanHashes = packetHashes
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        val bucketMap = mutableMapOf<Int, MutableList<String>>()
        for (i in 0..15) {
            bucketMap[i] = mutableListOf()
        }

        for (hash in cleanHashes) {
            val idx = getBucketIndex(hash)
            bucketMap[idx]!!.add(hash)
        }

        val entries = mutableListOf<BucketEntry>()
        for (i in 0..15) {
            val hashes = bucketMap[i]!!
            if (hashes.isEmpty()) {
                entries.add(
                    BucketEntry(
                        bucketIndex = i,
                        packetCount = 0,
                        bucketChecksumHex = PacketHasher.hashCiphertext(EMPTY_REPRESENTATION).lowercase()
                    )
                )
            } else {
                val sorted = hashes.sorted()
                val text = sorted.joinToString("\n")
                entries.add(
                    BucketEntry(
                        bucketIndex = i,
                        packetCount = sorted.size,
                        bucketChecksumHex = PacketHasher.hashCiphertext(text).lowercase()
                    )
                )
            }
        }
        return entries
    }

    fun buildPayload(packetHashes: Collection<String>): BucketChecksumsPayload {
        return BucketChecksumsPayload(buildBuckets(packetHashes))
    }

    /**
     * Identifies divergent bucket indices between local and remote bucket checksums.
     * Returns a sorted list of bucket indices (0..15) where checksums differ.
     */
    fun findDivergentBuckets(local: List<BucketEntry>, remote: List<BucketEntry>): List<Int> {
        require(local.size == 16 && remote.size == 16) {
            "Both local and remote must have exactly 16 buckets"
        }
        val localMap = local.associateBy { it.bucketIndex }
        val remoteMap = remote.associateBy { it.bucketIndex }

        val divergent = mutableListOf<Int>()
        for (i in 0..15) {
            val l = localMap[i]
            val r = remoteMap[i]
            if (l == null || r == null || l.bucketChecksumHex != r.bucketChecksumHex || l.packetCount != r.packetCount) {
                divergent.add(i)
            }
        }
        return divergent.sorted()
    }
}

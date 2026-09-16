package com.demo.upimesh.mesh.digest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BucketChecksumBuilderTest {

    @Test
    fun testGeneratesExactly16Buckets() {
        val buckets = BucketChecksumBuilder.buildBuckets(emptyList())
        assertEquals(16, buckets.size)
        for (i in 0..15) {
            assertEquals(i, buckets[i].bucketIndex)
            assertEquals(0, buckets[i].packetCount)
            assertEquals(64, buckets[i].bucketChecksumHex.length)
        }
    }

    @Test
    fun testBucketSelectionFirst4Bits() {
        val hash0 = "0abcdef1234567890abcdef1234567890abcdef1234567890abcdef123456789"
        val hash9 = "9abcdef1234567890abcdef1234567890abcdef1234567890abcdef123456789"
        val hashA = "aabcdef1234567890abcdef1234567890abcdef1234567890abcdef123456789"
        val hashF = "fabcdef1234567890abcdef1234567890abcdef1234567890abcdef123456789"

        assertEquals(0, BucketChecksumBuilder.getBucketIndex(hash0))
        assertEquals(9, BucketChecksumBuilder.getBucketIndex(hash9))
        assertEquals(10, BucketChecksumBuilder.getBucketIndex(hashA))
        assertEquals(15, BucketChecksumBuilder.getBucketIndex(hashF))
    }

    @Test
    fun testIdenticalBucketsProduceZeroDivergence() {
        val hashes = listOf(
            "0000000000000000000000000000000000000000000000000000000000000001",
            "1000000000000000000000000000000000000000000000000000000000000002",
            "f000000000000000000000000000000000000000000000000000000000000003"
        )
        val local = BucketChecksumBuilder.buildBuckets(hashes)
        val remote = BucketChecksumBuilder.buildBuckets(hashes)

        val divergent = BucketChecksumBuilder.findDivergentBuckets(local, remote)
        assertTrue(divergent.isEmpty(), "Identical bucket collections must yield 0 divergent buckets")
    }

    @Test
    fun testSingleBucketDivergenceIsIsolatedExactly() {
        val baseHashes = listOf(
            "0000000000000000000000000000000000000000000000000000000000000001",
            "1000000000000000000000000000000000000000000000000000000000000002",
            "a000000000000000000000000000000000000000000000000000000000000003"
        )
        val remoteHashes = baseHashes + "5000000000000000000000000000000000000000000000000000000000000004"

        val local = BucketChecksumBuilder.buildBuckets(baseHashes)
        val remote = BucketChecksumBuilder.buildBuckets(remoteHashes)

        val divergent = BucketChecksumBuilder.findDivergentBuckets(local, remote)
        assertEquals(listOf(5), divergent, "Only bucket 5 must be flagged as divergent")
    }

    @Test
    fun testMultipleBucketDivergence() {
        val localHashes = listOf(
            "2000000000000000000000000000000000000000000000000000000000000001"
        )
        val remoteHashes = listOf(
            "3000000000000000000000000000000000000000000000000000000000000002",
            "c000000000000000000000000000000000000000000000000000000000000003"
        )

        val local = BucketChecksumBuilder.buildBuckets(localHashes)
        val remote = BucketChecksumBuilder.buildBuckets(remoteHashes)

        val divergent = BucketChecksumBuilder.findDivergentBuckets(local, remote)
        assertEquals(listOf(2, 3, 12), divergent)
    }
}

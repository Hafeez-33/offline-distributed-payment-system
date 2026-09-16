package com.demo.upimesh.mesh.digest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StateDigestBuilderTest {

    @Test
    fun testEmptyDatabaseProducesCanonicalEmptyDigest() {
        val emptyDigest1 = StateDigestBuilder.calculateDigest(emptyList())
        val emptyDigest2 = StateDigestBuilder.calculateDigest(listOf("", "  "))
        assertEquals(emptyDigest1, emptyDigest2)
        assertEquals(64, emptyDigest1.length)
    }

    @Test
    fun testInsertionOrderIndependence() {
        val hashes1 = listOf(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "1111111111111111111111111111111111111111111111111111111111111111"
        )
        val hashes2 = listOf(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "1111111111111111111111111111111111111111111111111111111111111111",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )

        val digest1 = StateDigestBuilder.calculateDigest(hashes1)
        val digest2 = StateDigestBuilder.calculateDigest(hashes2)

        assertEquals(digest1, digest2, "State digest must be independent of insertion/collection order")
    }

    @Test
    fun testDuplicateHashesAreDeduplicatedInDigest() {
        val hashes1 = listOf(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
        val hashes2 = listOf(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )

        val digest1 = StateDigestBuilder.calculateDigest(hashes1)
        val digest2 = StateDigestBuilder.calculateDigest(hashes2)

        assertEquals(digest1, digest2, "Duplicates must produce identical state digest")
    }

    @Test
    fun testDifferentPacketSetProducesDifferentDigest() {
        val setA = listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val setB = listOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

        val digestA = StateDigestBuilder.calculateDigest(setA)
        val digestB = StateDigestBuilder.calculateDigest(setB)

        assertNotEquals(digestA, digestB, "Different packet sets must produce different digests")
    }

    @Test
    fun testCaseInsensitiveNormalization() {
        val upper = listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val lower = listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val digestUpper = StateDigestBuilder.calculateDigest(upper)
        val digestLower = StateDigestBuilder.calculateDigest(lower)

        assertEquals(digestUpper, digestLower, "Digest must normalize hex casing")
    }
}

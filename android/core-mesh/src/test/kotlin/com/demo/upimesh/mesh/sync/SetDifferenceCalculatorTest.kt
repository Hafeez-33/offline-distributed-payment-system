package com.demo.upimesh.mesh.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SetDifferenceCalculatorTest {

    @Test
    fun testIdenticalSetsProduceEmptyDifference() {
        val hashes = listOf("h1", "h2", "h3")
        val result = SetDifferenceCalculator.calculate(hashes, hashes)
        assertTrue(result.missingLocally.isEmpty())
        assertTrue(result.missingRemotely.isEmpty())
    }

    @Test
    fun testDisjointSets() {
        val local = listOf("a1", "a2")
        val remote = listOf("b1", "b2")

        val result = SetDifferenceCalculator.calculate(local, remote)
        assertEquals(listOf("b1", "b2"), result.missingLocally)
        assertEquals(listOf("a1", "a2"), result.missingRemotely)
    }

    @Test
    fun testPartialOverlap() {
        val local = listOf("common", "local_only")
        val remote = listOf("common", "remote_only")

        val result = SetDifferenceCalculator.calculate(local, remote)
        assertEquals(listOf("remote_only"), result.missingLocally)
        assertEquals(listOf("local_only"), result.missingRemotely)
    }

    @Test
    fun testDeterministicSorting() {
        val local = listOf("z", "a")
        val remote = listOf("y", "b")

        val result = SetDifferenceCalculator.calculate(local, remote)
        assertEquals(listOf("b", "y"), result.missingLocally)
        assertEquals(listOf("a", "z"), result.missingRemotely)
    }
}

package com.demo.upimesh.mesh.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SyncBatchPlannerTest {

    @Test
    fun testEmptyHashesYieldsEmptyBatches() {
        val batches = SyncBatchPlanner.planBatches(emptyList(), 50)
        assertTrue(batches.isEmpty())
    }

    @Test
    fun testSmallCountUnderMaxBatchSize() {
        val hashes = listOf("h1", "h2", "h3")
        val batches = SyncBatchPlanner.planBatches(hashes, 50)
        assertEquals(1, batches.size)
        assertEquals(3, batches[0].size)
    }

    @Test
    fun testExactBatchSize50() {
        val hashes = (1..50).map { "h%02d".format(it) }
        val batches = SyncBatchPlanner.planBatches(hashes, 50)
        assertEquals(1, batches.size)
        assertEquals(50, batches[0].size)
    }

    @Test
    fun testSplitsAcrossMultipleBatches() {
        val hashes = (1..125).map { "h%03d".format(it) }
        val batches = SyncBatchPlanner.planBatches(hashes, 50)
        assertEquals(3, batches.size)
        assertEquals(50, batches[0].size)
        assertEquals(50, batches[1].size)
        assertEquals(25, batches[2].size)
    }

    @Test
    fun testRejectsBatchSizeAbove50OrBelow1() {
        val hashes = listOf("h1")
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchPlanner.planBatches(hashes, 51)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchPlanner.planBatches(hashes, 0)
        }
    }
}

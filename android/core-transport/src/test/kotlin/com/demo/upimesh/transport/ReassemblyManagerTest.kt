package com.demo.upimesh.transport

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.transport.abstraction.FakeTimeProvider
import com.demo.upimesh.transport.fragment.FragmentationEngine
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.frame.BleFrameConstants
import com.demo.upimesh.transport.frame.BleFrameHeader
import com.demo.upimesh.transport.metrics.BleTransportMetrics
import com.demo.upimesh.transport.reassembly.ReassemblyManager
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReassemblyManagerTest {

    private lateinit var database: UpiMeshDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var metrics: BleTransportMetrics
    private lateinit var manager: ReassemblyManager

    @BeforeEach
    fun setUp() {
        database = UpiMeshDatabase.inMemory()
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
        metrics = BleTransportMetrics()
        manager = ReassemblyManager(
            database = database,
            timeProvider = timeProvider,
            metrics = metrics,
            timeoutMs = 60_000L
        )
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSingleFragmentPacketFastPath() {
        val payloadStr = "SINGLE_FRAGMENT_PAYLOAD_CIPHERTEXT"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val payload = payloadStr.toByteArray(StandardCharsets.UTF_8)

        val header = BleFrameHeader(
            messageType = BleMessageType.PACKET_OFFER,
            flags = BleFrameConstants.FLAG_LAST_FRAGMENT,
            fragmentIndex = 0,
            totalFragments = 1,
            transferId = 1,
            packetHashPrefix = FragmentationEngine.extractPrefix(expectedHash),
            payloadLength = payload.size
        )
        val frame = BleFrame(header, payload)

        val result = manager.processFrame(frame, expectedHash)
        assertNotNull(result)
        assertEquals(expectedHash, result!!.packetHash)
        assertEquals(payloadStr, result.ciphertext)

        // Verify stored in Room ReceivedPacket table
        val inDb = database.receivedPacketDao.getPacket(expectedHash)
        assertNotNull(inDb)
        assertEquals(expectedHash, inDb!!.packetHash)
        assertEquals(1, metrics.reassemblyCompletedTotal.get())
    }

    @Test
    fun testMultiFragmentInOrderReassembly() {
        val payloadStr = "MULTI_FRAGMENT_PAYLOAD_THAT_WILL_BE_SLICED_INTO_THREE_CHUNKS_OF_DATA_1234567890123456789012345678901234567890"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val payload = payloadStr.toByteArray(StandardCharsets.UTF_8)

        // Slices into 3 chunks (111 bytes / 45 = 3 chunks)
        val frames = FragmentationEngine.fragment(
            payload = payload,
            messageType = BleMessageType.PACKET_OFFER,
            transferId = 42,
            packetHash = expectedHash,
            negotiatedMtu = 64 // 45 byte chunks
        )
        assertEquals(3, frames.size)

        // Send Chunk 0
        val r0 = manager.processFrame(frames[0], expectedHash)
        assertNull(r0, "Reassembly must not be complete after frame 0")
        assertEquals(1, manager.getActiveSessionCount())

        // Send Chunk 1
        val r1 = manager.processFrame(frames[1], expectedHash)
        assertNull(r1, "Reassembly must not be complete after frame 1")

        // Send Chunk 2 (Final)
        val r2 = manager.processFrame(frames[2], expectedHash)
        assertNotNull(r2, "Reassembly must complete after frame 2")
        assertEquals(expectedHash, r2!!.packetHash)
        assertEquals(payloadStr, r2.ciphertext)

        // Verify session cleaned up from active memory
        assertEquals(0, manager.getActiveSessionCount())
        assertEquals(0L, manager.getActiveMemoryBytes())

        // Verify Room ReceivedPacket exists
        val dbPacket = database.receivedPacketDao.getPacket(expectedHash)
        assertNotNull(dbPacket)

        // Verify fragments cleaned up from Room PacketFragment table
        val fragmentsInDb = database.packetFragmentDao.queryFragments(expectedHash)
        assertTrue(fragmentsInDb.isEmpty())
    }

    @Test
    fun testMultiFragmentOutOfOrderReassembly() {
        val payloadStr = "OUT_OF_ORDER_MULTI_FRAGMENT_PAYLOAD_TESTING_ARBITRARY_CHUNK_ARRIVAL_SEQUENCE_0123456789012345678901234567890"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val payload = payloadStr.toByteArray(StandardCharsets.UTF_8)

        val frames = FragmentationEngine.fragment(
            payload = payload,
            messageType = BleMessageType.PACKET_OFFER,
            transferId = 99,
            packetHash = expectedHash,
            negotiatedMtu = 64
        )
        assertEquals(3, frames.size)

        // Send in reverse order: Frame 2, Frame 0, Frame 1
        assertNull(manager.processFrame(frames[2], expectedHash))
        assertNull(manager.processFrame(frames[0], expectedHash))

        val finalResult = manager.processFrame(frames[1], expectedHash)
        assertNotNull(finalResult)
        assertEquals(expectedHash, finalResult!!.packetHash)
        assertEquals(payloadStr, finalResult.ciphertext)
    }

    @Test
    fun testIdempotentDuplicateFragmentIsSafe() {
        val payloadStr = "IDEMPOTENT_DUPLICATE_TEST_PAYLOAD_THAT_HAS_OVER_NINETY_BYTES_TO_REQUIRE_MULTIPLE_CHUNKS_012345678901234567890"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val frames = FragmentationEngine.fragment(
            payload = payloadStr.toByteArray(StandardCharsets.UTF_8),
            transferId = 5,
            packetHash = expectedHash,
            negotiatedMtu = 64
        )
        assertTrue(frames.size >= 2)

        // Send frame 0
        assertNull(manager.processFrame(frames[0], expectedHash))

        // Send frame 0 AGAIN (identical bytes)
        assertNull(manager.processFrame(frames[0], expectedHash))

        // Send remaining frames
        for (i in 1 until frames.size - 1) {
            assertNull(manager.processFrame(frames[i], expectedHash))
        }
        val result = manager.processFrame(frames.last(), expectedHash)
        assertNotNull(result)
        assertEquals(payloadStr, result!!.ciphertext)
    }

    @Test
    fun testConflictingDuplicateFragmentThrowsError() {
        val payloadStr = "ORIGINAL_PAYLOAD_FOR_CONFLICT_TEST_WITH_ENOUGH_BYTES_TO_BE_MULTI_FRAGMENT_0123456789012345678901234567890"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val frames = FragmentationEngine.fragment(
            payload = payloadStr.toByteArray(StandardCharsets.UTF_8),
            transferId = 15,
            packetHash = expectedHash,
            negotiatedMtu = 64
        )
        assertTrue(frames.size >= 2)

        // Send frame 0
        assertNull(manager.processFrame(frames[0], expectedHash))

        // Create conflicting frame 0 with altered payload
        val tamperedPayload = frames[0].payload.copyOf()
        tamperedPayload[0] = (tamperedPayload[0].toInt() xor 0xFF).toByte()
        val conflictingFrame = BleFrame(frames[0].header, tamperedPayload)

        val ex = assertThrows(BleProtocolException::class.java) {
            manager.processFrame(conflictingFrame, expectedHash)
        }
        assertEquals(BleProtocolError.DUPLICATE_FRAME, ex.error)
    }

    @Test
    fun testMissingFragmentDetection() {
        val payloadStr = "MISSING_FRAGMENT_DETECTION_TEST_PAYLOAD_WITH_SEVERAL_CHUNKS_EXCEEDING_NINETY_BYTES_0123456789012345678901234567890"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val frames = FragmentationEngine.fragment(
            payload = payloadStr.toByteArray(StandardCharsets.UTF_8),
            transferId = 77,
            packetHash = expectedHash,
            negotiatedMtu = 64
        )
        assertTrue(frames.size >= 3)

        // Send frame 0 and frame 2 (skip frame 1)
        manager.processFrame(frames[0], expectedHash)
        manager.processFrame(frames[2], expectedHash)

        val missing = manager.getMissingFragmentIndices(expectedHash)
        assertEquals(listOf(1), missing)
    }

    @Test
    fun testPacketHashMismatchRejectsReassembledPayload() {
        val payloadStr = "PAYLOAD_THAT_WILL_BE_VERIFIED_AGAINST_WRONG_HASH_WITH_MORE_THAN_NINETY_BYTES_0123456789012345678901234567890"
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val frames = FragmentationEngine.fragment(
            payload = payloadStr.toByteArray(StandardCharsets.UTF_8),
            transferId = 88,
            packetHash = wrongHash,
            negotiatedMtu = 64
        )
        assertTrue(frames.size >= 2)

        for (i in 0 until frames.size - 1) {
            assertNull(manager.processFrame(frames[i], wrongHash))
        }

        // Final frame with wrong hash
        val ex = assertThrows(BleProtocolException::class.java) {
            manager.processFrame(frames.last(), wrongHash)
        }
        assertEquals(BleProtocolError.UNKNOWN_PACKET_HASH, ex.error)

        // Verify rejected packet was NOT stored in ReceivedPacket table
        val inDb = database.receivedPacketDao.getPacket(wrongHash)
        assertNull(inDb)
    }

    @Test
    fun testRestartRecoveryFromRoomFragments() {
        val payloadStr = "RESTART_RECOVERY_ROOM_PERSISTENCE_TEST_PAYLOAD_EXCEEDING_NINETY_BYTES_FOR_THREE_CHUNKS_012345678901234567890"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val frames = FragmentationEngine.fragment(
            payload = payloadStr.toByteArray(StandardCharsets.UTF_8),
            transferId = 123,
            packetHash = expectedHash,
            negotiatedMtu = 64
        )
        assertEquals(3, frames.size)

        // Process frames 0 and 1 in manager 1
        manager.processFrame(frames[0], expectedHash)
        manager.processFrame(frames[1], expectedHash)

        // Verify 2 fragments exist in Room
        val savedFragments = database.packetFragmentDao.queryFragments(expectedHash)
        assertEquals(2, savedFragments.size)

        // Simulate app restart: create a new ReassemblyManager instance
        val newManager = ReassemblyManager(
            database = database,
            timeProvider = timeProvider,
            metrics = metrics
        )

        // Restore state from Room
        val restoredSession = newManager.restoreFromDatabase(expectedHash)
        assertNotNull(restoredSession)
        assertEquals(2, restoredSession!!.fragments.size)
        assertEquals(listOf(2), restoredSession.getMissingIndices())

        // Feed remaining frame 2 into new manager -> must complete successfully!
        val result = newManager.processFrame(frames[2], expectedHash)
        assertNotNull(result)
        assertEquals(expectedHash, result!!.packetHash)
        assertEquals(payloadStr, result.ciphertext)
    }

    @Test
    fun testStaleReassemblyTimeout() {
        val payloadStr = "TIMEOUT_TEST_PAYLOAD_WITH_MORE_THAN_NINETY_BYTES_FOR_MULTIPLE_CHUNKS_0123456789012345678901234567890"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val frames = FragmentationEngine.fragment(
            payload = payloadStr.toByteArray(StandardCharsets.UTF_8),
            transferId = 444,
            packetHash = expectedHash,
            negotiatedMtu = 64
        )
        assertTrue(frames.size >= 2)

        // Send frame 0 at t=0
        manager.processFrame(frames[0], expectedHash)
        assertEquals(1, manager.getActiveSessionCount())

        // Advance time by 61 seconds (exceeding 60s timeout)
        timeProvider.advanceTime(61_000L)

        // Trigger cleanExpiredSessions
        manager.cleanExpiredSessions()

        // Active session should be removed and purged from Room
        assertEquals(0, manager.getActiveSessionCount())
        assertEquals(1, metrics.reassemblyTimeoutTotal.get())
        assertTrue(database.packetFragmentDao.queryFragments(expectedHash).isEmpty())
    }

    @Test
    fun testMax8ActiveReassemblyBuffersLimit() {
        for (i in 1..8) {
            val hash = "hash_${String.format("%04d", i)}0000000000000000000000000000000000000000000000000000"
            val frame = BleFrame(
                BleFrameHeader(
                    messageType = BleMessageType.PACKET_OFFER,
                    fragmentIndex = 0,
                    totalFragments = 2,
                    transferId = i,
                    payloadLength = 10
                ),
                ByteArray(10)
            )
            assertNull(manager.processFrame(frame, hash))
        }
        assertEquals(8, manager.getActiveSessionCount())

        // 9th session must throw OVERSIZED_PAYLOAD / buffer limit error
        val hash9 = "hash_00090000000000000000000000000000000000000000000000000000"
        val frame9 = BleFrame(
            BleFrameHeader(
                messageType = BleMessageType.PACKET_OFFER,
                fragmentIndex = 0,
                totalFragments = 2,
                transferId = 9,
                payloadLength = 10
            ),
            ByteArray(10)
        )
        val ex = assertThrows(BleProtocolException::class.java) {
            manager.processFrame(frame9, hash9)
        }
        assertEquals(BleProtocolError.OVERSIZED_PAYLOAD, ex.error)
    }

    @Test
    fun testReassemblyDoesNotMutateFinancialBalances() {
        // Invariant check: Receiving a packet over BLE must NEVER increment settledAmountPaisa
        val payloadStr = "FINANCIAL_INVARIANT_TEST_PAYLOAD"
        val expectedHash = PacketHasher.hashCiphertext(payloadStr)
        val frame = BleFrame(
            BleFrameHeader(
                messageType = BleMessageType.PACKET_OFFER,
                fragmentIndex = 0,
                totalFragments = 1,
                transferId = 1,
                payloadLength = payloadStr.toByteArray(StandardCharsets.UTF_8).size
            ),
            payloadStr.toByteArray(StandardCharsets.UTF_8)
        )

        val packet = manager.processFrame(frame, expectedHash)
        assertNotNull(packet)

        // Verify wallets table in DB remains completely unmutated
        val wallets = database.offlineWalletDao.getAllWallets()
        assertTrue(wallets.isEmpty())
    }
}

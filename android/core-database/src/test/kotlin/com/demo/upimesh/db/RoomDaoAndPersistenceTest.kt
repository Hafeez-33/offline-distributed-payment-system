package com.demo.upimesh.db

import com.demo.upimesh.db.entity.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RoomDaoAndPersistenceTest {

    private lateinit var database: UpiMeshDatabase

    @BeforeEach
    fun setUp() {
        database = UpiMeshDatabase.inMemory()
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDeviceIdentityDaoCrud() {
        val identity = DeviceIdentity(
            deviceId = "dev-100",
            ownerVpa = "alice@upi",
            publicKey = "pub-key-base64",
            encryptedPrivateKey = "enc-priv-key-base64",
            encryptionIv = "iv-base64",
            enrollmentState = EnrollmentState.ENROLLED
        )

        val rowId = database.deviceIdentityDao.insert(identity)
        assertTrue(rowId > 0)

        val retrieved = database.deviceIdentityDao.getIdentity("dev-100")
        assertNotNull(retrieved)
        assertEquals("alice@upi", retrieved!!.ownerVpa)
        assertEquals(EnrollmentState.ENROLLED, retrieved.enrollmentState)

        val active = database.deviceIdentityDao.getActiveIdentity()
        assertNotNull(active)
        assertEquals("dev-100", active!!.deviceId)

        // Update
        val updated = identity.copy(enrollmentState = EnrollmentState.SUSPENDED)
        database.deviceIdentityDao.update(updated)
        assertEquals(EnrollmentState.SUSPENDED, database.deviceIdentityDao.getIdentity("dev-100")!!.enrollmentState)

        // Delete
        database.deviceIdentityDao.delete("dev-100")
        assertNull(database.deviceIdentityDao.getIdentity("dev-100"))
    }

    @Test
    fun testOfflineWalletDaoPaisaIntegerEnforcement() {
        val wallet = OfflineWallet(
            walletId = "WLT-100",
            ownerVpa = "alice@upi",
            ownerPublicKey = "key-100",
            allocatedAmountPaisa = 150_000L, // ₹1,500.00
            localSpentAmountPaisa = 25_000L, // ₹250.00
            settledAmountPaisa = 0L,
            remainingAmountPaisa = 125_000L, // ₹1,250.00
            sequenceCounter = 2L,
            walletEpoch = 1L,
            validFrom = 1000L,
            validUntil = 2000L,
            certificateJson = "{}"
        )

        database.offlineWalletDao.insert(wallet)
        val fetched = database.offlineWalletDao.getWallet("WLT-100")

        assertNotNull(fetched)
        assertEquals(150_000L, fetched!!.allocatedAmountPaisa)
        assertEquals(25_000L, fetched.localSpentAmountPaisa)
        assertEquals(0L, fetched.settledAmountPaisa)
        assertEquals(125_000L, fetched.remainingAmountPaisa)
        assertEquals(2L, fetched.sequenceCounter)

        // Update balances
        database.offlineWalletDao.updateCounterAndBalances("WLT-100", 3L, 100_000L, 50_000L, 1500L)
        val updated = database.offlineWalletDao.getWallet("WLT-100")!!
        assertEquals(3L, updated.sequenceCounter)
        assertEquals(100_000L, updated.remainingAmountPaisa)
        assertEquals(50_000L, updated.localSpentAmountPaisa)
        assertEquals(0L, updated.settledAmountPaisa) // Settled amount remains untouched!
    }

    @Test
    fun testReceivedPacketDeduplication() {
        val packetHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val packet1 = ReceivedPacket(
            packetHash = packetHash,
            packetId = "pkt-001",
            ciphertext = "cipher-1",
            ttl = 5,
            hopCount = 1,
            receivedAt = 1000L
        )

        val insert1 = database.receivedPacketDao.insert(packet1)
        assertEquals(1L, insert1)
        assertTrue(database.receivedPacketDao.exists(packetHash))

        // Duplicate insert with same packetHash
        val packet2 = ReceivedPacket(
            packetHash = packetHash,
            packetId = "pkt-001-dup",
            ciphertext = "cipher-2",
            ttl = 4,
            hopCount = 2,
            receivedAt = 2000L
        )

        val insert2 = database.receivedPacketDao.insert(packet2)
        assertEquals(-1L, insert2, "Duplicate packetHash must be rejected/ignored without exception")

        // Total count remains 1
        assertEquals(1, database.receivedPacketDao.getAllPackets().size)
    }

    @Test
    fun testPacketFragmentCompositeKeyOperations() {
        val packetHash = "hash-fragment-test"
        val chunk0 = PacketFragment(packetHash, 0, 3, byteArrayOf(1, 2, 3), 1000L)
        val chunk1 = PacketFragment(packetHash, 1, 3, byteArrayOf(4, 5, 6), 1001L)
        val chunk2 = PacketFragment(packetHash, 2, 3, byteArrayOf(7, 8, 9), 1002L)

        database.packetFragmentDao.saveFragment(chunk0)
        database.packetFragmentDao.saveFragment(chunk1)
        database.packetFragmentDao.saveFragment(chunk2)

        assertEquals(3, database.packetFragmentDao.countFragments(packetHash))

        val fragments = database.packetFragmentDao.queryFragments(packetHash)
        assertEquals(3, fragments.size)
        assertEquals(0, fragments[0].chunkIndex)
        assertEquals(1, fragments[1].chunkIndex)
        assertEquals(2, fragments[2].chunkIndex)
        assertArrayEquals(byteArrayOf(1, 2, 3), fragments[0].data)

        // Clear reassembly
        database.packetFragmentDao.clearPacketReassemblyState(packetHash)
        assertEquals(0, database.packetFragmentDao.countFragments(packetHash))
    }

    @Test
    fun testFileBackedPersistenceAndRestartSurvival(@TempDir tempDir: File) {
        val dbFile = File(tempDir, "test_wallet.db")

        // 1. First process life: write records
        val db1 = UpiMeshDatabase.open(dbFile)
        val wallet = OfflineWallet(
            walletId = "WLT-RESTART",
            ownerVpa = "bob@upi",
            ownerPublicKey = "bob-key",
            allocatedAmountPaisa = 50_000L,
            remainingAmountPaisa = 50_000L,
            sequenceCounter = 5L,
            validFrom = 100L,
            validUntil = 200L,
            certificateJson = "{}"
        )
        db1.offlineWalletDao.insert(wallet)
        val fragment = PacketFragment("hash-restart", 0, 1, byteArrayOf(9, 9, 9), 500L)
        db1.packetFragmentDao.saveFragment(fragment)
        db1.close()

        // 2. Simulated process restart / device reboot: reopen same file
        val db2 = UpiMeshDatabase.open(dbFile)
        val loadedWallet = db2.offlineWalletDao.getWallet("WLT-RESTART")
        assertNotNull(loadedWallet)
        assertEquals("bob@upi", loadedWallet!!.ownerVpa)
        assertEquals(5L, loadedWallet.sequenceCounter)
        assertEquals(50_000L, loadedWallet.allocatedAmountPaisa)

        val loadedFragments = db2.packetFragmentDao.queryFragments("hash-restart")
        assertEquals(1, loadedFragments.size)
        assertArrayEquals(byteArrayOf(9, 9, 9), loadedFragments[0].data)
        db2.close()
    }
}

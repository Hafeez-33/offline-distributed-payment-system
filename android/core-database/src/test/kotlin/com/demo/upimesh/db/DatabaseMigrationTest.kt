package com.demo.upimesh.db

import com.demo.upimesh.db.entity.OfflineWallet
import com.demo.upimesh.db.migration.Migration1To2
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DatabaseMigrationTest {

    @Test
    fun testInitialDatabaseVersionIsOne() {
        val db = UpiMeshDatabase.inMemory(targetVersion = 1)
        assertEquals(1, db.getSchemaVersion())
        db.close()
    }

    @Test
    fun testMigrationFromV1ToV2PreservesData(@TempDir tempDir: File) {
        val dbFile = File(tempDir, "migration_test.db")

        // 1. Open at Version 1 and insert a wallet
        val dbV1 = UpiMeshDatabase.open(dbFile, targetVersion = 1)
        assertEquals(1, dbV1.getSchemaVersion())

        val wallet = OfflineWallet(
            walletId = "WLT-MIGRATE",
            ownerVpa = "carol@upi",
            ownerPublicKey = "carol-key",
            allocatedAmountPaisa = 80_000L,
            localSpentAmountPaisa = 10_000L,
            settledAmountPaisa = 0L,
            remainingAmountPaisa = 70_000L,
            sequenceCounter = 1L,
            validFrom = 100L,
            validUntil = 200L,
            certificateJson = "{}"
        )
        dbV1.offlineWalletDao.insert(wallet)
        dbV1.close()

        // 2. Open at Version 2 with Migration1To2
        val dbV2 = UpiMeshDatabase.open(
            dbFile = dbFile,
            targetVersion = 2,
            migrations = listOf(Migration1To2())
        )
        assertEquals(2, dbV2.getSchemaVersion())

        // 3. Verify existing record survived migration completely intact
        val loaded = dbV2.offlineWalletDao.getWallet("WLT-MIGRATE")
        assertNotNull(loaded)
        assertEquals("carol@upi", loaded!!.ownerVpa)
        assertEquals(80_000L, loaded.allocatedAmountPaisa)
        assertEquals(10_000L, loaded.localSpentAmountPaisa)
        assertEquals(70_000L, loaded.remainingAmountPaisa)
        assertEquals(1L, loaded.sequenceCounter)

        dbV2.close()
    }

    @Test
    fun testMissingMigrationPathThrowsExceptionWithoutDestructiveFallback(@TempDir tempDir: File) {
        val dbFile = File(tempDir, "missing_migration.db")

        // Open at Version 1
        val dbV1 = UpiMeshDatabase.open(dbFile, targetVersion = 1)
        dbV1.close()

        // Attempt to open at Version 2 without providing Migration1To2
        val ex = assertThrows(IllegalStateException::class.java) {
            UpiMeshDatabase.open(dbFile, targetVersion = 2, migrations = emptyList())
        }
        assertTrue(ex.message!!.contains("Destructive migrations are disallowed"))
    }
}

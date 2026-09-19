package com.demo.upimesh.app

import android.app.Application
import android.content.Context
import com.demo.upimesh.app.ble.AndroidBlePlatformDriver
import com.demo.upimesh.app.db.AndroidSqliteUpiMeshDatabase
import com.demo.upimesh.bridge.client.FakeBackendApiClient
import com.demo.upimesh.bridge.network.DefaultNetworkConnectivityProvider
import com.demo.upimesh.bridge.network.NetworkConnectivityProvider
import com.demo.upimesh.bridge.role.BridgeCapabilityManager
import com.demo.upimesh.bridge.role.BridgeConfig
import com.demo.upimesh.bridge.role.DeviceRole
import com.demo.upimesh.bridge.sync.WanBridgeSyncEngine
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.engine.OfflineWalletEngine
import com.demo.upimesh.db.keystore.KeyStoreManager
import com.demo.upimesh.db.recovery.StartupRecoveryManager
import com.demo.upimesh.transport.BleRole
import com.demo.upimesh.transport.abstraction.BleTransport
import java.io.File

/**
 * Android Application entrypoint and dependency container for the UPI Mesh Test Harness.
 *
 * NON-AUTHORITATIVE BOUNDARY:
 * Local database and wallet state on this device are strictly non-authoritative.
 * Final financial authority resides exclusively in the PostgreSQL database managed
 * by the Spring Boot backend.
 */
class UpiMeshApplication : Application() {

    lateinit var database: UpiMeshDatabase
        private set

    lateinit var keyStoreManager: KeyStoreManager
        private set

    lateinit var walletEngine: OfflineWalletEngine
        private set

    lateinit var recoveryManager: StartupRecoveryManager
        private set

    lateinit var bridgeManager: BridgeCapabilityManager
        private set

    lateinit var connectivityProvider: NetworkConnectivityProvider
        private set

    lateinit var syncEngine: WanBridgeSyncEngine
        private set

    lateinit var bleTransport: BleTransport
        private set

    private var initialized = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        initialize(context = this, dataDir = filesDir)
    }

    fun initialize(context: Context? = null, dataDir: File? = null, serverPublicKeyBase64: String? = null) {
        if (initialized) return

        database = if (context != null) {
            val dbFile = if (dataDir != null) File(dataDir, "upimesh_local.db") else null
            AndroidSqliteUpiMeshDatabase.open(context, dbFile)
        } else if (dataDir != null) {
            val dbFile = File(dataDir, "upimesh_local.db")
            UpiMeshDatabase.open(dbFile)
        } else {
            UpiMeshDatabase.inMemory()
        }

        keyStoreManager = KeyStoreManager()
        walletEngine = OfflineWalletEngine(database, keyStoreManager)
        recoveryManager = StartupRecoveryManager(database, walletEngine)

        bridgeManager = BridgeCapabilityManager(
            config = BridgeConfig(isBridgeCapable = false, maxBatchSize = 50)
        )
        bridgeManager.setRole(DeviceRole.PAYER)

        connectivityProvider = DefaultNetworkConnectivityProvider()

        val trustedServerKey = serverPublicKeyBase64 ?: "MCowBQYDK2VwAyEA0000000000000000000000000000000000000000000="

        syncEngine = WanBridgeSyncEngine(
            database = database,
            apiClient = FakeBackendApiClient(),
            serverIssuerPublicKeyBase64 = trustedServerKey,
            connectivityProvider = connectivityProvider,
            capabilityManager = bridgeManager
        )

        bleTransport = AndroidBlePlatformDriver(context = context, role = BleRole.PAYER)

        // Run recovery on startup
        recoveryManager.performStartupRecovery()

        initialized = true
    }

    companion object {
        var instance: UpiMeshApplication = UpiMeshApplication()
    }
}

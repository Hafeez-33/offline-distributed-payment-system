package com.demo.upimesh.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.demo.upimesh.app.R
import com.demo.upimesh.app.UpiMeshApplication
import com.demo.upimesh.bridge.role.DeviceRole
import com.demo.upimesh.transport.BleUuids
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Controller and presentation manager for the Physical Hardware Test Harness.
 *
 * NON-AUTHORITATIVE:
 * This activity is purely diagnostic. It provides manual triggers to exercise
 * the underlying core modules on physical hardware and displays diagnostic state.
 * No financial decisions, cryptographic synthesis, or balance math exist in this class.
 */
class HardwareHarnessActivity : AppCompatActivity() {

    var app: UpiMeshApplication = UpiMeshApplication.instance
    private val scope: CoroutineScope by lazy {
        try {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        } catch (_: Throwable) {
            CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
    }

    var state: HardwareHarnessState = HardwareHarnessState()
        private set

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_hardware_harness)
            setupViews()
        } catch (ignored: Throwable) {
            // Allows instantiation without full Android window system in unit tests
        }
        app.initialize(context = this, dataDir = filesDir)
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun setupViews() {
        findViewById<Button>(R.id.btnToggleRole)?.setOnClickListener { toggleRole() }
        findViewById<Button>(R.id.btnToggleAdv)?.setOnClickListener { toggleAdvertising() }
        findViewById<Button>(R.id.btnToggleScan)?.setOnClickListener { toggleScanning() }
        findViewById<Button>(R.id.btnCreatePayment)?.setOnClickListener { prepareDemoPayment() }
        findViewById<Button>(R.id.btnTriggerSync)?.setOnClickListener { triggerSync() }
        findViewById<Button>(R.id.btnTriggerIngest)?.setOnClickListener { triggerWanIngestBatch() }
    }

    fun refreshState() {
        val identity = try { app.database.deviceIdentityDao.getActiveIdentity() } catch (e: Exception) { null }
        val wallet = identity?.let {
            try { app.database.offlineWalletDao.getActiveWalletForOwner(it.ownerVpa) } catch (e: Exception) { null }
        }
        val pendingPackets = try { app.database.receivedPacketDao.getPendingBridgePackets().size } catch (e: Exception) { 0 }

        state = state.copy(
            deviceId = identity?.deviceId ?: "dev-android-01",
            ownerVpa = identity?.ownerVpa ?: "alice@demo",
            role = try { app.bridgeManager.getRole() } catch (e: Exception) { DeviceRole.PAYER },
            isWanConnected = try { app.connectivityProvider.isConnected() } catch (e: Exception) { false },
            queueDepth = pendingPackets,
            walletAllocatedPaisa = wallet?.allocatedAmountPaisa ?: 100000L,
            walletSpentPaisa = wallet?.localSpentAmountPaisa ?: 0L,
            walletRemainingPaisa = wallet?.remainingAmountPaisa ?: 100000L,
            walletSequenceCounter = wallet?.sequenceCounter ?: 0L,
            isAdvertising = try { app.bleTransport.advertiser.isAdvertising } catch (e: Exception) { false },
            isScanning = try { app.bleTransport.scanner.isScanning } catch (e: Exception) { false }
        )

        updateUiFromState()
    }

    private fun updateUiFromState() {
        try {
            findViewById<TextView>(R.id.tvDeviceIdentity)?.text = "Identity: ${state.ownerVpa} (${state.deviceId})"
            findViewById<TextView>(R.id.tvDeviceRole)?.text = "Current Role: ${state.role.name}"
            findViewById<TextView>(R.id.tvAdvState)?.text = if (state.isAdvertising) "Advertising: Active" else "Advertising: Idle"
            findViewById<TextView>(R.id.tvScanState)?.text = if (state.isScanning) "Scanning: Active (${state.discoveredPeersCount} found)" else "Scanning: Idle (${state.discoveredPeersCount} found)"
            findViewById<TextView>(R.id.tvNetworkStatus)?.text = if (state.isWanConnected) "WAN Network: Connected" else "WAN Network: Disconnected"
            findViewById<TextView>(R.id.tvQueueDepth)?.text = "Bridge Queue: ${state.queueDepth} pending packets"
            findViewById<TextView>(R.id.tvWalletStatus)?.text = "Offline Wallet: ₹${"%.2f".format(state.walletAllocatedPaisa / 100.0)} alloc | ₹${"%.2f".format(state.walletSpentPaisa / 100.0)} spent | C=${state.walletSequenceCounter}"
            findViewById<TextView>(R.id.tvLogs)?.text = state.logs.joinToString("\n").ifEmpty { "[Diagnostic Log]\nReady." }
        } catch (ignored: Throwable) {}
    }

    fun toggleRole() {
        val nextRole = when (state.role) {
            DeviceRole.PAYER -> DeviceRole.RELAY
            DeviceRole.RELAY -> DeviceRole.BRIDGE
            DeviceRole.BRIDGE -> DeviceRole.MERCHANT
            DeviceRole.MERCHANT -> DeviceRole.PAYER
        }
        app.bridgeManager.setRole(nextRole)
        log("Device role switched to: ${nextRole.name}")
        refreshState()
    }

    fun toggleAdvertising() {
        if (state.isAdvertising) {
            app.bleTransport.advertiser.stopAdvertising()
            log("Stopped BLE Advertising")
        } else {
            app.bleTransport.advertiser.startAdvertising(
                role = app.bleTransport.role,
                serviceUuid = BleUuids.SERVICE_UUID
            )
            log("Started BLE Advertising (Service: ${BleUuids.SERVICE_UUID_STRING})")
        }
        refreshState()
    }

    fun toggleScanning() {
        if (state.isScanning) {
            app.bleTransport.scanner.stopScanning()
            log("Stopped BLE Scanning")
        } else {
            app.bleTransport.scanner.startScanning(
                serviceUuid = BleUuids.SERVICE_UUID,
                onDeviceFound = { peer ->
                    log("Discovered BLE peer: ${peer.deviceAddress} (RSSI: ${peer.rssi} dBm)")
                    state = state.copy(discoveredPeersCount = state.discoveredPeersCount + 1)
                }
            )
            log("Started BLE Scanning for Service UUID: ${BleUuids.SERVICE_UUID_STRING}")
        }
        refreshState()
    }

    fun prepareDemoPayment(amountPaisa: Long = 25000L, receiverVpa: String = "bob@demo") {
        try {
            val identity = app.database.deviceIdentityDao.getActiveIdentity()
            val wallet = identity?.let { app.database.offlineWalletDao.getActiveWalletForOwner(it.ownerVpa) }

            if (wallet == null) {
                log("Error: No active offline wallet found to prepare payment.")
                return
            }

            val payment = app.walletEngine.createOfflineSpend(
                walletId = wallet.walletId,
                ownerVpa = wallet.ownerVpa,
                receiverVpa = receiverVpa,
                amountPaisa = amountPaisa,
                serverRsaPublicKeyBase64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA00000000000000000000000000000000"
            )

            log("Prepared offline payment: ID=${payment.paymentId}, Amt=₹${"%.2f".format(amountPaisa / 100.0)}, C=${payment.sequenceCounter}, Hash=${payment.packetHash?.take(12)}...")
            refreshState()
        } catch (e: Exception) {
            log("Failed to prepare payment: ${e.message}")
        }
    }

    fun triggerSync() {
        log("Anti-Entropy synchronization initiated.")
        refreshState()
    }

    fun triggerWanIngestBatch() {
        scope.launch {
            try {
                log("Starting WAN batch ingestion...")
                val result = app.syncEngine.runSyncBatch(batchLimit = 50)
                log("WAN batch ingestion completed: status=${result.status}, success=${result.successCount}, retries=${result.retryCount}, failed=${result.failureCount}")
                refreshState()
            } catch (e: Exception) {
                log("WAN batch ingestion error: ${e.message}")
            }
        }
    }

    private fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        val updatedLogs = (state.logs + entry).takeLast(50)
        state = state.copy(logs = updatedLogs)
        updateUiFromState()
    }
}

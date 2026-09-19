package com.demo.upimesh.app.ui

import com.demo.upimesh.app.permission.BlePermissionState
import com.demo.upimesh.bridge.role.DeviceRole

/**
 * Immutable diagnostic state representation for the Hardware Harness Activity.
 *
 * NON-AUTHORITATIVE: Purely presents diagnostic telemetry from the core modules.
 */
data class HardwareHarnessState(
    val deviceId: String = "dev-android-01",
    val ownerVpa: String = "alice@demo",
    val role: DeviceRole = DeviceRole.PAYER,
    val isBluetoothEnabled: Boolean = true,
    val permissionState: BlePermissionState = BlePermissionState(allGranted = true, missingPermissions = emptyList(), apiLevel = 34),
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val discoveredPeersCount: Int = 0,
    val connectedPeerAddress: String? = null,
    val negotiatedMtu: Int = 517,
    val isWanConnected: Boolean = false,
    val queueDepth: Int = 0,
    val walletAllocatedPaisa: Long = 100000L,
    val walletSpentPaisa: Long = 0L,
    val walletRemainingPaisa: Long = 100000L,
    val walletSequenceCounter: Long = 0L,
    val logs: List<String> = listOf("Harness initialized.")
) {
    val deviceIdentityDisplay: String
        get() = "Identity: $ownerVpa ($deviceId)"

    val deviceRoleDisplay: String
        get() = "Current Role: ${role.name}"

    val bleStatusDisplay: String
        get() = if (!isBluetoothEnabled) {
            "Bluetooth: Disabled"
        } else {
            "BLE Status: Ready (${permissionState.toDiagnosticString()})"
        }

    val advStateDisplay: String
        get() = if (isAdvertising) "Advertising: ACTIVE (UUID: e8a30001-...)" else "Advertising: IDLE"

    val scanStateDisplay: String
        get() = if (isScanning) "Scanning: ACTIVE ($discoveredPeersCount peers discovered)" else "Scanning: IDLE ($discoveredPeersCount peers found)"

    val peerStateDisplay: String
        get() = if (connectedPeerAddress != null) "Connected Peer: $connectedPeerAddress | MTU: $negotiatedMtu" else "Connected Peer: None | MTU: $negotiatedMtu"

    val networkStatusDisplay: String
        get() = if (isWanConnected) "WAN Network: ONLINE (Bridge Ready)" else "WAN Network: OFFLINE (Mesh Only)"

    val queueDepthDisplay: String
        get() = "Bridge Queue: $queueDepth pending packets"

    val walletStatusDisplay: String
        get() = "Offline Wallet: ₹${"%.2f".format(walletAllocatedPaisa / 100.0)} alloc | ₹${"%.2f".format(walletSpentPaisa / 100.0)} spent | Rem: ₹${"%.2f".format(walletRemainingPaisa / 100.0)} (C=$walletSequenceCounter)"
}

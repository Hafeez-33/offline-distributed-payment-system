package com.demo.upimesh.transport.abstraction

import com.demo.upimesh.transport.BleMessageType
import com.demo.upimesh.transport.BleRole
import com.demo.upimesh.transport.BleUuids
import com.demo.upimesh.transport.frame.BleFrame
import java.util.UUID

/**
 * State of a BLE GATT connection.
 */
enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

/**
 * Discovered BLE peer device metadata.
 */
data class BleDiscoveredDevice(
    val deviceAddress: String,
    val deviceName: String?,
    val rssi: Int,
    val serviceUuids: List<UUID> = emptyList(),
    val advertisedRole: BleRole? = null
)

/**
 * Listener for incoming BLE transport events and frames.
 */
interface BleTransportListener {
    fun onPeerConnected(connection: BleConnection)
    fun onPeerDisconnected(connection: BleConnection)
    fun onFrameReceived(connection: BleConnection, frame: BleFrame)
    fun onMtuChanged(connection: BleConnection, mtu: Int)
    fun onError(connection: BleConnection?, error: Throwable)
}

/**
 * Handle representing an active point-to-point BLE GATT connection.
 */
interface BleConnection {
    val peerAddress: String
    val peerRole: BleRole?
    val negotiatedMtu: Int
    val state: BleConnectionState

    /** Sends a framed message or chunk over the connection */
    suspend fun sendFrame(frame: BleFrame)

    /** Request specific MTU negotiation (e.g. 517) */
    suspend fun requestMtu(mtu: Int): Int

    /** Closes the GATT connection */
    fun disconnect()
}

/**
 * BLE Advertising abstraction for Peripheral role.
 */
interface BleAdvertiser {
    fun startAdvertising(role: BleRole, serviceUuid: UUID = BleUuids.SERVICE_UUID)
    fun stopAdvertising()
    val isAdvertising: Boolean
}

/**
 * BLE Scanning abstraction for Central role.
 */
interface BleScanner {
    fun startScanning(serviceUuid: UUID = BleUuids.SERVICE_UUID, onDeviceFound: (BleDiscoveredDevice) -> Unit)
    fun stopScanning()
    val isScanning: Boolean
}

/**
 * GATT Server abstraction (Peripheral).
 */
interface BleGattServer {
    fun startServer(listener: BleTransportListener)
    fun stopServer()
    val activeConnections: List<BleConnection>
}

/**
 * GATT Client abstraction (Central).
 */
interface BleGattClient {
    suspend fun connect(deviceAddress: String, listener: BleTransportListener): BleConnection
    fun disconnectAll()
}

/**
 * Unified high-level transport coordinating Advertiser, Scanner, Server, and Client.
 */
interface BleTransport {
    val role: BleRole
    val advertiser: BleAdvertiser
    val scanner: BleScanner
    val gattServer: BleGattServer
    val gattClient: BleGattClient

    fun initialize(listener: BleTransportListener)
    fun shutdown()
}

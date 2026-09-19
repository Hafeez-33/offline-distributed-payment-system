package com.demo.upimesh.app.ble

import com.demo.upimesh.transport.BleRole
import com.demo.upimesh.transport.abstraction.*
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.frame.BleFrameCodec
import com.demo.upimesh.transport.mtu.MtuManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic In-Memory BLE Transport Test Double for JVM unit tests.
 *
 * Explicitly provides in-memory simulated BLE behavior for test suites
 * without leaking simulation fallback paths into the production Android BLE driver.
 */
class InMemoryBleTransport(
    override val role: BleRole = BleRole.PAYER
) : BleTransport {

    var transportListener: BleTransportListener? = null
    val discoveredDevices = CopyOnWriteArrayList<BleDiscoveredDevice>()
    val activeConnectionsMap = ConcurrentHashMap<String, BleConnection>()

    override val advertiser: BleAdvertiser = object : BleAdvertiser {
        private var _isAdvertising = false
        override val isAdvertising: Boolean get() = _isAdvertising

        override fun startAdvertising(role: BleRole, serviceUuid: UUID) {
            _isAdvertising = true
        }

        override fun stopAdvertising() {
            _isAdvertising = false
        }
    }

    override val scanner: BleScanner = object : BleScanner {
        private var _isScanning = false
        override val isScanning: Boolean get() = _isScanning

        override fun startScanning(serviceUuid: UUID, onDeviceFound: (BleDiscoveredDevice) -> Unit) {
            _isScanning = true
            discoveredDevices.clear()
        }

        override fun stopScanning() {
            _isScanning = false
        }
    }

    override val gattServer: BleGattServer = object : BleGattServer {
        private var _running = false
        override val activeConnections: List<BleConnection>
            get() = activeConnectionsMap.values.toList()

        override fun startServer(listener: BleTransportListener) {
            _running = true
        }

        override fun stopServer() {
            _running = false
            activeConnectionsMap.clear()
        }
    }

    override val gattClient: BleGattClient = object : BleGattClient {
        override suspend fun connect(deviceAddress: String, listener: BleTransportListener): BleConnection {
            val conn = InMemoryConnection(deviceAddress, listener) {
                activeConnectionsMap.remove(deviceAddress)
            }
            activeConnectionsMap[deviceAddress] = conn
            listener.onPeerConnected(conn)
            return conn
        }

        override fun disconnectAll() {
            activeConnectionsMap.values.forEach { it.disconnect() }
            activeConnectionsMap.clear()
        }
    }

    override fun initialize(listener: BleTransportListener) {
        this.transportListener = listener
        gattServer.startServer(listener)
    }

    override fun shutdown() {
        advertiser.stopAdvertising()
        scanner.stopScanning()
        gattServer.stopServer()
        gattClient.disconnectAll()
    }

    class InMemoryConnection(
        override val peerAddress: String,
        private val listener: BleTransportListener,
        private val onDisconnect: () -> Unit
    ) : BleConnection {

        override var negotiatedMtu: Int = MtuManager.PREFERRED_MTU
            private set

        override var state: BleConnectionState = BleConnectionState.CONNECTED
            private set

        override val peerRole: BleRole? = null

        override suspend fun sendFrame(frame: BleFrame) {
            if (state != BleConnectionState.CONNECTED) {
                throw IllegalStateException("Connection to $peerAddress is not connected")
            }
            val encoded = BleFrameCodec.encode(frame)
            val decoded = BleFrameCodec.decode(encoded)
            listener.onFrameReceived(this, decoded)
        }

        override suspend fun requestMtu(mtu: Int): Int {
            negotiatedMtu = mtu
            listener.onMtuChanged(this, mtu)
            return negotiatedMtu
        }

        override fun disconnect() {
            if (state != BleConnectionState.DISCONNECTED) {
                state = BleConnectionState.DISCONNECTED
                onDisconnect()
                listener.onPeerDisconnected(this)
            }
        }
    }
}

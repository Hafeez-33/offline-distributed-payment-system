package com.demo.upimesh.transport.fake

import com.demo.upimesh.transport.BleRole
import com.demo.upimesh.transport.BleUuids
import com.demo.upimesh.transport.abstraction.BleAdvertiser
import com.demo.upimesh.transport.abstraction.BleConnection
import com.demo.upimesh.transport.abstraction.BleConnectionState
import com.demo.upimesh.transport.abstraction.BleDiscoveredDevice
import com.demo.upimesh.transport.abstraction.BleGattClient
import com.demo.upimesh.transport.abstraction.BleGattServer
import com.demo.upimesh.transport.abstraction.BleScanner
import com.demo.upimesh.transport.abstraction.BleTransport
import com.demo.upimesh.transport.abstraction.BleTransportListener
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.mtu.MtuManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * In-memory simulated BLE GATT transport connecting virtual Central and Peripheral nodes.
 */
class FakeBleTransport(
    override val role: BleRole,
    val deviceAddress: String,
    val deviceName: String = "FakeDevice-$deviceAddress"
) : BleTransport {

    private var transportListener: BleTransportListener? = null
    private val connections = ConcurrentHashMap<String, FakeBleConnection>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override val advertiser: BleAdvertiser = object : BleAdvertiser {
        override var isAdvertising: Boolean = false

        override fun startAdvertising(role: BleRole, serviceUuid: UUID) {
            isAdvertising = true
            FakeBleNetwork.registerAdvertiser(this@FakeBleTransport)
        }

        override fun stopAdvertising() {
            isAdvertising = false
            FakeBleNetwork.unregisterAdvertiser(this@FakeBleTransport)
        }
    }

    override val scanner: BleScanner = object : BleScanner {
        override var isScanning: Boolean = false
        private var callback: ((BleDiscoveredDevice) -> Unit)? = null

        override fun startScanning(serviceUuid: UUID, onDeviceFound: (BleDiscoveredDevice) -> Unit) {
            isScanning = true
            this.callback = onDeviceFound
            FakeBleNetwork.discoverDevices(serviceUuid).forEach { device ->
                onDeviceFound(device)
            }
        }

        override fun stopScanning() {
            isScanning = false
            callback = null
        }
    }

    override val gattServer: BleGattServer = object : BleGattServer {
        override fun startServer(listener: BleTransportListener) {
            transportListener = listener
        }

        override fun stopServer() {
            connections.values.forEach { it.disconnect() }
            connections.clear()
        }

        override val activeConnections: List<BleConnection>
            get() = connections.values.toList()
    }

    override val gattClient: BleGattClient = object : BleGattClient {
        override suspend fun connect(deviceAddress: String, listener: BleTransportListener): BleConnection {
            val target = FakeBleNetwork.getTransport(deviceAddress)
                ?: throw IllegalArgumentException("Target device $deviceAddress not found in fake network")

            val centralConn = FakeBleConnection(
                peerAddress = deviceAddress,
                peerRole = target.role,
                listener = listener
            )

            val peripheralConn = FakeBleConnection(
                peerAddress = this@FakeBleTransport.deviceAddress,
                peerRole = this@FakeBleTransport.role,
                listener = target.transportListener ?: object : BleTransportListener {
                    override fun onPeerConnected(connection: BleConnection) {}
                    override fun onPeerDisconnected(connection: BleConnection) {}
                    override fun onFrameReceived(connection: BleConnection, frame: BleFrame) {}
                    override fun onMtuChanged(connection: BleConnection, mtu: Int) {}
                    override fun onError(connection: BleConnection?, error: Throwable) {}
                }
            )

            // Cross-wire bidirectional pipe
            centralConn.peerConnection = peripheralConn
            peripheralConn.peerConnection = centralConn

            connections[deviceAddress] = centralConn
            target.connections[this@FakeBleTransport.deviceAddress] = peripheralConn

            listener.onPeerConnected(centralConn)
            target.transportListener?.onPeerConnected(peripheralConn)

            return centralConn
        }

        override fun disconnectAll() {
            connections.values.forEach { it.disconnect() }
            connections.clear()
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

    class FakeBleConnection(
        override val peerAddress: String,
        override val peerRole: BleRole?,
        private val listener: BleTransportListener
    ) : BleConnection {

        override var negotiatedMtu: Int = MtuManager.PREFERRED_MTU
            private set

        override var state: BleConnectionState = BleConnectionState.CONNECTED
            private set

        var peerConnection: FakeBleConnection? = null

        override suspend fun sendFrame(frame: BleFrame) {
            if (state != BleConnectionState.CONNECTED) {
                throw IllegalStateException("Cannot send frame, state is $state")
            }
            val peer = peerConnection ?: throw IllegalStateException("Peer connection not attached")
            peer.receiveFrame(frame)
        }

        fun receiveFrame(frame: BleFrame) {
            listener.onFrameReceived(this, frame)
        }

        override suspend fun requestMtu(mtu: Int): Int {
            negotiatedMtu = minOf(mtu, MtuManager.PREFERRED_MTU)
            peerConnection?.let { it.negotiatedMtu = negotiatedMtu }
            listener.onMtuChanged(this, negotiatedMtu)
            peerConnection?.listener?.onMtuChanged(peerConnection!!, negotiatedMtu)
            return negotiatedMtu
        }

        override fun disconnect() {
            if (state != BleConnectionState.DISCONNECTED) {
                state = BleConnectionState.DISCONNECTED
                listener.onPeerDisconnected(this)
                peerConnection?.let { peer ->
                    if (peer.state != BleConnectionState.DISCONNECTED) {
                        peer.state = BleConnectionState.DISCONNECTED
                        peer.listener.onPeerDisconnected(peer)
                    }
                }
            }
        }
    }
}

/**
 * Global registry for fake BLE transports in test runs.
 */
object FakeBleNetwork {
    private val transports = ConcurrentHashMap<String, FakeBleTransport>()

    fun registerAdvertiser(transport: FakeBleTransport) {
        transports[transport.deviceAddress] = transport
    }

    fun unregisterAdvertiser(transport: FakeBleTransport) {
        transports.remove(transport.deviceAddress)
    }

    fun getTransport(deviceAddress: String): FakeBleTransport? = transports[deviceAddress]

    fun discoverDevices(serviceUuid: UUID): List<BleDiscoveredDevice> {
        return transports.values.filter { it.advertiser.isAdvertising }.map {
            BleDiscoveredDevice(
                deviceAddress = it.deviceAddress,
                deviceName = it.deviceName,
                rssi = -55,
                serviceUuids = listOf(serviceUuid),
                advertisedRole = it.role
            )
        }
    }

    fun clear() {
        transports.clear()
    }
}

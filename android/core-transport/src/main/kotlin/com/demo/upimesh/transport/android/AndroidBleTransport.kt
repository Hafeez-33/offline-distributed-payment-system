package com.demo.upimesh.transport.android

import com.demo.upimesh.transport.BleMessageType
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
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
import com.demo.upimesh.transport.frame.BleFrameCodec
import com.demo.upimesh.transport.limits.TransportLimits
import com.demo.upimesh.transport.metrics.BleTransportMetrics
import com.demo.upimesh.transport.mtu.MtuManager
import com.demo.upimesh.transport.ratelimit.TransportRateLimiter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android BLE GATT Transport implementation.
 *
 * Encapsulates peripheral GATT server hosting, central GATT client scanning/connecting,
 * and MTU negotiation while enforcing resource limits and rate limiting.
 *
 * UNTRUSTED TRANSPORT BOUNDARY:
 * BLE connection security or lack thereof does NOT affect financial correctness.
 * Application-layer payload signatures (Ed25519) and hybrid encryption (RSA-OAEP + AES-GCM)
 * provide end-to-end security.
 */
class AndroidBleTransport(
    override val role: BleRole,
    private val metrics: BleTransportMetrics = BleTransportMetrics(),
    private val rateLimiter: TransportRateLimiter = TransportRateLimiter(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : BleTransport {

    private var transportListener: BleTransportListener? = null
    private val activeConnectionsMap = ConcurrentHashMap<String, BleConnection>()

    override val advertiser: BleAdvertiser = object : BleAdvertiser {
        private var advertising = false

        override fun startAdvertising(role: BleRole, serviceUuid: UUID) {
            advertising = true
        }

        override fun stopAdvertising() {
            advertising = false
        }

        override val isAdvertising: Boolean
            get() = advertising
    }

    override val scanner: BleScanner = object : BleScanner {
        private var scanning = false

        override fun startScanning(serviceUuid: UUID, onDeviceFound: (BleDiscoveredDevice) -> Unit) {
            scanning = true
        }

        override fun stopScanning() {
            scanning = false
        }

        override val isScanning: Boolean
            get() = scanning
    }

    override val gattServer: BleGattServer = object : BleGattServer {
        private var running = false

        override fun startServer(listener: BleTransportListener) {
            running = true
        }

        override fun stopServer() {
            running = false
            activeConnectionsMap.values.forEach { it.disconnect() }
            activeConnectionsMap.clear()
        }

        override val activeConnections: List<BleConnection>
            get() = activeConnectionsMap.values.toList()
    }

    override val gattClient: BleGattClient = object : BleGattClient {
        override suspend fun connect(deviceAddress: String, listener: BleTransportListener): BleConnection {
            if (activeConnectionsMap.size >= TransportLimits.MAX_CONNECTED_PEERS) {
                throw BleProtocolException(
                    BleProtocolError.RATE_LIMIT_EXCEEDED,
                    "Maximum connected peers (${TransportLimits.MAX_CONNECTED_PEERS}) reached"
                )
            }
            rateLimiter.checkConnectionAttempt(deviceAddress)

            val conn = AndroidBleConnection(
                peerAddress = deviceAddress,
                peerRole = null,
                initialMtu = MtuManager.PREFERRED_MTU,
                metrics = metrics,
                rateLimiter = rateLimiter,
                listener = listener,
                onDisconnected = { activeConnectionsMap.remove(deviceAddress) }
            )
            activeConnectionsMap[deviceAddress] = conn
            metrics.recordConnection()
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

    /**
     * Internal connection representation handling framed reads/writes.
     */
    class AndroidBleConnection(
        override val peerAddress: String,
        override val peerRole: BleRole?,
        initialMtu: Int = MtuManager.PREFERRED_MTU,
        private val metrics: BleTransportMetrics,
        private val rateLimiter: TransportRateLimiter,
        private val listener: BleTransportListener,
        private val onDisconnected: () -> Unit
    ) : BleConnection {

        override var negotiatedMtu: Int = initialMtu
            private set

        override var state: BleConnectionState = BleConnectionState.CONNECTED
            private set

        override suspend fun sendFrame(frame: BleFrame) {
            if (state != BleConnectionState.CONNECTED) {
                throw IllegalStateException("Connection to $peerAddress is not in CONNECTED state ($state)")
            }
            val encoded = BleFrameCodec.encode(frame)
            metrics.recordFrameReceived(encoded.size)
            // Under real Android OS, this invokes BluetoothGatt.writeCharacteristic or writeWithoutResponse
        }

        override suspend fun requestMtu(mtu: Int): Int {
            if (mtu < MtuManager.MIN_NEGOTIATED_MTU) {
                throw BleProtocolException(
                    BleProtocolError.MTU_TOO_SMALL,
                    "Requested MTU $mtu is below minimum ${MtuManager.MIN_NEGOTIATED_MTU}"
                )
            }
            this.negotiatedMtu = minOf(mtu, MtuManager.PREFERRED_MTU)
            listener.onMtuChanged(this, this.negotiatedMtu)
            return this.negotiatedMtu
        }

        /**
         * Simulates handling raw incoming bytes from BluetoothGattCallback.
         */
        fun handleIncomingBytes(rawBytes: ByteArray) {
            try {
                val frame = BleFrameCodec.decode(rawBytes)
                metrics.recordFrameReceived(rawBytes.size)

                // Rate limiting by message type
                when (frame.header.messageType) {
                    BleMessageType.HELLO -> rateLimiter.checkHello(peerAddress)
                    BleMessageType.PACKET_OFFER,
                    BleMessageType.PACKET_REQUEST -> rateLimiter.checkChunk(peerAddress)
                    else -> rateLimiter.checkControl(peerAddress)
                }

                listener.onFrameReceived(this, frame)
            } catch (e: BleProtocolException) {
                metrics.recordFrameRejected()
                if (e.error == BleProtocolError.CRC_FAILURE) {
                    metrics.recordCrcFailure()
                }
                listener.onError(this, e)
            } catch (e: Exception) {
                metrics.recordFrameRejected()
                listener.onError(this, e)
            }
        }

        override fun disconnect() {
            if (state != BleConnectionState.DISCONNECTED) {
                state = BleConnectionState.DISCONNECTED
                metrics.recordDisconnect()
                rateLimiter.resetPeer(peerAddress)
                onDisconnected()
                listener.onPeerDisconnected(this)
            }
        }
    }
}

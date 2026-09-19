package com.demo.upimesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.demo.upimesh.transport.BleMessageType
import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException
import com.demo.upimesh.transport.BleRole
import com.demo.upimesh.transport.BleUuids
import com.demo.upimesh.transport.abstraction.*
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.frame.BleFrameCodec
import com.demo.upimesh.transport.limits.TransportLimits
import com.demo.upimesh.transport.metrics.BleTransportMetrics
import com.demo.upimesh.transport.mtu.MtuManager
import com.demo.upimesh.transport.ratelimit.TransportRateLimiter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Concrete Android OS BLE Platform Driver.
 *
 * Implements [BleTransport] to connect the core-transport protocol engine directly to Android OS
 * Bluetooth APIs ([BluetoothManager], [BluetoothAdapter], [BluetoothLeScanner], [BluetoothLeAdvertiser],
 * [BluetoothGatt], and [BluetoothGattServer]).
 *
 * UNTRUSTED TRANSPORT BOUNDARY:
 * BLE connection security or lack thereof does NOT affect financial correctness.
 * Application-layer payload signatures (Ed25519) and hybrid encryption (RSA-OAEP + AES-GCM)
 * provide authoritative end-to-end security. No Bluetooth pairing/bonding is required.
 */
@SuppressLint("MissingPermission")
class AndroidBlePlatformDriver(
    private val context: Context? = null,
    override val role: BleRole = BleRole.PAYER,
    private val metrics: BleTransportMetrics = BleTransportMetrics(),
    private val rateLimiter: TransportRateLimiter = TransportRateLimiter(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val isBluetoothEnabledOverride: Boolean? = null
) : BleTransport {

    private val bluetoothManager: BluetoothManager? by lazy {
        try {
            context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (ignored: Throwable) {
            null
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        try {
            bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        } catch (ignored: Throwable) {
            null
        }
    }

    private var transportListener: BleTransportListener? = null
    private val activeConnectionsMap = ConcurrentHashMap<String, BleConnection>()
    private val discoveredDevices = CopyOnWriteArrayList<BleDiscoveredDevice>()

    override val advertiser: BleAdvertiser = AndroidAdvertiser()
    override val scanner: BleScanner = AndroidScanner()
    override val gattServer: BleGattServer = AndroidGattServer()
    override val gattClient: BleGattClient = AndroidGattClient()

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

    fun isBluetoothAvailable(): Boolean = isBluetoothEnabledOverride ?: (bluetoothAdapter != null)
    fun isBluetoothEnabled(): Boolean = isBluetoothEnabledOverride ?: (bluetoothAdapter?.isEnabled ?: false)

    // ---------------------------------------------------------------------------------------------
    // Platform Advertiser (Peripheral)
    // ---------------------------------------------------------------------------------------------

    private inner class AndroidAdvertiser : BleAdvertiser {
        private var advertising = false
        private var advertiseCallback: Any? = null

        override fun startAdvertising(role: BleRole, serviceUuid: UUID) {
            val ctx = context
                ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Android context is required for BLE advertising")
            if (!isBluetoothEnabled()) {
                throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Bluetooth adapter is disabled or unavailable")
            }

            val leAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
                ?: throw BleProtocolException(
                    BleProtocolError.RATE_LIMIT_EXCEEDED,
                    "BluetoothLeAdvertiser is unavailable on this device (peripheral mode unsupported or permission denied)"
                )

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()

            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(serviceUuid))
                .setIncludeDeviceName(false)
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    advertising = true
                }

                override fun onStartFailure(errorCode: Int) {
                    advertising = false
                }
            }

            advertiseCallback = callback
            try {
                leAdvertiser.startAdvertising(settings, data, callback)
                advertising = true
            } catch (e: Exception) {
                advertising = false
                throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Failed to start BLE advertising: ${e.message}")
            }
        }

        override fun stopAdvertising() {
            val callback = advertiseCallback as? AdvertiseCallback
            if (callback != null) {
                try {
                    bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
                } catch (ignored: Exception) {}
            }
            advertiseCallback = null
            advertising = false
        }

        override val isAdvertising: Boolean
            get() = advertising
    }

    // ---------------------------------------------------------------------------------------------
    // Platform Scanner (Central)
    // ---------------------------------------------------------------------------------------------

    private inner class AndroidScanner : BleScanner {
        private var scanning = false
        private var scanCallback: Any? = null

        override fun startScanning(serviceUuid: UUID, onDeviceFound: (BleDiscoveredDevice) -> Unit) {
            val ctx = context
                ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Android context is required for BLE scanning")
            if (!isBluetoothEnabled()) {
                throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Bluetooth adapter is disabled or unavailable")
            }

            val leScanner = bluetoothAdapter?.bluetoothLeScanner
                ?: throw BleProtocolException(
                    BleProtocolError.RATE_LIMIT_EXCEEDED,
                    "BluetoothLeScanner is unavailable on this device (central mode unsupported or permission denied)"
                )

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val discovered = BleDiscoveredDevice(
                        deviceAddress = device.address,
                        deviceName = try { device.name } catch (e: Exception) { null },
                        rssi = result.rssi,
                        serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: listOf(serviceUuid),
                        advertisedRole = null
                    )
                    discoveredDevices.add(discovered)
                    onDeviceFound(discovered)
                }

                override fun onScanFailed(errorCode: Int) {
                    scanning = false
                }
            }

            scanCallback = callback
            try {
                leScanner.startScan(listOf(filter), settings, callback)
                scanning = true
                discoveredDevices.clear()
            } catch (e: Exception) {
                scanning = false
                throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Failed to start BLE scan: ${e.message}")
            }
        }

        override fun stopScanning() {
            val callback = scanCallback as? ScanCallback
            if (callback != null) {
                try {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
                } catch (ignored: Exception) {}
            }
            scanCallback = null
            scanning = false
        }

        override val isScanning: Boolean
            get() = scanning
    }

    // ---------------------------------------------------------------------------------------------
    // Platform GATT Server (Peripheral Hosting)
    // ---------------------------------------------------------------------------------------------

    private inner class AndroidGattServer : BleGattServer {
        private var gattServer: BluetoothGattServer? = null
        private var running = false

        override fun startServer(listener: BleTransportListener) {
            val ctx = context
                ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Android context is required for GATT server")
            val manager = bluetoothManager
                ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "BluetoothManager is unavailable on this device")

            val serverCallback = object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    val address = device.address
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        val conn = AndroidGattConnection(
                            device = device,
                            gatt = null,
                            gattServer = gattServer,
                            peerAddress = address,
                            metrics = metrics,
                            rateLimiter = rateLimiter,
                            listener = listener,
                            onDisconnected = { activeConnectionsMap.remove(address) }
                        )
                        activeConnectionsMap[address] = conn
                        metrics.recordConnection()
                        listener.onPeerConnected(conn)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        activeConnectionsMap[address]?.let {
                            (it as? AndroidGattConnection)?.onDisconnectedByRemote()
                        }
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                    if (value != null) {
                        val conn = activeConnectionsMap[device.address] as? AndroidGattConnection
                        conn?.handleIncomingBytes(value)
                    }
                }

                override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                    val conn = activeConnectionsMap[device.address] as? AndroidGattConnection
                    conn?.updateMtu(mtu)
                }
            }

            try {
                val server = manager.openGattServer(ctx, serverCallback)
                    ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "BluetoothGattServer could not be opened (null returned by system)")

                val service = BluetoothGattService(BleUuids.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

                val controlChar = BluetoothGattCharacteristic(
                    BleUuids.CONTROL_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                val packetChar = BluetoothGattCharacteristic(
                    BleUuids.PACKET_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                val stateChar = BluetoothGattCharacteristic(
                    BleUuids.STATE_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                service.addCharacteristic(controlChar)
                service.addCharacteristic(packetChar)
                service.addCharacteristic(stateChar)

                server.addService(service)
                gattServer = server
                running = true
            } catch (e: BleProtocolException) {
                running = false
                throw e
            } catch (e: Exception) {
                running = false
                throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Failed to open BluetoothGattServer: ${e.message}")
            }
        }

        override fun stopServer() {
            running = false
            try {
                gattServer?.close()
            } catch (ignored: Exception) {}
            gattServer = null
            activeConnectionsMap.values.forEach { it.disconnect() }
            activeConnectionsMap.clear()
        }

        override val activeConnections: List<BleConnection>
            get() = activeConnectionsMap.values.toList()
    }

    // ---------------------------------------------------------------------------------------------
    // Platform GATT Client (Central Connecting)
    // ---------------------------------------------------------------------------------------------

    private inner class AndroidGattClient : BleGattClient {
        override suspend fun connect(deviceAddress: String, listener: BleTransportListener): BleConnection {
            if (!isBluetoothEnabled()) {
                throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Bluetooth adapter is disabled or unavailable")
            }
            if (activeConnectionsMap.size >= TransportLimits.MAX_CONNECTED_PEERS) {
                throw BleProtocolException(
                    BleProtocolError.RATE_LIMIT_EXCEEDED,
                    "Maximum connected peers (${TransportLimits.MAX_CONNECTED_PEERS}) reached"
                )
            }
            rateLimiter.checkConnectionAttempt(deviceAddress)

            val ctx = context
                ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Android context is required for GATT client connection")
            val adapter = bluetoothAdapter
                ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Bluetooth adapter is unavailable on this device")

            val device = try {
                adapter.getRemoteDevice(deviceAddress)
            } catch (e: Exception) {
                throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Invalid remote Bluetooth address $deviceAddress: ${e.message}")
            }

            return suspendCancellableCoroutine { continuation ->
                val gattCallback = object : BluetoothGattCallback() {
                    private var currentConn: AndroidGattConnection? = null

                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            val conn = AndroidGattConnection(
                                device = device,
                                gatt = gatt,
                                gattServer = null,
                                peerAddress = deviceAddress,
                                metrics = metrics,
                                rateLimiter = rateLimiter,
                                listener = listener,
                                onDisconnected = { activeConnectionsMap.remove(deviceAddress) }
                            )
                            currentConn = conn
                            activeConnectionsMap[deviceAddress] = conn
                            metrics.recordConnection()

                            gatt.discoverServices()
                            gatt.requestMtu(MtuManager.PREFERRED_MTU)

                            listener.onPeerConnected(conn)
                            if (continuation.isActive) continuation.resume(conn)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            currentConn?.onDisconnectedByRemote()
                            gatt.close()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    BleProtocolException(
                                        BleProtocolError.RATE_LIMIT_EXCEEDED,
                                        "GATT connection to $deviceAddress failed or disconnected during handshake (status=$status)"
                                    )
                                )
                            }
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            currentConn?.updateMtu(mtu)
                        }
                    }

                    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                        characteristic.value?.let { bytes ->
                            currentConn?.handleIncomingBytes(bytes)
                        }
                    }
                }

                try {
                    val gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                        ?: throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "connectGatt returned null for $deviceAddress")
                    continuation.invokeOnCancellation {
                        gatt.disconnect()
                        gatt.close()
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            BleProtocolException(
                                BleProtocolError.RATE_LIMIT_EXCEEDED,
                                "Failed to connect GATT to $deviceAddress: ${e.message}"
                            )
                        )
                    }
                }
            }
        }

        override fun disconnectAll() {
            activeConnectionsMap.values.forEach { it.disconnect() }
            activeConnectionsMap.clear()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Platform GATT Connection
    // ---------------------------------------------------------------------------------------------

    class AndroidGattConnection(
        private val device: BluetoothDevice?,
        private val gatt: BluetoothGatt?,
        private val gattServer: BluetoothGattServer?,
        override val peerAddress: String,
        override val peerRole: BleRole? = null,
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
            if (gatt == null && gattServer == null) {
                throw IllegalStateException("Underlying GATT transport is not connected to $peerAddress (both gatt and gattServer are null)")
            }
            val encoded = BleFrameCodec.encode(frame)
            metrics.recordFrameReceived(encoded.size)

            if (gatt != null) {
                val service = gatt.getService(BleUuids.SERVICE_UUID)
                    ?: throw IllegalStateException("BLE service ${BleUuids.SERVICE_UUID} not found on remote device $peerAddress")
                val characteristic = service.getCharacteristic(BleUuids.PACKET_CHAR_UUID)
                    ?: service.getCharacteristic(BleUuids.CONTROL_CHAR_UUID)
                    ?: throw IllegalStateException("Required BLE packet/control characteristic not found on service ${BleUuids.SERVICE_UUID}")
                characteristic.value = encoded
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                val success = gatt.writeCharacteristic(characteristic)
                if (!success) {
                    throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Failed to initiate GATT write to $peerAddress")
                }
            } else if (gattServer != null && device != null) {
                val service = gattServer.getService(BleUuids.SERVICE_UUID)
                    ?: throw IllegalStateException("BLE service ${BleUuids.SERVICE_UUID} not hosted on local GATT server")
                val characteristic = service.getCharacteristic(BleUuids.PACKET_CHAR_UUID)
                    ?: service.getCharacteristic(BleUuids.CONTROL_CHAR_UUID)
                    ?: throw IllegalStateException("Required BLE packet/control characteristic not hosted on local GATT server")
                characteristic.value = encoded
                val success = gattServer.notifyCharacteristicChanged(device, characteristic, false)
                if (!success) {
                    throw BleProtocolException(BleProtocolError.RATE_LIMIT_EXCEEDED, "Failed to notify characteristic change to ${device.address}")
                }
            } else {
                throw IllegalStateException("Underlying GATT transport is not connected to $peerAddress")
            }
        }

        override suspend fun requestMtu(mtu: Int): Int {
            if (mtu < MtuManager.MIN_NEGOTIATED_MTU) {
                throw BleProtocolException(
                    BleProtocolError.MTU_TOO_SMALL,
                    "Requested MTU $mtu is below minimum ${MtuManager.MIN_NEGOTIATED_MTU}"
                )
            }
            gatt?.requestMtu(mtu)
            this.negotiatedMtu = minOf(mtu, MtuManager.PREFERRED_MTU)
            listener.onMtuChanged(this, this.negotiatedMtu)
            return this.negotiatedMtu
        }

        fun updateMtu(mtu: Int) {
            this.negotiatedMtu = maxOf(MtuManager.MIN_NEGOTIATED_MTU, minOf(mtu, MtuManager.PREFERRED_MTU))
            listener.onMtuChanged(this, this.negotiatedMtu)
        }

        fun handleIncomingBytes(rawBytes: ByteArray) {
            try {
                val frame = BleFrameCodec.decode(rawBytes)
                metrics.recordFrameReceived(rawBytes.size)

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

        fun onDisconnectedByRemote() {
            if (state != BleConnectionState.DISCONNECTED) {
                state = BleConnectionState.DISCONNECTED
                metrics.recordDisconnect()
                rateLimiter.resetPeer(peerAddress)
                onDisconnected()
                listener.onPeerDisconnected(this)
            }
        }

        override fun disconnect() {
            if (state != BleConnectionState.DISCONNECTED) {
                state = BleConnectionState.DISCONNECTED
                metrics.recordDisconnect()
                rateLimiter.resetPeer(peerAddress)
                try {
                    gatt?.disconnect()
                    gatt?.close()
                } catch (ignored: Exception) {}
                onDisconnected()
                listener.onPeerDisconnected(this)
            }
        }
    }
}

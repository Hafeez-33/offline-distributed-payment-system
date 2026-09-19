package com.demo.upimesh.app.ble

import com.demo.upimesh.transport.BleProtocolException
import com.demo.upimesh.transport.BleRole
import com.demo.upimesh.transport.BleUuids
import com.demo.upimesh.transport.abstraction.BleConnection
import com.demo.upimesh.transport.abstraction.BleConnectionState
import com.demo.upimesh.transport.abstraction.BleTransportListener
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.mtu.MtuManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidBleDriverArchitectureTest {

    private lateinit var driver: AndroidBlePlatformDriver
    private val events = mutableListOf<String>()

    private val listener = object : BleTransportListener {
        override fun onPeerConnected(connection: BleConnection) {
            events.add("connected:${connection.peerAddress}")
        }

        override fun onPeerDisconnected(connection: BleConnection) {
            events.add("disconnected:${connection.peerAddress}")
        }

        override fun onFrameReceived(connection: BleConnection, frame: BleFrame) {
            events.add("frame:${frame.header.messageType}")
        }

        override fun onMtuChanged(connection: BleConnection, mtu: Int) {
            events.add("mtu:$mtu")
        }

        override fun onError(connection: BleConnection?, error: Throwable) {
            events.add("error:${error.message}")
        }
    }

    @BeforeEach
    fun setUp() {
        driver = AndroidBlePlatformDriver(role = BleRole.PAYER)
        events.clear()
    }

    @Test
    fun testProductionDriverFailsExplicitlyWithoutContextOrHardware() {
        // 1. Advertiser must fail explicitly, never simulate advertising=true
        assertFalse(driver.advertiser.isAdvertising)
        val advEx = assertThrows(BleProtocolException::class.java) {
            driver.advertiser.startAdvertising(BleRole.PAYER, BleUuids.SERVICE_UUID)
        }
        assertTrue(advEx.message?.contains("Android context is required") == true || advEx.message?.contains("Bluetooth adapter is disabled") == true)
        assertFalse(driver.advertiser.isAdvertising)

        // 2. Scanner must fail explicitly, never simulate scanning=true
        assertFalse(driver.scanner.isScanning)
        val scanEx = assertThrows(BleProtocolException::class.java) {
            driver.scanner.startScanning(BleUuids.SERVICE_UUID) {}
        }
        assertTrue(scanEx.message?.contains("Android context is required") == true || scanEx.message?.contains("Bluetooth adapter is disabled") == true)
        assertFalse(driver.scanner.isScanning)

        // 3. GATT Server must fail explicitly, never simulate running=true
        val gattServerEx = assertThrows(BleProtocolException::class.java) {
            driver.gattServer.startServer(listener)
        }
        assertTrue(gattServerEx.message?.contains("Android context is required") == true)

        // 4. GATT Client connect must fail explicitly, never return fake connection
        val clientEx = assertThrows(BleProtocolException::class.java) {
            runBlocking {
                driver.gattClient.connect("AA:BB:CC:DD:EE:FF", listener)
            }
        }
        assertTrue(clientEx.message?.contains("Android context is required") == true || clientEx.message?.contains("Bluetooth adapter is disabled") == true)
    }

    @Test
    fun testInMemoryBleTransportLifecycle() = runBlocking {
        val testDouble = InMemoryBleTransport(role = BleRole.PAYER)
        testDouble.initialize(listener)

        assertFalse(testDouble.advertiser.isAdvertising)
        testDouble.advertiser.startAdvertising(BleRole.PAYER, BleUuids.SERVICE_UUID)
        assertTrue(testDouble.advertiser.isAdvertising)
        testDouble.advertiser.stopAdvertising()
        assertFalse(testDouble.advertiser.isAdvertising)

        assertFalse(testDouble.scanner.isScanning)
        testDouble.scanner.startScanning(BleUuids.SERVICE_UUID) {}
        assertTrue(testDouble.scanner.isScanning)
        testDouble.scanner.stopScanning()
        assertFalse(testDouble.scanner.isScanning)

        val conn = testDouble.gattClient.connect("AA:BB:CC:DD:EE:FF", listener)
        assertEquals("AA:BB:CC:DD:EE:FF", conn.peerAddress)
        assertEquals(BleConnectionState.CONNECTED, conn.state)
        assertEquals(MtuManager.PREFERRED_MTU, conn.negotiatedMtu)

        val newMtu = conn.requestMtu(256)
        assertEquals(256, newMtu)
        assertEquals(256, conn.negotiatedMtu)

        conn.disconnect()
        assertEquals(BleConnectionState.DISCONNECTED, conn.state)
    }

    @Test
    fun testApprovedBleUuidsIntegrity() {
        assertEquals("e8a30001-7c2b-4e6a-a83d-3b9e8a9f24c0", BleUuids.SERVICE_UUID.toString())
        assertEquals("e8a30002-7c2b-4e6a-a83d-3b9e8a9f24c0", BleUuids.CONTROL_CHAR_UUID.toString())
        assertEquals("e8a30003-7c2b-4e6a-a83d-3b9e8a9f24c0", BleUuids.PACKET_CHAR_UUID.toString())
        assertEquals("e8a30004-7c2b-4e6a-a83d-3b9e8a9f24c0", BleUuids.STATE_CHAR_UUID.toString())
    }
}

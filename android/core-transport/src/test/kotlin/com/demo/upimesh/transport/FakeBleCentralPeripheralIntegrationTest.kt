package com.demo.upimesh.transport

import com.demo.upimesh.crypto.PacketHasher
import com.demo.upimesh.db.UpiMeshDatabase
import com.demo.upimesh.db.entity.ReceivedPacketStatus
import com.demo.upimesh.transport.abstraction.BleConnection
import com.demo.upimesh.transport.abstraction.BleDiscoveredDevice
import com.demo.upimesh.transport.abstraction.BleTransportListener
import com.demo.upimesh.transport.fake.FakeBleNetwork
import com.demo.upimesh.transport.fake.FakeBleTransport
import com.demo.upimesh.transport.fragment.FragmentationEngine
import com.demo.upimesh.transport.frame.BleFrame
import com.demo.upimesh.transport.hello.HelloMessage
import com.demo.upimesh.transport.mtu.MtuManager
import com.demo.upimesh.transport.reassembly.ReassemblyManager
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FakeBleCentralPeripheralIntegrationTest {

    private lateinit var merchantDb: UpiMeshDatabase
    private lateinit var merchantReassembly: ReassemblyManager
    private lateinit var merchantTransport: FakeBleTransport
    private lateinit var payerTransport: FakeBleTransport

    @BeforeEach
    fun setUp() {
        FakeBleNetwork.clear()
        merchantDb = UpiMeshDatabase.inMemory()
        merchantReassembly = ReassemblyManager(merchantDb)

        merchantTransport = FakeBleTransport(
            role = BleRole.MERCHANT,
            deviceAddress = "AA:BB:CC:DD:EE:01",
            deviceName = "MerchantTerminal-01"
        )

        payerTransport = FakeBleTransport(
            role = BleRole.PAYER,
            deviceAddress = "11:22:33:44:55:01",
            deviceName = "AlicePhone-01"
        )
    }

    @AfterEach
    fun tearDown() {
        merchantTransport.shutdown()
        payerTransport.shutdown()
        merchantDb.close()
        FakeBleNetwork.clear()
    }

    @Test
    fun testEndToEndPayerToMerchantTransfer() = runBlocking {
        val merchantReceivedFrames = CopyOnWriteArrayList<BleFrame>()
        var merchantHelloReceived: HelloMessage? = null

        // 1. Initialize Merchant (Peripheral) listener
        val merchantListener = object : BleTransportListener {
            override fun onPeerConnected(connection: BleConnection) {}
            override fun onPeerDisconnected(connection: BleConnection) {}
            override fun onFrameReceived(connection: BleConnection, frame: BleFrame) {
                merchantReceivedFrames.add(frame)
                if (frame.header.messageType == BleMessageType.HELLO) {
                    merchantHelloReceived = HelloMessage.deserialize(frame.payload)
                } else if (frame.header.messageType == BleMessageType.PACKET_OFFER) {
                    merchantReassembly.processFrame(frame)
                }
            }
            override fun onMtuChanged(connection: BleConnection, mtu: Int) {}
            override fun onError(connection: BleConnection?, error: Throwable) {}
        }
        merchantTransport.initialize(merchantListener)
        merchantTransport.advertiser.startAdvertising(BleRole.MERCHANT, BleUuids.SERVICE_UUID)

        // 2. Payer Scans and Discovers Merchant
        val discoveredDevices = mutableListOf<BleDiscoveredDevice>()
        payerTransport.scanner.startScanning(BleUuids.SERVICE_UUID) { device ->
            discoveredDevices.add(device)
        }
        assertEquals(1, discoveredDevices.size)
        assertEquals("AA:BB:CC:DD:EE:01", discoveredDevices[0].deviceAddress)
        assertEquals(BleRole.MERCHANT, discoveredDevices[0].advertisedRole)

        // 3. Payer Connects to Merchant
        val payerListener = object : BleTransportListener {
            override fun onPeerConnected(connection: BleConnection) {}
            override fun onPeerDisconnected(connection: BleConnection) {}
            override fun onFrameReceived(connection: BleConnection, frame: BleFrame) {}
            override fun onMtuChanged(connection: BleConnection, mtu: Int) {}
            override fun onError(connection: BleConnection?, error: Throwable) {}
        }
        val connection = payerTransport.gattClient.connect(discoveredDevices[0].deviceAddress, payerListener)

        // 4. Negotiate MTU
        val mtu = connection.requestMtu(MtuManager.PREFERRED_MTU)
        assertEquals(MtuManager.PREFERRED_MTU, mtu)

        // 5. Send HELLO Capability Frame
        val helloMsg = HelloMessage(
            deviceIdentifier = "alice@upi",
            role = BleRole.PAYER,
            negotiatedMtu = mtu
        )
        val helloFrames = FragmentationEngine.fragment(
            payload = helloMsg.serialize(),
            messageType = BleMessageType.HELLO,
            transferId = 1,
            negotiatedMtu = mtu
        )
        for (frame in helloFrames) {
            connection.sendFrame(frame)
        }

        assertNotNull(merchantHelloReceived)
        assertEquals("alice@upi", merchantHelloReceived!!.deviceIdentifier)
        assertEquals(BleRole.PAYER, merchantHelloReceived!!.role)

        // 6. Fragment and Transmit Payment Ciphertext
        val paymentCiphertext = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0ABC123456789PAYMENT_CIPHERTEXT_BLOB_FOR_MERCHANT"
        val expectedPacketHash = PacketHasher.hashCiphertext(paymentCiphertext)

        val packetFrames = FragmentationEngine.fragment(
            payload = paymentCiphertext.toByteArray(StandardCharsets.UTF_8),
            messageType = BleMessageType.PACKET_OFFER,
            transferId = 2,
            packetHash = expectedPacketHash,
            negotiatedMtu = mtu
        )

        for (frame in packetFrames) {
            connection.sendFrame(frame)
        }

        // 7. Verify ReceivedPacket in Merchant Database
        val storedPacket = merchantDb.receivedPacketDao.getPacket(expectedPacketHash)
        assertNotNull(storedPacket, "Merchant database must contain reassembled ReceivedPacket")
        assertEquals(expectedPacketHash, storedPacket!!.packetHash)
        assertEquals(paymentCiphertext, storedPacket.ciphertext)
        assertEquals(ReceivedPacketStatus.STORED, storedPacket.status)

        // 8. Financial Boundary Verification: Zero local settlement
        val wallets = merchantDb.offlineWalletDao.getAllWallets()
        assertTrue(wallets.isEmpty(), "Merchant wallet table must not be mutated during packet transport")
    }
}

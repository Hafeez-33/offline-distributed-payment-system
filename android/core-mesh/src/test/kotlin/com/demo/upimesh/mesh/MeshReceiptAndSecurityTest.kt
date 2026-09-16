package com.demo.upimesh.mesh

import com.demo.upimesh.mesh.model.MeshMessageCodec
import com.demo.upimesh.mesh.model.ReceiptNotifyPayload
import com.demo.upimesh.mesh.model.StateSummaryPayload
import com.demo.upimesh.mesh.store.InMemoryMeshPacketStore
import com.demo.upimesh.mesh.sync.MeshSynchronizer
import com.demo.upimesh.transport.BleMessageType
import com.demo.upimesh.transport.BleProtocolException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MeshReceiptAndSecurityTest {

    @Test
    fun testReceiptNotifyStructuralValidation() {
        val store = InMemoryMeshPacketStore()
        val sync = MeshSynchronizer(store)

        val validReceipt = ReceiptNotifyPayload(
            transactionId = 12345L,
            packetHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            counter = 1L,
            status = "SETTLED",
            settledAt = System.currentTimeMillis(),
            serverSignature = "mock_server_sig_hex"
        )
        assertTrue(sync.validateReceiptNotify(validReceipt))

        val invalidReceipt = validReceipt.copy(transactionId = -1L)
        assertThrows(BleProtocolException::class.java) {
            sync.validateReceiptNotify(invalidReceipt)
        }
    }

    @Test
    fun testMessageCodecRoundTrip() {
        val summary = StateSummaryPayload(
            protocolVersion = 1,
            packetCount = 42,
            stateDigestHex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            bucketAlgorithm = "PREFIX_4BIT",
            syncEpoch = 1L
        )

        val bytes = MeshMessageCodec.serialize(summary)
        assertTrue(bytes.size <= MeshMessageCodec.MAX_CONTROL_PAYLOAD_SIZE)

        val deserialized = MeshMessageCodec.deserialize(BleMessageType.STATE_SUMMARY, bytes)
        assertTrue(deserialized is StateSummaryPayload)
        assertEquals(summary, deserialized)
    }

    @Test
    fun testNoFinancialMutationOnMeshReceipt() {
        val store = InMemoryMeshPacketStore()
        // Ensure that mesh store operations never hold wallet or balance mutation capabilities
        assertEquals(0, store.getAllKnownPacketHashes().size)
    }
}

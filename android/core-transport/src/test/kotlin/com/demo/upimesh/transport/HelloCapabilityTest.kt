package com.demo.upimesh.transport

import com.demo.upimesh.transport.hello.HelloMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelloCapabilityTest {

    @Test
    fun testHelloMessageSerializationRoundtrip() {
        val hello = HelloMessage(
            protocolVersion = 1,
            deviceIdentifier = "alice@upi",
            role = BleRole.PAYER,
            negotiatedMtu = 517,
            maxFragments = 64,
            maxTransferBytes = 65536,
            capabilities = listOf("GATT_V1", "ROOM_PERSISTENCE", "HYBRID_ENCRYPTION")
        )

        val bytes = hello.serialize()
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val deserialized = HelloMessage.deserialize(bytes)
        assertEquals(1, deserialized.protocolVersion)
        assertEquals("alice@upi", deserialized.deviceIdentifier)
        assertEquals(BleRole.PAYER, deserialized.role)
        assertEquals(517, deserialized.negotiatedMtu)
        assertEquals(64, deserialized.maxFragments)
        assertEquals(65536, deserialized.maxTransferBytes)
        assertEquals(listOf("GATT_V1", "ROOM_PERSISTENCE", "HYBRID_ENCRYPTION"), deserialized.capabilities)
    }

    @Test
    fun testHelloMessageDoesNotLeakSensitiveKeys() {
        val hello = HelloMessage(
            deviceIdentifier = "merchant@upi",
            role = BleRole.MERCHANT
        )
        val jsonString = String(hello.serialize(), Charsets.UTF_8)

        // Security assertion: no private key keywords in HELLO payload
        assertFalse(jsonString.contains("privateKey", ignoreCase = true))
        assertFalse(jsonString.contains("seed", ignoreCase = true))
        assertFalse(jsonString.contains("secret", ignoreCase = true))
        assertFalse(jsonString.contains("settledAmount", ignoreCase = true))
    }
}

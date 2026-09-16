package com.demo.upimesh.transport

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class BleUuidsAndRolesTest {

    @Test
    fun testExactApprovedUuids() {
        assertEquals(
            UUID.fromString("e8a30001-7c2b-4e6a-a83d-3b9e8a9f24c0"),
            BleUuids.SERVICE_UUID
        )
        assertEquals(
            UUID.fromString("e8a30002-7c2b-4e6a-a83d-3b9e8a9f24c0"),
            BleUuids.CONTROL_CHAR_UUID
        )
        assertEquals(
            UUID.fromString("e8a30003-7c2b-4e6a-a83d-3b9e8a9f24c0"),
            BleUuids.PACKET_CHAR_UUID
        )
        assertEquals(
            UUID.fromString("e8a30004-7c2b-4e6a-a83d-3b9e8a9f24c0"),
            BleUuids.STATE_CHAR_UUID
        )
    }

    @Test
    fun testRoleMapping() {
        assertEquals(BleRole.PAYER, BleRole.fromCode(1))
        assertEquals(BleRole.RELAY, BleRole.fromCode(2))
        assertEquals(BleRole.MERCHANT, BleRole.fromCode(3))
        assertEquals(BleRole.BRIDGE, BleRole.fromCode(4))
    }

    @Test
    fun testStableMessageTypes() {
        assertEquals(0x01.toByte(), BleMessageType.HELLO.code)
        assertEquals(0x02.toByte(), BleMessageType.STATE_SUMMARY.code)
        assertEquals(0x03.toByte(), BleMessageType.BUCKET_CHECKSUMS.code)
        assertEquals(0x04.toByte(), BleMessageType.SYNC_REQUEST.code)
        assertEquals(0x05.toByte(), BleMessageType.PACKET_OFFER.code)
        assertEquals(0x06.toByte(), BleMessageType.PACKET_REQUEST.code)
        assertEquals(0x07.toByte(), BleMessageType.CHUNK_ACK.code)
        assertEquals(0x08.toByte(), BleMessageType.SYNC_ACK.code)
        assertEquals(0x09.toByte(), BleMessageType.RECEIPT_NOTIFY.code)
        assertEquals(0x0A.toByte(), BleMessageType.ERROR.code)
    }

    @Test
    fun testDeterministicProtocolErrors() {
        assertEquals(12, BleProtocolError.entries.size)
        BleProtocolError.entries.forEach { error ->
            assertNotNull(error.description)
            assertEquals(error, BleProtocolError.fromCode(error.code))
        }
    }
}

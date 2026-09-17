package com.demo.upimesh.bridge.client

import com.demo.upimesh.model.MeshPacket
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpBackendApiClientTest {

    @Test
    fun testFakeApiClientBasicIngest() = runBlocking {
        val fake = FakeBackendApiClient(defaultOutcome = "SETTLED")
        val packet = MeshPacket(
            packetId = "pkt-1234-5678",
            ttl = 3,
            createdAt = 1700000000000L,
            ciphertext = "encrypted_payload_sample_data"
        )

        val response = fake.ingestPacket(
            packet = packet,
            bridgeNodeId = "bridge-alpha",
            hopCount = 2,
            requestId = "req-custom-99"
        )

        assertEquals("SETTLED", response.outcome)
        assertEquals(1001L, response.transactionId)
        assertEquals(200, response.httpStatusCode)
        assertEquals(1, fake.uploadedPackets.size)
        assertEquals("pkt-1234-5678", fake.uploadedPackets[0].packetId)

        val header = fake.recordedHeaders[0]
        assertEquals("bridge-alpha", header["X-Bridge-Node-Id"])
        assertEquals("2", header["X-Hop-Count"])
        assertEquals("req-custom-99", header["X-Request-ID"])
    }

    @Test
    fun testFakeApiClientTimeoutSimulation() = runBlocking {
        val fake = FakeBackendApiClient(shouldTimeout = true)
        val packet = MeshPacket(
            packetId = "pkt-timeout",
            ttl = 1,
            createdAt = 1700000000000L,
            ciphertext = "ciphertext_timeout"
        )

        val response = fake.ingestPacket(packet, "bridge-01")
        assertEquals("TRANSIENT_FAILURE", response.outcome)
        assertEquals(408, response.httpStatusCode)
        assertEquals("simulated_timeout", response.reason)
    }

    @Test
    fun testFakeApiClientNetworkLossSimulation() = runBlocking {
        val fake = FakeBackendApiClient(shouldFailWithNetworkException = true)
        val packet = MeshPacket(
            packetId = "pkt-loss",
            ttl = 1,
            createdAt = 1700000000000L,
            ciphertext = "ciphertext_loss"
        )

        val response = fake.ingestPacket(packet, "bridge-01")
        assertEquals("TRANSIENT_FAILURE", response.outcome)
        assertEquals(0, response.httpStatusCode)
        assertEquals("simulated_network_loss", response.reason)
    }

    @Test
    fun testFakeApiClientSequentialResponses() = runBlocking {
        val fake = FakeBackendApiClient()
        val r1 = WanIngestResponse(outcome = "TRANSIENT_FAILURE", packetHash = "hash1", httpStatusCode = 503)
        val r2 = WanIngestResponse(outcome = "SETTLED", packetHash = "hash1", transactionId = 55L, httpStatusCode = 200)

        fake.enqueueSequentialResponse(r1)
        fake.enqueueSequentialResponse(r2)

        val packet = MeshPacket(packetId = "p1", ttl = 1, createdAt = 100L, ciphertext = "c1")

        val res1 = fake.ingestPacket(packet, "b1")
        assertEquals("TRANSIENT_FAILURE", res1.outcome)
        assertEquals(503, res1.httpStatusCode)

        val res2 = fake.ingestPacket(packet, "b1")
        assertEquals("SETTLED", res2.outcome)
        assertEquals(55L, res2.transactionId)
    }
}

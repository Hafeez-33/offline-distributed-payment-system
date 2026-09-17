package com.demo.upimesh.bridge.client

import com.demo.upimesh.model.MeshPacket
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Interface abstraction for HTTPS communication with the Spring Boot backend.
 */
interface BackendApiClient {
    suspend fun ingestPacket(
        packet: MeshPacket,
        bridgeNodeId: String,
        hopCount: Int = 1,
        requestId: String? = null
    ): WanIngestResponse

    suspend fun checkHealth(): Boolean
}

/**
 * Production HTTPS client implementation for backend bridge ingestion.
 */
class HttpBackendApiClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000,
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
) : BackendApiClient {

    override suspend fun ingestPacket(
        packet: MeshPacket,
        bridgeNodeId: String,
        hopCount: Int,
        requestId: String?
    ): WanIngestResponse {
        val corrId = requestId ?: UUID.randomUUID().toString()
        val endpoint = "${baseUrl.trimEnd('/')}/api/bridge/ingest"
        val packetHash = packet.ciphertext?.let {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(it.toByteArray(StandardCharsets.UTF_8))
            digest.joinToString("") { b -> "%02x".format(b) }
        } ?: "unknown"

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Bridge-Node-Id", bridgeNodeId)
                setRequestProperty("X-Hop-Count", hopCount.toString())
                setRequestProperty("X-Request-ID", corrId)
            }

            // Write JSON payload
            val jsonPayload = objectMapper.writeValueAsString(packet)
            connection.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            val statusCode = connection.responseCode
            val isSuccess = statusCode in 200..299
            val inputStream = if (isSuccess) connection.inputStream else connection.errorStream
            val responseBody = inputStream?.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
            } ?: ""

            if (isSuccess && responseBody.isNotBlank()) {
                try {
                    val parsed = objectMapper.readValue(responseBody, WanIngestResponse::class.java)
                    parsed.copy(httpStatusCode = statusCode, rawBody = responseBody)
                } catch (pe: Exception) {
                    WanIngestResponse(
                        outcome = "INVALID",
                        packetHash = packetHash,
                        reason = "malformed_response_json: ${pe.message}",
                        httpStatusCode = statusCode,
                        rawBody = responseBody
                    )
                }
            } else {
                val outcome = when (statusCode) {
                    408, 429, 500, 502, 503, 504 -> "TRANSIENT_FAILURE"
                    else -> "INVALID"
                }
                WanIngestResponse(
                    outcome = outcome,
                    packetHash = packetHash,
                    reason = "http_error_$statusCode: $responseBody",
                    httpStatusCode = statusCode,
                    rawBody = responseBody
                )
            }
        } catch (ste: SocketTimeoutException) {
            WanIngestResponse(
                outcome = "TRANSIENT_FAILURE",
                packetHash = packetHash,
                reason = "timeout: ${ste.message}",
                httpStatusCode = 408
            )
        } catch (e: Exception) {
            WanIngestResponse(
                outcome = "TRANSIENT_FAILURE",
                packetHash = packetHash,
                reason = "network_exception: ${e.javaClass.simpleName}: ${e.message}",
                httpStatusCode = 0
            )
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun checkHealth(): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("${baseUrl.trimEnd('/')}/actuator/health")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Deterministic test double for BackendApiClient.
 */
class FakeBackendApiClient(
    var defaultOutcome: String = "DUPLICATE_DROPPED",
    var defaultReason: String? = null,
    var shouldTimeout: Boolean = false,
    var shouldFailWithNetworkException: Boolean = false,
    var httpStatusCode: Int = 200,
    var simulatedDelayMs: Long = 0L
) : BackendApiClient {

    val uploadedPackets = mutableListOf<MeshPacket>()
    val recordedHeaders = mutableListOf<Map<String, String>>()
    val responseOverrides = mutableMapOf<String, WanIngestResponse>()
    val sequentialResponses = mutableListOf<WanIngestResponse>()

    override suspend fun ingestPacket(
        packet: MeshPacket,
        bridgeNodeId: String,
        hopCount: Int,
        requestId: String?
    ): WanIngestResponse {
        uploadedPackets.add(packet)
        recordedHeaders.add(
            mapOf(
                "X-Bridge-Node-Id" to bridgeNodeId,
                "X-Hop-Count" to hopCount.toString(),
                "X-Request-ID" to (requestId ?: "req-test")
            )
        )

        if (simulatedDelayMs > 0) {
            kotlinx.coroutines.delay(simulatedDelayMs)
        }

        val packetHash = computeHash(packet)

        if (shouldTimeout) {
            return WanIngestResponse(
                outcome = "TRANSIENT_FAILURE",
                packetHash = packetHash,
                reason = "simulated_timeout",
                httpStatusCode = 408
            )
        }

        if (shouldFailWithNetworkException) {
            return WanIngestResponse(
                outcome = "TRANSIENT_FAILURE",
                packetHash = packetHash,
                reason = "simulated_network_loss",
                httpStatusCode = 0
            )
        }

        if (sequentialResponses.isNotEmpty()) {
            return sequentialResponses.removeAt(0)
        }

        val override = responseOverrides[packetHash]
            ?: (if (packet.packetId != null) responseOverrides[packet.packetId] else null)
            ?: (if (packet.ciphertext != null) responseOverrides[packet.ciphertext] else null)

        if (override != null) {
            return override
        }

        return WanIngestResponse(
            outcome = defaultOutcome,
            packetHash = packetHash,
            reason = defaultReason,
            transactionId = if (defaultOutcome == "SETTLED") 1001L else null,
            receiptSignature = if (defaultOutcome == "SETTLED") "valid_test_signature" else null,
            counter = if (defaultOutcome == "SETTLED") 1L else null,
            settledAt = if (defaultOutcome == "SETTLED") System.currentTimeMillis() else null,
            httpStatusCode = httpStatusCode
        )
    }

    override suspend fun checkHealth(): Boolean = !shouldFailWithNetworkException

    fun setResponseForPacket(packetHash: String, response: WanIngestResponse) {
        responseOverrides[packetHash] = response
    }

    fun enqueueSequentialResponse(response: WanIngestResponse) {
        sequentialResponses.add(response)
    }

    private fun computeHash(packet: MeshPacket): String {
        return packet.ciphertext?.let {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(it.toByteArray(StandardCharsets.UTF_8))
            digest.joinToString("") { b -> "%02x".format(b) }
        } ?: "empty_hash"
    }
}

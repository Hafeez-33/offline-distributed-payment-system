package com.demo.upimesh.bridge.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Parsed response from the backend bridge ingestion endpoint `/api/bridge/ingest`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WanIngestResponse(
    @JsonProperty("outcome")
    val outcome: String,

    @JsonProperty("packetHash")
    val packetHash: String,

    @JsonProperty("reason")
    val reason: String? = null,

    @JsonProperty("transactionId")
    val transactionId: Long? = null,

    @JsonProperty("receiptSignature")
    val receiptSignature: String? = null,

    @JsonProperty("counter")
    val counter: Long? = null,

    @JsonProperty("settledAt")
    val settledAt: Long? = null,

    val httpStatusCode: Int = 200,
    val rawBody: String? = null
)

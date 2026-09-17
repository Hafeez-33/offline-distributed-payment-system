package com.demo.upimesh.bridge.retry

import com.demo.upimesh.bridge.client.WanIngestResponse

/**
 * Classification of WAN ingestion results for retry scheduling and state transition.
 */
enum class ErrorClassification {
    SUCCESS,
    RETRYABLE,
    PERMANENT,
    TERMINAL_CONFLICT,
    IGNORED_DUPLICATE,
    IN_FLIGHT_GAP
}

/**
 * Deterministic classifier for HTTP response codes, network outcomes, and backend ingest results.
 */
class WanErrorClassifier {

    fun classify(response: WanIngestResponse): ErrorClassification {
        // 1. Check HTTP status code boundaries
        when (response.httpStatusCode) {
            408, 429, 500, 502, 503, 504 -> return ErrorClassification.RETRYABLE
            in 400..499 -> {
                // Client error (e.g. 400 bad request, 401 unauthorized, 403 forbidden, 404, 422)
                return ErrorClassification.PERMANENT
            }
        }

        // 2. Check outcome string
        return when (response.outcome.uppercase().trim()) {
            "SETTLED" -> ErrorClassification.SUCCESS
            "DUPLICATE_DROPPED" -> ErrorClassification.IGNORED_DUPLICATE
            "PENDING_SEQUENCE_GAP" -> ErrorClassification.IN_FLIGHT_GAP
            "CONFLICTING" -> ErrorClassification.TERMINAL_CONFLICT
            "REJECTED" -> ErrorClassification.PERMANENT
            "INVALID" -> ErrorClassification.PERMANENT
            "TRANSIENT_FAILURE" -> ErrorClassification.RETRYABLE
            else -> ErrorClassification.RETRYABLE
        }
    }

    fun isRetryable(response: WanIngestResponse): Boolean {
        return classify(response) == ErrorClassification.RETRYABLE
    }

    fun isPermanent(response: WanIngestResponse): Boolean {
        val c = classify(response)
        return c == ErrorClassification.PERMANENT || c == ErrorClassification.TERMINAL_CONFLICT
    }
}

/**
 * Bounded exponential backoff policy for retryable WAN uploads.
 */
class WanBackoffPolicy(
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 60000L,
    val multiplier: Double = 2.0,
    val maxRetries: Int = 5
) {
    fun computeDelay(retryCount: Int): Long {
        if (retryCount <= 0) return baseDelayMs
        val calculated = (baseDelayMs * Math.pow(multiplier, retryCount.toDouble())).toLong()
        return minOf(calculated, maxDelayMs)
    }

    fun canRetry(retryCount: Int): Boolean {
        return retryCount < maxRetries
    }
}

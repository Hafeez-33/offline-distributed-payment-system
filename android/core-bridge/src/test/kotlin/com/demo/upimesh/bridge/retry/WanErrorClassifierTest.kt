package com.demo.upimesh.bridge.retry

import com.demo.upimesh.bridge.client.WanIngestResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WanErrorClassifierTest {

    private val classifier = WanErrorClassifier()
    private val backoff = WanBackoffPolicy()

    @Test
    fun testClassifyOutcomes() {
        assertEquals(
            ErrorClassification.SUCCESS,
            classifier.classify(WanIngestResponse(outcome = "SETTLED", packetHash = "h1"))
        )

        assertEquals(
            ErrorClassification.IGNORED_DUPLICATE,
            classifier.classify(WanIngestResponse(outcome = "DUPLICATE_DROPPED", packetHash = "h1"))
        )

        assertEquals(
            ErrorClassification.IN_FLIGHT_GAP,
            classifier.classify(WanIngestResponse(outcome = "PENDING_SEQUENCE_GAP", packetHash = "h1"))
        )

        assertEquals(
            ErrorClassification.TERMINAL_CONFLICT,
            classifier.classify(WanIngestResponse(outcome = "CONFLICTING", packetHash = "h1"))
        )

        assertEquals(
            ErrorClassification.PERMANENT,
            classifier.classify(WanIngestResponse(outcome = "REJECTED", packetHash = "h1", reason = "insufficient_balance"))
        )

        assertEquals(
            ErrorClassification.PERMANENT,
            classifier.classify(WanIngestResponse(outcome = "INVALID", packetHash = "h1", reason = "invalid_signature"))
        )

        assertEquals(
            ErrorClassification.RETRYABLE,
            classifier.classify(WanIngestResponse(outcome = "TRANSIENT_FAILURE", packetHash = "h1"))
        )
    }

    @Test
    fun testClassifyHttpStatusCodes() {
        // Retryable status codes
        for (code in listOf(408, 429, 500, 502, 503, 504)) {
            val res = WanIngestResponse(outcome = "UNKNOWN", packetHash = "h1", httpStatusCode = code)
            assertEquals(ErrorClassification.RETRYABLE, classifier.classify(res), "Status $code should be RETRYABLE")
            assertTrue(classifier.isRetryable(res))
            assertFalse(classifier.isPermanent(res))
        }

        // Permanent client error status codes
        for (code in listOf(400, 401, 403, 404, 422)) {
            val res = WanIngestResponse(outcome = "UNKNOWN", packetHash = "h1", httpStatusCode = code)
            assertEquals(ErrorClassification.PERMANENT, classifier.classify(res), "Status $code should be PERMANENT")
            assertFalse(classifier.isRetryable(res))
            assertTrue(classifier.isPermanent(res))
        }
    }

    @Test
    fun testExponentialBackoffBounds() {
        assertEquals(1000L, backoff.computeDelay(0))
        assertEquals(2000L, backoff.computeDelay(1))
        assertEquals(4000L, backoff.computeDelay(2))
        assertEquals(8000L, backoff.computeDelay(3))
        assertEquals(16000L, backoff.computeDelay(4))
        assertEquals(32000L, backoff.computeDelay(5))
        assertEquals(60000L, backoff.computeDelay(6)) // Max cap is 60000
        assertEquals(60000L, backoff.computeDelay(10))

        assertTrue(backoff.canRetry(0))
        assertTrue(backoff.canRetry(4))
        assertFalse(backoff.canRetry(5))
    }
}

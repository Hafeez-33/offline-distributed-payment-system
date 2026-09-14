package com.demo.upimesh.service;

/**
 * Thrown when a settlement operation fails due to transient database/concurrency errors
 * (e.g. optimistic lock contention) after exhausting all retry attempts.
 */
public class TransientSettlementException extends RuntimeException {
    public TransientSettlementException(String message) {
        super(message);
    }

    public TransientSettlementException(String message, Throwable cause) {
        super(message, cause);
    }
}

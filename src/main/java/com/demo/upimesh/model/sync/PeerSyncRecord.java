package com.demo.upimesh.model.sync;

/**
 * Tracks synchronization status with a specific peer.
 */
public class PeerSyncRecord {

    public enum Status { ACTIVE, DEGRADED, DISCONNECTED }

    private final String peerId;
    private long lastSyncTimestamp;
    private String lastKnownDigest;
    private int consecutiveMatches;
    private Status status;
    private int failureCount;

    public PeerSyncRecord(String peerId) {
        this.peerId = peerId;
        this.lastSyncTimestamp = 0L;
        this.lastKnownDigest = "";
        this.consecutiveMatches = 0;
        this.status = Status.ACTIVE;
        this.failureCount = 0;
    }

    public String getPeerId() { return peerId; }
    public long getLastSyncTimestamp() { return lastSyncTimestamp; }
    public void setLastSyncTimestamp(long lastSyncTimestamp) { this.lastSyncTimestamp = lastSyncTimestamp; }
    public String getLastKnownDigest() { return lastKnownDigest; }
    public void setLastKnownDigest(String lastKnownDigest) { this.lastKnownDigest = lastKnownDigest; }
    public int getConsecutiveMatches() { return consecutiveMatches; }
    public void incrementConsecutiveMatches() { this.consecutiveMatches++; }
    public void resetConsecutiveMatches() { this.consecutiveMatches = 0; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getFailureCount() { return failureCount; }
    public void recordFailure() {
        this.failureCount++;
        if (failureCount >= 2) {
            this.status = Status.DEGRADED;
        }
    }
    public void recordSuccess() {
        this.failureCount = 0;
        this.status = Status.ACTIVE;
    }
}

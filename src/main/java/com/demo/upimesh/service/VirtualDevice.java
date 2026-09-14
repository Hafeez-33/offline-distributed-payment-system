package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.OfflineWalletCertificate;
import com.demo.upimesh.model.sync.PacketSyncMeta;
import com.demo.upimesh.model.sync.PeerSyncRecord;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A simulated phone in the mesh.
 *
 * Maintains:
 *   - Local packet store keyed by authoritative SHA-256(ciphertext) packetHash
 *   - Packet metadata and causal sequence tracking
 *   - Deterministic state digest and 16 prefix bucket checksums
 *   - Peer synchronization state and monotonic clocks
 *   - Monotonic wallet counter and escrow certificate (from Phase 3)
 */
public class VirtualDevice {

    private final String deviceId;
    private final boolean hasInternet;

    // Authoritative store keyed by packetHash = SHA-256(ciphertext)
    private final Map<String, MeshPacket> packetStore = new ConcurrentHashMap<>();
    private final Map<String, String> packetIdToHash = new ConcurrentHashMap<>();
    private final Map<String, PacketSyncMeta> packetMetadata = new ConcurrentHashMap<>();

    // Causal metadata / origin sequence tracking (NOT used for packet reconciliation)
    private final Map<String, Long> originHighWaterMark = new ConcurrentHashMap<>();
    private final Map<String, Long> nodeSyncClock = new ConcurrentHashMap<>();

    // Neighbor synchronization state
    private final Map<String, PeerSyncRecord> peerSyncTable = new ConcurrentHashMap<>();

    // Deterministic state digest and 16 prefix bucket checksums
    private volatile String stateDigest;
    private final List<String> bucketChecksums = new CopyOnWriteArrayList<>();

    // Simulated offline wallet state (from Phase 3)
    private String walletId;
    private Long walletEpoch;
    private long sequenceCounter = 0L;
    private BigDecimal cumulativeSpend = BigDecimal.ZERO;
    private OfflineWalletCertificate certificate;

    public VirtualDevice(String deviceId, boolean hasInternet) {
        this.deviceId = deviceId;
        this.hasInternet = hasInternet;
        recalculateDigestAndBuckets();
    }

    public String getDeviceId() { return deviceId; }
    public boolean hasInternet() { return hasInternet; }

    /**
     * Store an incoming packet keyed by authoritative packetHash.
     * Drops duplicates idempotently.
     *
     * @param packet the MeshPacket to store
     * @return true if newly added, false if already present (duplicate)
     */
    public synchronized boolean hold(MeshPacket packet) {
        if (packet == null) return false;
        String hash = packet.getPacketHash();
        if (hash == null || hash.isBlank()) return false;

        if (packetStore.containsKey(hash)) {
            return false; // duplicate
        }

        packetStore.put(hash, packet);
        if (packet.getPacketId() != null) {
            packetIdToHash.put(packet.getPacketId(), hash);
        }

        PacketSyncMeta meta = new PacketSyncMeta(
                hash,
                packet.getPacketId(),
                null,
                walletId,
                null,
                Instant.now().toEpochMilli(),
                packet.getTtl()
        );
        packetMetadata.put(hash, meta);

        recalculateDigestAndBuckets();
        return true;
    }

    /**
     * Store packet with full metadata.
     */
    public synchronized boolean holdWithMeta(MeshPacket packet, String originVpa, String walletId, Long seqCounter) {
        if (packet == null) return false;
        String hash = packet.getPacketHash();
        if (hash == null || hash.isBlank()) return false;

        if (packetStore.containsKey(hash)) {
            return false;
        }

        packetStore.put(hash, packet);
        if (packet.getPacketId() != null) {
            packetIdToHash.put(packet.getPacketId(), hash);
        }

        PacketSyncMeta meta = new PacketSyncMeta(
                hash,
                packet.getPacketId(),
                originVpa,
                walletId,
                seqCounter,
                Instant.now().toEpochMilli(),
                packet.getTtl()
        );
        packetMetadata.put(hash, meta);

        if (originVpa != null && seqCounter != null) {
            originHighWaterMark.merge(originVpa, seqCounter, Math::max);
        }

        recalculateDigestAndBuckets();
        return true;
    }

    public Collection<MeshPacket> getHeldPackets() {
        return Collections.unmodifiableCollection(packetStore.values());
    }

    public Map<String, MeshPacket> getPacketStore() {
        return Collections.unmodifiableMap(packetStore);
    }

    public MeshPacket getPacket(String packetHash) {
        return packetStore.get(packetHash);
    }

    /**
     * Check if device holds a packet by either authoritative packetHash or transport packetId.
     */
    public boolean holds(String packetIdOrHash) {
        if (packetIdOrHash == null) return false;
        return packetStore.containsKey(packetIdOrHash) || packetIdToHash.containsKey(packetIdOrHash);
    }

    public boolean holdsHash(String packetHash) {
        if (packetHash == null) return false;
        return packetStore.containsKey(packetHash);
    }

    public int packetCount() {
        return packetStore.size();
    }

    public synchronized void clear() {
        packetStore.clear();
        packetIdToHash.clear();
        packetMetadata.clear();
        originHighWaterMark.clear();
        nodeSyncClock.clear();
        peerSyncTable.clear();
        recalculateDigestAndBuckets();
    }

    /**
     * Recompute root state digest and 16 prefix bucket checksums.
     * Must be called under lock whenever packetStore mutates.
     */
    private void recalculateDigestAndBuckets() {
        bucketChecksums.clear();
        List<String>[] buckets = new List[16];
        for (int i = 0; i < 16; i++) {
            buckets[i] = new ArrayList<>();
        }

        if (packetStore.isEmpty()) {
            this.stateDigest = sha256Hex("EMPTY");
            for (int i = 0; i < 16; i++) {
                String hex = Integer.toHexString(i);
                bucketChecksums.add(sha256Hex("EMPTY_" + hex));
            }
            return;
        }

        List<String> sortedHashes = new ArrayList<>(packetStore.keySet());
        Collections.sort(sortedHashes);

        StringBuilder fullConcat = new StringBuilder();
        for (String hash : sortedHashes) {
            fullConcat.append(hash);
            int bIdx = getBucketIndex(hash);
            buckets[bIdx].add(hash);
        }
        this.stateDigest = sha256Hex(fullConcat.toString());

        for (int i = 0; i < 16; i++) {
            String hex = Integer.toHexString(i);
            if (buckets[i].isEmpty()) {
                bucketChecksums.add(sha256Hex("EMPTY_" + hex));
            } else {
                Collections.sort(buckets[i]);
                StringBuilder bConcat = new StringBuilder();
                for (String h : buckets[i]) bConcat.append(h);
                bucketChecksums.add(sha256Hex(bConcat.toString()));
            }
        }
    }

    public static int getBucketIndex(String hash) {
        if (hash == null || hash.isEmpty()) return 0;
        char first = Character.toLowerCase(hash.charAt(0));
        int digit = Character.digit(first, 16);
        return digit >= 0 && digit < 16 ? digit : 0;
    }

    public List<String> getAuthoritativeHashesForBucket(int bucketIndex) {
        List<String> hashes = new ArrayList<>();
        for (String hash : packetStore.keySet()) {
            if (getBucketIndex(hash) == bucketIndex) {
                hashes.add(hash);
            }
        }
        Collections.sort(hashes);
        return hashes;
    }

    public String getStateDigest() {
        return stateDigest;
    }

    public List<String> getBucketChecksums() {
        return Collections.unmodifiableList(bucketChecksums);
    }

    public PeerSyncRecord getPeerRecord(String peerId) {
        return peerSyncTable.computeIfAbsent(peerId, PeerSyncRecord::new);
    }

    public Map<String, PeerSyncRecord> getPeerSyncTable() {
        return peerSyncTable;
    }

    public Map<String, Long> getOriginHighWaterMark() {
        return Collections.unmodifiableMap(originHighWaterMark);
    }

    public void incrementSyncClock(String origin) {
        nodeSyncClock.merge(origin, 1L, Long::sum);
    }

    // Phase 3 Wallet methods
    public void loadWalletCertificate(OfflineWalletCertificate cert) {
        this.walletId = cert.walletId();
        this.walletEpoch = cert.walletEpoch();
        this.sequenceCounter = cert.initialCounter();
        this.cumulativeSpend = BigDecimal.ZERO;
        this.certificate = cert;
    }

    public synchronized long nextSequenceCounter() {
        this.sequenceCounter++;
        return this.sequenceCounter;
    }

    public synchronized void recordSpend(BigDecimal amount) {
        this.cumulativeSpend = this.cumulativeSpend.add(amount);
    }

    public String getWalletId() { return walletId; }
    public Long getWalletEpoch() { return walletEpoch; }
    public long getSequenceCounter() { return sequenceCounter; }
    public void setSequenceCounter(long counter) { this.sequenceCounter = counter; }
    public BigDecimal getCumulativeSpend() { return cumulativeSpend; }
    public OfflineWalletCertificate getCertificate() { return certificate; }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}

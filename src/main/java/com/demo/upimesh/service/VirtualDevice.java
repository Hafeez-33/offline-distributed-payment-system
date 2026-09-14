package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simulated phone in the mesh. Holds packets it has seen.
 *
 * In the real system, this state would be on a physical Android device,
 * with packets exchanged via BLE GATT characteristics.
 */
public class VirtualDevice {

    private final String deviceId;
    private final boolean hasInternet;
    private final Map<String, MeshPacket> heldPackets = new ConcurrentHashMap<>();

    // Simulated offline wallet state (simulation of device-secure monotonic state; in-memory software only)
    private String walletId;
    private Long walletEpoch;
    private long sequenceCounter = 0L;
    private java.math.BigDecimal cumulativeSpend = java.math.BigDecimal.ZERO;
    private com.demo.upimesh.model.OfflineWalletCertificate certificate;

    public VirtualDevice(String deviceId, boolean hasInternet) {
        this.deviceId = deviceId;
        this.hasInternet = hasInternet;
    }

    public String getDeviceId() { return deviceId; }
    public boolean hasInternet() { return hasInternet; }

    public void hold(MeshPacket packet) {
        heldPackets.putIfAbsent(packet.getPacketId(), packet);
    }

    public Collection<MeshPacket> getHeldPackets() {
        return heldPackets.values();
    }

    public boolean holds(String packetId) {
        return heldPackets.containsKey(packetId);
    }

    public int packetCount() {
        return heldPackets.size();
    }

    public void clear() {
        heldPackets.clear();
    }

    public void loadWalletCertificate(com.demo.upimesh.model.OfflineWalletCertificate cert) {
        this.walletId = cert.walletId();
        this.walletEpoch = cert.walletEpoch();
        this.sequenceCounter = cert.initialCounter();
        this.cumulativeSpend = java.math.BigDecimal.ZERO;
        this.certificate = cert;
    }

    public synchronized long nextSequenceCounter() {
        this.sequenceCounter++;
        return this.sequenceCounter;
    }

    public synchronized void recordSpend(java.math.BigDecimal amount) {
        this.cumulativeSpend = this.cumulativeSpend.add(amount);
    }

    public String getWalletId() { return walletId; }
    public Long getWalletEpoch() { return walletEpoch; }
    public long getSequenceCounter() { return sequenceCounter; }
    public void setSequenceCounter(long counter) { this.sequenceCounter = counter; }
    public java.math.BigDecimal getCumulativeSpend() { return cumulativeSpend; }
    public com.demo.upimesh.model.OfflineWalletCertificate getCertificate() { return certificate; }
}

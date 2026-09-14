package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates the Bluetooth mesh and coordinates both epidemic push and
 * anti-entropy pull synchronization across virtual devices.
 *
 * Supports:
 *   - Epidemic push gossip with hop-based TTL decrement
 *   - Pairwise anti-entropy synchronization via AntiEntropyService
 *   - Network partitioning (severing communication links between nodes/submeshes)
 *   - Network healing and automatic post-heal anti-entropy convergence
 */
@Service
public class MeshSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(MeshSimulatorService.class);

    private final Map<String, VirtualDevice> devices = new ConcurrentHashMap<>();
    private final Set<String> severedLinks = ConcurrentHashMap.newKeySet();

    @Autowired
    private AntiEntropyService antiEntropyService;

    @Autowired(required = false)
    private com.demo.upimesh.fault.FaultInterceptor faultInterceptor;

    public MeshSimulatorService() {
        seedDefaultDevices();
    }

    public MeshSimulatorService(AntiEntropyService antiEntropyService) {
        this.antiEntropyService = antiEntropyService;
        seedDefaultDevices();
    }

    public MeshSimulatorService(AntiEntropyService antiEntropyService, com.demo.upimesh.fault.FaultInterceptor faultInterceptor) {
        this.antiEntropyService = antiEntropyService;
        this.faultInterceptor = faultInterceptor;
        seedDefaultDevices();
    }

    public void setFaultInterceptor(com.demo.upimesh.fault.FaultInterceptor faultInterceptor) {
        this.faultInterceptor = faultInterceptor;
    }

    private void seedDefaultDevices() {
        devices.put("phone-alice",     new VirtualDevice("phone-alice",     false));
        devices.put("phone-stranger1", new VirtualDevice("phone-stranger1", false));
        devices.put("phone-stranger2", new VirtualDevice("phone-stranger2", false));
        devices.put("phone-stranger3", new VirtualDevice("phone-stranger3", false));
        devices.put("phone-bridge",    new VirtualDevice("phone-bridge",    true));
    }

    public Collection<VirtualDevice> getDevices() {
        return devices.values();
    }

    public VirtualDevice getDevice(String id) {
        return devices.get(id);
    }

    public void addDevice(VirtualDevice device) {
        devices.put(device.getDeviceId(), device);
    }

    // ---------------------------------------------------------------- partition controls

    public String linkKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "<->" + b : b + "<->" + a;
    }

    public void severLink(String a, String b) {
        severedLinks.add(linkKey(a, b));
        log.warn("Severed mesh link between {} and {}", a, b);
    }

    public void healLink(String a, String b) {
        severedLinks.remove(linkKey(a, b));
        log.info("Healed mesh link between {} and {}", a, b);
    }

    public void partitionSubmeshes(List<String> submeshA, List<String> submeshB) {
        for (String a : submeshA) {
            for (String b : submeshB) {
                severLink(a, b);
            }
        }
        log.warn("Partitioned submesh {} from submesh {}", submeshA, submeshB);
    }

    public void healAll() {
        severedLinks.clear();
        log.info("Healed all mesh links. Network topology fully reconnected.");
    }

    public boolean isReachable(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (faultInterceptor != null && (!faultInterceptor.isPeerAvailable(a, b) || !faultInterceptor.isPeerAvailable(b, a))) {
            return false;
        }
        return !severedLinks.contains(linkKey(a, b));
    }

    public Set<String> getSeveredLinks() {
        return Collections.unmodifiableSet(severedLinks);
    }

    // ---------------------------------------------------------------- injection

    public void inject(String senderDeviceId, MeshPacket packet) {
        VirtualDevice sender = devices.get(senderDeviceId);
        if (sender == null) throw new IllegalArgumentException("Unknown device: " + senderDeviceId);
        sender.hold(packet);
        log.info("Packet {} injected at {} (TTL={}, hash={})",
                packet.getPacketId().substring(0, Math.min(8, packet.getPacketId().length())),
                senderDeviceId, packet.getTtl(),
                packet.getPacketHash() != null ? packet.getPacketHash().substring(0, 12) : "?");
    }

    // ---------------------------------------------------------------- Phase 1: Epidemic Push

    /**
     * One round of epidemic push gossip.
     * Decrements TTL per hop. Severed links block communication.
     */
    public GossipResult gossipOnce() {
        int transfers = 0;
        List<VirtualDevice> deviceList = new ArrayList<>(devices.values());

        Map<String, List<MeshPacket>> snapshot = new HashMap<>();
        for (VirtualDevice d : deviceList) {
            snapshot.put(d.getDeviceId(), new ArrayList<>(d.getHeldPackets()));
        }

        for (VirtualDevice src : deviceList) {
            for (MeshPacket pkt : snapshot.get(src.getDeviceId())) {
                if (pkt.getTtl() <= 0) continue;
                for (VirtualDevice dst : deviceList) {
                    if (dst == src) continue;
                    if (!isReachable(src.getDeviceId(), dst.getDeviceId())) continue; // partition check
                    if (dst.holds(pkt.getPacketHash())) continue; // dedup by authoritative hash
                    if (faultInterceptor != null && !faultInterceptor.allowGossipPush(src.getDeviceId(), dst.getDeviceId(), pkt)) {
                        continue; // Dropped or delayed by fault rule
                    }

                    int dups = faultInterceptor != null ? faultInterceptor.getGossipDuplicateCount(src.getDeviceId(), dst.getDeviceId(), pkt) : 1;
                    for (int k = 0; k < dups; k++) {
                        MeshPacket copy = new MeshPacket();
                        copy.setPacketId(pkt.getPacketId());
                        copy.setTtl(pkt.getTtl() - 1);
                        copy.setCreatedAt(pkt.getCreatedAt());
                        copy.setCiphertext(pkt.getCiphertext());

                        MeshPacket payload = faultInterceptor != null ? faultInterceptor.interceptPacketPayload(src.getDeviceId(), dst.getDeviceId(), copy) : copy;
                        if (dst.hold(payload)) {
                            transfers++;
                        }
                    }
                }
            }
        }

        log.info("Epidemic gossip round complete: {} packet transfers", transfers);
        return new GossipResult(transfers, snapshotMap());
    }

    // ---------------------------------------------------------------- Phase 2: Anti-Entropy Pull

    public record AntiEntropySyncResult(
            int totalTransfers,
            int sessionsInSync,
            int sessionsRepaired,
            Map<String, Integer> deviceCounts,
            boolean allReachableConverged
    ) {}

    /**
     * Execute one complete anti-entropy synchronization round across all reachable peer pairs.
     */
    public AntiEntropySyncResult syncAntiEntropy() {
        int totalTransfers = 0;
        int sessionsInSync = 0;
        int sessionsRepaired = 0;

        List<VirtualDevice> deviceList = new ArrayList<>(devices.values());

        // Perform pairwise sync across all reachable pairs
        for (int i = 0; i < deviceList.size(); i++) {
            for (int j = i + 1; j < deviceList.size(); j++) {
                VirtualDevice d1 = deviceList.get(i);
                VirtualDevice d2 = deviceList.get(j);

                if (!isReachable(d1.getDeviceId(), d2.getDeviceId())) {
                    continue; // Skip severed partition links
                }

                AntiEntropyService.PairwiseSyncResult res = antiEntropyService.syncPair(d1, d2);
                if (res.wasInSync()) {
                    sessionsInSync++;
                } else {
                    sessionsRepaired++;
                    totalTransfers += res.totalTransfers();
                }
            }
        }

        boolean converged = isConnectedComponentConverged(deviceList);
        log.info("Anti-entropy round complete: {} transfers, {} in-sync, {} repaired, converged={}",
                totalTransfers, sessionsInSync, sessionsRepaired, converged);

        return new AntiEntropySyncResult(totalTransfers, sessionsInSync, sessionsRepaired, snapshotMap(), converged);
    }

    /**
     * Check if all devices in a connected component share identical state digests.
     */
    public boolean isConnectedComponentConverged(List<VirtualDevice> componentDevices) {
        if (componentDevices.isEmpty()) return true;
        String expectedDigest = componentDevices.get(0).getStateDigest();
        for (VirtualDevice d : componentDevices) {
            if (!expectedDigest.equals(d.getStateDigest())) {
                return false;
            }
        }
        return true;
    }

    public boolean isMeshFullyConverged() {
        return isConnectedComponentConverged(new ArrayList<>(devices.values()));
    }

    public Map<String, Integer> snapshotMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) {
            m.put(d.getDeviceId(), d.packetCount());
        }
        return m;
    }

    public Map<String, String> digestMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) {
            m.put(d.getDeviceId(), d.getStateDigest());
        }
        return m;
    }

    /**
     * Returns all packets held by devices with internet.
     */
    public List<BridgeUpload> collectBridgeUploads() {
        List<BridgeUpload> out = new ArrayList<>();
        for (VirtualDevice d : devices.values()) {
            if (!d.hasInternet()) continue;
            for (MeshPacket pkt : d.getHeldPackets()) {
                if (faultInterceptor != null && !faultInterceptor.allowBridgeUpload(d.getDeviceId(), pkt)) {
                    log.warn("Bridge upload suppressed by fault rule for node {}", d.getDeviceId());
                    continue; // Bridge transport failure; packet remains in mesh buffer intact
                }
                out.add(new BridgeUpload(d.getDeviceId(), pkt));
            }
        }
        return out;
    }

    public void resetMesh() {
        severedLinks.clear();
        devices.values().forEach(VirtualDevice::clear);
    }

    public record GossipResult(int transfers, Map<String, Integer> deviceCounts) {}
    public record BridgeUpload(String bridgeNodeId, MeshPacket packet) {}
}

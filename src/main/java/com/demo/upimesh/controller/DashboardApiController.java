package com.demo.upimesh.controller;

import com.demo.upimesh.dto.*;
import com.demo.upimesh.dto.DashboardOverviewDto;
import com.demo.upimesh.dto.DeviceDetailDto.*;
import com.demo.upimesh.dto.MeshSummaryDto.*;
import com.demo.upimesh.dto.PaginatedTransactionsDto.*;
import com.demo.upimesh.dto.ReliabilityReportDto.*;
import com.demo.upimesh.dto.WalletSummaryDto.*;
import com.demo.upimesh.fault.*;
import com.demo.upimesh.model.*;
import com.demo.upimesh.model.sync.PeerSyncRecord;
import com.demo.upimesh.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Controller for the Phase 6 React Dashboard.
 * Serves aggregated overview, summarized mesh topology, on-demand device details,
 * wallet escrow views, paginated transactions, live invariant audits, and simulator fault controls.
 */
@RestController
@RequestMapping("/api")
public class DashboardApiController {

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    @Autowired private MeshSimulatorService mesh;
    @Autowired private AccountRepository accountRepo;
    @Autowired private OfflineWalletRepository walletRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private InvariantAuditService invariantService;
    @Autowired(required = false) private FaultInjector faultInjector;
    @Autowired(required = false) private ReliabilityMetrics metrics;

    // ------------------------------------------------------------------ Overview

    @GetMapping("/dashboard/overview")
    public ResponseEntity<DashboardOverviewDto> getOverview() {
        List<Account> accounts = accountRepo.findAll();
        List<OfflineWallet> wallets = walletRepo.findAll();
        List<Transaction> txs = txRepo.findAll();

        BigDecimal totalLiquid = accounts.stream()
                .map(Account::getLiquidBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalEscrow = accounts.stream()
                .map(Account::getOfflineLockedBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int activeWallets = (int) wallets.stream()
                .filter(w -> w.getStatus() == OfflineWallet.WalletStatus.ACTIVE)
                .count();

        int disputedWallets = (int) wallets.stream()
                .filter(w -> w.getStatus() == OfflineWallet.WalletStatus.LOCKED_DISPUTED)
                .count();

        long settledTxCount = txs.stream()
                .filter(t -> t.getStatus() == Transaction.Status.SETTLED)
                .count();

        long conflictingTxCount = txs.stream()
                .filter(t -> t.getStatus() == Transaction.Status.CONFLICTING)
                .count();

        long pendingGapCount = txs.stream()
                .filter(t -> t.getStatus() == Transaction.Status.PENDING_SEQUENCE_GAP)
                .count();

        long invariantViolations = metrics != null ? metrics.getInvariantViolationsTotal() : 0L;
        int activeFaultRules = faultInjector != null ? faultInjector.getRules().size() : 0;
        int severedCount = mesh.getSeveredLinks().size();
        boolean converged = mesh.isMeshFullyConverged();

        String status;
        if (invariantViolations > 0) {
            status = "FAILED";
        } else if (disputedWallets > 0 || conflictingTxCount > 0) {
            status = "DISPUTED";
        } else if (severedCount > 0) {
            status = "PARTITIONED";
        } else if (pendingGapCount > 0 || activeFaultRules > 0) {
            status = "DEGRADED";
        } else {
            status = "HEALTHY";
        }

        int totalHeldPackets = mesh.getDevices().stream()
                .mapToInt(VirtualDevice::packetCount)
                .sum();

        int onlineBridges = (int) mesh.getDevices().stream()
                .filter(VirtualDevice::hasInternet)
                .count();

        DashboardOverviewDto dto = new DashboardOverviewDto(
                status,
                mesh.getDevices().size(),
                onlineBridges,
                converged,
                severedCount,
                totalHeldPackets,
                accounts.size(),
                totalLiquid,
                totalEscrow,
                activeWallets,
                disputedWallets,
                settledTxCount,
                conflictingTxCount,
                pendingGapCount,
                activeFaultRules,
                invariantViolations,
                Instant.now()
        );

        return ResponseEntity.ok(dto);
    }

    // ------------------------------------------------------------------ Mesh Topology

    @GetMapping("/dashboard/mesh")
    public ResponseEntity<MeshSummaryDto> getMeshSummary() {
        List<DeviceSummary> deviceSummaries = mesh.getDevices().stream()
                .map(d -> new DeviceSummary(
                        d.getDeviceId(),
                        d.hasInternet(),
                        d.getStateDigest(),
                        d.packetCount()
                ))
                .toList();

        return ResponseEntity.ok(new MeshSummaryDto(
                deviceSummaries,
                mesh.getSeveredLinks(),
                mesh.isMeshFullyConverged()
        ));
    }

    @GetMapping("/dashboard/mesh/devices/{deviceId}")
    public ResponseEntity<DeviceDetailDto> getDeviceDetail(@PathVariable String deviceId) {
        VirtualDevice d = mesh.getDevice(deviceId);
        if (d == null) {
            return ResponseEntity.notFound().build();
        }

        List<PacketSummary> packets = d.getHeldPackets().stream()
                .map(p -> new PacketSummary(
                        p.getPacketId(),
                        p.getPacketHash(),
                        p.getTtl(),
                        p.getCreatedAt() != null ? Instant.ofEpochMilli(p.getCreatedAt()) : null
                ))
                .toList();

        Map<String, PeerSyncSummary> peerSync = new LinkedHashMap<>();
        d.getPeerSyncTable().forEach((peerId, rec) -> {
            peerSync.put(peerId, new PeerSyncSummary(
                    rec.getPeerId(),
                    rec.getLastSyncTimestamp(),
                    rec.getStatus() == PeerSyncRecord.Status.ACTIVE,
                    rec.getConsecutiveMatches()
            ));
        });

        WalletStateSummary walletState = null;
        if (d.getWalletId() != null) {
            walletState = new WalletStateSummary(
                    d.getWalletId(),
                    d.getWalletEpoch(),
                    d.getSequenceCounter(),
                    d.getCumulativeSpend()
            );
        }

        DeviceDetailDto dto = new DeviceDetailDto(
                d.getDeviceId(),
                d.hasInternet(),
                d.getStateDigest(),
                d.packetCount(),
                packets,
                d.getBucketChecksums(),
                peerSync,
                walletState
        );

        return ResponseEntity.ok(dto);
    }

    // ------------------------------------------------------------------ Wallets & Accounts

    @GetMapping("/dashboard/wallets")
    public ResponseEntity<WalletSummaryDto> getWallets() {
        List<WalletItem> walletItems = walletRepo.findAll().stream()
                .map(w -> new WalletItem(
                        w.getWalletId(),
                        w.getOwnerVpa(),
                        w.getAllocatedAmount(),
                        w.getSettledAmount(),
                        w.getRemainingAmount(),
                        w.getWalletEpoch(),
                        w.getLastSettledCounter(),
                        w.getValidUntil(),
                        w.getStatus().name()
                ))
                .toList();

        List<AccountItem> accountItems = accountRepo.findAll().stream()
                .map(a -> new AccountItem(
                        a.getVpa(),
                        a.getHolderName(),
                        a.getLiquidBalance(),
                        a.getOfflineLockedBalance(),
                        a.getVersion()
                ))
                .toList();

        return ResponseEntity.ok(new WalletSummaryDto(walletItems, accountItems));
    }

    // ------------------------------------------------------------------ Transactions

    @GetMapping("/dashboard/transactions")
    public ResponseEntity<PaginatedTransactionsDto> getDashboardTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search
    ) {
        List<Transaction> all = txRepo.findAll();

        // Sort by ID descending
        List<Transaction> filtered = all.stream()
                .sorted(Comparator.comparing(Transaction::getId).reversed())
                .filter(t -> {
                    if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
                        return t.getStatus().name().equalsIgnoreCase(status);
                    }
                    return true;
                })
                .filter(t -> {
                    if (search != null && !search.isBlank()) {
                        String q = search.trim().toLowerCase();
                        boolean matchHash = t.getPacketHash() != null && t.getPacketHash().toLowerCase().contains(q);
                        boolean matchSender = t.getSenderVpa() != null && t.getSenderVpa().toLowerCase().contains(q);
                        boolean matchReceiver = t.getReceiverVpa() != null && t.getReceiverVpa().toLowerCase().contains(q);
                        boolean matchWallet = t.getWalletId() != null && t.getWalletId().toLowerCase().contains(q);
                        return matchHash || matchSender || matchReceiver || matchWallet;
                    }
                    return true;
                })
                .toList();

        int totalElements = filtered.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<TransactionItem> pageItems = filtered.subList(fromIndex, toIndex).stream()
                .map(t -> new TransactionItem(
                        t.getId(),
                        t.getPacketHash(),
                        t.getSenderVpa(),
                        t.getReceiverVpa(),
                        t.getAmount(),
                        t.getSignedAt(),
                        t.getSettledAt(),
                        t.getBridgeNodeId(),
                        t.getHopCount(),
                        t.getStatus().name(),
                        t.getWalletId(),
                        t.getSequenceCounter(),
                        t.getConflictReason(),
                        t.getWinningTransactionId(),
                        t.getReceiptSignature()
                ))
                .toList();

        return ResponseEntity.ok(new PaginatedTransactionsDto(
                pageItems, page, size, totalElements, totalPages
        ));
    }

    // ------------------------------------------------------------------ Reliability

    @GetMapping("/dashboard/reliability")
    public ResponseEntity<ReliabilityReportDto> getReliability() {
        MetricsSummary metricsSummary = new MetricsSummary(
                metrics != null ? metrics.getFaultInjectionsTotal() : 0,
                metrics != null ? metrics.getFaultDropsTotal() : 0,
                metrics != null ? metrics.getFaultDuplicatesTotal() : 0,
                metrics != null ? metrics.getFaultDelaysTotal() : 0,
                metrics != null ? metrics.getFaultReordersTotal() : 0,
                metrics != null ? metrics.getFaultPartitionsTotal() : 0,
                metrics != null ? metrics.getFaultRecoveriesTotal() : 0,
                metrics != null ? metrics.getRetryAttemptsTotal() : 0,
                metrics != null ? metrics.getReconciliationRecoveryTotal() : 0,
                metrics != null ? metrics.getInvariantViolationsTotal() : 0
        );

        List<InvariantResult> invariants = invariantService.evaluateAllInvariants();
        return ResponseEntity.ok(new ReliabilityReportDto(metricsSummary, invariants));
    }

    // ------------------------------------------------------------------ Fault Controls

    @GetMapping("/faults/rules")
    public ResponseEntity<?> listFaultRules() {
        if (faultInjector == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<Map<String, Object>> out = faultInjector.getRules().stream()
                .map(r -> {
                    int acts = faultInjector.getActivationCount(r.faultId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("faultId", r.faultId());
                    m.put("faultType", r.faultType().name());
                    m.put("sourceNode", r.sourceNode());
                    m.put("destinationNode", r.destinationNode());
                    m.put("occurrenceLimit", r.occurrenceLimit());
                    m.put("activations", acts);
                    m.put("packetHash", r.targetPacketHash());
                    m.put("status", acts >= r.occurrenceLimit() ? "EXHAUSTED" : "ACTIVE");
                    return m;
                })
                .toList();

        return ResponseEntity.ok(out);
    }

    @PostMapping("/faults/rule")
    public ResponseEntity<?> registerFaultRule(@RequestBody FaultRuleRequest req) {
        if (faultInjector == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "FaultInjector not enabled in environment"));
        }

        // Strict backend validation
        if (req.getFaultType() == null || req.getFaultType().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "faultType is required"));
        }

        FaultType type;
        try {
            type = FaultType.valueOf(req.getFaultType().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown faultType: " + req.getFaultType()));
        }

        int limit = req.getOccurrenceLimit() != null ? req.getOccurrenceLimit() : 1;
        if (limit < 1 || limit > 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "occurrenceLimit must be between 1 and 1000"));
        }

        long delay = req.getDelayMs() != null ? req.getDelayMs() : 0L;
        if (delay < 0 || delay > 60000) {
            return ResponseEntity.badRequest().body(Map.of("error", "delayMs must be between 0 and 60000"));
        }

        String src = (req.getSourceNode() == null || req.getSourceNode().isBlank()) ? "*" : req.getSourceNode().trim();
        if (!src.equals("*") && mesh.getDevice(src) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown sourceNode device ID: " + src));
        }

        String dst = (req.getDestinationNode() == null || req.getDestinationNode().isBlank()) ? "*" : req.getDestinationNode().trim();
        if (!dst.equals("*") && mesh.getDevice(dst) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown destinationNode device ID: " + dst));
        }

        String hash = req.getPacketHash();
        if (hash != null && !hash.isBlank()) {
            hash = hash.trim();
            if (!SHA256_HEX_PATTERN.matcher(hash).matches()) {
                return ResponseEntity.badRequest().body(Map.of("error", "packetHash must be a valid 64-character hexadecimal SHA-256 string"));
            }
        } else {
            hash = null;
        }

        String ruleId = "rule-" + UUID.randomUUID().toString().substring(0, 8);
        FaultRule rule = new FaultRule(ruleId, type, src, dst, null, hash, limit, delay);
        faultInjector.addRule(rule);

        return ResponseEntity.ok(Map.of(
                "status", "RULE_REGISTERED",
                "faultId", ruleId,
                "faultType", type.name()
        ));
    }

    @DeleteMapping("/faults/rule/{faultId}")
    public ResponseEntity<?> deleteFaultRule(@PathVariable String faultId) {
        if (faultInjector == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        boolean removed = faultInjector.removeRule(faultId);
        if (removed) {
            return ResponseEntity.ok(Map.of("status", "RULE_DELETED", "faultId", faultId));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Fault rule not found: " + faultId));
        }
    }

    @PostMapping("/faults/reset")
    public ResponseEntity<?> resetFaults() {
        if (faultInjector != null) {
            faultInjector.reset();
        }
        return ResponseEntity.ok(Map.of("status", "FAULTS_RESET"));
    }

    @PostMapping("/faults/toggle")
    public ResponseEntity<?> toggleFaults(@RequestBody Map<String, Boolean> body) {
        if (faultInjector == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        boolean enable = Boolean.TRUE.equals(body.get("enabled"));
        if (enable) {
            faultInjector.enable();
        } else {
            faultInjector.disable();
        }

        return ResponseEntity.ok(Map.of(
                "status", "TOGGLED",
                "enabled", faultInjector.isEnabled()
        ));
    }
}

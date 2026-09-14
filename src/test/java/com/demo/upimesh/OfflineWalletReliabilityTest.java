package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.BridgeIngestionService.IngestResult;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.OfflineWalletService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 Verification Test Suite:
 * Offline Wallet & Double-Spending Mitigation
 *
 * Implements the 20 approved reliability and security tests.
 */
@SpringBootTest
public class OfflineWalletReliabilityTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private OfflineWalletRepository walletRepository;
    @Autowired private OfflineWalletService walletService;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private SignatureService signatureService;
    @Autowired private SettlementService settlement;

    @BeforeEach
    void setUp() {
        idempotency.clear();
        transactions.deleteAll();
        walletRepository.deleteAll();

        // Reset demo account balances
        Account alice = accounts.findById("alice@demo").orElse(null);
        if (alice != null) {
            alice.setBalance(new BigDecimal("5000.00"));
            alice.setOfflineLockedBalance(BigDecimal.ZERO);
            accounts.save(alice);
        }
        Account bob = accounts.findById("bob@demo").orElse(null);
        if (bob != null) {
            bob.setBalance(new BigDecimal("1000.00"));
            bob.setOfflineLockedBalance(BigDecimal.ZERO);
            accounts.save(bob);
        }
    }

    private MeshPacket createOfflinePacket(String senderVpa, String receiverVpa, BigDecimal amount,
                                          String walletId, Long walletEpoch,
                                          long sequenceCounter, BigDecimal cumulativeAmount,
                                          OfflineWalletCertificate cert) throws Exception {
        return demoService.createOfflinePacket(
                senderVpa, receiverVpa, amount, "1234", 5,
                walletId, walletEpoch, sequenceCounter, cumulativeAmount, cert
        );
    }

    // 1. validOfflineAllocationEscrowsFunds
    @Test
    void validOfflineAllocationEscrowsFunds() {
        Account alice = accounts.findById("alice@demo").orElseThrow();
        BigDecimal initialLiquid = alice.getBalance();
        BigDecimal initialLocked = alice.getOfflineLockedBalance();
        BigDecimal allocateAmount = new BigDecimal("500.00");

        var res = walletService.allocate("alice@demo", allocateAmount, 24L);
        assertNotNull(res.wallet());
        assertNotNull(res.certificate());

        Account updated = accounts.findById("alice@demo").orElseThrow();
        assertEquals(initialLiquid.subtract(allocateAmount), updated.getBalance());
        assertEquals(initialLocked.add(allocateAmount), updated.getOfflineLockedBalance());
        assertEquals(initialLiquid.add(initialLocked), updated.getTotalFunds());
        assertEquals(OfflineWallet.WalletStatus.ACTIVE, res.wallet().getStatus());

        // Verify certificate signature with server issuer public key
        assertTrue(signatureService.verifyCertificate(res.certificate(), serverKey.getIssuerPublicKey()));

        // Cleanup
        walletService.reconcileAndClose(res.wallet().getWalletId());
    }

    // 2. spendingWithinAllocationSucceeds
    @Test
    void spendingWithinAllocationSucceeds() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("300.00"), 24L);
        Account bobBefore = accounts.findById("bob@demo").orElseThrow();

        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("100.00"), alloc.certificate()
        );

        IngestResult res = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("SETTLED", res.outcome());
        assertNotNull(res.receiptSignature());

        OfflineWallet updatedWallet = walletRepository.findByWalletId(alloc.wallet().getWalletId()).orElseThrow();
        assertEquals(new BigDecimal("100.00"), updatedWallet.getSettledAmount());
        assertEquals(new BigDecimal("200.00"), updatedWallet.getRemainingAmount());
        assertEquals(1L, updatedWallet.getLastSettledCounter());

        Account bobAfter = accounts.findById("bob@demo").orElseThrow();
        assertEquals(bobBefore.getBalance().add(new BigDecimal("100.00")), bobAfter.getBalance());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 3. outOfOrderCounterArrivalTriggersSequenceGap
    @Test
    void outOfOrderCounterArrivalTriggersSequenceGap() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("300.00"), 24L);

        // Counter 2 arrives when expected is 1
        MeshPacket packet2 = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                2L, new BigDecimal("50.00"), alloc.certificate()
        );

        IngestResult res = bridge.ingest(packet2, "bridge-1", 1);
        assertEquals("PENDING_SEQUENCE_GAP", res.outcome());

        Optional<Transaction> txOpt = transactions.findByWalletIdAndSequenceCounter(alloc.wallet().getWalletId(), 2L);
        assertTrue(txOpt.isPresent());
        assertEquals(Transaction.Status.PENDING_SEQUENCE_GAP, txOpt.get().getStatus());

        OfflineWallet wallet = walletRepository.findByWalletId(alloc.wallet().getWalletId()).orElseThrow();
        assertEquals(0L, wallet.getLastSettledCounter());
        assertEquals(0, BigDecimal.ZERO.compareTo(wallet.getSettledAmount()));

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 4. missingCounterGapEventuallyResolved
    @Test
    void missingCounterGapEventuallyResolved() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("400.00"), 24L);
        Account bobBefore = accounts.findById("bob@demo").orElseThrow();

        // 1. Out-of-order counter 2 arrives first -> PENDING_SEQUENCE_GAP
        MeshPacket packet2 = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("70.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                2L, new BigDecimal("120.00"), alloc.certificate()
        );
        IngestResult res2 = bridge.ingest(packet2, "bridge-1", 1);
        assertEquals("PENDING_SEQUENCE_GAP", res2.outcome());

        // 2. Missing counter 1 arrives -> Settle counter 1 AND cascade settle counter 2!
        MeshPacket packet1 = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("50.00"), alloc.certificate()
        );
        IngestResult res1 = bridge.ingest(packet1, "bridge-1", 1);
        assertEquals("SETTLED", res1.outcome());

        // Verify wallet state after cascade
        OfflineWallet wallet = walletRepository.findByWalletId(alloc.wallet().getWalletId()).orElseThrow();
        assertEquals(2L, wallet.getLastSettledCounter());
        assertEquals(new BigDecimal("120.00"), wallet.getSettledAmount());
        assertEquals(new BigDecimal("280.00"), wallet.getRemainingAmount());

        // Verify transaction 2 is now SETTLED with a receipt
        Transaction tx2 = transactions.findByWalletIdAndSequenceCounter(alloc.wallet().getWalletId(), 2L).orElseThrow();
        assertEquals(Transaction.Status.SETTLED, tx2.getStatus());
        assertNotNull(tx2.getReceiptSignature());

        Account bobAfter = accounts.findById("bob@demo").orElseThrow();
        assertEquals(bobBefore.getBalance().add(new BigDecimal("120.00")), bobAfter.getBalance());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 5. gapTimeoutRejectsPendingTransaction
    @Test
    void gapTimeoutRejectsPendingTransaction() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("200.00"), 24L);

        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("40.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                3L, new BigDecimal("40.00"), alloc.certificate()
        );
        bridge.ingest(packet, "bridge-1", 1);

        Transaction pendingTx = transactions.findByWalletIdAndSequenceCounter(alloc.wallet().getWalletId(), 3L).orElseThrow();
        assertEquals(Transaction.Status.PENDING_SEQUENCE_GAP, pendingTx.getStatus());

        // Backdate signedAt to exceed the 1800s gap window
        pendingTx.setSignedAt(Instant.now().minusSeconds(3600));
        transactions.save(pendingTx);

        settlement.resolveExpiredGaps(alloc.wallet().getWalletId());

        Transaction expiredTx = transactions.findByWalletIdAndSequenceCounter(alloc.wallet().getWalletId(), 3L).orElseThrow();
        assertEquals(Transaction.Status.REJECTED, expiredTx.getStatus());
        assertEquals("REJECTED_UNRESOLVED_SEQUENCE_GAP", expiredTx.getConflictReason());

        OfflineWallet wallet = walletRepository.findByWalletId(alloc.wallet().getWalletId()).orElseThrow();
        assertEquals(OfflineWallet.WalletStatus.AUDIT_REQUIRED, wallet.getStatus());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 6. oldEpochCertificateIsRejected
    @Test
    void oldEpochCertificateIsRejected() throws Exception {
        var alloc1 = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24L);
        walletService.reconcileAndClose(alloc1.wallet().getWalletId());

        // Allocate epoch 2
        var alloc2 = walletService.allocate("alice@demo", new BigDecimal("150.00"), 24L);
        assertEquals(2L, alloc2.wallet().getWalletEpoch());

        // Attempt spending with old epoch 1 certificate
        MeshPacket packetEpoch1 = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("30.00"),
                alloc2.wallet().getWalletId(), 1L, // obsolete epoch
                1L, new BigDecimal("30.00"), alloc1.certificate()
        );

        IngestResult res = bridge.ingest(packetEpoch1, "bridge-1", 1);
        assertEquals("INVALID", res.outcome());
        assertEquals("obsolete_wallet_epoch", res.reason());

        walletService.reconcileAndClose(alloc2.wallet().getWalletId());
    }

    // 7. expiredCertificateIsRejected
    @Test
    void expiredCertificateIsRejected() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24L);

        // Forge an expired certificate with valid signature
        long now = Instant.now().toEpochMilli();
        OfflineWalletCertificate expiredCertUnsigned = new OfflineWalletCertificate(
                alloc.wallet().getWalletId(),
                "alice@demo",
                alloc.certificate().ownerPublicKey(),
                alloc.certificate().allocatedAmount(),
                alloc.certificate().walletEpoch(),
                now - 200000,
                now - 1000, // expired 1 sec ago
                0L,
                ""
        );
        String issuerSig = signatureService.signCertificate(expiredCertUnsigned, serverKey.getIssuerPrivateKey());
        OfflineWalletCertificate expiredCert = new OfflineWalletCertificate(
                expiredCertUnsigned.walletId(),
                expiredCertUnsigned.ownerVpa(),
                expiredCertUnsigned.ownerPublicKey(),
                expiredCertUnsigned.allocatedAmount(),
                expiredCertUnsigned.walletEpoch(),
                expiredCertUnsigned.validFrom(),
                expiredCertUnsigned.validUntil(),
                expiredCertUnsigned.initialCounter(),
                issuerSig
        );

        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("20.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("20.00"), expiredCert
        );

        IngestResult res = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("INVALID", res.outcome());
        assertEquals("expired_wallet_certificate", res.reason());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 8. unusedEscrowReturnedOnReconciliation
    @Test
    void unusedEscrowReturnedOnReconciliation() throws Exception {
        Account aliceBefore = accounts.findById("alice@demo").orElseThrow();
        BigDecimal liquidBefore = aliceBefore.getBalance();

        var alloc = walletService.allocate("alice@demo", new BigDecimal("250.00"), 24L);
        Account aliceAllocated = accounts.findById("alice@demo").orElseThrow();
        assertEquals(liquidBefore.subtract(new BigDecimal("250.00")), aliceAllocated.getBalance());

        // Spend 100
        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("100.00"), alloc.certificate()
        );
        bridge.ingest(packet, "bridge-1", 1);

        // Reconcile and close wallet: unused 150 must be returned
        OfflineWallet closed = walletService.reconcileAndClose(alloc.wallet().getWalletId());
        assertEquals(OfflineWallet.WalletStatus.RECONCILED_CLOSED, closed.getStatus());

        Account aliceAfter = accounts.findById("alice@demo").orElseThrow();
        assertEquals(liquidBefore.subtract(new BigDecimal("100.00")), aliceAfter.getBalance());
        assertEquals(BigDecimal.ZERO, closed.getRemainingAmount());
    }

    // 9. escrowCorrectlyReducedAfterSettlement
    @Test
    void escrowCorrectlyReducedAfterSettlement() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("200.00"), 24L);
        Account alicePre = accounts.findById("alice@demo").orElseThrow();
        BigDecimal preLocked = alicePre.getOfflineLockedBalance();

        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("60.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("60.00"), alloc.certificate()
        );
        bridge.ingest(packet, "bridge-1", 1);

        Account alicePost = accounts.findById("alice@demo").orElseThrow();
        assertEquals(preLocked.subtract(new BigDecimal("60.00")), alicePost.getOfflineLockedBalance());
        assertEquals(alicePre.getBalance(), alicePost.getBalance()); // liquid balance not debited again!

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 10. conflictingCounterFreezesWallet
    @Test
    void conflictingCounterFreezesWallet() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("300.00"), 24L);

        // First spend on counter 1
        MeshPacket packet1A = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("40.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("40.00"), alloc.certificate()
        );
        IngestResult res1A = bridge.ingest(packet1A, "bridge-1", 1);
        assertEquals("SETTLED", res1A.outcome());

        // Conflicting spend on counter 1 to carol with different content / nonce / hash
        MeshPacket packet1B = createOfflinePacket(
                "alice@demo", "carol@demo", new BigDecimal("40.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("40.00"), alloc.certificate()
        );
        IngestResult res1B = bridge.ingest(packet1B, "bridge-2", 1);
        assertEquals("CONFLICTING", res1B.outcome());
        assertEquals("double_spend_counter_collision", res1B.reason());

        OfflineWallet wallet = walletRepository.findByWalletId(alloc.wallet().getWalletId()).orElseThrow();
        assertEquals(OfflineWallet.WalletStatus.LOCKED_DISPUTED, wallet.getStatus());

        // Verify subsequent transactions are rejected because wallet is disputed
        MeshPacket packet2 = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("20.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                2L, new BigDecimal("60.00"), alloc.certificate()
        );
        IngestResult res2 = bridge.ingest(packet2, "bridge-1", 1);
        assertEquals("REJECTED", res2.outcome());
        assertEquals("wallet_locked_disputed", res2.reason());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 11. twoConflictingTransactionsBeforeReconciliation
    @Test
    void twoConflictingTransactionsBeforeReconciliation() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("300.00"), 24L);

        MeshPacket txA = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("100.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("100.00"), alloc.certificate()
        );

        MeshPacket txB = createOfflinePacket(
                "alice@demo", "dave@demo", new BigDecimal("100.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("100.00"), alloc.certificate()
        );

        // First to commit wins
        IngestResult resA = bridge.ingest(txA, "b1", 1);
        assertEquals("SETTLED", resA.outcome());

        // Second is flagged conflicting
        IngestResult resB = bridge.ingest(txB, "b2", 1);
        assertEquals("CONFLICTING", resB.outcome());
        assertEquals("double_spend_counter_collision", resB.reason());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 12. clonedWalletStateDetectedOnSync
    @Test
    void clonedWalletStateDetectedOnSync() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("500.00"), 24L);

        // Device 1 spends counter 1 & counter 2
        MeshPacket d1_c1 = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("50.00"), alloc.certificate()
        );
        MeshPacket d1_c2 = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                2L, new BigDecimal("100.00"), alloc.certificate()
        );
        bridge.ingest(d1_c1, "b1", 1);
        bridge.ingest(d1_c2, "b1", 1);

        // Cloned device 2 also spends starting from counter 1 with different nonce/receiver
        MeshPacket d2_c1 = createOfflinePacket(
                "alice@demo", "carol@demo", new BigDecimal("60.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("60.00"), alloc.certificate()
        );
        IngestResult resClone = bridge.ingest(d2_c1, "b2", 1);
        assertEquals("CONFLICTING", resClone.outcome());

        OfflineWallet wallet = walletRepository.findByWalletId(alloc.wallet().getWalletId()).orElseThrow();
        assertEquals(OfflineWallet.WalletStatus.LOCKED_DISPUTED, wallet.getStatus());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 13. walletReissuanceAfterEpochIncrement
    @Test
    void walletReissuanceAfterEpochIncrement() throws Exception {
        var alloc1 = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24L);
        assertEquals(1L, alloc1.wallet().getWalletEpoch());
        walletService.reconcileAndClose(alloc1.wallet().getWalletId());

        // Allocate epoch 2
        var alloc2 = walletService.allocate("alice@demo", new BigDecimal("200.00"), 24L);
        assertEquals(2L, alloc2.wallet().getWalletEpoch());
        assertEquals(OfflineWallet.WalletStatus.ACTIVE, alloc2.wallet().getStatus());

        // Spend with epoch 2 works starting from counter 1
        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("40.00"),
                alloc2.wallet().getWalletId(), 2L,
                1L, new BigDecimal("40.00"), alloc2.certificate()
        );
        IngestResult res = bridge.ingest(packet, "b1", 1);
        assertEquals("SETTLED", res.outcome());

        walletService.reconcileAndClose(alloc2.wallet().getWalletId());
    }

    // 14. spendingAboveAllocationIsRejected
    @Test
    void spendingAboveAllocationIsRejected() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24L);

        // Attempt spending 150 when allocation is only 100
        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("150.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("150.00"), alloc.certificate()
        );

        IngestResult res = bridge.ingest(packet, "b1", 1);
        assertEquals("INVALID", res.outcome());
        assertEquals("allocation_exceeded", res.reason());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 15. forgedWalletCertificateIsRejected
    @Test
    void forgedWalletCertificateIsRejected() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24L);

        // Attacker creates a forged certificate with an arbitrary invalid signature
        OfflineWalletCertificate forgedCert = new OfflineWalletCertificate(
                alloc.wallet().getWalletId(),
                "alice@demo",
                alloc.certificate().ownerPublicKey(),
                alloc.certificate().allocatedAmount(),
                alloc.certificate().walletEpoch(),
                alloc.certificate().validFrom(),
                alloc.certificate().validUntil(),
                0L,
                "AAAAForgedSignatureXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX=="
        );

        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("50.00"), forgedCert
        );

        IngestResult res = bridge.ingest(packet, "b1", 1);
        assertEquals("INVALID", res.outcome());
        assertEquals("forged_wallet_certificate", res.reason());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 16. tamperedTransactionAmountFailsSignature
    @Test
    void tamperedTransactionAmountFailsSignature() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("200.00"), 24L);

        // Sign for 20.00
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("20.00"),
                "pinHash", UUID.randomUUID().toString(), Instant.now().toEpochMilli(),
                null, SignatureService.ALGORITHM,
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("20.00"), alloc.certificate()
        );
        PrivateKey aliceKey = demoService.getSimulatedClientPrivateKey("alice@demo");
        String sig = signatureService.sign(instruction, aliceKey);
        instruction.setSignature(sig);

        // Tamper amount to 80.00 after signing
        instruction.setAmount(new BigDecimal("80.00"));

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        IngestResult res = bridge.ingest(packet, "b1", 1);
        assertEquals("INVALID", res.outcome());
        assertEquals("invalid_signature", res.reason());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 17. tamperedRecipientFailsSignature
    @Test
    void tamperedRecipientFailsSignature() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("200.00"), 24L);

        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("25.00"),
                "pinHash", UUID.randomUUID().toString(), Instant.now().toEpochMilli(),
                null, SignatureService.ALGORITHM,
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("25.00"), alloc.certificate()
        );
        PrivateKey aliceKey = demoService.getSimulatedClientPrivateKey("alice@demo");
        String sig = signatureService.sign(instruction, aliceKey);
        instruction.setSignature(sig);

        // Tamper recipient to eve@demo
        instruction.setReceiverVpa("eve@demo");

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        IngestResult res = bridge.ingest(packet, "b1", 1);
        assertEquals("INVALID", res.outcome());
        assertEquals("invalid_signature", res.reason());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 18. reusedPacketHashFollowsPhase2Idempotency
    @Test
    void reusedPacketHashFollowsPhase2Idempotency() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("150.00"), 24L);

        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("35.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("35.00"), alloc.certificate()
        );

        // First ingestion settles
        IngestResult res1 = bridge.ingest(packet, "b1", 1);
        assertEquals("SETTLED", res1.outcome());

        // Immediate retry with identical packet returns committed record under Phase 2 idempotency
        IngestResult res2 = bridge.ingest(packet, "b2", 2);
        assertEquals("SETTLED", res2.outcome());
        assertEquals(res1.transactionId(), res2.transactionId());

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 19. terminalTransferRejectsRespentFunds
    @Test
    void terminalTransferRejectsRespentFunds() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("200.00"), 24L);

        // Bob tries to initiate an offline payment claiming alice's walletId
        // Violating terminal transfer policy: sender must be wallet owner
        PaymentInstruction instruction = new PaymentInstruction(
                "bob@demo", "carol@demo", new BigDecimal("50.00"),
                "pinHash", UUID.randomUUID().toString(), Instant.now().toEpochMilli(),
                null, SignatureService.ALGORITHM,
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("50.00"), alloc.certificate()
        );
        PrivateKey bobKey = demoService.getSimulatedClientPrivateKey("bob@demo");
        String sig = signatureService.sign(instruction, bobKey);
        instruction.setSignature(sig);

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        IngestResult res = bridge.ingest(packet, "b1", 1);
        assertEquals("INVALID", res.outcome());
        // Certificate owner mismatch or terminal transfer violation
        assertTrue(res.reason().contains("certificate_owner_mismatch") || res.reason().contains("terminal_transfer_violation"));

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }

    // 20. signedSettlementReceiptVerification
    @Test
    void signedSettlementReceiptVerification() throws Exception {
        var alloc = walletService.allocate("alice@demo", new BigDecimal("100.00"), 24L);

        MeshPacket packet = createOfflinePacket(
                "alice@demo", "bob@demo", new BigDecimal("45.00"),
                alloc.wallet().getWalletId(), alloc.wallet().getWalletEpoch(),
                1L, new BigDecimal("45.00"), alloc.certificate()
        );

        IngestResult res = bridge.ingest(packet, "b1", 1);
        assertEquals("SETTLED", res.outcome());
        assertNotNull(res.receiptSignature());

        Transaction tx = transactions.findById(res.transactionId()).orElseThrow();
        assertEquals("SETTLED", tx.getStatus().name());

        SettlementReceipt receipt = new SettlementReceipt(
                tx.getId(),
                tx.getPacketHash(),
                tx.getSequenceCounter(),
                tx.getStatus().name(),
                tx.getSettledAt().toEpochMilli(),
                tx.getReceiptSignature()
        );

        // Verify receipt signature using server issuer public key
        assertTrue(signatureService.verifyReceipt(receipt, serverKey.getIssuerPublicKey()));

        walletService.reconcileAndClose(alloc.wallet().getWalletId());
    }
}

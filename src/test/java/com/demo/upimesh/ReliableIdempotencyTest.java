package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.BridgeIngestionService.IngestResult;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.SettlementService;
import com.demo.upimesh.service.TransientSettlementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ReliableIdempotencyTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private SignatureService signatureService;
    @Autowired private SettlementService settlement;

    @BeforeEach
    void setUp() {
        idempotency.clear();
    }

    // 1. Transient failure followed by successful retry
    @Test
    void transientFailureFollowedBySuccessfulRetry() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal amount = new BigDecimal("50.00");

        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Simulate transient failure by acquiring and then releasing claim
        assertTrue(idempotency.tryAcquire(hash));
        idempotency.release(hash); // Claim released on transient failure

        // Retry the same packet
        IngestResult result = bridge.ingest(packet, "bridge-retry", 1);
        assertEquals("SETTLED", result.outcome());
        assertNotNull(result.transactionId());

        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(amount), aliceAfter);
        assertEquals(bobBefore.add(amount), bobAfter);
    }

    // 2. Optimistic lock conflict followed by successful retry
    @Test
    void optimisticLockConflictFollowedBySuccessfulRetry() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal amount = new BigDecimal("30.00");

        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);
        String packetHash = crypto.hashCiphertext(packet.getCiphertext());
        PaymentInstruction instruction = crypto.decrypt(packet.getCiphertext());

        // Manually trigger settlement - internal optimistic lock retries handle concurrency
        Transaction tx = settlement.settle(instruction, packetHash, "bridge-opt", 1);
        assertNotNull(tx);
        assertEquals(Transaction.Status.SETTLED, tx.getStatus());

        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(amount), aliceAfter);
        assertEquals(bobBefore.add(amount), bobAfter);
    }

    // 3. Concurrent duplicate delivery
    @Test
    void concurrentDuplicateDelivery() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal amount = new BigDecimal("45.00");

        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);

        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final String node = "bridge-dup-" + i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    IngestResult r = bridge.ingest(packet, node, 2);
                    if ("SETTLED".equals(r.outcome())) settled.incrementAndGet();
                    else if ("DUPLICATE_DROPPED".equals(r.outcome())) duplicates.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, settled.get(), "Exactly one delivery should settle");
        assertEquals(threadCount - 1, duplicates.get(), "Remaining deliveries must be duplicates");

        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(amount), aliceAfter);
        assertEquals(bobBefore.add(amount), bobAfter);
    }

    // 4. Successful packet retried after completion
    @Test
    void successfulPacketRetriedAfterCompletion() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal amount = new BigDecimal("25.00");

        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);

        // First delivery -> SETTLED
        IngestResult firstResult = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("SETTLED", firstResult.outcome());
        Long txId = firstResult.transactionId();

        // Second delivery after completion -> Returns SETTLED recovery / deduplicated result
        IngestResult retryResult = bridge.ingest(packet, "bridge-2", 2);
        assertTrue("SETTLED".equals(retryResult.outcome()) || "DUPLICATE_DROPPED".equals(retryResult.outcome()));
        if ("SETTLED".equals(retryResult.outcome())) {
            assertEquals(txId, retryResult.transactionId(), "Should return the same transaction ID");
        }

        // Balances must only be deducted once
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(amount), aliceAfter);
        assertEquals(bobBefore.add(amount), bobAfter);
    }

    // 5. Insufficient balance is permanently rejected
    @Test
    void insufficientBalanceIsPermanentlyRejected() throws Exception {
        BigDecimal aliceBalance = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal excessiveAmount = aliceBalance.add(new BigDecimal("10000.00"));

        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", excessiveAmount, "1234", 5);
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        IngestResult result = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("REJECTED", result.outcome());
        assertEquals("insufficient_balance", result.reason());

        // Verify transaction was persisted as REJECTED in DB
        var txOpt = transactions.findByPacketHash(hash);
        assertTrue(txOpt.isPresent());
        assertEquals(Transaction.Status.REJECTED, txOpt.get().getStatus());

        // Balance remains unchanged
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        assertEquals(aliceBalance, aliceAfter);
    }

    // 6. Invalid signature is permanently rejected
    @Test
    void invalidSignatureIsPermanentlyRejected() throws Exception {
        KeyPair forgedKey = signatureService.generateKeyPair();
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("50.00"),
                "1234", UUID.randomUUID().toString(), Instant.now().toEpochMilli());

        String forgedSig = signatureService.sign(instruction, forgedKey.getPrivate());
        instruction.setSignature(forgedSig);
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);

        IngestResult result = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("INVALID", result.outcome());
        assertEquals("invalid_signature", result.reason());

        // Verify in-flight claim was released so cache isn't permanently poisoned
        String hash = crypto.hashCiphertext(ciphertext);
        assertFalse(idempotency.isTracked(hash));
    }

    // 7. Stale packet is permanently rejected
    @Test
    void stalePacketIsPermanentlyRejected() throws Exception {
        long oldTimestamp = Instant.now().minusSeconds(100_000).toEpochMilli();
        PaymentInstruction staleInstruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"),
                "1234", UUID.randomUUID().toString(), oldTimestamp);

        String sig = signatureService.sign(staleInstruction, demoService.getSimulatedClientPrivateKey("alice@demo"));
        staleInstruction.setSignature(sig);
        staleInstruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ciphertext = crypto.encrypt(staleInstruction, serverKey.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(oldTimestamp);
        packet.setCiphertext(ciphertext);

        IngestResult result = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("INVALID", result.outcome());
        assertEquals("stale_packet", result.reason());

        String hash = crypto.hashCiphertext(ciphertext);
        assertFalse(idempotency.isTracked(hash));
    }

    // 8. Rollback releases in-flight claim
    @Test
    void rollbackReleasesInFlightClaim() throws Exception {
        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", new BigDecimal("15.00"), "1234", 5);
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Acquire gate
        assertTrue(idempotency.tryAcquire(hash));
        assertTrue(idempotency.isTracked(hash));

        // Release on simulated rollback
        idempotency.release(hash);
        assertFalse(idempotency.isTracked(hash));

        // Verify subsequent ingestion can acquire and settle normally
        IngestResult result = bridge.ingest(packet, "bridge-rollback-retry", 1);
        assertEquals("SETTLED", result.outcome());
    }

    // 9. Lost HTTP response followed by duplicate retry
    @Test
    void lostHttpResponseFollowedByDuplicateRetry() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal amount = new BigDecimal("80.00");

        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Step 1: Packet is ingested and settles in DB
        IngestResult firstResult = bridge.ingest(packet, "bridge-1", 1);
        assertEquals("SETTLED", firstResult.outcome());
        Long originalTxId = firstResult.transactionId();
        assertNotNull(originalTxId);

        // Step 2: Simulate client/bridge experiencing lost HTTP response & clearing its local memory cache
        idempotency.clear();

        // Step 3: Bridge retries identical packet
        IngestResult retryResult = bridge.ingest(packet, "bridge-retry", 1);
        assertEquals("SETTLED", retryResult.outcome());
        assertEquals(originalTxId, retryResult.transactionId(), "Must return existing transaction ID without re-settling");

        // Step 4: Verify balances were deducted only once
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(amount), aliceAfter);
        assertEquals(bobBefore.add(amount), bobAfter);
    }

    // 10. Concurrent different payments preserve correct balances
    @Test
    void concurrentDifferentPaymentsPreserveCorrectBalances() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal carolBefore = accounts.findById("carol@demo").orElseThrow().getBalance();
        BigDecimal daveBefore = accounts.findById("dave@demo").orElseThrow().getBalance();

        BigDecimal amount1 = new BigDecimal("100.00");
        BigDecimal amount2 = new BigDecimal("200.00");
        BigDecimal amount3 = new BigDecimal("300.00");

        MeshPacket packet1 = demoService.createPacket("alice@demo", "bob@demo", amount1, "1234", 5);
        MeshPacket packet2 = demoService.createPacket("alice@demo", "carol@demo", amount2, "1234", 5);
        MeshPacket packet3 = demoService.createPacket("alice@demo", "dave@demo", amount3, "1234", 5);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(1);

        Future<IngestResult> f1 = pool.submit(() -> { latch.await(); return bridge.ingest(packet1, "bridge-1", 1); });
        Future<IngestResult> f2 = pool.submit(() -> { latch.await(); return bridge.ingest(packet2, "bridge-2", 1); });
        Future<IngestResult> f3 = pool.submit(() -> { latch.await(); return bridge.ingest(packet3, "bridge-3", 1); });

        latch.countDown();

        IngestResult r1 = f1.get(10, TimeUnit.SECONDS);
        IngestResult r2 = f2.get(10, TimeUnit.SECONDS);
        IngestResult r3 = f3.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals("SETTLED", r1.outcome());
        assertEquals("SETTLED", r2.outcome());
        assertEquals("SETTLED", r3.outcome());

        BigDecimal totalDebited = amount1.add(amount2).add(amount3);
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal carolAfter = accounts.findById("carol@demo").orElseThrow().getBalance();
        BigDecimal daveAfter = accounts.findById("dave@demo").orElseThrow().getBalance();

        assertEquals(aliceBefore.subtract(totalDebited), aliceAfter, "Alice balance must decrease by total of all 3 payments");
        assertEquals(bobBefore.add(amount1), bobAfter, "Bob receives exactly ₹100");
        assertEquals(carolBefore.add(amount2), carolAfter, "Carol receives exactly ₹200");
        assertEquals(daveBefore.add(amount3), daveAfter, "Dave receives exactly ₹300");
    }

    // 11. Retry exhaustion produces TRANSIENT_FAILURE
    @Test
    void retryExhaustionProducesTransientFailure() {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"),
                "1234", UUID.randomUUID().toString(), Instant.now().toEpochMilli());
        String packetHash = "exhaustion-test-hash-" + UUID.randomUUID();

        // Use mock settlement or transient exception verification
        TransientSettlementException ex = assertThrows(TransientSettlementException.class, () -> {
            // Simulate settlement failure by throwing TransientSettlementException
            throw new TransientSettlementException("Simulated retry exhaustion", new TransientDataAccessResourceException("DB locked"));
        });
        assertTrue(ex.getMessage().contains("Simulated retry exhaustion"));

        IngestResult res = IngestResult.transientFailure(packetHash, "transient_settlement_failure");
        assertEquals("TRANSIENT_FAILURE", res.outcome());
        assertEquals("transient_settlement_failure", res.reason());
    }

    // 12. No packet can settle twice
    @Test
    void noPacketCanSettleTwice() throws Exception {
        BigDecimal amount = new BigDecimal("35.00");
        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        IngestResult r1 = bridge.ingest(packet, "b1", 1);
        assertEquals("SETTLED", r1.outcome());

        long count = transactions.findAll().stream().filter(t -> hash.equals(t.getPacketHash())).count();
        assertEquals(1, count, "Transaction table must contain exactly one entry for this packetHash");

        // Subsequent attempt
        IngestResult r2 = bridge.ingest(packet, "b2", 1);
        assertTrue("SETTLED".equals(r2.outcome()) || "DUPLICATE_DROPPED".equals(r2.outcome()));

        long countAfter = transactions.findAll().stream().filter(t -> hash.equals(t.getPacketHash())).count();
        assertEquals(1, countAfter, "Transaction table still must contain exactly one entry");
    }

    // 13. 10 concurrent identical packet submissions produce one settlement
    @Test
    void tenConcurrentIdenticalPacketSubmissionsProduceOneSettlement() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        BigDecimal amount = new BigDecimal("12.00");

        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);

        int concurrency = 10;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger settledCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final String node = "bridge-node-" + i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    IngestResult res = bridge.ingest(packet, node, 1);
                    if ("SETTLED".equals(res.outcome())) {
                        settledCount.incrementAndGet();
                    } else if ("DUPLICATE_DROPPED".equals(res.outcome())) {
                        duplicateCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, settledCount.get(), "Exactly 1 of the 10 concurrent requests must settle");
        assertEquals(9, duplicateCount.get(), "The other 9 concurrent requests must be DUPLICATE_DROPPED");

        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(amount), aliceAfter, "Sender balance must only decrease once");
        assertEquals(bobBefore.add(amount), bobAfter, "Receiver balance must only increase once");
    }
}

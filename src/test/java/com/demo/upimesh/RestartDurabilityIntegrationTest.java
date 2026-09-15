package com.demo.upimesh;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.OfflineWalletService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restart Durability Integration Test (Phase 7).
 *
 * Proves that financial balances, escrow allocations, settled transactions,
 * wallet conflict states, and the idempotency barrier survive a full
 * ApplicationContext restart against the authoritative database.
 */
class RestartDurabilityIntegrationTest {

    private static final String SHARED_DB_URL = "jdbc:h2:mem:durability_test_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    @Test
    @DisplayName("Financial state, escrow, transactions, and idempotency survive ApplicationContext restart")
    void testStateSurvivesContextRestart() throws Exception {
        String aliceVpa = "durability-alice@demo";
        String bobVpa = "durability-bob@demo";
        String packetHash1 = "11112222333344445555666677778888aaaabbbbccccddddeeeeffff00001111";
        String conflictHash = "99998888777766665555444433332222aaaabbbbccccddddeeeeffff00009999";

        String walletId;
        Long tx1Id;

        // =====================================================================
        // PHASE A: Boot Context 1, mutate authoritative ledger, allocate escrow, settle tx
        // =====================================================================
        try (ConfigurableApplicationContext context1 = createTestContext()) {
            AccountRepository accounts1 = context1.getBean(AccountRepository.class);
            OfflineWalletRepository wallets1 = context1.getBean(OfflineWalletRepository.class);
            TransactionRepository txRepo1 = context1.getBean(TransactionRepository.class);
            OfflineWalletService walletService1 = context1.getBean(OfflineWalletService.class);
            SettlementService settlementService1 = context1.getBean(SettlementService.class);
            SignatureService signatureService1 = context1.getBean(SignatureService.class);

            // 1. Create accounts
            KeyPair aliceKeys = signatureService1.generateKeyPair();
            Account alice = new Account(aliceVpa, "Alice Durability", new BigDecimal("5000.00"),
                    signatureService1.encodePublicKey(aliceKeys.getPublic()), "Ed25519");
            accounts1.save(alice);

            KeyPair bobKeys = signatureService1.generateKeyPair();
            Account bob = new Account(bobVpa, "Bob Durability", new BigDecimal("1000.00"),
                    signatureService1.encodePublicKey(bobKeys.getPublic()), "Ed25519");
            accounts1.save(bob);

            // 2. Allocate offline wallet escrow: ₹1500.00
            OfflineWalletService.AllocationResult alloc = walletService1.allocate(aliceVpa, new BigDecimal("1500.00"), 24);
            walletId = alloc.wallet().getWalletId();
            assertNotNull(walletId);

            // Verify post-allocation balances
            Account alicePostAlloc = accounts1.findById(aliceVpa).orElseThrow();
            assertEquals(new BigDecimal("3500.00"), alicePostAlloc.getBalance());
            assertEquals(new BigDecimal("1500.00"), alicePostAlloc.getOfflineLockedBalance());

            // 3. Settle offline transaction 1: ₹400.00, counter 1
            PaymentInstruction instr1 = new PaymentInstruction(
                    aliceVpa, bobVpa, new BigDecimal("400.00"),
                    "dummy_pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli()
            );
            instr1.setWalletId(walletId);
            instr1.setSequenceCounter(1L);

            Transaction settled1 = settlementService1.settle(instr1, packetHash1, "bridge-test", 1);
            assertEquals(Transaction.Status.SETTLED, settled1.getStatus());
            tx1Id = settled1.getId();

            // 4. Provoke a double-spend conflict on counter 1 with a different hash
            PaymentInstruction conflictInstr = new PaymentInstruction(
                    aliceVpa, bobVpa, new BigDecimal("400.00"),
                    "dummy_pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli()
            );
            conflictInstr.setWalletId(walletId);
            conflictInstr.setSequenceCounter(1L);

            Transaction conflictTx = settlementService1.settle(conflictInstr, conflictHash, "bridge-test", 1);
            assertEquals(Transaction.Status.CONFLICTING, conflictTx.getStatus());
            assertEquals("double_spend_counter_collision", conflictTx.getConflictReason());

            // Verify wallet is now LOCKED_DISPUTED
            OfflineWallet disputedWallet = wallets1.findByWalletId(walletId).orElseThrow();
            assertEquals(OfflineWallet.WalletStatus.LOCKED_DISPUTED, disputedWallet.getStatus());
            assertEquals(new BigDecimal("400.00"), disputedWallet.getSettledAmount());
            assertEquals(new BigDecimal("1100.00"), disputedWallet.getRemainingAmount());
        } // Context 1 closes here; in-memory services, caches, and Singletons destroyed

        // =====================================================================
        // PHASE B: Boot Context 2 against identical database and verify persistence
        // =====================================================================
        try (ConfigurableApplicationContext context2 = createTestContext()) {
            AccountRepository accounts2 = context2.getBean(AccountRepository.class);
            OfflineWalletRepository wallets2 = context2.getBean(OfflineWalletRepository.class);
            TransactionRepository txRepo2 = context2.getBean(TransactionRepository.class);
            SettlementService settlementService2 = context2.getBean(SettlementService.class);

            // 1. Verify Account state unchanged
            Account aliceReloaded = accounts2.findById(aliceVpa).orElseThrow();
            assertEquals(new BigDecimal("3500.00"), aliceReloaded.getBalance(),
                    "Alice liquid balance must survive context restart");
            assertEquals(new BigDecimal("1100.00"), aliceReloaded.getOfflineLockedBalance(),
                    "Alice offline locked escrow must survive context restart");

            Account bobReloaded = accounts2.findById(bobVpa).orElseThrow();
            assertEquals(new BigDecimal("1400.00"), bobReloaded.getBalance(),
                    "Bob received balance must survive context restart");

            // 2. Verify Offline Wallet state unchanged
            OfflineWallet walletReloaded = wallets2.findByWalletId(walletId).orElseThrow();
            assertEquals(OfflineWallet.WalletStatus.LOCKED_DISPUTED, walletReloaded.getStatus(),
                    "Wallet conflict state must survive context restart");
            assertEquals(new BigDecimal("1500.00"), walletReloaded.getAllocatedAmount(),
                    "Allocated amount must survive context restart");
            assertEquals(new BigDecimal("400.00"), walletReloaded.getSettledAmount(),
                    "Settled amount must survive context restart");
            assertEquals(new BigDecimal("1100.00"), walletReloaded.getRemainingAmount(),
                    "Remaining escrow must survive context restart");
            assertEquals(1L, walletReloaded.getLastSettledCounter(),
                    "Last settled counter must survive context restart");

            // 3. Verify Transactions still exist
            Optional<Transaction> tx1Reloaded = txRepo2.findById(tx1Id);
            assertTrue(tx1Reloaded.isPresent(), "Settled transaction must survive restart");
            assertEquals(Transaction.Status.SETTLED, tx1Reloaded.get().getStatus());
            assertEquals(packetHash1, tx1Reloaded.get().getPacketHash());

            Optional<Transaction> conflictReloaded = txRepo2.findByPacketHash(conflictHash);
            assertTrue(conflictReloaded.isPresent(), "Conflicting transaction must survive restart");
            assertEquals(Transaction.Status.CONFLICTING, conflictReloaded.get().getStatus());

            // 4. Verify Idempotency Barrier after restart:
            // Resubmitting packetHash1 MUST be recognized as already committed in DB without creating a second debit
            PaymentInstruction duplicateInstr = new PaymentInstruction(
                    aliceVpa, bobVpa, new BigDecimal("400.00"),
                    "dummy_pin", UUID.randomUUID().toString(), Instant.now().toEpochMilli()
            );
            duplicateInstr.setWalletId(walletId);
            duplicateInstr.setSequenceCounter(1L);

            Transaction replayedTx = settlementService2.settle(duplicateInstr, packetHash1, "bridge-replay", 1);
            assertEquals(tx1Id, replayedTx.getId(), "Must return identical transaction record for duplicate hash");

            // Verify balances remained strictly untouched (no second debit)
            Account alicePostReplay = accounts2.findById(aliceVpa).orElseThrow();
            assertEquals(new BigDecimal("3500.00"), alicePostReplay.getBalance(),
                    "Alice balance must NOT be debited again on replayed packet");
            assertEquals(new BigDecimal("1100.00"), alicePostReplay.getOfflineLockedBalance());

            Account bobPostReplay = accounts2.findById(bobVpa).orElseThrow();
            assertEquals(new BigDecimal("1400.00"), bobPostReplay.getBalance(),
                    "Bob balance must NOT be credited again on replayed packet");
        }
    }

    private ConfigurableApplicationContext createTestContext() {
        return new SpringApplicationBuilder(UpiMeshApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + SHARED_DB_URL,
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.flyway.enabled=true",
                        "spring.data.redis.repositories.enabled=false",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379",
                        "spring.data.redis.timeout=50ms"
                )
                .run();
    }
}

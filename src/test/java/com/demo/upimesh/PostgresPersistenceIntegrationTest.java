package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.OfflineWallet;
import com.demo.upimesh.model.OfflineWalletRepository;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PostgresPersistenceIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void checkDocker() {
        boolean dockerAvailable = false;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            dockerAvailable = false;
        }

        if (dockerAvailable) {
            try {
                postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                        .withDatabaseName("upimesh")
                        .withUsername("postgres")
                        .withPassword("postgres");
                postgres.start();
            } catch (Exception e) {
                dockerAvailable = false;
            }
        }

        Assumptions.assumeTrue(dockerAvailable,
                "Docker is not available on host. Live PostgreSQL container test skipped; running H2-Postgres profile tests.");
    }

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        if (postgres != null && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        }
    }

    @Autowired private AccountRepository accountRepository;
    @Autowired private OfflineWalletRepository walletRepository;
    @Autowired private TransactionRepository transactionRepository;

    @Test
    @DisplayName("PostgreSQL enforces UNIQUE(packet_hash) constraint")
    void testPostgresUniquePacketHashConstraint() {
        String testVpa1 = "pg-sender-" + UUID.randomUUID().toString().substring(0, 6) + "@demo";
        String testVpa2 = "pg-receiver-" + UUID.randomUUID().toString().substring(0, 6) + "@demo";

        accountRepository.save(new Account(testVpa1, "Sender", new BigDecimal("1000.00"), "pubkey1", "Ed25519"));
        accountRepository.save(new Account(testVpa2, "Receiver", new BigDecimal("500.00"), "pubkey2", "Ed25519"));

        String hash = ("deadbeef" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).substring(0, 64);

        Transaction tx1 = new Transaction();
        tx1.setPacketHash(hash);
        tx1.setSenderVpa(testVpa1);
        tx1.setReceiverVpa(testVpa2);
        tx1.setAmount(new BigDecimal("100.00"));
        tx1.setSignedAt(Instant.now());
        tx1.setSettledAt(Instant.now());
        tx1.setBridgeNodeId("bridge-1");
        tx1.setStatus(Transaction.Status.SETTLED);
        transactionRepository.save(tx1);

        Transaction tx2 = new Transaction();
        tx2.setPacketHash(hash); // duplicate hash
        tx2.setSenderVpa(testVpa1);
        tx2.setReceiverVpa(testVpa2);
        tx2.setAmount(new BigDecimal("50.00"));
        tx2.setSignedAt(Instant.now());
        tx2.setSettledAt(Instant.now());
        tx2.setBridgeNodeId("bridge-2");
        tx2.setStatus(Transaction.Status.SETTLED);

        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionRepository.saveAndFlush(tx2);
        }, "PostgreSQL MUST reject duplicate packet_hash with DataIntegrityViolationException");
    }

    @Test
    @DisplayName("PostgreSQL persists offline wallet escrow and enforces bounds")
    void testPostgresWalletPersistence() {
        String ownerVpa = "pg-wallet-" + UUID.randomUUID().toString().substring(0, 6) + "@demo";
        accountRepository.save(new Account(ownerVpa, "Wallet Owner", new BigDecimal("2000.00"), "pubkey3", "Ed25519"));

        String walletId = "WLT-PG-" + UUID.randomUUID().toString().substring(0, 8);
        OfflineWallet wallet = new OfflineWallet(
                walletId,
                ownerVpa,
                new BigDecimal("500.00"),
                1L,
                Instant.now().plus(24, ChronoUnit.HOURS)
        );
        walletRepository.saveAndFlush(wallet);

        Optional<OfflineWallet> retrieved = walletRepository.findByWalletId(walletId);
        assertTrue(retrieved.isPresent());
        assertEquals(new BigDecimal("500.00"), retrieved.get().getAllocatedAmount());
        assertEquals(new BigDecimal("500.00"), retrieved.get().getRemainingAmount());
        assertEquals(0L, retrieved.get().getLastSettledCounter());
    }
}

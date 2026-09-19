package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.InfrastructureMetrics;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class RedisFailureResilienceTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private BridgeIngestionService bridgeIngestionService;
    @Autowired private SettlementService settlementService;
    @Autowired private InfrastructureMetrics metrics;
    @Autowired private SignatureService signatureService;
    @Autowired private HybridCryptoService cryptoService;
    @Autowired private ServerKeyHolder serverKeyHolder;

    private StringRedisTemplate brokenRedisTemplate;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        idempotencyService.clear();
        metrics.reset();

        // Configure a broken Redis template that throws RedisConnectionFailureException on all operations
        brokenRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(brokenRedisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any())).thenThrow(
                new RedisConnectionFailureException("Connection refused: no Redis instance reachable"));
        when(brokenRedisTemplate.delete(anyString())).thenThrow(
                new RedisConnectionFailureException("Connection refused: no Redis instance reachable"));

        idempotencyService.setRedisTemplate(brokenRedisTemplate);
    }

    @Test
    @DisplayName("When Redis fails, payments settle safely via PostgreSQL authoritative barrier without duplicate debits")
    void testSettlementSucceedsWhenRedisFails() throws Exception {
        String senderVpa = "resilience-sender-" + UUID.randomUUID().toString().substring(0, 6) + "@demo";
        String receiverVpa = "resilience-receiver-" + UUID.randomUUID().toString().substring(0, 6) + "@demo";

        KeyPair senderKeys = signatureService.generateKeyPair();
        KeyPair receiverKeys = signatureService.generateKeyPair();

        Account sender = new Account(senderVpa, "Sender", new BigDecimal("1000.00"),
                signatureService.encodePublicKey(senderKeys.getPublic()), "Ed25519");
        Account receiver = new Account(receiverVpa, "Receiver", new BigDecimal("200.00"),
                signatureService.encodePublicKey(receiverKeys.getPublic()), "Ed25519");

        accountRepository.save(sender);
        accountRepository.save(receiver);

        // 1. Create a signed and encrypted MeshPacket
        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa, receiverVpa, new BigDecimal("150.00"),
                "pin123", UUID.randomUUID().toString(), Instant.now().toEpochMilli()
        );
        String sig = signatureService.sign(instruction, senderKeys.getPrivate());
        instruction.setSignature(sig);
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId("PKT-RESILIENCE-1");
        packet.setCiphertext(ciphertext);
        packet.setTtl(5);

        // 2. Ingest through BridgeIngestionService while Redis is DOWN
        long initialFallbacks = metrics.getRedisFallbackTotal();
        BridgeIngestionService.IngestResult result1 = bridgeIngestionService.ingest(packet, "bridge-failover", 1);

        // Assert settlement succeeded despite Redis failure
        assertEquals("SETTLED", result1.outcome(),
                "Transaction MUST settle successfully via PostgreSQL even when Redis is down");
        assertNotNull(result1.transactionId());

        // Assert fallback metric was recorded
        assertTrue(metrics.getRedisFallbackTotal() > initialFallbacks,
                "Redis fallback metric must increment on Redis connection failure");

        // Verify authoritative ledger updated correctly in database
        Account postSender = accountRepository.findById(senderVpa).orElseThrow();
        Account postReceiver = accountRepository.findById(receiverVpa).orElseThrow();
        assertEquals(new BigDecimal("850.00"), postSender.getBalance(), "Sender balance debited ₹150");
        assertEquals(new BigDecimal("350.00"), postReceiver.getBalance(), "Receiver balance credited ₹150");

        // 3. Re-ingest the EXACT same packet (simulating duplicate delivery while Redis remains down)
        BridgeIngestionService.IngestResult result2 = bridgeIngestionService.ingest(packet, "bridge-failover-2", 2);

        // Assert duplicate is recognized by the authoritative PostgreSQL barrier
        assertEquals("SETTLED", result2.outcome(),
                "Replayed packet must return previous SETTLED result");
        assertEquals(result1.transactionId(), result2.transactionId(),
                "Must return identical transaction ID from database");

        // 4. Verify no double debit or financial corruption occurred
        Account postReplaySender = accountRepository.findById(senderVpa).orElseThrow();
        Account postReplayReceiver = accountRepository.findById(receiverVpa).orElseThrow();
        assertEquals(new BigDecimal("850.00"), postReplaySender.getBalance(),
                "Sender balance must remain ₹850.00 — no duplicate debit allowed!");
        assertEquals(new BigDecimal("350.00"), postReplayReceiver.getBalance(),
                "Receiver balance must remain ₹350.00 — no duplicate credit allowed!");
    }
}

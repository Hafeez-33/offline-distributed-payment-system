package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.crypto.SignatureService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper service that:
 *   - seeds demo accounts and their Ed25519 keypairs on startup
 *   - maintains a simulated client-side private key store (simulating mobile device secure enclaves)
 *   - simulates "sender phone creates, signs, and encrypts a packet" flow
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    @Autowired private AccountRepository accounts;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private SignatureService signatureService;

    // Simulated client-side secure keystores (in production, private keys reside inside Android StrongBox/TEE)
    private final Map<String, PrivateKey> clientPrivateKeys = new ConcurrentHashMap<>();

    @PostConstruct
    public void seedAccounts() throws Exception {
        if (accounts.count() == 0) {
            seedAccount("alice@demo", "Alice", new BigDecimal("5000.00"));
            seedAccount("bob@demo",   "Bob",   new BigDecimal("1000.00"));
            seedAccount("carol@demo", "Carol", new BigDecimal("2500.00"));
            seedAccount("dave@demo",  "Dave",  new BigDecimal("500.00"));
            log.info("Seeded 4 demo accounts with registered Ed25519 public keys");
        }
    }

    private void seedAccount(String vpa, String name, BigDecimal balance) throws Exception {
        KeyPair keyPair = signatureService.generateKeyPair();
        String encodedPublicKey = signatureService.encodePublicKey(keyPair.getPublic());

        Account account = new Account(vpa, name, balance, encodedPublicKey, SignatureService.ALGORITHM);
        accounts.save(account);

        // Store private key in simulated device wallet
        clientPrivateKeys.put(vpa, keyPair.getPrivate());
    }

    /**
     * Simulates the sender's phone:
     *   1. Build a PaymentInstruction with a fresh nonce + signedAt timestamp.
     *   2. Sign the canonical instruction bytes using the sender's Ed25519 private key.
     *   3. Attach signature and algorithm to the PaymentInstruction.
     *   4. Encrypt the entire instruction with the server's public key (hybrid RSA+AES).
     *   5. Wrap in a MeshPacket with TTL.
     */
    public MeshPacket createPacket(String senderVpa, String receiverVpa,
                                   BigDecimal amount, String pin, int ttl) throws Exception {
        PrivateKey senderPrivateKey = clientPrivateKeys.get(senderVpa);
        if (senderPrivateKey == null) {
            throw new IllegalStateException("Simulated sender device has no signing key for: " + senderVpa);
        }

        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                sha256Hex(pin),
                UUID.randomUUID().toString(),       // nonce — guarantees uniqueness
                Instant.now().toEpochMilli()        // signedAt — for freshness check
        );

        // Sign canonical payment data before encryption
        String signature = signatureService.sign(instruction, senderPrivateKey);
        instruction.setSignature(signature);
        instruction.setSignatureAlgorithm(SignatureService.ALGORITHM);

        // Encrypt the signed payment instruction
        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(ttl);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);
        return packet;
    }

    /**
     * Simulates the sender's phone creating an offline payment instruction under Phase 3:
     *   1. Build PaymentInstruction with walletId, epoch, sequence counter, cumulative amount, certificate.
     *   2. Sign canonical instruction bytes using sender's Ed25519 private key.
     *   3. Encrypt payload with server's RSA public key (hybrid RSA+AES).
     *   4. Wrap in a MeshPacket.
     */
    public MeshPacket createOfflinePacket(String senderVpa, String receiverVpa,
                                          BigDecimal amount, String pin, int ttl,
                                          String walletId, Long walletEpoch,
                                          long sequenceCounter, BigDecimal cumulativeAmount,
                                          com.demo.upimesh.model.OfflineWalletCertificate certificate) throws Exception {
        PrivateKey senderPrivateKey = clientPrivateKeys.get(senderVpa);
        if (senderPrivateKey == null) {
            throw new IllegalStateException("Simulated sender device has no signing key for: " + senderVpa);
        }

        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                sha256Hex(pin),
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli(),
                null,
                SignatureService.ALGORITHM,
                walletId,
                walletEpoch,
                sequenceCounter,
                cumulativeAmount,
                certificate
        );

        String signature = signatureService.sign(instruction, senderPrivateKey);
        instruction.setSignature(signature);

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(ttl);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);
        return packet;
    }

    /**
     * Helper for test context to access simulated client private keys without exposing them via REST.
     */
    public PrivateKey getSimulatedClientPrivateKey(String vpa) {
        return clientPrivateKeys.get(vpa);
    }

    /**
     * Helper for test context to register simulated client keys.
     */
    public void registerSimulatedClientKey(String vpa, PrivateKey privateKey) {
        clientPrivateKeys.put(vpa, privateKey);
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes());
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}

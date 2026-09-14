package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop20ByOrderByIdDesc();
    boolean existsByPacketHash(String packetHash);
    Optional<Transaction> findByPacketHash(String packetHash);
    Optional<Transaction> findByWalletIdAndSequenceCounter(String walletId, Long sequenceCounter);
    List<Transaction> findByWalletIdAndStatus(String walletId, Transaction.Status status);
    List<Transaction> findByWalletIdOrderBySequenceCounterAsc(String walletId);
}

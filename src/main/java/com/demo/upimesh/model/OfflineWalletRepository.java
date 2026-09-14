package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OfflineWalletRepository extends JpaRepository<OfflineWallet, String> {
    Optional<OfflineWallet> findByWalletId(String walletId);
    List<OfflineWallet> findByOwnerVpa(String ownerVpa);
    List<OfflineWallet> findByOwnerVpaOrderByWalletEpochDesc(String ownerVpa);
    Optional<OfflineWallet> findByOwnerVpaAndStatus(String ownerVpa, OfflineWallet.WalletStatus status);
}

package com.demo.upimesh.db

import com.demo.upimesh.crypto.Ed25519Crypto
import com.demo.upimesh.db.entity.DeviceIdentity
import com.demo.upimesh.db.entity.EnrollmentState
import com.demo.upimesh.db.entity.OfflineWallet
import com.demo.upimesh.db.entity.WalletStatus
import com.demo.upimesh.db.keystore.JvmSimulatedMasterKeyProvider
import com.demo.upimesh.db.keystore.KeyStoreManager
import com.demo.upimesh.model.OfflineWalletCertificate
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

object TestFixtures {

    val mapper = jacksonObjectMapper()

    fun createTestMasterKeyManager(): KeyStoreManager {
        val deterministicKey = ByteArray(32) { (it + 1).toByte() }
        return KeyStoreManager(JvmSimulatedMasterKeyProvider(deterministicKey))
    }

    fun generateRsaKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }

    fun setupEnrolledDevice(
        database: UpiMeshDatabase,
        keyStoreManager: KeyStoreManager,
        ownerVpa: String = "alice@upi",
        deviceId: String = "dev-alice-001"
    ): KeyPair {
        val deviceKeyPair = Ed25519Crypto.generateKeyPair()
        val privateKeyBytes = deviceKeyPair.private.encoded
        val envelope = keyStoreManager.encryptPrivateKey(privateKeyBytes)
        val publicKeyBase64 = Ed25519Crypto.encodePublicKey(deviceKeyPair.public)

        val identity = DeviceIdentity(
            deviceId = deviceId,
            ownerVpa = ownerVpa,
            publicKey = publicKeyBase64,
            encryptedPrivateKey = envelope.ciphertextBase64,
            encryptionIv = envelope.ivBase64,
            enrollmentState = EnrollmentState.ENROLLED
        )
        database.deviceIdentityDao.insert(identity)
        return deviceKeyPair
    }

    fun setupActiveWallet(
        database: UpiMeshDatabase,
        ownerVpa: String = "alice@upi",
        walletId: String = "WLT-TEST-001",
        allocatedPaisa: Long = 100_000L, // ₹1,000.00
        validFrom: Long = System.currentTimeMillis() - 60_000L,
        validUntil: Long = System.currentTimeMillis() + 86_400_000L,
        deviceKeyPair: KeyPair? = null,
        serverIssuerKeyPair: KeyPair = Ed25519Crypto.generateKeyPair()
    ): OfflineWallet {
        val pubKey = if (deviceKeyPair != null) {
            Ed25519Crypto.encodePublicKey(deviceKeyPair.public)
        } else {
            "dummy_pub_key"
        }

        val unsignedCert = OfflineWalletCertificate(
            walletId = walletId,
            ownerVpa = ownerVpa,
            ownerPublicKey = pubKey,
            allocatedAmount = BigDecimal(allocatedPaisa).divide(BigDecimal(100)),
            walletEpoch = 1L,
            validFrom = validFrom,
            validUntil = validUntil,
            initialCounter = 0L
        )
        val certCanonicalBytes = com.demo.upimesh.crypto.CanonicalSerializer.toCanonicalBytes(unsignedCert)
        val issuerSig = Ed25519Crypto.sign(certCanonicalBytes, serverIssuerKeyPair.private)
        val cert = unsignedCert.copy(issuerSignature = issuerSig)
        val certJson = mapper.writeValueAsString(cert)

        val wallet = OfflineWallet(
            walletId = walletId,
            ownerVpa = ownerVpa,
            ownerPublicKey = pubKey,
            allocatedAmountPaisa = allocatedPaisa,
            localSpentAmountPaisa = 0L,
            settledAmountPaisa = 0L,
            remainingAmountPaisa = allocatedPaisa,
            sequenceCounter = 0L,
            walletEpoch = 1L,
            validFrom = validFrom,
            validUntil = validUntil,
            certificateJson = certJson,
            status = WalletStatus.ACTIVE
        )
        database.offlineWalletDao.insert(wallet)
        return wallet
    }
}

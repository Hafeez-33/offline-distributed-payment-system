package com.demo.upimesh.db.keystore

import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope containing an encrypted private key and its initialization vector.
 */
data class EncryptedKeyEnvelope(
    val ciphertextBase64: String,
    val ivBase64: String
)

/**
 * Interface abstracting master key operations.
 * Android runtime uses AndroidKeyStore; JVM unit testing uses JvmSimulatedMasterKeyProvider.
 */
interface MasterKeyProvider {
    fun getOrCreateKey(): SecretKey
    fun isHardwareBacked(): Boolean
}

/**
 * Android Keystore implementation for physical devices.
 * Uses KeyStore provider "AndroidKeyStore" and alias "upi_mesh_master_key".
 */
class AndroidKeyStoreMasterKeyProvider(
    private val keyAlias: String = KeyStoreManager.MASTER_KEY_ALIAS
) : MasterKeyProvider {

    override fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (ks.containsAlias(keyAlias)) {
            val entry = ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }
        // When running on real Android, reflection/spec is used so host JVM doesn't fail compilation
        val keyGenClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val builder = keyGenClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(keyAlias, 3) // PURPOSE_ENCRYPT (1) | PURPOSE_DECRYPT (2) = 3

        val setBlockModes = keyGenClass.getMethod("setBlockModes", Array<String>::class.java)
        setBlockModes.invoke(builder, arrayOf("GCM"))

        val setEncryptionPaddings = keyGenClass.getMethod("setEncryptionPaddings", Array<String>::class.java)
        setEncryptionPaddings.invoke(builder, arrayOf("NoPadding"))

        val setKeySize = keyGenClass.getMethod("setKeySize", Int::class.javaPrimitiveType)
        setKeySize.invoke(builder, 256)

        val buildMethod = keyGenClass.getMethod("build")
        val spec = buildMethod.invoke(builder)

        val kg = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val initMethod = KeyGenerator::class.java.getMethod("init", java.security.spec.AlgorithmParameterSpec::class.java)
        initMethod.invoke(kg, spec)
        return kg.generateKey()
    }

    override fun isHardwareBacked(): Boolean = true
}

/**
 * Software-backed AES-256 provider for deterministic JVM testing and simulation.
 */
class JvmSimulatedMasterKeyProvider(
    private val deterministicSecret: ByteArray? = null
) : MasterKeyProvider {

    private val masterKey: SecretKey by lazy {
        if (deterministicSecret != null) {
            require(deterministicSecret.size == 32) { "AES-256 key must be 32 bytes" }
            SecretKeySpec(deterministicSecret, "AES")
        } else {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(256)
            kg.generateKey()
        }
    }

    override fun getOrCreateKey(): SecretKey = masterKey

    override fun isHardwareBacked(): Boolean = false
}

/**
 * Key protection manager responsible for encrypting and decrypting device Ed25519 private keys.
 * Uses AES-256-GCM with a stable alias "upi_mesh_master_key".
 *
 * MEMORY HYGIENE NOTE:
 * Best-effort RAM zeroization is applied to sensitive byte arrays upon completion of cryptographic
 * operations. In garbage-collected runtimes (JVM / Android ART), RAM zeroization is best-effort memory
 * hygiene, not an absolute cryptographic guarantee.
 */
class KeyStoreManager(
    private val keyProvider: MasterKeyProvider = defaultProvider()
) {

    companion object {
        const val MASTER_KEY_ALIAS = "upi_mesh_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private fun defaultProvider(): MasterKeyProvider {
            return try {
                // If running on Android device with AndroidKeyStore
                KeyStore.getInstance("AndroidKeyStore")
                AndroidKeyStoreMasterKeyProvider(MASTER_KEY_ALIAS)
            } catch (e: Throwable) {
                // Fallback to JVM provider for test execution
                JvmSimulatedMasterKeyProvider()
            }
        }
    }

    private val rng = SecureRandom()

    /**
     * Encrypts the raw Ed25519 private key bytes using AES-256-GCM under the master key.
     */
    fun encryptPrivateKey(privateKeyBytes: ByteArray): EncryptedKeyEnvelope {
        val masterKey = keyProvider.getOrCreateKey()
        val iv = ByteArray(GCM_IV_BYTES)
        rng.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(privateKeyBytes)

        val envelope = EncryptedKeyEnvelope(
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            ivBase64 = Base64.getEncoder().encodeToString(iv)
        )

        // Best-effort RAM zeroization
        bestEffortZeroize(iv)

        return envelope
    }

    /**
     * Decrypts an encrypted private key envelope using AES-256-GCM under the master key.
     */
    fun decryptPrivateKey(envelope: EncryptedKeyEnvelope): ByteArray {
        val masterKey = keyProvider.getOrCreateKey()
        val iv = Base64.getDecoder().decode(envelope.ivBase64)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val decryptedBytes = cipher.doFinal(ciphertext)

        // Best-effort RAM zeroization on iv
        bestEffortZeroize(iv)

        return decryptedBytes
    }

    /**
     * Best-effort memory hygiene to wipe sensitive byte arrays in RAM.
     */
    fun bestEffortZeroize(data: ByteArray?) {
        if (data != null) {
            java.util.Arrays.fill(data, 0.toByte())
        }
    }
}

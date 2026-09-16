package com.demo.upimesh.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

object HybridCrypto {

    private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_KEY_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val RSA_ENCRYPTED_KEY_BYTES = 256 // 2048-bit RSA

    private val defaultRng = SecureRandom()

    fun decodeRsaPublicKey(base64PublicKey: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64PublicKey)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePublic(X509EncodedKeySpec(keyBytes))
    }

    fun decodeRsaPrivateKey(base64PrivateKey: String): PrivateKey {
        val keyBytes = Base64.getDecoder().decode(base64PrivateKey)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    fun encrypt(
        plaintext: ByteArray,
        serverPublicKey: PublicKey,
        fixedAesKeyBytes: ByteArray? = null,
        fixedIv: ByteArray? = null,
        oaepRng: SecureRandom? = null
    ): String {
        // 1. Generate or load 256-bit AES key
        val aesKey: SecretKey = if (fixedAesKeyBytes != null) {
            require(fixedAesKeyBytes.size == 32) { "AES-256 key must be exactly 32 bytes" }
            SecretKeySpec(fixedAesKeyBytes, "AES")
        } else {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(AES_KEY_BITS)
            kg.generateKey()
        }

        // 2. Generate or load 12-byte IV
        val iv = if (fixedIv != null) {
            require(fixedIv.size == GCM_IV_BYTES) { "GCM IV must be exactly 12 bytes" }
            fixedIv
        } else {
            val buf = ByteArray(GCM_IV_BYTES)
            defaultRng.nextBytes(buf)
            buf
        }

        // 3. AES-GCM encrypt
        val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val aesCiphertextTag = aesCipher.doFinal(plaintext)

        // 4. RSA-OAEP encrypt AES key
        val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
        val oaep = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        if (oaepRng != null) {
            rsaCipher.init(Cipher.ENCRYPT_MODE, serverPublicKey, oaep, oaepRng)
        } else {
            rsaCipher.init(Cipher.ENCRYPT_MODE, serverPublicKey, oaep)
        }
        val rsaEncryptedAesKey = rsaCipher.doFinal(aesKey.encoded)
        require(rsaEncryptedAesKey.size == RSA_ENCRYPTED_KEY_BYTES) {
            "RSA ciphertext must be exactly $RSA_ENCRYPTED_KEY_BYTES bytes"
        }

        // 5. Pack into envelope: [256-byte RSA key][12-byte IV][AES ciphertext + 16-byte tag]
        val buffer = ByteBuffer.allocate(rsaEncryptedAesKey.size + iv.size + aesCiphertextTag.size)
        buffer.put(rsaEncryptedAesKey)
        buffer.put(iv)
        buffer.put(aesCiphertextTag)

        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decrypt(base64Ciphertext: String, serverPrivateKey: PrivateKey): ByteArray {
        val all = Base64.getDecoder().decode(base64Ciphertext)
        val minLen = RSA_ENCRYPTED_KEY_BYTES + GCM_IV_BYTES + (GCM_TAG_BITS / 8)
        if (all.size < minLen) {
            throw IllegalArgumentException("Ciphertext too short: ${all.size} bytes (expected >= $minLen)")
        }

        val encryptedAesKey = ByteArray(RSA_ENCRYPTED_KEY_BYTES)
        val iv = ByteArray(GCM_IV_BYTES)
        val aesCiphertextTag = ByteArray(all.size - RSA_ENCRYPTED_KEY_BYTES - GCM_IV_BYTES)

        val buffer = ByteBuffer.wrap(all)
        buffer.get(encryptedAesKey)
        buffer.get(iv)
        buffer.get(aesCiphertextTag)

        // 1. RSA-OAEP decrypt AES key
        val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
        val oaep = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        rsaCipher.init(Cipher.DECRYPT_MODE, serverPrivateKey, oaep)
        val aesKeyBytes = rsaCipher.doFinal(encryptedAesKey)
        val aesKey = SecretKeySpec(aesKeyBytes, "AES")

        // 2. AES-GCM decrypt payload + verify tag
        val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return aesCipher.doFinal(aesCiphertextTag)
    }
}

package com.demo.upimesh.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Ed25519Crypto {

    const val ALGORITHM = "Ed25519"

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun getSignature(): Signature {
        return try {
            Signature.getInstance(ALGORITHM, BouncyCastleProvider.PROVIDER_NAME)
        } catch (e: Exception) {
            Signature.getInstance(ALGORITHM)
        }
    }

    private fun getKeyFactory(): KeyFactory {
        return try {
            KeyFactory.getInstance(ALGORITHM, BouncyCastleProvider.PROVIDER_NAME)
        } catch (e: Exception) {
            KeyFactory.getInstance(ALGORITHM)
        }
    }

    fun generateKeyPair(): KeyPair {
        val kpg = try {
            KeyPairGenerator.getInstance(ALGORITHM, BouncyCastleProvider.PROVIDER_NAME)
        } catch (e: Exception) {
            KeyPairGenerator.getInstance(ALGORITHM)
        }
        return kpg.generateKeyPair()
    }

    fun decodePublicKey(base64PublicKey: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64PublicKey)
        val kf = getKeyFactory()
        return kf.generatePublic(X509EncodedKeySpec(keyBytes))
    }

    fun decodePrivateKey(base64PrivateKey: String): PrivateKey {
        val keyBytes = Base64.getDecoder().decode(base64PrivateKey)
        val kf = getKeyFactory()
        return kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    fun encodePublicKey(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    fun encodePrivateKey(privateKey: PrivateKey): String {
        return Base64.getEncoder().encodeToString(privateKey.encoded)
    }

    fun sign(payload: ByteArray, privateKey: PrivateKey): String {
        val sig = getSignature()
        sig.initSign(privateKey)
        sig.update(payload)
        val signatureBytes = sig.sign()
        require(signatureBytes.size == 64) { "Ed25519 signature must be exactly 64 bytes" }
        return Base64.getEncoder().encodeToString(signatureBytes)
    }

    fun verify(payload: ByteArray, signatureBase64: String?, publicKey: PublicKey?): Boolean {
        if (signatureBase64.isNullOrBlank() || publicKey == null) {
            return false
        }
        return try {
            val sigBytes = Base64.getDecoder().decode(signatureBase64)
            if (sigBytes.size != 64) {
                return false
            }
            val sig = getSignature()
            sig.initVerify(publicKey)
            sig.update(payload)
            sig.verify(sigBytes)
        } catch (e: Exception) {
            false
        }
    }
}

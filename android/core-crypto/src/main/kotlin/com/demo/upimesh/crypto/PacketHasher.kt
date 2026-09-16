package com.demo.upimesh.crypto

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object PacketHasher {

    fun hashCiphertext(base64Ciphertext: String?): String {
        if (base64Ciphertext == null) {
            throw IllegalArgumentException("ciphertext must not be null")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(base64Ciphertext.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}

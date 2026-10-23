package com.awclippy.android

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ClipboardCrypto(private val sharedSecret: ByteArray) {
    
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE_BITS = 128
    }

    init {
        require(sharedSecret.size == KEY_SIZE) { "Secret key must be 32 bytes" }
    }

    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        
        // Generate random nonce
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, nonce)
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        
        // Prepend 8-byte millisecond timestamp to plaintext for replay protection
        val nowMs = System.currentTimeMillis()
        val textBytes = plaintext.toByteArray(Charsets.UTF_8)
        val dataToEncrypt = java.nio.ByteBuffer.allocate(8 + textBytes.size)
            .putLong(nowMs)
            .put(textBytes)
            .array()
        
        val ciphertext = cipher.doFinal(dataToEncrypt)
        
        // Prepend nonce to ciphertext
        return nonce + ciphertext
    }

    fun decrypt(data: ByteArray): String {
        require(data.size > NONCE_SIZE) { "Data too short" }
        
        val nonce = data.sliceArray(0 until NONCE_SIZE)
        val ciphertext = data.sliceArray(NONCE_SIZE until data.size)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, nonce)
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val decrypted = cipher.doFinal(ciphertext)
        
        require(decrypted.size >= 8) { "Decrypted data too short for timestamp" }
        
        // Verify timestamp within 60 seconds to prevent replay attacks
        val buffer = java.nio.ByteBuffer.wrap(decrypted)
        val packetTimeMs = buffer.long
        val nowMs = System.currentTimeMillis()
        val diff = Math.abs(nowMs - packetTimeMs)
        
        if (diff > 60_000) {
            throw SecurityException("Replay attack or clock skew detected: packet diff is ${diff}ms (limit 60000ms)")
        }
        
        val textBytes = ByteArray(decrypted.size - 8)
        buffer.get(textBytes)
        return String(textBytes, Charsets.UTF_8)
    }

    fun hashText(text: String): Long {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
        return hash.sliceArray(0..7).let { bytes ->
            bytes.foldIndexed(0L) { i, acc, byte ->
                acc or ((byte.toLong() and 0xFF) shl (i * 8))
            }
        }
    }
}

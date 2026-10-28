package com.mewsic.app.harbour

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts JioSaavn's `encrypted_media_url` into a direct CDN stream URL.
 * Uses standard DES in ECB mode with key "38346591".
 */
object JioSaavnDecryptor {

    private const val DES_KEY = "38346591"

    fun decrypt(encryptedBase64: String): String? {
        if (encryptedBase64.isBlank()) return null
        return try {
            val keyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            val url = String(decrypted, Charsets.UTF_8).trim()

            // Upgrade to 320kbps for pristine audio quality
            url.replace("_96.mp4", "_320.mp4")
                .replace("_160.mp4", "_320.mp4")
                .replace("_96.mp3", "_320.mp3")
                .replace("_160.mp3", "_320.mp3")
        } catch (_: Exception) {
            null
        }
    }
}

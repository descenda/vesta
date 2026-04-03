package org.des.vesta.crypto

import android.util.Base64
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.*

class CryptoEngine {
    private var keyPair: KeyPair

    constructor() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        keyPair = kpg.genKeyPair()
    }

    constructor(privateKeyPem: String, publicKeyPem: String) {
        val keyFactory = KeyFactory.getInstance("RSA")
        val privBytes = Base64.decode(stripPemHeaders(privateKeyPem), Base64.DEFAULT)
        val pubBytes = Base64.decode(stripPemHeaders(publicKeyPem), Base64.DEFAULT)
        
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
        keyPair = KeyPair(publicKey, privateKey)
    }

    private fun stripPemHeaders(pem: String): String {
        return pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
            .trim()
    }

    fun getPublicKeyPem(): String {
        val pubKey = keyPair.public.encoded
        val b64 = Base64.encodeToString(pubKey, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----"
    }
    
    fun getPrivateKeyPem(): String {
        val privKey = keyPair.private.encoded
        val b64 = Base64.encodeToString(privKey, Base64.NO_WRAP)
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----"
    }

    fun generateFernetKey(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun decryptFernetKey(encryptedB64: String): String {
        val encryptedData = Base64.decode(encryptedB64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val oaepSpec = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private, oaepSpec)
        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes) // Return the B64 string itself
    }

    fun encryptFernetKey(fernetKeyB64: String, peerPublicKeyB64: String): String {
        // 1. Более надежная очистка ключа
        val cleanKey = peerPublicKeyB64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            // Удаляем ВСЕ невидимые символы, включая реальные переносы строк и пробелы
            .replace("\\s".toRegex(), "")
            // Важно: если сервер прислал "буквальные" \n как текст (из JSON)
            .replace("\\n", "")
            .replace("\\r", "")
            .trim()

        val fernetKeyBytes = fernetKeyB64.toByteArray()

        // 2. Декодируем аккуратно
        val peerKeyBytes = try {
            Base64.decode(cleanKey, Base64.DEFAULT)
        } catch (e: Exception) {
            throw Exception("Base64 decode failed: ${e.message}")
        }

        val keyFactory = KeyFactory.getInstance("RSA")
        // ТУТ происходил вылет (строка 81 в твоем файле)
        val peerPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerKeyBytes))

        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val oaepSpec = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.ENCRYPT_MODE, peerPublicKey, oaepSpec)
        val encrypted = cipher.doFinal(fernetKeyBytes)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
    fun encryptFernet(text: String, keyB64: String): String {
        val key = Base64.decode(keyB64, Base64.URL_SAFE)
        val signingKey = key.copyOfRange(0, 16)
        val encryptionKey = key.copyOfRange(16, 32)

        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(text.toByteArray())

        val version = 0x80.toByte()
        val timestamp = (System.currentTimeMillis() / 1000)
        val timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array()

        val dataToSign = ByteBuffer.allocate(1 + 8 + 16 + ciphertext.size)
            .put(version)
            .put(timestampBytes)
            .put(iv)
            .put(ciphertext)
            .array()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        val hmac = mac.doFinal(dataToSign)

        val result = ByteBuffer.allocate(dataToSign.size + 32)
            .put(dataToSign)
            .put(hmac)
            .array()

        return Base64.encodeToString(result, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun decryptFernet(tokenB64: String, keyB64: String): String {
        val key = Base64.decode(keyB64, Base64.URL_SAFE)
        val signingKey = key.copyOfRange(0, 16)
        val encryptionKey = key.copyOfRange(16, 32)

        val token = Base64.decode(tokenB64, Base64.URL_SAFE)
        if (token[0] != 0x80.toByte()) throw Exception("Invalid version")

        val hmacReceived = token.copyOfRange(token.size - 32, token.size)
        val dataToVerify = token.copyOfRange(0, token.size - 32)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        val hmacCalculated = mac.doFinal(dataToVerify)

        if (!MessageDigest.isEqual(hmacReceived, hmacCalculated)) throw Exception("HMAC mismatch")

        val iv = dataToVerify.copyOfRange(9, 25)
        val ciphertext = dataToVerify.copyOfRange(25, dataToVerify.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext))
    }
}

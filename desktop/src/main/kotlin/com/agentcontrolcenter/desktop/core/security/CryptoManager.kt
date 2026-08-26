package com.agentcontrolcenter.desktop.core.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端对称加密工具 — 与 Android/iOS 端 `AH1:` 格式完全互操作。
 *
 *  - AES/GCM/NoPadding（AEAD，带完整性校验），密钥由用户口令经
 *    PBKDF2WithHmacSHA256 派生，每次加密使用随机 IV 与随机 salt。
 *  - 纯 `javax.crypto` + `java.util.Base64`（桌面端无 android.util.Base64），
 *    三平台（Windows/macOS/Linux）行为一致。
 *  - 输出格式：`AH1:` + Base64( IV[12] ‖ salt[16] ‖ ciphertext )。
 *  - 适用场景：Agent Control Center ↔ Agent Control Center 对等模式
 *    （Hermes/OpenClaw 自托管）。
 *  - [decrypt] 对「非 AH1: 前缀」或解密失败的内容返回 `null`，调用方据此
 *    回退为明文展示，保证与不支持 E2E 的对端互通时不崩溃。
 */
object CryptoManager {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_LENGTH = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 600000 // 与 Android/iOS 端一致（OWASP 2023 推荐）
    private const val PBKDF2_SALT_LENGTH = 16
    private const val PREFIX = "AH1:"

    private val secureRandom = SecureRandom()

    private fun b64Encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun b64Decode(s: String): ByteArray =
        Base64.getDecoder().decode(s)

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    /** 加密明文，返回带 `AH1:` 前缀的可传输字符串。 */
    fun encrypt(plaintext: String, passphrase: String): String {
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val salt = ByteArray(PBKDF2_SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = iv + salt + ciphertext
        return PREFIX + b64Encode(payload)
    }

    /**
     * 解密 [payload]。若不是本工具加密的内容（无 `AH1:` 前缀）或口令错误导致
     * 校验失败，返回 `null`，调用方应回退为原文展示。
     */
    fun decrypt(payload: String, passphrase: String): String? {
        if (!payload.startsWith(PREFIX)) return null
        return try {
            val raw = b64Decode(payload.removePrefix(PREFIX))
            val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
            val salt = raw.copyOfRange(GCM_IV_LENGTH, GCM_IV_LENGTH + PBKDF2_SALT_LENGTH)
            val ciphertext = raw.copyOfRange(GCM_IV_LENGTH + PBKDF2_SALT_LENGTH, raw.size)
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun isEncrypted(payload: String): Boolean = payload.startsWith(PREFIX)
}

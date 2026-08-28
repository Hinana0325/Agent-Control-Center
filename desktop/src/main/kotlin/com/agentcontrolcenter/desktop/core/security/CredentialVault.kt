package com.agentcontrolcenter.desktop.core.security

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 静态凭据加密（`AKS:` 格式）— 桌面端对应 Android `KeystoreManager` 与 iOS
 * `KeychainManager` 的第三端实现。
 *
 * ## 为什么不是硬件 Keystore
 *
 * Android 由 TEE/StrongBox 保护主密钥、iOS 由 Keychain 保护，二者**密钥材料
 * 不可导出**。桌面端（Windows/macOS/Linux）在纯 JVM 下没有跨平台等价物
 * （DPAPI / Keychain / libsecret 均需 JNI 或原生依赖），因此主密钥退化为：
 *
 * **随机 256 位主密钥 + 仅属主可读的本地密钥文件**
 * （`~/.agent-control-center/master.key`，POSIX 权限 600，目录 700）。
 *
 * 保护强度**低于**移动端硬件方案：能防住「文件被拷走/同步到云端后离线破解」，
 * 防不住「以同一用户身份运行的恶意进程」。这是桌面端的能力上限，不是实现
 * 疏漏——相比此前的 API Key **明文落盘**已是实质改进。
 *
 * ## 与移动端的一致性
 *
 * 密文格式严格遵循 `SECURITY.md` §4.1，与 Android/iOS 逐字节同构：
 *
 * ```
 * AKS:<Base64(IV[12] ‖ ciphertext)>     // ciphertext 含 16 字节 GCM 认证标签
 * ```
 *
 * 语义亦对齐 `SECURITY.md` §4.4 的三态约定（见 [decryptOrRaw]）。
 *
 * @param rootDir 数据目录，默认 `~/.agent-control-center`（与 [JsonStore] 同目录）
 */
class CredentialVault(
    private val rootDir: File = File(System.getProperty("user.home"), ".agent-control-center")
) {
    private val keyFile = File(rootDir, MASTER_KEY_FILE)

    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * 获取或创建主密钥。首次调用时生成随机密钥并落盘，之后走内存缓存。
     *
     * 并发安全：@Synchronized 防止并发首次调用各自生成密钥互相覆盖
     * （对齐 Android KeystoreManager 的 High 8 修复）。
     *
     * @throws CredentialVaultException 密钥文件存在但内容损坏。此时**不会**
     *   静默重新生成密钥——那会让已有凭据永久不可解密。宁可失败可见，
     *   不可静默丢数据。
     */
    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        if (!rootDir.isDirectory && !rootDir.mkdirs() && !rootDir.isDirectory) {
            throw CredentialVaultException("Cannot create data directory: ${rootDir.absolutePath}")
        }
        restrictDirectoryPermissions()
        val key = if (keyFile.exists()) loadKey() else generateAndPersistKey()
        cachedKey = key
        return key
    }

    private fun generateAndPersistKey(): SecretKey {
        val generated = KeyGenerator.getInstance(ALGORITHM).apply { init(KEY_BITS) }.generateKey()
        val encoded = Base64.getEncoder().encodeToString(generated.encoded)
        keyFile.writeText(encoded, Charsets.UTF_8)
        restrictKeyFilePermissions()
        return generated
    }

    private fun loadKey(): SecretKey {
        val raw = runCatching { keyFile.readText(Charsets.UTF_8).trim() }
            .getOrElse { throw CredentialVaultException("Cannot read master key: ${it.message}") }
        val bytes = runCatching { Base64.getDecoder().decode(raw) }
            .getOrNull()
        if (bytes == null || bytes.size != KEY_BYTES) {
            throw CredentialVaultException(
                "Master key file is corrupted (${keyFile.absolutePath}). " +
                    "Stored credentials can no longer be decrypted; delete the file to reset."
            )
        }
        return SecretKeySpec(bytes, ALGORITHM)
    }

    /**
     * 加密明文，返回带 `AKS:` 前缀的可存储字符串。
     *
     * 幂等：空白串原样返回；已是 `AKS:` 前缀的内容直接返回，避免双重加密
     * （与 Android KeystoreManager.encrypt 行为一致）。
     *
     * @throws CredentialVaultException 主密钥不可用（无法安全落盘凭据）
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return plaintext
        if (plaintext.startsWith(PREFIX)) return plaintext
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    /**
     * 解密 [payload]。无 `AKS:` 前缀或 GCM 认证失败时返回 `null`，
     * 调用方据此区分「旧版明文」与「密文损坏」两种情形。
     */
    fun decrypt(payload: String): String? {
        if (!payload.startsWith(PREFIX)) return null
        return try {
            val raw = Base64.getDecoder().decode(payload.removePrefix(PREFIX))
            if (raw.size <= GCM_IV_LENGTH) return null
            val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 智能解密，遵循 `SECURITY.md` §4.4 三态约定：
     *
     * | 输入情形 | 判定 | 返回 |
     * |:---|:---|:---|
     * | 空白 | `isBlank()` | 原值 |
     * | 未加密（旧版明文） | 无 `AKS:` 前缀 | 原值（向后兼容，待下次写入转密文） |
     * | 已加密且解密成功 | 认证通过 | 明文 |
     * | 已加密但解密失败 | 认证失败/密钥不可用 | 空串 `""` |
     *
     * 解密失败返回空串而非回退原文，避免把密文当明文使用导致鉴权失败难以定位。
     */
    fun decryptOrRaw(value: String): String {
        if (value.isBlank()) return value
        if (!value.startsWith(PREFIX)) return value
        return decrypt(value) ?: ""
    }

    /** 判断内容是否已按 `AKS:` 格式加密。 */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    // MARK: - 文件权限（best-effort）

    /**
     * POSIX 平台收紧密钥文件权限为 600（仅属主读写）。
     *
     * Windows 不支持 PosixFilePermission，此处静默跳过；额外尝试设置
     * `dos:hidden` 降低误操作概率。非 POSIX 平台无法做到仅属主可读，
     * 保护强度相应下降，属已知限制。
     */
    private fun restrictKeyFilePermissions() {
        runCatching {
            Files.setPosixFilePermissions(
                keyFile.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
        runCatching { Files.setAttribute(keyFile.toPath(), "dos:hidden", true) }
    }

    private fun restrictDirectoryPermissions() {
        runCatching {
            Files.setPosixFilePermissions(
                rootDir.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
        }
    }

    private companion object {
        const val ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val KEY_BYTES = 32
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
        const val PREFIX = "AKS:"
        const val MASTER_KEY_FILE = "master.key"
    }
}

/** 凭据保险库不可用（主密钥缺失且无法生成，或密钥文件损坏）。 */
class CredentialVaultException(message: String) : IllegalStateException(message)

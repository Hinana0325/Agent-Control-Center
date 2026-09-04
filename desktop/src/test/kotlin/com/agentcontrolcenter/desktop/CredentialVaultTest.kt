package com.agentcontrolcenter.desktop

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.app.AppStore
import com.agentcontrolcenter.desktop.core.security.CredentialVault
import com.agentcontrolcenter.desktop.core.security.CredentialVaultException
import com.agentcontrolcenter.desktop.data.persistence.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 静态凭据加密测试 — `AKS:` 格式与 `SECURITY.md` §4.1/§4.4 对齐，
 * 语义对齐 Android KeystoreManagerTest 与 iOS KeychainManager 行为。
 *
 * 用例 16.9（v5.3.0 P0）：桌面端 API Key 明文落盘修复。
 */
class CredentialVaultTest {

    private val tmp = File(
        System.getProperty("java.io.tmpdir"),
        "acc-vault-test-${System.nanoTime()}"
    ).apply { mkdirs() }

    private val vault = CredentialVault(tmp)

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    // MARK: - 格式与往返

    @Test
    fun `加解密往返`() {
        val plaintext = "sk-proj-abc123中文密钥🔐"
        val encrypted = vault.encrypt(plaintext)
        assertTrue(encrypted.startsWith("AKS:"))
        assertEquals(plaintext, vault.decrypt(encrypted))
    }

    @Test
    fun `密文结构为 AKS 前缀加 IV12 加 GCM 密文`() {
        val payload = Base64.getDecoder().decode(vault.encrypt("secret").removePrefix("AKS:"))
        // IV[12] + ciphertext（含 16 字节 GCM 认证标签）
        assertTrue(payload.size > 12 + 16, "payload 应长于 IV+GCM tag，实际 ${payload.size}")
    }

    @Test
    fun `同一明文两次加密产生不同密文（随机 IV）`() {
        assertNotEquals(vault.encrypt("same"), vault.encrypt("same"))
    }

    @Test
    fun `空白内容原样返回不加密`() {
        assertEquals("", vault.encrypt(""))
        assertEquals("   ", vault.encrypt("   "))
    }

    @Test
    fun `已加密内容不重复加密（幂等）`() {
        val once = vault.encrypt("secret")
        assertEquals(once, vault.encrypt(once))
    }

    // MARK: - decrypt 契约

    @Test
    fun `decrypt 对非 AKS 内容返回 null`() {
        assertNull(vault.decrypt("plain-text-key"))
        assertNull(vault.decrypt(""))
        assertNull(vault.decrypt("AH1:not-our-static-format"))
    }

    @Test
    fun `口令或密文被篡改时解密失败返回 null`() {
        val encrypted = vault.encrypt("secret")
        val raw = Base64.getDecoder().decode(encrypted.removePrefix("AKS:"))
        // 翻转密文首字节，GCM 认证必然失败
        raw[13] = (raw[13].toInt() xor 0xFF).toByte()
        val tampered = "AKS:" + Base64.getEncoder().encodeToString(raw)
        assertNull(vault.decrypt(tampered))
    }

    @Test
    fun `截断的密文不会导致异常`() {
        assertNull(vault.decrypt("AKS:" + Base64.getEncoder().encodeToString(ByteArray(4))))
    }

    // MARK: - decryptOrRaw 三态（SECURITY.md §4.4）

    @Test
    fun `decryptOrRaw 三态语义`() {
        val encrypted = vault.encrypt("real-secret")
        // 1) 空白 → 原值
        assertEquals("", vault.decryptOrRaw(""))
        // 2) 旧版明文（无前缀）→ 原值，向后兼容
        assertEquals("legacy-plaintext", vault.decryptOrRaw("legacy-plaintext"))
        // 3) 正常密文 → 明文
        assertEquals("real-secret", vault.decryptOrRaw(encrypted))
    }

    @Test
    fun `decryptOrRaw 对损坏密文返回空串而非原文`() {
        val broken = "AKS:" + Base64.getEncoder().encodeToString(ByteArray(40))
        assertEquals("", vault.decryptOrRaw(broken), "损坏密文须返回空串，避免密文被当明文使用")
    }

    @Test
    fun `isEncrypted 识别前缀`() {
        assertTrue(vault.isEncrypted(vault.encrypt("x")))
        assertFalse(vault.isEncrypted("normal-content"))
    }

    // MARK: - 主密钥生命周期

    @Test
    fun `同目录新实例可解密既有密文（主密钥持久化）`() {
        val encrypted = vault.encrypt("persisted-secret")
        assertEquals("persisted-secret", CredentialVault(tmp).decrypt(encrypted))
    }

    @Test
    fun `不同目录主密钥不同无法互解（设备绑定语义）`() {
        val encrypted = vault.encrypt("device-bound")
        val other = File(tmp, "other-profile").apply { mkdirs() }
        assertNull(CredentialVault(other).decrypt(encrypted))
    }

    @Test
    fun `密钥文件损坏时抛异常且不静默重生成`() {
        val keyFile = File(tmp, "master.key")
        vault.encrypt("warm-up") // 触发主密钥生成
        val corrupted = "not-valid-base64-!!!"
        // Windows：CredentialVault 生成密钥后设 dos:hidden，而 File.writeText
        // 底层 FileOutputStream 打开隐藏文件直接拒绝访问（本机与 windows-latest
        // CI 均复现，v5.3.0 起 desktop Windows 矩阵一直红）。NIO
        // Files.writeString 会携带既有属性打开，可正常覆写。
        Files.writeString(keyFile.toPath(), corrupted)

        val fresh = CredentialVault(tmp)
        assertFailsWith<CredentialVaultException> { fresh.encrypt("anything") }
        // 关键：不得静默重新生成密钥，否则既有凭据永久不可解密
        assertEquals(corrupted, keyFile.readText(), "损坏的密钥文件必须原样保留，交由用户决定")
    }

    @Test
    fun `密钥文件权限收敛为仅属主可读写（POSIX）`() {
        val keyFile = File(tmp, "master.key")
        vault.encrypt("warm-up")
        val view = Files.getFileAttributeView(keyFile.toPath(), PosixFileAttributeView::class.java)
        // Windows 无 POSIX 权限模型，跳过（已知限制）
        if (view != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(keyFile.toPath()),
                "密钥文件必须仅属主可读写"
            )
        }
        assertTrue(keyFile.exists())
    }

    // MARK: - AppStore 接线（迁移）

    @Test
    fun `AppStore 启动时把历史明文 API Key 迁移为密文`() = runBlocking {
        val dir = File(tmp, "migration").apply { mkdirs() }
        val seed = JsonStore(dir)
        val config = AgentConfig(id = "a1", name = "Legacy", apiKey = "sk-legacy-plaintext")
        seed.saveAgents(listOf(config))
        // 前置校验：v5.2.0 行为——明文直接进文件
        assertTrue(File(dir, "agents.json").readText().contains("sk-legacy-plaintext"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appStore = AppStore(JsonStore(dir), scope, vault = CredentialVault(dir))
        try {
            // 内存态保持明文，供传输层建连直接使用
            assertEquals("sk-legacy-plaintext", appStore.agents.value.single().apiKey)

            // 轮询条件必须容忍「尚未就绪」的**所有**中间态。
            //
            // 原实现写作 `while (!JsonStore(dir).loadAgents().single().apiKey.startsWith("AKS:"))`，
            // 而 `loadAgents()` 在文件缺失**或解析失败**时都返回 `emptyList()`——
            // 一旦轮询撞上写盘过程中被截断的瞬间，`.single()` 抛
            // `NoSuchElementException`，把「还没写完」误判成「迁移失败」。
            //
            // 这是真实的平台分歧而非偶发抖动：Linux/macOS 上
            // `Files.move(ATOMIC_MOVE)` 生效，读取者永远看不到中间态；
            // Windows 上该调用退化后会先截断目标文件再写入，窗口显著变长，
            // 因此只在 windows-latest 上稳定复现（v5.3.0 发版核查时发现）。
            withTimeout(15_000) {
                while (JsonStore(dir).loadAgents().firstOrNull()?.apiKey?.startsWith("AKS:") != true) {
                    delay(20)
                }
            }
            val persisted = JsonStore(dir).loadAgents().single().apiKey
            assertTrue(persisted.startsWith("AKS:"))
            assertFalse(persisted.contains("sk-legacy-plaintext"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `AppStore 载入后内存态为明文而文件为密文`() = runBlocking {
        val dir = File(tmp, "roundtrip").apply { mkdirs() }
        val stored = CredentialVault(dir)
        JsonStore(dir).saveAgents(
            listOf(AgentConfig(id = "a1", name = "Encrypted", apiKey = stored.encrypt("sk-stored")))
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appStore = AppStore(JsonStore(dir), scope, vault = CredentialVault(dir))
        try {
            assertEquals("sk-stored", appStore.agents.value.single().apiKey, "内存态须解密回明文")
            assertTrue(
                File(dir, "agents.json").readText().contains("AKS:"),
                "文件态须保持密文"
            )
        } finally {
            scope.cancel()
        }
    }
}

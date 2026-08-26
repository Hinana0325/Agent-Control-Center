package com.agentcontrolcenter.desktop

import com.agentcontrolcenter.desktop.core.security.CryptoManager
import com.agentcontrolcenter.desktop.core.security.UrlValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 安全部件测试 — 与 Android 端 CryptoManagerTest / UrlValidator 行为对齐。
 */
class SecurityTest {

    // MARK: - UrlValidator（SSRF 防护）

    @Test
    fun `允许 http-https-ws-wss scheme`() {
        assertNotNull(UrlValidator.validate("http://example.com:8080/api"))
        assertNotNull(UrlValidator.validate("https://api.openai.com/v1"))
        assertNotNull(UrlValidator.validate("ws://127.0.0.1:9000/ws"))
        assertNotNull(UrlValidator.validate("wss://agent.example.com/ws"))
    }

    @Test
    fun `拒绝危险 scheme`() {
        assertNull(UrlValidator.validate("file:///etc/passwd"))
        assertNull(UrlValidator.validate("data:text/html,<script>"))
        assertNull(UrlValidator.validate("ftp://example.com"))
        assertNull(UrlValidator.validate("javascript:alert(1)"))
    }

    @Test
    fun `拒绝云 metadata 与保留地址段`() {
        assertNull(UrlValidator.validate("http://169.254.169.254/latest/meta-data")) // AWS metadata
        assertNull(UrlValidator.validate("http://0.0.0.0/"))
        assertNull(UrlValidator.validate("http://255.255.255.255/"))
        assertNull(UrlValidator.validate("http://100.64.0.1/"))       // CGNAT
        assertNull(UrlValidator.validate("http://224.0.0.1/"))        // 组播
        assertNull(UrlValidator.validate("http://240.0.0.1/"))        // 保留
    }

    @Test
    fun `本地部署场景放行`() {
        assertNotNull(UrlValidator.validate("http://127.0.0.1:11434"))     // Ollama
        assertNotNull(UrlValidator.validate("http://localhost:1234"))      // LM Studio
        assertNotNull(UrlValidator.validate("http://192.168.1.100:8000"))  // 局域网自托管
        assertNotNull(UrlValidator.validate("http://10.0.0.5:9000"))
        // allowLocalhost=false 时私网与回环被拒绝（公网出口校验）
        assertNull(UrlValidator.validate("http://127.0.0.1:11434", allowLocalhost = false))
        assertNull(UrlValidator.validate("http://192.168.1.100:8000", allowLocalhost = false))
    }

    @Test
    fun `拒绝链路本地 IPv6`() {
        assertNull(UrlValidator.validate("http://[fe80::1]/"))
        assertNotNull(UrlValidator.validate("http://[2001:db8::1]/"))
    }

    // MARK: - CryptoManager（E2E AH1: 格式，与移动端互操作）

    @Test
    fun `加解密往返`() {
        val passphrase = "correct horse battery staple"
        val plaintext = "你好，Agent！Hello from desktop. 🖥️"
        val encrypted = CryptoManager.encrypt(plaintext, passphrase)
        assertTrue(encrypted.startsWith("AH1:"))
        assertEquals(plaintext, CryptoManager.decrypt(encrypted, passphrase))
    }

    @Test
    fun `非 AH1 内容返回 null 由调用方回退明文`() {
        assertNull(CryptoManager.decrypt("plain text message", "key"))
        assertNull(CryptoManager.decrypt("", "key"))
    }

    @Test
    fun `口令错误解密失败返回 null`() {
        val encrypted = CryptoManager.encrypt("secret", "right-passphrase")
        assertNull(CryptoManager.decrypt(encrypted, "wrong-passphrase"))
    }

    @Test
    fun `同一明文两次加密产生不同密文（随机 IV-salt）`() {
        val a = CryptoManager.encrypt("same", "pass")
        val b = CryptoManager.encrypt("same", "pass")
        assertFalse(a == b)
    }

    @Test
    fun `isEncrypted 识别前缀`() {
        assertTrue(CryptoManager.isEncrypted(CryptoManager.encrypt("x", "p")))
        assertFalse(CryptoManager.isEncrypted("normal content"))
    }
}

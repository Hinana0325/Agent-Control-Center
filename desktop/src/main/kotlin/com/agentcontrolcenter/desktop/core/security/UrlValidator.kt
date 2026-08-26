package com.agentcontrolcenter.desktop.core.security

import java.net.URL

/**
 * URL 安全校验器 — 与 Android/iOS 端 `UrlValidator` / `URLValidator` 完全对齐。
 *
 * 防止用户输入危险 URL 触发 SSRF（如 `http://169.254.169.254/...` 探活
 * 云 metadata 泄漏 apiKey）、危险 scheme（file:// / data://）等。
 * 纯 JVM 实现（java.net.URL），桌面三平台行为一致。
 */
object UrlValidator {

    /** 允许的 scheme 白名单 */
    val allowedSchemes: Set<String> = setOf("http", "https", "ws", "wss")

    /**
     * 禁止访问的 IP 段（链路本地 / 元数据服务 / 广播 / CGNAT 等）。
     * 注意：127.0.0.1 / localhost 允许（本地 Agent 部署场景）。
     */
    private val blockedCIDRs: List<Pair<ByteArray, Int>> = listOf(
        byteArrayOf(169.toByte(), 254.toByte(), 0.toByte(), 0.toByte()) to 16,    // 链路本地（含 AWS/GCP/Azure metadata）
        byteArrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) to 8,        // 本网络
        byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()) to 32, // 广播
        byteArrayOf(100.toByte(), 64.toByte(), 0.toByte(), 0.toByte()) to 10,    // CGNAT
        byteArrayOf(192.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) to 24,     // IETF 协议分配
        byteArrayOf(198.toByte(), 18.toByte(), 0.toByte(), 0.toByte()) to 15,    // 基准测试
        byteArrayOf(224.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) to 4,      // 组播
        byteArrayOf(240.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) to 4       // 保留
    )

    /**
     * 校验 URL 字符串是否安全可用。
     *
     * @param allowLocalhost 是否允许 localhost / 127.0.0.1 / 内网私网地址（默认 true，
     *   本地 Agent / Ollama 部署需要）
     * @return 校验通过返回 URL；失败返回 null
     */
    fun validate(urlString: String, allowLocalhost: Boolean = true): URL? {
        // JVM 的 java.net.URL 未注册 ws/wss 协议 handler（MalformedURLException），
        // 解析前规范化为 http/https 等价形式（scheme 白名单仍按原值校验）
        val normalizable = urlString.trim()
        val parseable = when {
            normalizable.startsWith("ws://", ignoreCase = true) ->
                "http://" + normalizable.substring(5)
            normalizable.startsWith("wss://", ignoreCase = true) ->
                "https://" + normalizable.substring(6)
            else -> normalizable
        }
        val url = try { URL(parseable) } catch (_: Exception) { return null }
        val scheme = normalizable.substringBefore("://").lowercase()
        if (scheme !in allowedSchemes) return null

        val host = url.host?.lowercase() ?: return null
        if (host.isEmpty()) return null

        // localhost 显式放行
        if (allowLocalhost && (host == "localhost" || host == "ip6-localhost" || host == "::1")) {
            return url
        }

        // IPv4 字面量校验
        parseIPv4(host)?.let { ipv4 ->
            return if (isIPv4Allowed(ipv4, allowLocalhost)) url else null
        }

        // IPv6 字面量
        if (host.contains(":")) {
            val lower = host.removeSurrounding("[", "]").lowercase()
            // 链路本地 fe80::/10（覆盖 fe80~febf）
            if (lower.startsWith("fe8") || lower.startsWith("fe9") ||
                lower.startsWith("fea") || lower.startsWith("feb")) {
                return null
            }
            // 唯一本地地址 fc00::/7
            if (lower.startsWith("fc") || lower.startsWith("fd")) {
                return if (allowLocalhost) url else null
            }
            // IPv4-mapped IPv6: ::ffff:a.b.c.d
            if (lower.startsWith("::ffff:")) {
                val ipv4Part = lower.removePrefix("::ffff:")
                parseIPv4(ipv4Part)?.let { ipv4 ->
                    return if (isIPv4Allowed(ipv4, allowLocalhost)) url else null
                }
            }
            return url
        }

        // 域名校验：.local / .internal（mDNS SSRF）
        if (host.endsWith(".local") || host.endsWith(".internal")) {
            return if (allowLocalhost) url else null
        }
        return url
    }

    // MARK: - IPv4 Helpers

    private fun parseIPv4(s: String): ByteArray? {
        val parts = s.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.length > 3) return null
            val v = p.toIntOrNull() ?: return null
            if (v < 0 || v > 255) return null
            bytes[i] = v.toByte()
        }
        return bytes
    }

    private fun isIPv4Allowed(ip: ByteArray, allowLocalhost: Boolean): Boolean {
        if (ip[0] == 127.toByte()) return allowLocalhost          // 127.0.0.0/8 回环
        if (ip[0] == 10.toByte()) return allowLocalhost           // 10.0.0.0/8 私网
        if (ip[0] == 172.toByte() && ip[1] in 16..31) return allowLocalhost // 172.16/12
        if (ip[0] == 192.toByte() && ip[1] == 168.toByte()) return allowLocalhost // 192.168/16

        for ((cidr, prefix) in blockedCIDRs) {
            if (inCIDR(ip, cidr, prefix)) return false
        }
        return true
    }

    private fun inCIDR(ip: ByteArray, cidr: ByteArray, prefix: Int): Boolean {
        val fullBytes = prefix / 8
        val remainder = prefix % 8
        for (i in 0 until fullBytes) {
            if (ip[i] != cidr[i]) return false
        }
        if (remainder > 0 && fullBytes < 4) {
            val mask: Byte = ((0xFF shl (8 - remainder)) and 0xFF).toByte()
            if ((ip[fullBytes].toInt() and mask.toInt()) != (cidr[fullBytes].toInt() and mask.toInt())) {
                return false
            }
        }
        return true
    }
}

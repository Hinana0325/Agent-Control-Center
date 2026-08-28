package com.agentcontrolcenter.app.transport.http

import okhttp3.CertificatePinner
import java.net.URI
import java.util.Locale

/**
 * 证书锁定（Certificate Pinning）工厂。
 *
 * ## 设计目标
 *
 * 通过 OkHttp 的 [CertificatePinner] 对公网 API 端点实施证书公钥锁定，
 * 防止中间人攻击（MITM）——即使攻击者持有受信任 CA 签发的证书，也无法
 * 冒充目标服务器，因为证书公钥的哈希不匹配。
 *
 * ## 适用范围
 *
 * - **仅锁定公网 API**（如 OpenAI、Google Generative Language API）。
 * - **不锁定本地 LLM 端点**（127.0.0.1 / 10.0.x / 192.168.x / localhost）——
 *   这些端点使用自签证书或明文 HTTP，锁定会导致连接失败。
 * - **不锁定用户自定义服务器**——地址不固定，无法预置 pin。
 *
 * OkHttp 的 [CertificatePinner] 仅校验已登记 pin 的主机；未登记的主机
 * 不受影响，因此将公网 pin 加入 pinner 后，本地 / 自定义端点仍可正常连接。
 *
 * ## 启用方式
 *
 * 1. 在 [PUBLIC_API_PINS] 中填入实际的 SHA-256 公钥哈希（见下方注释获取方式）。
 * 2. 在 [OpenAIHttpTransport] 构造时传入 `enableCertificatePinning = true`。
 * 3. （可选）在 Agent 配置中暴露开关，让用户自行决定是否启用。
 *
 * ## 获取 pin 值
 *
 * ```sh
 * # 方法 1：从服务器证书提取公钥哈希（推荐）
 * openssl s_client -connect api.openai.com:443 2>/dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary \
 *   | openssl enc -base64
 * # 输出形如：+Zy...=，使用时加 "sha256/" 前缀
 *
 * # 方法 2：用 OkHttp 运行时打印（首次连接时日志输出 pin）
 * # 见 https://square.github.io/okhttp/4.x/okhttp/okhttp3/-certificate-pinner/
 * ```
 *
 * ## 注意事项
 *
 * - pin 绑定的是**公钥**而非证书本身，因此证书轮换（同一密钥对）不会导致失效。
 * - 但如果服务端更换密钥对（罕见但可能），pin 会失效导致连接被拒。
 *   建议每个主机至少配置一个 primary pin 和一个 backup pin，并在更换前提前更新。
 * - [PUBLIC_API_PINS] 当前仅含占位 pin（含 "PLACEHOLDER" 标记），[hasRealPins] 返回
 *   `false`，[buildPinner] 不会启用锁定。真实部署前需将占位 pin 替换为实际 SPKI
 *   哈希（获取方法见 [PUBLIC_API_PINS] 上方注释及 protocol/transport/tls-pinning.md）。
 */
object CertificatePinnerFactory {

    /**
     * 已知公网 API 的证书锁定 pin（SHA-256 公钥哈希，Base64 编码，带 "sha256/" 前缀）。
     *
     * 这些 pin 只对对应主机生效——OkHttp 的 [CertificatePinner] 仅校验已登记
     * pin 的主机，未登记的主机（如本地 LLM、用户自定义服务器）不受影响。
     *
     * **TODO：填入实际 pin 值后取消注释。** 空 map 表示不锁定任何主机（安全默认）。
     * 建议每个主机至少配置一个 primary pin 和一个 backup pin（用于密钥轮换）。
     *
     * **key 必须全小写**：OkHttp 的 `HttpUrl` 会把 host 规范化为小写后再与 pinner
     * 中登记的主机匹配，大写 key 将永远匹配不到，导致该主机的锁定静默失效。运行
     * `bash scripts/check-tls-pins.sh` 可校验大小写与格式。
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val PUBLIC_API_PINS: Map<String, List<String>> = buildMap {
        // ⚠️ Pin 值需通过 protocol/transport/tls-pinning.md 第 3.3 节方法获取。
        //    以下为占位值，真实部署前必须替换（含 "PLACEHOLDER" 标记的 pin 会被
        //    [hasRealPins] 判定为非真实 pin，[buildPinner] 在此期间不会启用锁定，
        //    从而保证占位 pin 不会导致连接失败）。
        //
        // OpenAI API
        // TODO_GET_REAL_PIN: 用以下命令获取（见 protocol/transport/tls-pinning.md 第 3.3 节）：
        //   echo | openssl s_client -connect api.openai.com:443 2>/dev/null \
        //     | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
        //     | openssl dgst -sha256 -binary | openssl enc -base64
        // 当前为占位值，真实部署前必须替换。
        put("api.openai.com", listOf(
            "sha256/PLACEHOLDER_PRIMARY_PIN_REPLACE_BEFORE_PRODUCTION=",   // primary — 当前公钥
            "sha256/PLACEHOLDER_BACKUP_PIN_REPLACE_BEFORE_PRODUCTION="     // backup  — 备用公钥（轮换时启用）
        ))
        // Google Generative Language API（Gemini）—— 暂未启用，需获取真实 pin 后取消注释
        // put("generativelanguage.googleapis.com", listOf(
        //     "sha256/PRIMARY_PIN_BASE64="
        // ))
    }

    /**
     * 构建一个 [CertificatePinner] 实例。
     *
     * @param enabled 是否启用证书锁定。
     *   - `false`（默认）：返回空 pinner（`Builder().build()`），不锁定任何主机。
     *   - `true`：将 [PUBLIC_API_PINS] 中的条目加入 pinner。
     *   即使 `enabled = true`，若 [PUBLIC_API_PINS] 为空，也不会锁定任何主机。
     * @return 配置好的 [CertificatePinner]。
     */
    fun buildPinner(enabled: Boolean = false): CertificatePinner {
        // 双重保险：即便上层误传 enabled=true，只要 pin 全为占位（[hasRealPins] 为
        // false）也返回空 pinner，确保占位 pin 绝不会导致连接失败。
        if (!enabled || PUBLIC_API_PINS.isEmpty() || !hasRealPins()) {
            // CertificatePinner.Builder().build() 创建一个不锁定任何主机的空 pinner，
            // 跨 OkHttp 3.x/4.x/5.x 版本兼容（EMPTY 常量在部分版本中不可见）。
            return CertificatePinner.Builder().build()
        }
        val builder = CertificatePinner.Builder()
        PUBLIC_API_PINS.forEach { (host, pins) ->
            pins.forEach { pin -> builder.add(host, pin) }
        }
        return builder.build()
    }

    /**
     * 判断 [PUBLIC_API_PINS] 中是否存在至少一个真实（非占位）pin。
     *
     * 占位 pin 以 "PLACEHOLDER" 标记（见 [PUBLIC_API_PINS] 中条目），在真实部署前
     * 必须替换为实际 SPKI 哈希。本方法用于：
     * - 上层（如 [com.agentcontrolcenter.app.transport.TransportFactory]）动态决定
     *   是否启用证书锁定——仅当存在真实 pin 时才开启，避免占位 pin 导致连接失败。
     * - [buildPinner] 的二次保险，确保占位 pin 永不进入 pinner。
     *
     * @return `true` 表示至少存在一个不含 "PLACEHOLDER" 标记的 pin。
     */
    fun hasRealPins(): Boolean =
        PUBLIC_API_PINS.values.flatten().any { !it.contains("PLACEHOLDER") }

    /**
     * 判断给定 URL 是否为公网 API 端点（即应考虑应用证书锁定的端点）。
     *
     * 本地端点（127.0.0.1 / localhost / 10.0.x / 192.168.x 等）返回 `false`，
     * 因为它们使用自签证书或明文 HTTP，不适用证书锁定。
     *
     * 此方法供上层决定是否为某个 Agent 连接启用 pinning。但实际锁定行为
     * 由 [buildPinner] 返回的 pinner 中已登记的主机决定——即使对本地端点
     * 应用了 pinner，OkHttp 也不会校验未登记的主机。
     *
     * @param url 完整的 URL 字符串（如 `https://api.openai.com/v1/...`）。
     * @return `true` 表示该 URL 指向公网端点。
     */
    fun isPublicEndpoint(url: String): Boolean {
        val host = try {
            URI(url).host
        } catch (_: Exception) {
            return false
        }
        if (host.isNullOrBlank()) return false
        // 用 Locale.ROOT 而非默认 locale：默认 locale 下 'I' 在土耳其语区域会
        // 变成无点 'ı'（U+0131），导致 "I.OPENAI.COM" 这类输入被误判。主机名
        // 的规范化必须是 locale 无关的，才能与 iOS 端 Swift `lowercased()`
        // （locale-independent）以及 OkHttp `HttpUrl` 的规范化结果严格一致。
        return !isLocalHost(host.lowercase(Locale.ROOT))
    }

    /**
     * 判断主机名是否为本地 / 局域网地址。
     *
     * 判定规则与 `network_security_config.xml` 中的明文白名单保持一致，
     * 确保本地 LLM 端点不会被误判为公网端点。
     */
    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "ip6-localhost") return true
        if (host == "127.0.0.1" || host == "::1") return true
        // 10.0.x（含模拟器宿主 10.0.2.2 / 10.0.3.2）/ 192.168.x 局域网段
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true
        // 172.16.0.0 – 172.31.255.255 私有地址段
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}

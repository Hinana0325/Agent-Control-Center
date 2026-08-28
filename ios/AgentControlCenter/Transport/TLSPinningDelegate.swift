import Foundation
import CryptoKit

// MARK: - TLSPinningDelegate
// 对应 Android com.agentcontrolcenter.app.transport.http.CertificatePinnerFactory
// 协议契约: protocol/transport/tls-pinning.md（pin 列表单一事实来源）

/// TLS 证书锁定（Certificate Pinning）URLSession 委托。
///
/// ## 设计目标
///
/// 对公网 API 端点实施证书公钥锁定（SPKI Pinning），防止中间人攻击（MITM）——
/// 即使攻击者持有受信任 CA 签发的证书，只要其证书公钥的 SPKI 哈希不在 pin 列表中，
/// 连接即被拒绝。
///
/// ## 适用范围（与 Android `CertificatePinnerFactory` 对齐）
///
/// - **仅锁定公网固定 API**（如 `api.openai.com`），通过 `PUBLIC_API_PINS` 登记。
/// - **不锁定本地端点**（127.0.0.1 / 10.x / 192.168.x / 172.16-31.x / localhost）——
///   这些端点使用自签证书或明文 HTTP，锁定会导致连接失败。
/// - **不锁定用户自定义服务器**——地址不固定，无法预置 pin。
///
/// 仅校验已登记 pin 的主机；未登记的主机（本地 / 自定义端点）走系统默认信任校验，
/// 不受影响。
///
/// ## 占位 pin 安全放行（关键）
///
/// `PUBLIC_API_PINS` 当前仅含占位 pin（含 "PLACEHOLDER" 标记）。`hasRealPins`
/// 返回 `false`，delegate 对所有挑战降级为 `.performDefaultHandling`（等价于
/// 无 delegate，走系统 CA 链校验），**绝不因占位 pin 阻塞连接**。
///
/// 仅当存在真实 pin 且叶子证书 SPKI 不匹配时，才 `.cancelAuthenticationChallenge`
/// 拒绝连接。真实部署前必须将占位 pin 替换为实际 SPKI 哈希。
///
/// ## pin 值获取
///
/// pin 值需通过 `protocol/transport/tls-pinning.md` 第 3.3 节方法获取：
/// ```sh
/// echo | openssl s_client -connect api.openai.com:443 2>/dev/null \
///   | openssl x509 -pubkey -noout \
///   | openssl pkey -pubin -outform der \
///   | openssl dgst -sha256 -binary \
///   | openssl enc -base64
/// ```
/// 使用时加 `sha256/` 前缀。替换 `PUBLIC_API_PINS` 中的 PLACEHOLDER 后即自动启用锁定。
final class TLSPinningDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {

    /// 单例。注入到 `OpenAIHTTPTransport` / `WebSocketTransport` / `McpClient`
    /// 的 `URLSession(configuration:delegate:delegateQueue:)`。
    static let shared = TLSPinningDelegate()

    /// 已知公网 API 的证书锁定 pin（SHA-256 公钥哈希，Base64 编码，带 "sha256/" 前缀）。
    ///
    /// 与 Android `CertificatePinnerFactory.PUBLIC_API_PINS` 完全一致，作为双端
    /// 共享的单一事实来源（协议层定义见 `protocol/transport/tls-pinning.md`）。
    ///
    /// ⚠️ **当前为占位值**：含 "PLACEHOLDER" 标记的 pin 会被 `hasRealPins` 判定为
    /// 非真实 pin，delegate 在此期间对所有挑战降级为系统默认校验，不会启用锁定。
    /// 真实部署前必须替换（获取方法见本类文档与 tls-pinning.md 第 3.3 节）。
    /// 建议每个主机配置一个 primary pin 和一个 backup pin（用于密钥轮换）。
    ///
    /// ⚠️ **key 必须全小写**：查表前 host 会先 `lowercased()`（见 `urlSession(_:didReceive:)`），
    /// 大写 key 将永远匹配不到，导致该主机的锁定静默失效。运行
    /// `bash scripts/check-tls-pins.sh` 可校验大小写与格式。
    static let PUBLIC_API_PINS: [String: [String]] = [
        // OpenAI API
        // TODO_GET_REAL_PIN: 见 protocol/transport/tls-pinning.md 第 3.3 节获取方法
        "api.openai.com": [
            "sha256/PLACEHOLDER_PRIMARY_PIN_REPLACE_BEFORE_PRODUCTION=",   // primary — 当前公钥
            "sha256/PLACEHOLDER_BACKUP_PIN_REPLACE_BEFORE_PRODUCTION="     // backup  — 备用公钥（轮换时启用）
        ]
        // Google Generative Language API（Gemini）—— 暂未启用，需获取真实 pin 后取消注释
        // "generativelanguage.googleapis.com": [
        //     "sha256/PRIMARY_PIN_BASE64="
        // ]
    ]

    /// 判断 `PUBLIC_API_PINS` 中是否存在至少一个真实（非占位）pin。
    ///
    /// 占位 pin 以 "PLACEHOLDER" 标记。本属性用于对齐 Android
    /// `CertificatePinnerFactory.hasRealPins()`，并作为 delegate 的全局闸门：
    /// 全为占位时不启用任何锁定（降级为系统默认校验），避免占位 pin 导致连接失败。
    static var hasRealPins: Bool {
        PUBLIC_API_PINS.values.flatMap { $0 }.contains { !$0.contains("PLACEHOLDER") }
    }

    /// 判断给定 URL 是否为公网 API 端点（即应考虑应用证书锁定的端点）。
    ///
    /// 本地端点（127.0.0.1 / localhost / 10.x / 192.168.x / 172.16-31.x）返回 `false`，
    /// 因为它们使用自签证书或明文 HTTP，不适用证书锁定。
    ///
    /// 与 Android `CertificatePinnerFactory.isPublicEndpoint(url)` 行为一致。
    /// - Parameter url: 完整 URL
    /// - Returns: `true` 表示该 URL 指向公网端点
    static func isPublicEndpoint(url: URL) -> Bool {
        guard let host = url.host?.lowercased(), !host.isEmpty else { return false }
        return !isLocalHost(host)
    }

    /// 判断主机名是否为本地 / 局域网地址。
    ///
    /// 判定规则与 Android `CertificatePinnerFactory.isLocalHost(host)` 对齐，
    /// 确保本地 LLM 端点不会被误判为公网端点。
    private static func isLocalHost(_ host: String) -> Bool {
        if host == "localhost" || host == "ip6-localhost" { return true }
        if host == "127.0.0.1" || host == "::1" { return true }
        // 10.x（含模拟器宿主 10.0.2.2 / 10.0.3.2）/ 192.168.x 局域网段
        if host.hasPrefix("10.") || host.hasPrefix("192.168.") { return true }
        // 172.16.0.0 – 172.31.255.255 私有地址段
        if host.hasPrefix("172.") {
            let parts = host.split(separator: ".")
            if parts.count >= 2, let second = Int(parts[1]), (16...31).contains(second) {
                return true
            }
        }
        return false
    }

    // MARK: - URLSessionDelegate

    /// 服务端信任挑战处理。
    ///
    /// 处理流程（对齐 Android OkHttp `CertificatePinner` 语义：pinning 是在正常
    /// TLS 校验之上的额外检查，而非替代）：
    /// 1. 全局闸门：`hasRealPins == false`（所有 pin 均为占位）→ 降级为系统默认
    ///    校验（`.performDefaultHandling`，等价于无 delegate），不阻塞连接。
    /// 2. 主机未登记 pin（本地 / 自定义端点）→ 系统默认校验。
    /// 3. 该主机仅有占位 pin（部分迁移）→ 系统默认校验。
    /// 4. 存在真实 pin：先用 `SecTrustEvaluateWithError` 校验证书链（系统 CA），
    ///    再校验叶子证书 SPKI 是否在 pin 列表中。链无效或 pin 不匹配 → `.cancel`。
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        // 仅处理服务端信任挑战；其他类型（客户端证书等）走系统默认
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // 主机名统一小写后再查表。
        //
        // `URLProtectionSpace.host` 保留 URL 中 host 的**原始大小写**：用户把
        // serverUrl 配成 `https://Api.OpenAI.com/v1` 时，此处拿到的是
        // "Api.OpenAI.com"，直接查 `PUBLIC_API_PINS`（key 为 "api.openai.com"）
        // 会失败，并在下方第 2 步降级为系统默认校验——**pinning 静默失效**。
        //
        // 而 Android 侧 OkHttp 的 `HttpUrl` 在解析时会把 host 规范化为小写，同一
        // URL 在 Android 上会正常锁定。不小写化即造成双端行为分歧：Android 提供
        // pin 保护、iOS 静默没有。这比两端都不锁定更危险——它让「已启用证书锁定」
        // 的声明落空，而用户与审计者都无从察觉。
        let host = challenge.protectionSpace.host.lowercased()

        // 1. 全局闸门：所有 pin 均为占位 → 不锁定，走系统默认 CA 校验。
        //    对齐 Android hasRealPins=false 时 buildPinner 返回空 pinner。
        guard Self.hasRealPins else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // 2. 该主机未登记 pin（本地 / 自定义端点）→ 系统默认校验
        guard let pins = Self.PUBLIC_API_PINS[host], !pins.isEmpty else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // 3. 过滤占位 pin；该主机仅有占位（部分迁移）→ 系统默认校验，避免误锁
        let realPins = pins.filter { !$0.contains("PLACEHOLDER") }
        guard !realPins.isEmpty else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // 4. 存在真实 pin：先校验证书链（系统 CA），再校验 SPKI pin
        var trustError: CFError?
        let chainValid = SecTrustEvaluateWithError(serverTrust, &trustError)
        guard chainValid, trustError == nil else {
            // 证书链无效（系统 CA 校验失败）→ 拒绝
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        // 提取叶子证书并计算 SPKI SHA-256
        guard let leafCert = Self.leafCertificate(from: serverTrust),
              let serverPin = Self.spkiSha256Base64(of: leafCert) else {
            // 解析失败 → 保守拒绝
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        // pin 匹配判定：去除 "sha256/" 前缀后比较
        let matched = realPins.contains { pin in
            let normalized = pin.hasPrefix("sha256/") ? String(pin.dropFirst("sha256/".count)) : pin
            return normalized == serverPin
        }

        if matched {
            // 链有效且 pin 匹配 → 接受
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            // pin 不匹配 → 拒绝（即使持有受信任 CA 证书，公钥不在 pin 列表即判定为 MITM）
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    // MARK: - 证书解析

    /// 从 SecTrust 中提取叶子证书（证书链第一个）。
    private static func leafCertificate(from trust: SecTrust) -> SecCertificate? {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else {
            return nil
        }
        return chain.first
    }

    /// 计算证书的 SubjectPublicKeyInfo (SPKI) SHA-256 的 Base64 表示。
    ///
    /// 对应 OkHttp `CertificatePinner` 的 pin 语义：对证书公钥的 SPKI DER 求 SHA-256
    /// 后 Base64 编码（使用时加 "sha256/" 前缀）。SPKI 是包含 AlgorithmIdentifier +
    /// subjectPublicKey 的完整 SEQUENCE，等价于 Java
    /// `X509Certificate.getPublicKey().getEncoded()` 的返回值，保证双端 pin 一致。
    ///
    /// - Parameter certificate: 叶子证书
    /// - Returns: SPKI SHA-256 的 Base64 字符串；解析失败时返回 nil
    private static func spkiSha256Base64(of certificate: SecCertificate) -> String? {
        let der = SecCertificateCopyData(certificate) as Data
        guard let spki = subjectPublicKeyInfoBytes(from: der) else { return nil }
        let digest = SHA256.hash(data: spki)
        return Data(digest).base64EncodedString()
    }

    /// 从 DER 编码的 X.509 证书中提取 SubjectPublicKeyInfo 的完整 DER 字节。
    ///
    /// X.509 结构（ASN.1）：
    /// ```
    /// Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
    /// TBSCertificate ::= SEQUENCE {
    ///     [0] version (optional), serialNumber, signature, issuer,
    ///     validity, subject, subjectPublicKeyInfo, ...
    /// }
    /// ```
    /// 本方法按长度前缀 TLV 逐层解析：进入 Certificate → 取首个元素 tbsCertificate →
    /// 跳过可选 [0] version 与 serialNumber/signature/issuer/validity/subject 共 5 项
    /// → 取到 subjectPublicKeyInfo，返回其完整 TLV 字节（含 tag + length + content）。
    ///
    /// - Parameter der: 证书 DER 数据
    /// - Returns: SPKI 完整 DER 字节；解析失败时返回 nil
    private static func subjectPublicKeyInfoBytes(from der: Data) -> Data? {
        // 外层 Certificate SEQUENCE
        guard let cert = readASN1Element(der, at: 0) else { return nil }
        // tbsCertificate 是 Certificate 内首个元素
        guard let tbs = readASN1Element(der, at: cert.contentStart) else { return nil }

        var pos = tbs.contentStart
        // 跳过可选的 [0] version（context-specific constructed tag 0xA0）
        if let first = readASN1Element(der, at: pos), first.tag == 0xA0 {
            pos = first.nextIndex
        }
        // 依次跳过 serialNumber / signature(AlgorithmIdentifier) / issuer /
        // validity / subject 共 5 项，下一项即为 subjectPublicKeyInfo
        for _ in 0..<5 {
            guard let elem = readASN1Element(der, at: pos) else { return nil }
            pos = elem.nextIndex
        }
        // subjectPublicKeyInfo 完整 TLV（tag + length + content）
        guard let spki = readASN1Element(der, at: pos) else { return nil }
        return der.subdata(in: pos..<spki.nextIndex)
    }

    /// 读取 DER 中指定位置的 ASN.1 TLV 元素。
    ///
    /// 支持短格式与长格式长度编码。返回 tag、内容区间与下一个元素的起始位置。
    /// - Parameters:
    ///   - data: DER 数据
    ///   - start: TLV 起始位置
    /// - Returns: 解析结果；越界或格式错误时返回 nil
    private static func readASN1Element(_ data: Data, at start: Int) -> (tag: UInt8, contentStart: Int, contentEnd: Int, nextIndex: Int)? {
        guard start < data.count else { return nil }
        let tag = data[start]
        var i = start + 1
        guard i < data.count else { return nil }
        let firstLen = data[i]
        i += 1
        let length: Int
        if firstLen & 0x80 == 0 {
            // 短格式：长度即首字节
            length = Int(firstLen)
        } else {
            // 长格式：低 7 位为后续长度字节数
            let numBytes = Int(firstLen & 0x7F)
            guard numBytes > 0, numBytes <= 4, i + numBytes <= data.count else { return nil }
            var len = 0
            for _ in 0..<numBytes {
                len = (len << 8) | Int(data[i])
                i += 1
            }
            length = len
        }
        let contentStart = i
        let contentEnd = i + length
        guard contentEnd <= data.count else { return nil }
        return (tag, contentStart, contentEnd, contentEnd)
    }
}

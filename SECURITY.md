# 安全策略 · Agent Control Center

本文件描述 Agent Control Center（双原生：Android Kotlin + iOS Swift）的安全机制、漏洞报告流程与已知限制。加密方案的完整契约见 [`protocol/transport/auth.md`](protocol/transport/auth.md)。

---

## 1. 支持版本

| 版本 | 状态 |
|:---|:---|
| 4.x.x | 主动维护 |
| 1.x.x | 已停止支持（EOL） |

---

## 2. 漏洞报告

若发现安全漏洞，请负责任地披露：

1. **切勿**公开提交 GitHub Issue
2. 通过 GitHub 私密漏洞报告渠道或直接邮件联系维护者
3. 报告内容应包含：
   - 漏洞描述
   - 复现步骤
   - 潜在影响
   - 修复建议（如有）

### 响应时限

- **确认收到**：48 小时内
- **初步评估**：1 周内
- **修复发布**：视严重程度而定

---

## 3. 加密体系总览

Agent Control Center 采用两套独立加密体系，二者前缀、密钥来源、使用场景均不同，双端格式严格一致：

| 维度 | 静态凭据存储 | E2E 传输加密 |
|:---|:---|:---|
| 密文前缀 | `AKS:` | `AH1:` |
| 算法 | AES-256-GCM | AES-256-GCM |
| 密钥来源 | 硬件密钥库生成，设备绑定 | passphrase 经 PBKDF2 派生 |
| 密钥派生 | 无（直接使用硬件密钥） | PBKDF2WithHmacSHA256，600000 迭代 |
| 密文结构 | `IV ‖ ciphertext` | `IV ‖ salt ‖ ciphertext` |
| 硬件 backed | 是（TEE/StrongBox/Secure Enclave） | 否（纯软件） |
| 使用场景 | 本地存储 / 读取 apiKey | WebSocket 消息内容加密 |
| 跨设备 | 不可（密钥绑定设备） | 可（只要 passphrase 相同） |

---

## 4. 静态数据保护（Data-at-Rest）

### 4.1 `AKS:` 加密格式

API Key 等敏感凭据在本地持久化时使用统一的 `AKS:` 前缀格式：

```
AKS:<Base64(IV[12] ‖ ciphertext)>
```

- **前缀**：固定字符串 `AKS:`，标识 Agent Control Center Keystore 加密方案
- **IV**：12 字节初始化向量，每次加密随机生成
- **ciphertext**：AES-256-GCM 密文（含 16 字节 GCM 认证标签）
- **无 passphrase**：主密钥由硬件密钥库保护，无需用户输入密码派生

### 4.2 Android 实现（KeystoreManager）

- **密钥库**：AndroidKeyStore
- **密钥别名**：`agentcontrolcenter_master_key`
- **硬件支持**：硬件 backed（TEE / StrongBox，设备支持时自动启用）
- **算法**：`Cipher.getInstance("AES/GCM/NoPadding")`
- **密钥不可导出**：通过 `KeyGenerator` 在 AndroidKeyStore 中生成 AES-256 密钥，绑定设备

### 4.3 iOS 实现（KeychainManager）

- **密钥库**：iOS Keychain
- **密钥标签**：`com.agentcontrolcenter.app.master-key`
- **访问控制**：`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，绑定设备
- **算法**：CryptoKit `AES.GCM`（`AES.GCM.seal` / `AES.GCM.open`）
- **密钥生成**：`SymmetricKey(size: .bits256)`，存储于 Keychain

### 4.4 桌面实现（CredentialVault）

> 新增于 v5.3.0。桌面端（Windows/macOS/Linux）在纯 JVM 下**没有**跨平台的硬件
> 密钥库等价物（DPAPI / Keychain / libsecret 均需 JNI 或原生依赖），因此主密钥的
> 保护方式与移动端不同，保护强度**低于** §4.2 / §4.3。

- **密钥库**：无（无硬件支持）
- **主密钥**：256 位随机密钥，存储于 `~/.agent-control-center/master.key`
- **文件保护**：POSIX 平台权限 `600`（目录 `700`）；Windows 无 POSIX 权限模型，
  仅附加 `dos:hidden`，**无法做到仅属主可读**
- **算法**：`Cipher.getInstance("AES/GCM/NoPadding")`，随机 IV，密文格式与移动端同构
- **密钥可导出**：是（这是与移动端的核心差异）

**威胁模型（须如实理解）**：

| 攻击场景 | 移动端（TEE / Keychain） | 桌面端（密钥文件） |
|:---|:---|:---|
| 数据文件被拷走 / 同步到云端后离线破解 | ✅ 防住 | ✅ 防住 |
| 同一用户身份运行的恶意进程读取 | ✅ 防住 | ❌ **防不住** |
| 磁盘整体被盗且未做全盘加密 | ✅ 防住 | ❌ 防不住 |

桌面端方案属「防顺手牵羊、防文件外泄」，**不等价于**移动端硬件绑定。在纯 JVM
跨平台前提下这是能力上限；如需更强保护，应启用全盘加密（BitLocker / FileVault / LUKS）。

**故障行为**：主密钥文件存在但内容损坏时，`CredentialVault` 抛 `CredentialVaultException`
而**不静默重新生成密钥**——静默重生成会让既有凭据永久不可解密。宁可失败可见，不可静默丢数据。

### 4.5 decryptOrRaw 向后兼容

统一的解密入口 `decryptOrRaw(value)` 处理三种情形：

| 输入情形 | 判定条件 | 返回值 |
|:---|:---|:---|
| 已加密且解密成功 | 前缀为 `AKS:` 且 GCM 认证通过 | 解密后的明文 |
| 已加密但解密失败 | 前缀为 `AKS:` 但认证失败或密钥不存在 | 空字符串 `""` |
| 未加密（明文，旧版兼容） | 无 `AKS:` 前缀 | 原始输入值 |

> 设计要点：解密失败时返回空串而非抛异常或回退原文，避免将密文误当明文使用导致鉴权失败难以定位。

### 4.6 数据库加密

- **Android**：Room 数据库存储会话与消息，敏感字段（apiKey）在写入前经 `AKS:` 加密
- **iOS**：SwiftData 存储实体，敏感字段同样经 `AKS:` 加密后持久化
- **HarmonyOS**：relationalStore 存储，敏感字段经 `AKS:` 加密后持久化
- **桌面端**（v5.3.0 起）：`JsonStore` 落盘 `agents.json` 前经 `CredentialVault` 加密
  `apiKey`；内存态保持明文供传输层使用，加解密只发生在持久化边界。
  v5.2.0 及更早版本为**明文落盘**，启动时由一次性迁移自动转为密文。
- **迁移**：旧版明文数据在首次读取时通过 `decryptOrRaw` 自动识别并保留，后续写入时自动转为加密格式

---

## 5. 传输数据保护（Data-in-Transit）

### 5.1 TLS 强制

- 所有网络连接默认要求 HTTPS
- 明文 HTTP 仅允许用于本地模型端点（`127.0.0.1`、`10.0.2.2`、`192.168.*`）
- **Android**：通过 `network_security_config.xml` 控制，Release 构建仅信任系统 CA
- **iOS**：App Transport Security（ATS）允许任意加载（本地 Agent 连接需要），生产环境建议收紧

### 5.2 `AH1:` E2E 加密格式

WebSocket 等 P2P / 中继传输场景下的消息内容端到端加密：

```
AH1:<Base64(IV[12] ‖ salt[16] ‖ ciphertext)>
```

- **前缀**：固定字符串 `AH1:`，标识 Agent Control Center E2E 加密方案版本 1
- **IV**：12 字节，每消息随机生成
- **salt**：16 字节，每消息随机生成
- **ciphertext**：AES-256-GCM 密文（含 16 字节 GCM 认证标签）

### 5.3 加密参数

| 参数 | 值 |
|:---|:---|
| 算法 | AES-256-GCM |
| 密钥派生 | PBKDF2WithHmacSHA256 |
| 迭代次数 | 600000（OWASP 2023 推荐） |
| 盐长度 | 16 字节 |
| IV 长度 | 12 字节 |
| 密钥长度 | 256 位（32 字节） |
| 认证标签 | GCM 内置，16 字节 |

### 5.4 双端实现映射

| 维度 | Android（Kotlin） | iOS（Swift） |
|:---|:---|:---|
| 凭据密钥库 | AndroidKeyStore | Keychain |
| 凭据加密算法 | `AES/GCM/NoPadding` | CryptoKit `AES.GCM` |
| E2E 密钥派生 | `SecretKeyFactory` PBKDF2 | `HKDF<SHA256>` / `CCKeyDerivationPBKDF` |
| 硬件 backed | TEE / StrongBox | Secure Enclave（设备支持时） |

### 5.5 使用约束

- **双端共享 passphrase**：通信双方必须使用相同的 `e2eKey`，否则解密失败
- **每消息独立 salt/IV**：保证语义安全，IV 不可复用
- **应用场景**：仅用于 WebSocket 传输的消息 `content` 字段加密；HTTP/SSE 依赖 TLS，不使用 `AH1:`
- **传输层支持**：WebSocket（鉴权帧 + 自动重连）与 HTTP + SSE（流式）

---

## 6. 应用安全

| 维度 | 说明 |
|:---|:---|
| XSS 防护 | 所有用户生成内容经 `escapeHtml()` 处理 |
| 协议过滤 | 阻断 `javascript:`、`data:`、`vbscript:` URI |
| 无 CSP 违规 | 纯本地存储，无外部数据泄露 |
| 网络配置 | Android 限制明文流量，Release 仅信任系统 CA |
| 日志脱敏 | 任何日志不得包含 apiKey 明文、`AH1:` 密文、passphrase；`Authorization` 头与 WebSocket auth 帧必须脱敏 |
| 内存最小化 | 解密后的明文 apiKey 仅在发送请求时短暂驻留，不长期保存在内存 |

---

## 7. 权限

### Android

| 权限 | 用途 |
|:---|:---|
| `INTERNET` | Agent 连接的网络访问 |
| `POST_NOTIFICATIONS` | 聊天消息与连接状态通知 |
| `FOREGROUND_SERVICE` | 维持持久 Agent 连接 |
| `RECORD_AUDIO` | 语音输入 / 语音对话模式 |
| `READ_MEDIA_IMAGES` | 附加图片到消息 |
| `REQUEST_INSTALL_PACKAGES` | 通过 GitHub Releases 自更新（可选） |

### iOS

| 权限 | 用途 |
|:---|:---|
| 网络访问 | Info.plist `NSAppTransportSecurity`（本地 Agent 需要） |
| 后台模式 | `fetch` / `remote-notification` / `processing`，与 Android 对齐 |
| 麦克风 | 语音输入（运行时申请） |
| 照片库 | 附加图片到消息（运行时申请） |
| APNs | 远程推送通知 |

---

## 8. 已知限制

- `usesCleartextTraffic` 由 `network_security_config.xml` 完全控制（默认拒绝）；iOS 通过 ATS 控制
- `REQUEST_INSTALL_PACKAGES` 仅自更新需要，通过应用商店分发时可移除
- 用户 CA 证书仅在 Debug 构建中受信任，用于开发与测试
- `AKS:` 密文绑定设备硬件，迁移至新设备时需用户重新输入 apiKey 重新加密存储
- E2E 加密的 passphrase 应具有足够熵；PBKDF2 600000 迭代提供较强抗暴力破解能力，但弱 passphrase 仍是风险
- GCM 认证失败时必须丢弃数据，不得返回部分解密结果

---

## 9. 密钥生命周期

### apiKey 生命周期

```
[用户输入明文 apiKey]
        ↓
[KeystoreManager / KeychainManager.encrypt] → 本地存储 (AKS: 密文)
        ↓
[读取时] decryptOrRaw(storedValue) → 明文 apiKey
        ↓
[注入传输层] HTTP Header / WebSocket auth 帧 / MCP Header
```

### e2eKey（passphrase）生命周期

```
[用户配置 e2eKey]
        ↓
[本地存储] (建议同样经 AKS: 加密，传输时不带前缀)
        ↓
[发送消息] CryptoManager.encrypt(content, e2eKey) → AH1: 密文
        ↓
[接收消息] CryptoManager.decrypt(AH1: 密文, e2eKey) → 明文 content
```

---

## 10. 变更记录

| 版本 | 日期 | 变更说明 |
|:---|:---|:---|
| 1.0 | 2026-07-19 | 初始版本，从 Android 实现抽取并固化为双端契约 |
| 1.1 | 2026-07-20 | 补充 iOS Keychain / CryptoKit 实现，明确双端加密映射 |

# Agent Control Center TLS 证书锁定规范

本文件定义 Agent Control Center 的 TLS 证书锁定（Certificate Pinning）策略，作为 Android（Kotlin）与 iOS（Swift）双端共享的永久契约。pin 列表以本文件为单一事实来源，双端必须保持一致。

## 1. 背景与目标

证书锁定用于防止中间人攻击（MITM）。即使攻击者持有受信任 CA 签发的证书，只要其证书公钥不在 pin 列表中，连接即被拒绝。

Agent Control Center 连接多种 Agent 端点，其中公网固定 API 端点应锁定，本地端点和用户自定义端点不应锁定。

## 2. 锁定范围

### 2.1 应锁定的域名（公网固定 API）

| 域名 | 用途 | 备注 |
|------|------|------|
| `api.openai.com` | OpenAI / OpenAI 兼容 AgentType | 所有走 OpenAI 协议的 Agent 共用 |

### 2.2 候选锁定域名（Marketplace 公网请求）

| 域名 | 用途 | 备注 |
|------|------|------|
| `openclaw.supplies` | OpenClaw Marketplace API | 可选，加固 Marketplace 请求 |
| `clawhub.ai` | ClawHub API | 可选，加固 Marketplace 请求 |

### 2.3 不锁定的域名

以下端点**不应锁定**，因为地址不固定或为本地服务：

- `127.0.0.1` / `localhost`（ComfyUI `:8188`、OpenWebUI `:3000`、Ollama `:11434` 等本地端点）
- `10.x` / `172.16-31.x` / `192.168.x`（局域网自部署服务）
- 用户自定义 serverUrl（地址不固定）
- WebSocket Agent 端点（Hermes/OpenClaw/OpenCode，serverUrl 用户自配）

## 3. Pin 策略

### 3.1 Pin 格式

使用 SPKI（Subject Public Key Info）SHA-256 Base64 编码，前缀 `sha256/`：

```
sha256/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUV=
```

### 3.2 Primary + Backup Pin

每个域名配置 **2 个 pin**：

- **Primary pin**：当前证书公钥的 SPKI hash
- **Backup pin**：备用密钥的 SPKI hash（用于密钥轮换时无缝切换）

> ⚠️ **重要**：必须配置 backup pin。服务端密钥轮换时若仅有 primary pin 会导致连接失败。

#### Backup pin 应取中间 CA 公钥，而非第二张叶子证书

这是本规范中**最容易造成线上事故**的一处，务必遵守。

`api.openai.com` 等公网端点由 CDN 签发证书，叶子证书的密钥对轮换周期远短于 App
发版周期（典型 90 天甚至更短）。若 primary 与 backup 都绑定叶子证书的密钥对，
服务端一旦轮换密钥对：

1. **所有已发布的存量客户端立即拒绝连接**——不是部分用户，是全部；
2. 修复手段**只有发新版**——pin 写死在客户端里，服务端无法自救；
3. 用户**没有任何自救手段**——双端均未实现锁定开关（§5.1、§7），只能等发版。
   这一点把「填错 pin」从「部分用户受影响」放大成了「全量用户功能不可用」。

推荐配置：

| 位置 | 取值 | 抗轮换能力 |
|:----:|------|-----------|
| Primary | **叶子证书**公钥 SPKI | 叶子密钥轮换后失效 |
| Backup | **中间 CA** 公钥 SPKI | 只要仍由同一 CA 签发就持续有效（CA 有效期通常 10 年） |

中间 CA 的公钥在叶子证书轮换（同一 CA 重新签发）时**保持不变**。把 backup 设在
中间 CA 上，就把「叶子密钥一轮换就全网断连」降级为「CA 本身更换才需要发版」——
后者的频率低一到两个数量级，且 CA 更换通常有数月预告期。

`scripts/fetch-tls-pins.sh` 默认即按此产出：primary 取链首叶子证书，backup 取
链上第二张证书（中间 CA）。

> ❌ **禁止**把 primary 与 backup 填成同一个值。两个相同的 pin 等于没有 backup。
> `scripts/check-tls-pins.sh` 会检测并报错。

### 3.3 Pin 获取方法

> ⚠️ **必须在可信网络下执行**。若在 MITM 代理（企业 DLP、抓包工具、fake-ip 分流
> 工具、CI 沙箱）下获取，拿到的是**代理自己的证书**，其 pin 写入源码后将导致
> 所有用户连接被拒，且只能发版修复。

推荐使用仓库自带脚本，它内置了代理环境变量、DNS 私有/保留地址、证书链校验三道
拦截，任一可疑即拒绝产出：

```sh
bash scripts/fetch-tls-pins.sh api.openai.com
```

手工获取（不推荐，缺少上述拦截）：

```sh
# 获取叶子证书的 SPKI SHA-256（primary pin）
echo | openssl s_client -connect api.openai.com:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64

# 输出示例（非真实值，需实际获取）:
# aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789aBcDeFg=
```

使用时加 `sha256/` 前缀。获取中间 CA 的 backup pin 需先 `-showcerts` 导出完整链，
再对链上第二张证书重复上述计算。

### 3.4 主机名大小写规范

pin 表的 key 必须**全小写**，且双端查表前必须把 host 规范化为小写。

| 端 | host 来源 | 是否已自动小写 | 要求 |
|:--:|----------|:-------------:|------|
| Android | OkHttp `HttpUrl` | ✅ 解析时规范化 | `isPublicEndpoint` 中另需显式 `lowercase(Locale.ROOT)` |
| iOS | `URLProtectionSpace.host` | ❌ **保留原始大小写** | 必须显式 `lowercased()` 后再查表 |

两个细节：

- Android 必须用 `lowercase(Locale.ROOT)` 而**不能**用默认的 `lowercase()`。
  默认 locale 下，土耳其语区域会把 `I` 转成无点 `ı`（U+0131），导致
  `I.OPENAI.COM` 这类输入被误判，与 iOS 的 locale-independent `lowercased()`
  产生分歧。
- iOS 的 `URLProtectionSpace.host` 直接沿用 URL 中的写法，不做规范化。

**不遵守的后果**：用户把 serverUrl 配成 `https://Api.OpenAI.com/v1` 时，该主机
查表失败 → 静默降级为不锁定。这比两端都不锁定**更危险**——它让「已启用证书锁定」
的声明落空，而用户与审计者都无从察觉（连接一切正常，只是没有保护）。

`scripts/check-tls-pins.sh` 会校验双端 key 全小写且一致。

```sh
# 获取某域名的 SPKI SHA-256（primary pin）
echo | openssl s_client -connect api.openai.com:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64

# 输出示例（非真实值，需实际获取）:
# aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789aBcDeFg=
```

使用时加 `sha256/` 前缀。

## 4. Pin 列表（单一事实来源）

> ⚠️ **当前状态：占位值，证书锁定未生效。**
>
> 两端代码已有完整的 pinning 接线——Android `TransportFactory` 动态启用、iOS
> 三处 transport 注入 `TLSPinningDelegate`——但下表的 pin 为占位值，
> `hasRealPins` 返回 `false`，双端均降级为系统默认 CA 校验。
>
> 这一状态**不会导致连接失败**，但也**不提供任何 pin 保护**。填入真实 pin 前，
> 请勿对外宣称本项目具备证书锁定能力。

### api.openai.com

| 位置 | 来源 | pin | 获取日期 | 有效期至 |
|:----:|------|-----|:--------:|:--------:|
| Primary | 叶子证书公钥 | `sha256/REPLACE_WITH_ACTUAL_PRIMARY_PIN=` | — | — |
| Backup | 中间 CA 公钥（推荐，见 §3.2） | `sha256/REPLACE_WITH_ACTUAL_BACKUP_PIN=` | — | — |

**填入步骤**：

```sh
# 1. 在可信网络下获取（脚本会拦截代理与 DNS 劫持环境）
bash scripts/fetch-tls-pins.sh api.openai.com

# 2. 把输出的片段分别粘贴到双端，并回填本表（含获取日期与有效期）
#    Android: android/app/src/main/java/com/agentcontrolcenter/app/transport/http/CertificatePinnerFactory.kt
#    iOS:     ios/AgentControlCenter/Transport/TLSPinningDelegate.swift

# 3. 校验双端一致且格式合法
bash scripts/check-tls-pins.sh

# 4. 冒烟验证（缺一不可）
#    a) 正常网络连接该域名必须成功
#    b) 用代理工具注入假证书，连接必须失败——确认锁定真的生效
```

记录获取日期与有效期是必要的：pin 本身不含任何时间信息，没有元信息就无法判断
它是否即将随证书轮换而失效。`scripts/fetch-tls-pins.sh` 会一并输出证书有效期。

## 5. 实现规范

### 5.1 Android（Kotlin）

- 使用 OkHttp `CertificatePinner`
- `CertificatePinnerFactory` 构建 pin map，注入 OkHttpClient
- `isPublicEndpoint(url)` 判断是否应锁定（排除 localhost / 局域网）
- 启用与否由 `CertificatePinnerFactory.hasRealPins()` **动态决定**：仅当
  `PUBLIC_API_PINS` 中存在真实（非占位）pin 时才开启，占位期间自动降级为不锁定

> ⚠️ **不存在用户设置开关。** 本规范此前记载「Settings → 安全 → 证书锁定，默认
> 开启」——经 2026-08 全仓检索核实（`.kt` / `.swift` / `.xml` / `.ets`），
> **双端均无任何 UI 实现**，所有 pinning 相关代码只在 transport 层。
>
> 请不要照该描述对外承诺，也不要把「用户可自行关闭」写进应急预案（见 §7）。

### 5.2 iOS（Swift）

- 实现 `URLSessionDelegate`，在 `urlSession(_:didReceive:completionHandler:)` 中校验 `SecTrust` 叶子证书 SPKI
- 注入到 `OpenAIHTTPTransport` / `WebSocketTransport` / `McpClient` 的 `URLSession(configuration:delegate:)`
- pin 列表从本文件同步，与 Android 保持一致
- 查表前必须 `lowercased()`（`URLProtectionSpace.host` 保留原始大小写），
  pin 表 key 必须全小写 —— 详见 §3.4
- ⚠️ **重建 `URLSession` 时必须重新注入 delegate**。`invalidateAndCancel()` 后
  重建的 session 若漏掉 `delegate:` 参数，锁定会静默失效
  （`OpenAIHTTPTransport` 的重建处已处理，改动时需留意保持）

### 5.3 一致性校验（CI）

双端 pin 列表必须逐条一致，漂移由脚本自动拦截：

```sh
bash scripts/check-tls-pins.sh            # 默认：占位 pin 仅告警
bash scripts/check-tls-pins.sh --strict   # 发布门禁：占位 pin 也判失败
npm run check                             # 版本 + pin 两项一起校验
```

校验项：双端 host 集合一致、同 host pin 集合一致、host key 全小写（§3.4）、
真实 pin 格式合法（`sha256/` + 44 字符 Base64）、每 host 至少 2 个**互不相同**的
pin（§3.2）。

占位 pin 默认只告警不失败——否则 CI 会长期红灯，反而把真正的漂移淹没在噪音里；
发布前用 `--strict` 卡住即可。

获取 pin 用 `bash scripts/fetch-tls-pins.sh <host>`（含代理 / DNS 劫持拦截，
见 §3.3）。

`fetch-tls-pins.sh --self-test` 提供**离线**自检（不联网）：用本地生成的证书验证
pin 算法正确性，并用一条自建的 leaf → intermediate → root 链验证拆分与
primary/backup 提取逻辑。改动该脚本后应先跑自检再提交。

## 6. 密钥轮换流程

需要区分两种轮换，二者的处置方式完全不同。

### 6.1 叶子证书密钥轮换（常见，约每 90 天）

**若 backup pin 取自中间 CA 公钥（§3.2 推荐做法），则无需任何操作**——新叶子证书
仍由同一 CA 签发，backup pin 持续命中，客户端无感。

此时只需：核对新证书的签发 CA 未变，并在本文件 §4 表格中更新「有效期至」。
若确认 CA 未变，**不要**改动任何 pin——改动反而会引入风险。

### 6.2 CA 更换（罕见，通常有数月预告）

此时中间 CA 公钥变化，backup pin 会失效，必须走完整流程：

1. 服务端生成新密钥对，获取新证书
2. 用新证书（及新 CA）公钥计算新 SPKI hash
3. 将新 hash 作为 backup pin 加入列表（此时 primary 仍为旧 pin）
4. **发版并等待绝大多数存量用户升级**——这一步不能跳过，否则未升级用户在
   服务端切换后会立即断连（且无开关可自救，见 §7）
5. 服务端切换到新证书
6. 确认客户端连接正常后，将新 hash 提升为 primary，旧 hash 降级为 backup
7. 旧证书过期后移除旧 pin

> ⚠️ 跳过第 3-4 步直接更换 pin 会导致客户端连接失败。
>
> ⚠️ 第 4 步「等待用户升级」是本流程中最容易被忽略、代价也最高的一步。旧版本
> 客户端里的 pin 无法远程更新，服务端一旦切换，这部分用户将永久失去连接——
> 除非他们升级。切换前应先统计版本分布，确认未升级占比可接受。

## 7. 故障处理

- Pin 校验失败时，连接必须被拒绝，不得回退到系统 CA 验证
- UI 应提示"证书锁定校验失败，连接已被拒绝"
- ~~用户可在 Settings → 安全 中临时关闭证书锁定（降级到系统 CA 验证）~~
  **该条目前不成立**，见下方说明

> ⚠️ **用户没有自救手段。** 上述第三条描述的开关**从未实现**——2026-08 全仓检索
> 确认双端均无相关 UI（见 §5.1）。因此一旦 pin 与服务端证书不匹配：
>
> | 情形 | 有开关时 | 当前（无开关） |
> |------|---------|---------------|
> | pin 填错 | 用户关闭锁定即可恢复 | **功能完全不可用，只能等发版** |
> | 服务端轮换叶子密钥 | 同上 | 同上，且影响**全部**存量用户 |
> | 服务端更换 CA | 同上 | 同上，且影响**全部**存量用户 |
>
> 这直接决定了填 pin 必须采取保守策略：
>
> 1. **backup pin 必须取中间 CA 公钥**（§3.2）——把最常见的叶子密钥轮换变成无感事件；
> 2. **填前必须完成 §4 的两项冒烟验证**，缺一不可：正常网络能连通、注入假证书被拒绝；
> 3. **只锁地址固定的端点**。当前仅 `api.openai.com` 符合；§2.2 的 Marketplace
>    域名（`openclaw.supplies` / `clawhub.ai`）在未确认其证书供给方式前不要锁定，
>    尤其要确认它们不会因 CDN 切换而更换 CA。
>
> **建议任务**：把「新增证书锁定用户开关」作为填 pin 的**前置任务**——有开关时
> pin 出错是「可以降级的问题」，没开关时是「线上事故」。见 `DEV_PLAN.md`。

## 8. 版本与变更

- 本文件为协议层永久契约
- Pin 列表变更须双端同步发布
- 新增域名锁定须先在本文档登记，再在双端实现

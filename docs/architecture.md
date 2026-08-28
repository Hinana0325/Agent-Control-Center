# Agent Control Center 架构文档

> 仓库：`Hinana0325/Agent-Control-Center`
> 包名：`com.agentcontrolcenter.app`（Android）/ `com.agentcontrolcenter.app.ios`（iOS）
> bundleID：`com.agentcontrolcenter.desktop`（桌面）
> 当前版本：**v5.2.0**（versionCode 44，单一事实来源 `version.properties`）
> 最后核对：v5.2.0

## 1. 总览

Agent Control Center 是**六端原生**多 Agent 控制中心：Android（Kotlin + Jetpack Compose）、iOS（Swift + SwiftUI）、HarmonyOS NEXT（ArkTS + ArkUI）、桌面三端 Windows/macOS/Linux（Kotlin + Compose Multiplatform）。四套代码库各自采用平台原生技术栈独立实现，**不共享任何跨平台 UI 代码**——只统一协议，不强求代码复用。数据模型与传输格式以 `protocol/` 为唯一事实来源。

项目已从早期 PWA + Capacitor 架构完全重构：v4.8.0 双端 → v5.1.0 三端（+HarmonyOS）→ v5.2.0 六端（+桌面三端）。

> **版本编号缺口**：v4.9.0 与 v5.0.0 **从未发布**，版本号从 4.8.0 直接跳到 5.1.0（见 `CHANGELOG.md`）。被跳过的这两个版本原计划承载「Workflow 持久化 + 执行历史」与「可视化拖拽编辑器」——前者已补上，后者至今六端全缺。引用版本号时请以 `CHANGELOG.md` 为准。

```
┌──────────────────────────────────────────────────────────────────────┐
│                            Protocol Layer                             │
│          11 JSON Schema + 5 Transport Protocol (六端共享)              │
│   android/ ← ios/ ← harmony/ ← desktop/ ← protocol/ (单一事实来源)      │
├──────────┬──────────┬───────────────┬────────────────────────────────┤
│ Android  │   iOS    │  HarmonyOS    │   Desktop (Win / macOS / Linux) │
│ Kotlin   │  Swift   │    ArkTS      │        Kotlin (Compose MP)      │
│ Compose  │ SwiftUI  │    ArkUI      │      Compose Multiplatform      │
│ Hilt     │@Observab.│   HSP 多模块   │        AppStore 状态流           │
│ Room     │ SwiftData│relationalStore│        JSON 文件存储             │
│ Ktor     │URLSession│ @ohos.net.http│         Ktor + OkHttp           │
│ Keystore │ CryptoKit│  HUKS+cryptoFW│          javax.crypto           │
└──────────┴──────────┴───────────────┴────────────────────────────────┘
```

## 2. 共享协议层（protocol/）

协议层是六端的**唯一事实来源**。所有数据模型字段名、类型、枚举值以 JSON Schema 文件为准；传输格式（HTTP/SSE/WebSocket/认证/TLS 锁定）的线上字节流六端必须完全一致。

### 2.1 数据契约：11 个 JSON Schema（JSON Schema 2020-12）

| Schema | 职责 |
|-------|------|
| `agent-schema.json` | Agent 标识 + 信息模型 + 配置 |
| `session-schema.json` | 会话模型 |
| `message-schema.json` | 消息模型 + 角色枚举 + 附件 |
| `task-schema.json` | 异步任务，5 类型 / 5 状态 |
| `workflow-schema.json` | DAG 工作流，4 节点 / 8 变换 / 执行状态 / 运行记录 |
| `event-schema.json` | 6 种实时事件（判别联合） |
| `error-codes.json` | 37 个错误码，10 个类别 |
| `plugin-schema.json` | 插件协议（HttpCall/Broadcast/Workflow） |
| `mcp-schema.json` | MCP 桥接（JSON-RPC 2.0） |
| `file-transfer-schema.json` | 文件传输（v1 内联 / v2 分块） |
| `collab-schema.json` | 多端协作 |

### 2.2 传输协议：5 份规范文档

| 文档 | 协议 |
|------|------|
| `transport/http-api.md` | HTTP REST（OpenAI 兼容 `/v1/chat/completions`） |
| `transport/sse-protocol.md` | SSE 流式输出（`text/event-stream`） |
| `transport/websocket-protocol.md` | WebSocket 双向通信 |
| `transport/auth.md` | 认证与令牌（Bearer / `AKS:` 静态存储 / `AH1:` E2E 传输） |
| `transport/tls-pinning.md` | 证书锁定（pin 获取方法见 3.3 节） |

### 2.3 跨平台实现映射

| 维度 | Android (Kotlin) | iOS (Swift) | HarmonyOS (ArkTS) | Desktop (Kotlin) |
|------|------------------|-------------|-------------------|------------------|
| 数据模型 | `data class` + `@Serializable` | `struct` + `Codable` | `interface` + 字面量对象 | `data class` + `@Serializable` |
| 枚举 | `enum class` | `enum` + `String` rawValue | `enum` / 字符串常量 | `enum class` |
| 可空类型 | `String?` | `String?` | `undefined` 联合 | `String?` |
| 时间戳 | `Long`（Unix 毫秒） | `Int64`（Unix 毫秒） | `number`（Unix 毫秒） | `Long`（Unix 毫秒） |
| 序列化 | Gson | `JSONEncoder`/`JSONDecoder` | `JSON.parse`/`stringify` | Gson（wire）+ kotlinx-serialization（落盘） |
| 密钥存储 | AndroidKeyStore + KeystoreManager | Keychain + KeychainManager | HUKS | javax.crypto |
| WebSocket | Ktor WebSocket | `URLSessionWebSocketTask` | `@ohos.net.webSocket` | Ktor WebSocket（OkHttp 引擎） |
| SSE | Ktor SSE | URLSession + 自实现 EventSource | `@ohos.net.http` 分块读 | Ktor SSE |

> **序列化双轨（桌面端）**：持久化用 kotlinx-serialization（默认值语义安全，文件缺字段不致 NPE），wire 协议用 Gson（与移动端行为一致）。Gson 反射构造会绕过 Kotlin 非空校验，因此**不得**用于读磁盘文件。

## 3. Android 架构（Kotlin + Jetpack Compose）

包名 `com.agentcontrolcenter.app`，采用 MVVM + 单向数据流（UDF）+ Hilt 依赖注入。

### 3.1 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Kotlin 2.4.10 |
| UI | Jetpack Compose（Material 3 + Liquid Glass 主题） |
| 编译 | AGP 9（内置 Kotlin，不再单独 apply `kotlin-android`）/ compileSdk 37 / KSP |
| DI | Hilt（`@HiltAndroidApp` + `@HiltViewModel`） |
| 持久化 | Room（8 实体 / 6 DAO / 迁移）+ DataStore |
| 网络 | Ktor 3.2.3（HTTP / SSE / WebSocket）+ OkHttp 引擎 |
| 序列化 | Gson |
| 安全 | Android Keystore（AES-256-GCM）+ PBKDF2 600000 轮 |
| 导航 | Navigation Compose |
| 最低版本 | minSdk 24 / targetSdk 36 |

### 3.2 分层

```
com.agentcontrolcenter.app/
├── [App Shell]   MainActivity / App / AgentControlCenterApplication(@HiltAndroidApp)
│                 AgentConnectionService(前台保活) / AgentControlCenterWidget
├── navigation/   Screen(路由) + AppNavigation(NavHost)
├── feature/      Chat / Sessions / Agents / Settings / Activity / Insights /
│                 Compare / Workflow / Marketplace / Plugin / Sync / Onboarding
│                 Task / MCP（Screen + ViewModel）
├── ui/
│   ├── theme/        Theme / Color / Type / GlassModifier / GlassBackdrop / GlassMotion
│   ├── components/   PressAnimation / Snackbar
│   └── adaptive/     AdaptiveUtils(3 断点 + 折叠屏检测)
├── core/
│   ├── security/     KeystoreManager(AKS:) / CryptoManager(AH1:) / UrlValidator(SSRF)
│   ├── database/     AppDatabase + DAO + Entity + Converters
│   ├── datastore/    SettingsDataStore
│   ├── vendor/       厂商适配（金标联盟四厂商保活 + 公平内存）
│   ├── hardware/     SoC 芯片检测与推理优化
│   └── common/       Extensions / PerformanceMonitor
├── data/
│   ├── repository/   ChatRepository(单一数据源)
│   ├── model/        Session / Message / ActivityItem / ChatBackup / MarketplaceAgent
│   ├── insights/     DataInsightsManager
│   ├── collab/       CollaborationManager
│   ├── sync/         DeviceSyncManager
│   ├── update/       UpdateManager(GitHub Releases 自更新)
│   ├── backup/       BackupManager
│   ├── notification/ SmartNotificationManager
│   └── marketplace/  MarketplaceClient
├── transport/
│   ├── protocol/     AgentTransport(sealed interface)
│   ├── http/         OpenAIHttpTransport(HTTP + SSE) / CertificatePinnerFactory
│   ├── websocket/    WebSocketTransport
│   ├── comfyui/      ComfyWorkflowBuilder
│   └── TransportFactory + ConnectionRepository
├── runtime/
│   ├── agent/        AgentManager + AgentRegistry
│   ├── session/      SessionManager
│   ├── task/         TaskManager
│   ├── workflow/     WorkflowEngine(BFS 拓扑排序 + 环检测)
│   └── notification/ StatusNotificationManager / LocalNotificationManager
├── mcp/             model / registry / client / bridge
├── plugin/          api / runtime(PluginExecutor + PluginManager)
├── localmodel/      LocalModelManager(Ollama / LM Studio 发现)
├── di/              DatabaseModule(@Provides)
└── widget/          WidgetDataProvider + WidgetInputActivity
```

### 3.3 数据流

```
用户输入 → ViewModel → ChatRepository → Room DB
                      ↓
                 Transport → Agent Server
                      ↓
              ViewModel ← Transport Events
                      ↓
                UI State → Compose UI
```

### 3.4 自适应布局

- **Compact**（< 600dp）：手机竖屏，底部导航栏，单列
- **Medium**（600–839dp）：折叠屏 / 小平板，按方向切换底部栏或导航栏
- **Expanded**（≥ 840dp）：大平板，NavigationRail，双栏布局

## 4. iOS 架构（Swift + SwiftUI）

Bundle ID `com.agentcontrolcenter.app.ios`，采用 MVVM + `@Observable` + Swift Concurrency（async/await + AsyncStream），SwiftData 持久化，Keychain 密钥存储。工程由 XcodeGen（`project.yml`）生成，`.xcodeproj` 被 `.gitignore` 忽略。

### 4.1 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Swift 6.0 |
| UI | SwiftUI |
| 状态 | `@Observable` + `@State` |
| 异步 | async/await + AsyncStream |
| 持久化 | SwiftData（`@Model` 实体）+ UserDefaults |
| 网络 | URLSession + `URLSessionWebSocketTask` |
| SSE | URLSession + 自实现 EventSource |
| JSON | Codable |
| 安全 | CryptoKit（AES-256-GCM）+ Keychain |
| 后台 | BackgroundTasks + APNs |
| 最低版本 | iOS 18.0（deploymentTarget） |

### 4.2 十层架构

```
AgentControlCenter/
├── 1. App Root      AgentControlCenterApp(@main) + AppState(@Observable 容器)
│                    + ContentView(NavigationSplitView)
├── 2. Models        Agent / Session / Message / Task / Workflow / AgentEvent
│                    / Plugin / MCPModels（匹配 11 schema）
├── 3. Security      KeychainManager(AKS: + AH1:，与 Android 对齐)
│                    / CryptoManager / URLValidator / TLSPinningDelegate
├── 4. Transport     AgentTransport(协议 + 工厂) / OpenAIHTTPTransport(HTTP + SSE)
│                    / WebSocketTransport(重连)
├── 5. Runtime       AgentManager(capabilityIndex) / SessionManager / TaskManager
│                    / WorkflowEngine(BFS 拓扑排序 + 4 模板)
├── 6. MCP           McpRegistry(NSLock) / McpClient(JSON-RPC 2.0) / McpBridge(编排)
├── 7. Plugin        PluginExecutor(HttpCall / Broadcast / Workflow)
├── 8. Persistence   SwiftDataModels(@Model) + DataController(@Observable ModelContainer)
├── 9. Features      Chat / Agents / Tasks / MCP / Settings / Workflow（SwiftUI Views）
└── 10. Theme        AppTheme(颜色常量 + timeAgo) / GlassPresets
```

### 4.3 数据流

```
用户交互 → Feature View → Runtime Manager → Transport → Agent Server
                          ↓                    ↓
                    SwiftData ←──── AsyncStream Events
                          ↓
                     @Observable → SwiftUI View
```

## 5. HarmonyOS 架构（ArkTS + ArkUI）

> 新增于 v5.1.0。Bundle 由 DevEco（`oh-package.json5` + `hvigorfile.ts`）组织，目标 HarmonyOS NEXT（API 12，`compatibleSdkVersion`/`targetSdkVersion` = `5.0.0(12)`）。

### 5.1 模块结构

```
harmony/
├── common/     HSP — 协议 / 传输 / 安全 / 持久化 / 运行时 / MCP / 插件 / 卡片数据桥
├── features/   8 个 feature HSP
│               chat / agents / activity / marketplace / settings
│               workflow / mcp / compare
├── entry/      HAP — 主壳（Tabs 导航）+ FormKit 服务卡片
└── oh-package.json5 / build-profile.json5
```

### 5.2 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | ArkTS |
| UI | ArkUI（声明式，`@Component` / `@State`） |
| 异步 | Promise / async-await |
| 持久化 | relationalStore + preferences |
| 网络 | `@ohos.net.http` / `@ohos.net.webSocket` |
| 安全 | HUKS（`AKS:`）+ cryptoFramework（`AH1:`） |
| 后台 | 长时任务（DATA_TRANSFER）保活 |
| 卡片 | FormKit 服务卡片（2x2 竖版 + 2x4 宽版） |
| 本地化 | base / zh_CN / en_US / ja_JP |

### 5.3 已知接线缺口

`entry/oh-package.json5` 声明了 `@features/workflow`、`@features/mcp`、`@features/compare` 三个依赖，但主壳 `entry/src/main/ets/pages/Index.ets` 仅有 5 个 Tab（Chat / Agents / Activity / Marketplace / Settings）且未导入这三个 feature——**已开发的功能对用户不可达**。详见 `DEV_PLAN.md` Sprint 16.3。

## 6. Desktop 架构（Kotlin + Compose Multiplatform）

> 新增于 v5.2.0。独立 Gradle 工程（`desktop/`），一套代码打包 Windows（msi）/ macOS（dmg）/ Linux（deb）。与 Android 端同语言同栈，协议层逐字段对齐。

### 6.1 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Kotlin 2.4.10（jvmTarget 17） |
| UI | Compose Multiplatform 1.12（Material 3） |
| 状态 | `AppStore` 单仓状态流（`MutableStateFlow`） |
| 异步 | Coroutines + Flow |
| 持久化 | JSON 文件存储（`~/.agent-control-center/`） |
| 网络 | Ktor 3.2.3 + OkHttp 引擎（与 Android 同栈） |
| 安全 | javax.crypto：`CryptoManager`（E2E `AH1:`）+ `CredentialVault`（静态 `AKS:`，主密钥文件）+ `UrlValidator`（SSRF） |
| 打包 | jpackage（Msi / Dmg / Deb） |
| 本地化 | en / zh（ja、ko 列入路线图） |

### 6.2 分层

```
com.agentcontrolcenter.desktop/
├── Main.kt              窗口 + 系统托盘 + close-to-tray
├── agent/model/         AgentConfig / AgentType(8) / AgentProtocol / Agent / ConnectionState
├── core/error/          AppErrorCode(37)
├── core/security/       UrlValidator(SSRF 防护) / CryptoManager(E2E AH1:)
│                        / CredentialVault(静态 AKS:，主密钥文件)
├── data/model/          Message / Session
├── data/persistence/    JsonStore（原子写 + 损坏回退空态）/ AppSettings
├── transport/           TransportFactory + protocol + http(OpenAIHttpTransport)
│                        + websocket(WebSocketTransport)
├── app/                 AppStore(状态编排) + Strings(en/zh)
└── ui/                  AppRoot + ChatPane / AgentsPane / SettingsPane + Theme
```

### 6.3 数据布局

```
~/.agent-control-center/
├── settings.json              — AppSettings
├── agents.json                — List<AgentConfig>（apiKey 为 AKS: 密文）
├── sessions.json              — List<Session>
├── master.key                 — 256 位主密钥（AKS: 用，POSIX 600）
└── messages/<sessionId>.json  — List<Message>
```

> `master.key` 丢失 = 已存储的 `AKS:` 凭据永久不可解密。备份数据目录时须一并包含它；反之，只拷走 `agents.json` 而无 `master.key` 则拿到的是无法解密的密文。

写入经 `Mutex` 串行化 + NIO `Files.move(REPLACE_EXISTING + ATOMIC_MOVE)` 原子替换（`File.renameTo` 在 Windows 上目标存在时必然失败，禁止使用）。

## 7. 导航

| 端 | 导航形态 | 入口 |
|----|---------|------|
| Android | 底部导航栏 / NavigationRail + NavHost | Chat、Sessions、Activity、Settings（Tab）+ Agents、Marketplace、Insights、Compare、Workflow、Plugins、DeviceSync、Onboarding |
| iOS | NavigationSplitView 侧栏 | Chat、Agents、Tasks、MCP、Settings、Workflow |
| HarmonyOS | Tabs（底部，BarMode.Fixed） | Chat、Agents、Activity、Marketplace、Settings（workflow/mcp/compare 未接线） |
| Desktop | NavigationRail | Chat、Agents、Settings（Workflow 列入 v5.3 路线图） |

## 8. 安全模型（四端对齐）

| 维度 | Android | iOS | HarmonyOS | Desktop |
|------|---------|-----|-----------|---------|
| 静态存储加密 | AndroidKeyStore，`AKS:` 前缀 | Keychain，`AKS:` 前缀 | HUKS，`AKS:` 前缀 | ⚠️ `AKS:` 前缀，主密钥为本地文件（v5.3.0 起） |
| E2E 传输加密 | CryptoManager，`AH1:`，PBKDF2 600000 轮 | CryptoKit，`AH1:`，相同轮数 | cryptoFramework，`AH1:`，相同轮数 | CryptoManager，`AH1:`，相同轮数 |
| SSRF 防护 | `UrlValidator` | `URLValidator` | 内置校验 | `UrlValidator`（同 Android 实现） |
| 证书锁定 | `CertificatePinnerFactory`（**pin 为空**） | `TLSPinningDelegate`（**pin 为空**） | — | — |
| 证书锁定开关 | ❌ 未实现 | ❌ 未实现 | ❌ | ❌ |
| 传输安全默认 | `allowBackup=false`，密钥不进云备份 | ATS 允许本地 Agent 连接 | — | 本地端点放行 |

> **证书锁定现状（v5.3.0 更新）**：Android `CertificatePinnerFactory.kt` 与 iOS `TLSPinningDelegate.swift` 均保留 `TODO_GET_REAL_PIN` 标记，pin 集合为占位值——运行时降级为系统默认 CA 校验，**不会导致连接失败，但也不提供 pin 保护**。
>
> v5.3.0 已完成**代码侧**修复与工具链：
>
> - 修复 iOS 用原始大小写 host 查 pin 表导致的**静默降级**（Android 侧 OkHttp 会规范化小写，同一 URL 双端行为分歧），Android `isPublicEndpoint` 改用 `Locale.ROOT` 消除土耳其 locale 陷阱；
> - 新增 `scripts/fetch-tls-pins.sh`（含代理 / DNS 劫持 / 证书链三道 MITM 拦截）与 `scripts/check-tls-pins.sh`（双端一致性 CI 校验）。
>
> **真实 pin 仍待填入**：需在无代理的可信网络下执行 `bash scripts/fetch-tls-pins.sh api.openai.com`。当前环境（CI 沙箱）无法完成——DNS 被解析到保留地址，抓到的证书可能来自 MITM 代理，其 pin 写入源码会导致全球用户连接被拒。见 `DEV_PLAN.md` 任务 16.1。
>
> ⚠️ **双端均无用户开关**（2026-08 全仓核实）。协议文档曾记载「Settings → 安全 → 证书锁定」，但无任何 UI 实现。这意味着 pin 一旦填错，用户**没有自救手段**，只能等发版——已登记为任务 16.10，建议作为填 pin 的前置。

> **桌面端静态存储（v5.3.0 已修复，原为 P0）**：v5.2.0 及更早 `AppStore.saveAgent()` 直接把 `AgentConfig` 交给 `JsonStore.saveAgents()`，`apiKey` **明文写入** `~/.agent-control-center/agents.json`，与 `SECURITY.md` §4 冲突。v5.3.0 新增 `core/security/CredentialVault.kt` 实现 `AKS:` 加密，并在持久化边界（`AppStore.toPersisted()` / `fromPersisted()`）自动加解密，内存态保持明文供传输层使用；启动时一次性迁移历史明文。
>
> **强度差异（重要）**：桌面端在纯 JVM 下无跨平台硬件密钥库，主密钥为 `~/.agent-control-center/master.key` 中的 256 位随机密钥（POSIX 权限 600）。可防「数据文件被拷走后离线破解」，**防不住**「同用户身份的恶意进程」——弱于 Android TEE/StrongBox 与 iOS Keychain。完整威胁模型见 `SECURITY.md` §4.4。

## 9. 实现完整度边界（v5.2.0 审计）

六端铺开速度快于深度验证速度。下表区分**已坐实**与**已宣发但存在空洞**的能力，避免把 README 的功能列表误读为完成度。

| 能力 | Android | iOS | HarmonyOS | Desktop |
|------|:-------:|:---:|:---------:|:-------:|
| 多协议连接（8 AgentType） | ✅ | ✅ | ✅ | ⚠️ ComfyUI 优雅降级 |
| 流式聊天 | ✅ | ✅ | ✅ | ✅ |
| WorkflowEngine（DAG 执行） | ✅ | ✅ | ✅ | ❌ 未移植 |
| Workflow 可视化编辑 | ❌ 只读画布 | ❌ 只读画布 | ❌ 只读画布 | ❌ 未移植 |
| Workflow 运行历史 | ✅ Room | ✅ SwiftData | ✅ relationalStore | ❌ 未移植 |
| MCP（JSON-RPC 2.0） | ✅ | ✅ | ✅ | ❌ |
| 插件系统 | ✅ | ✅ | ✅ | ❌ |
| E2E 加密 `AH1:` | ✅ | ✅ | ✅ | ✅ |
| 静态存储加密 `AKS:` | ✅ 硬件 | ✅ 硬件 | ✅ 硬件 | ⚠️ 密钥文件（v5.3.0 起，原明文） |
| 证书锁定 | ⚠️ 接线完整·pin 为空 | ⚠️ 接线完整·pin 为空 | ❌ | ❌ |
| 跨端同步 | ⚠️ 部分 | ⚠️ MultipeerConnectivity 全 TODO | ❌ | ❌ |
| 单元测试 | 20 文件 | 13 文件 | **0** | 4 文件 / 40 用例 |
| Instrumented / UI 测试 | 1 文件（DAO） | **0** | **0** | **0** |

图例：✅ 已坐实 ｜ ⚠️ 已宣发但存在空洞 ｜ ❌ 缺失或未接线

### 9.1 易被误读为缺陷的项（实为陈旧注释）

`ios/AgentControlCenter/Runtime/WorkflowEngine.swift` 第 46 与 375 行的文档注释仍写着「AgentConfigDao 尚未实现，当前使用默认配置占位」。**该注释已过期**：C9 修复后，引擎通过注入的 `DataController.fetchAgentConfigs()` 查询真实配置，仅在空库（无匹配 AgentType）时回落 `defaultConfig`。运行时行为与 Android 对齐，**不是缺陷**。

处理：保留行为，清理注释（见 `DEV_PLAN.md` Sprint 16 任务 16.7）。此类「注释与实现背离」在六端架构下尤其危险——审计者与被审计代码都容易被误导。

## 10. 协议层变更约束

1. 新增字段：向后兼容，提供 `default` 值，旧客户端忽略未知字段
2. 移除字段：先标记 `deprecated`，至少一个版本周期后移除
3. 枚举变更：只能新增值，不能移除或重命名已有值
4. 传输格式变更：六端必须同步发布
5. 新增端：先移植 `protocol/schemas/` 全量契约，再实现传输层与 UI

### 10.1 版本号单一事实来源

`version.properties`（仓库根）为唯一事实来源，由 `scripts/check-version-sync.sh` 在 CI（`check-version.yml`）中校验同步：

| 落地位置 | 字段 |
|---------|------|
| `android/app/build.gradle` | `versionCode` / `versionName` fallback |
| `ios/project.yml` | `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`（main + Widget 两个 target） |
| `desktop/build.gradle.kts` | `packageVersion` |
| `package.json`（根） | `version` |

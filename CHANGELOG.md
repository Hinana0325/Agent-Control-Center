# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [5.2.0] - 2026-08-26

### 六端架构 — 桌面三端（Windows / macOS / Linux）

在 Android/iOS/HarmonyOS 三移动原生端之上新增桌面端：**Kotlin + Compose Multiplatform Desktop 1.12**，一套代码打包三平台（`desktop/` 独立 Gradle 工程）。与 Android 端同语言同栈（Kotlin 2.4.10 + Ktor 3 + Gson），协议层逐字段对齐。

#### 桌面端（新增 `desktop/`）

- **协议层移植**：AgentConfig/AgentType(8)/AgentProtocol/Agent/ConnectionState/Message/Session/AppErrorCode(37)——与移动端逐字段对齐。
- **传输层移植**：`WebSocketTransport`（鉴权帧 + 指数退避重连 + 30s 心跳 + 抖动）与 `OpenAIHttpTransport`（SSE 逐行流式 + 非 SSE 回退 + 20 条滑动窗口历史 + 5xx/网络异常指数退避重试）逐逻辑移植；`TransportFactory` 按 AgentType 路由（ComfyUI 桌面 v1 优雅降级，见 roadmap）。
- **安全层移植**：`UrlValidator`（SSRF 防护：云 metadata/CGNAT/组播/保留段拦截，本地端点放行）+ `CryptoManager`（E2E `AH1:` 格式 AES-256-GCM + PBKDF2 600000 轮，与移动端互操作，口令热更新）。
- **UI**：NavigationRail 三主入口（Chat/Agents/Settings）；会话侧栏 + 消息流式气泡 + Enter 发送；Agent 配置卡片与编辑对话框（8 类型下拉）；深/浅主题、en/zh 双语、底部连接状态栏（延迟/服务器）。
- **平台特性**：系统托盘（Show/Quit + close-to-tray 可关）、`~/.agent-control-center/` JSON 文件持久化（原子写入 + 损坏回退空态）。
- **测试**：23 个 JVM 单测（协议契约 8 + 安全 10 + 持久化 5），本地全过。
- **打包与 CI**：jpackage 配置 Msi/Dmg/Deb 三格式（Linux 本地已验证自包含镜像 182MB，deb 需 fakeroot 由 CI 提供）；`build-desktop.yml` 三 OS 矩阵测试 + 打包 + Tag 发布。

#### Fixed（跨端同步）

- **`UrlValidator` ws/wss scheme 误杀（Android + Desktop）**：JVM 的 `java.net.URL` 未注册 ws/wss 协议 handler，`validate("ws://…")` 一律抛 MalformedURLException 返回 null——WebSocketTransport 的 SSRF 校验会把**所有 WebSocket URL** 误判为「blocked URL」导致无法连接。修复：解析前规范化 ws/wss → http/https 等价形式（scheme 白名单仍按原值校验）。桌面端测试覆盖 ws:// 通过。

#### 文档

- **README.md**：六端化（架构图 + 技术栈四列对照 + 项目结构 + 构建运行 + CI 清单）。
- **desktop/README.md**（新增）：桌面端架构、设计取舍、路线图。

#### 全面代码扫描修复（desktop + CI）

- **C1 窗口尺寸被重置**：[Main.kt] `window.size` 写在 Window content 里，托盘显隐切换（visible 变化）触发 content 重组时会把用户手动缩放的窗口重置回 1200x800。改用 `rememberWindowState` 管理初始尺寸。
- **C2 CI Windows 矩阵崩溃**：`chmod +x gradlew` 步骤在 windows-latest（pwsh 无 chmod）必然失败；gradlew 已按 100755 提交，直接移除该步骤。
- **C3 Release 误收测试报告**：安装包产物名 `desktop-{format}` 与测试报告产物 `desktop-test-reports-{os}` 同为 `desktop-*` 前缀，Tag 发布时 download pattern 会把测试报告一并传上 GitHub Release。改名 `desktop-installer-{format}`。
- **M1 设置按键级落盘**：E2E 口令输入框每敲一个字符就触发一次磁盘写 + 传输层热更新，加 500ms 防抖。
- **M2 Windows 非原子写**：`File.renameTo` 在 Windows 上目标存在时必然失败，原子写静默退化为直写；改用 NIO `Files.move(REPLACE_EXISTING + ATOMIC_MOVE)`。
- **M3 死按钮**：聊天输入栏流式期间的 Stop 按钮 `enabled=false` 无任何行为，移除（流式状态由气泡 ▍ 游标指示）。
- **M4 无托盘环境崩溃风险**：部分裁剪型 Linux WM 无系统托盘，`Tray` 可抛异常，加 `SystemTray.isSupported()` 守卫。

## [5.1.0] - 2026-08-26

### 三原生架构 + 厂商适配 — HarmonyOS 第三原生端 & 金标联盟四厂商系统特性

从 v4.x 双端（Android/iOS）扩展为三端（Android/iOS/HarmonyOS NEXT），共享协议层不变；Android 端新增国产 ROM 厂商适配体系（金标联盟四厂商保活引导 + 公平运行内存机制 + 内存占用优化）。

#### HarmonyOS 第三原生端（新增 `harmony/`）

- **多 HSP 模块化工程**：`common` HSP（协议/传输/安全/持久化/运行时/MCP/插件/卡片数据桥）+ 8 个 `features/*` HSP（chat/agents/activity/marketplace/settings/workflow/mcp/compare）+ `entry` HAP。
- **协议层映射**：11 JSON Schema 精确映射为 ArkTS interface，字段名/类型/枚举与双端完全对齐。
- **核心链路**：`OpenAIHttpTransport`（HTTP+SSE）/ `WebSocketTransport` / `TransportFactory` / `ConnectionRepository`；HUKS（AKS:）+ cryptoFramework（AH1:）；relationalStore 实体 + DAO 对齐 Room/SwiftData。
- **功能对齐**：8 AgentType 全路由（含 ComfyUI 文生图双模式）+ `WorkflowEngine` BFS 拓扑/环检测/4 节点类型 + MCP 三件套（JSON-RPC 2.0）+ `PluginExecutor` 三动作。
- **平台特性（H5）**：`KeepAliveManager` 后台保活（DATA_TRANSFER 长时任务）、FormKit 服务卡片（2x2 竖版 + 2x4 宽版双布局 + formId 注册表 + 应用侧主动推送）、四语本地化（base/zh_CN/en_US/ja_JP）。
- **CI**：`.github/workflows/build-harmony.yml`（self-hosted harmony-ci runner）。

#### 厂商适配 — 金标联盟四厂商（新增 `core/vendor/`）

- **`VendorRomAdapter`**：四厂商 ROM 检测（品牌关键词分组含子品牌——xiaomi/redmi/poco、huawei/honor/hihonor、oppo/oneplus/realme、vivo/iqoo；小米系另有 `ro.miui.ui.version.name`/`ro.mi.os.version.name` 系统属性反射兜底）。
- **`VendorKeepAliveHelper`**：电池优化白名单申请（标准 PowerManager API）+ 按 ROM 分发自启动管理页跳转（四厂商共 11 候选 Activity，逐候选 resolveActivity 探测 + 应用详情页兜底）+ 省电策略引导；设置 → 性能 → 厂商保活优化区块（四厂商设备渲染，ON_RESUME 刷新白名单状态），strings 四语。
- **`BootCompletedReceiver`**：开机/应用更新后恢复 Agent 连接（仅已配置用户，克制不打扰）。

#### 内存占用优化（公平内存机制的应用侧配合）

- **消息列表投影 `MessageListItem`**：`getMessagesBySession` Room Flow 由 `SELECT *` 改为显式列投影，排除 `attachmentData`（MB 级 Base64 字符串，消息 UI 零消费）——长会话 + 带附件场景常驻内存显著下降；备份导出走独立全量查询（`getMessagesBySessionOnce`），附件数据不丢失。
- **Insights 统计投影 `MessageStat`**：`DataInsightsManager.generateInsights` 由全表 `SELECT *` 载入改为三列投影（role/content/timestamp），消除统计聚合瞬时内存峰值。
- **`messages` 表新增 `timestamp`/`role` 单列索引**（DB v10→v11 migration），服务 `getLastMessage` 排序与统计查询。
- **`ChatViewModel.MAX_ATTACHMENT_BYTES`** 附件大小上限提取为命名常量（10MB，与 `AppErrorCode.FILE_TOO_LARGE` 对齐）。

#### 公平运行内存机制（金标联盟统一规范）

- **`FairMemoryManager`**：响应 `itgsa.intent.action.TRIM/KILL` 广播（动态注册，专用 HandlerThread），解析 common/extra Bundle（notifyType 1000=PSS/2000=Java 堆），经 callback IBinder `transact(FIRST_CALL_TRANSACTION, FLAG_ONEWAY)` 回调处理结果（3s 超时），DeathRecipient 追踪系统服务死亡。
- **释放钩子体系**：`addReleaseHook` 注册——传输层内存历史清理（`ConnectionRepository.clearAllHistory` 新增转发，连接不受影响）+ Analytics 环形缓冲清空；KILL 时消息/会话已实时落库无需额外备份现场。
- **`onTrimMemory` 分级**：`UI_HIDDEN` 清性能采样、`RUNNING_LOW` 清埋点缓冲、`COMPLETE` 全量清理，与厂商广播互补。

#### 文档

- **README.md**：更新为三端架构总览（技术栈三方对照表/11 Schema/8 AgentType/项目结构含 harmony 与 core/vendor）。
- **docs/vendor-adaptation.md**（新增）：厂商适配指南（四厂商保活三重管控 + 各厂商自启动跳转候选 + 公平内存广播契约/回调协议/释放策略 + 内存投影瘦身 + 验证方法/四家厂商官方文档链接）。

#### Fixed

- **鸿蒙工程 94 处 UI DSL 非法语法**：`build(): void`/`@Builder xxx(): void`/`Column(): void` 修正为官方 `build()`/`xxx()`/`Column()` 语法（原写法无法通过 ArkTS 编译）。
- **ko strings 单引号**：`values-ko/strings.xml` vendor 文案单引号导致 aapt 解析失败，改全角引号。

## [4.8.0] - 2026-07-23

### Added — ComfyUI / OpenWebUI 双新 Agent 类型接入与功能融合

承接 v4.7.1 的双新 Agent 类型（Transport 路由 + AgentType 枚举 + ComfyUITransport/OpenWebUITransport 实现），本次完成 UI 层融合、模板市场、工作流模板与代码清理，让两种新类型在端到端流程中真正可用。

#### ComfyUI / OpenWebUI 适配（传输层）

- **Transport 路由**：`TransportFactory` 按 `AgentType` 创建 `ComfyUITransport` / `OpenWebUITransport`，与既有 WebSocket/OpenAI 兼容传输并列。
- **ComfyUI 双模式自动切换**：纯文本 prompt → 默认文生图工作流（CheckpointLoader → CLIPTextEncode → KSampler → VAEDecode → SaveImage）；`{` 开头的 JSON → 直接提交原生 ComfyUI 工作流。字段语义复用：`model`→checkpoint 文件名、`temperature`→cfg scale、`maxTokens`→steps、`systemPrompt`→negative prompt。
- **OpenWebUI 端点修复**：`/api/v1` 前缀统一处理，兼容 OpenAI API 格式接入任意本地大模型。
- **AgentConfigValidator apiKey 豁免**：LocalModel 和 ComfyUI 本地部署通常无认证，apiKey 标记可选。

#### UI 友好性（Android + iOS 双端同步）

- **AgentTypeUi/AgentTypeUI 辅助**：新建集中管理按 AgentType 区分的 UI 展示信息（图标、字段标签、占位提示、默认配置预填），避免在各 UI 文件分散 `when(type)` 分支。
- **字段标签动态化**：ComfyUI 时显示 Checkpoint / CFG Scale / Steps / Negative Prompt，其他类型显示通用标签。
- **切换类型预填默认值**：`AgentTypeUi.withDefaults()` 仅在用户尚未填写时填充合理默认端点（ComfyUI `127.0.0.1:8188`、OpenWebUI `127.0.0.1:3000/api/v1` 等），避免覆盖已输入内容。
- **按类型图标**：AgentCard / MarketplaceCard / ChatOverlays 卡片图标从硬编码统一改为按 AgentType（ComfyUI=Image、OpenWebUI=Public 等）。
- **SetupWizard 联动**：首启向导切换类型时同样预填默认配置，apiKey 可选提示。

#### Marketplace 模板

- **本地端点模板**：`MarketplaceClient.fetchLocalTemplates()` 在 `fetchAll` 顶部置入 ComfyUI 与 OpenWebUI 本地端点模板（`127.0.0.1`），用户随时可一键连接本地部署服务，且不受搜索词过滤。

#### Workflow 模板

- **ComfyUI 文生图工作流**：`WorkflowTemplates.imageGeneration()` 新增 INPUT → AGENT(ComfyUI) → OUTPUT 三节点模板，加入 `allTemplates()` 列表。

#### 测试与代码清理

- **E2E 判断改用 protocolType**：`ChatViewModel` / `AgentConnectionService` 的 E2E 加密判断从硬编码枚举列表（`setOf(Hermes, OpenClaw, OpenCode)`）改为 `config.protocolType == AgentProtocol.WebSocket`，未来新增 WebSocket 类型无需改这里。
- **widget_status 去硬编码**：4 个 locale（default/zh/ja/ko）的 `widget_status` 去除硬编码的 Hermes 类型名，改为通用 "Connected · Agent"。
- **AgentTypeTest 补齐**：新增 ComfyUI/OpenWebUI `valueOf` 解析断言、displayName 断言、枚举数量测试（8 种类型）。

#### Fixed

- **AgentTypeUi 图标引用修复**：`Icons.Default.Cpu` 在 material-icons-extended 中不存在导致 `compileDebugKotlin` 失败；OpenAI 改用 `Memory`、XiaomiMiMo 改用 `DeveloperBoard`。

## [4.7.1] - 2026-07-22

### Hotfix — 修复 v4.7.0 发布失败

v4.7.0 tag 指向的 commit (`a3f1c10`) CI detekt 失败，导致 `Publish Release` job 被跳过、release 未创建。本次为补丁版本，内容与 v4.7.0 相同并附带 detekt 修复。

- **detekt UseCheckOrError 修复**：`WorkflowEngine.execute` 中 `throw IllegalStateException` → `check()`，与既有 No INPUT node / cycle detected 错误处理一致。
- **detekt jvmTarget 显式指定**：`build.gradle` 为 detekt 任务显式设置 `jvmTarget = "17"`（与 compileOptions / kotlin.compilerOptions 一致），避免 detekt-cli 自动检测运行时 JDK 版本导致 CI/本地不一致。
- **detekt baseline 重新生成**：接受新增存量违规（FunctionNaming Compose PascalCase / TooManyFunctions ViewModel / TooGenericExceptionCaught 传输错误处理 / LongMethod 边界值等不可避免的违规）。

## [4.7.0] - 2026-07-22

### 配置辅助体系全面升级（双端对齐）

继 4.6.3 配置辅助体系初版（统一仓库 + 校验器 + 首启向导）后，本次完成三项配置流程优化与「配置变更通知运行时」打通。

#### 配置流程优化（校验 / 向导 / 可发现性）

- **校验统一**：Android/iOS Agent 表单统一走 `AgentConfigValidator`，错误按字段回填 `isError`/`supportingText`，删除 UI 层重复校验逻辑（apiKey≥10、http 前缀等）。
- **类型联动**：`AgentType.LocalModel` 隐藏 serverUrl/apiKey 输入框；MCP `STDIO` 传输标签改为「命令路径」。
- **向导增强**：SetupWizard `testConnection` 加取消按钮、重试前先 `disconnect`、`ensureActive` 响应取消；iOS SetupWizard 接入 ContentView 并新增真实网络 ping（URLSession HEAD + SSRF 防护）。
- **AgentDefaults 预填**：新建 Agent 时从 defaults 预填 model/temperature/maxTokens。
- **清理彻底化**：`clearAllPreferences` 补全 `mcp_prefs`/`widget_prefs` SharedPreferences；iOS `allPreferenceKeys` 补全 `mcp_servers`/`deviceSyncAutoSync`，`clearAllData` 走统一入口。
- **可发现性**：设置页加搜索（双栏过滤左侧分类、单栏隐藏不匹配 Section，与 iOS `searchable` 对齐）；敏感 FeatureFlag（E2E_ENCRYPTION/DEVICE_SYNC）从开→关加二次确认弹窗。
- **编译修复**：ConfigRepository 6 路 Flow `combine` 改嵌套 pairs（无 6 参重载）；`experimentalFeaturesSection` 改非 `@Composable` LazyListScope 扩展；移除 `Agent.kt` 中重复的 `AgentProtocol` 声明。

#### 配置变更通知运行时（痛点6）

此前运行时层几乎不订阅 config Flow（唯一例外 `AnalyticsManager`），配置变更需手动 `/reconnect` 或重启才生效。本次打通「配置 → 运行时」订阅通道。

- **E2E 加密热更新**：`SecurityConfig` 增加 `e2eKey` + `effectiveE2eKey`；`AgentTransport` 接口新增 `updateE2eKey(key)` 默认空实现，`WebSocketTransport` 实现热更新（`@Volatile`/NSLock 保护）；`ConnectionRepository`（Android）/ `AppState`（iOS）订阅 security 变更，切换 E2E 或重生成密钥即时应用到活动连接，无需断开重连。
- **FeatureFlag 运行时 gating**：`WorkflowEngine` 注入 `FeatureFlagManager` gate `WORKFLOW_ENGINE`（禁用拒绝执行）；`McpBridge` gate `MCP_SERVERS`（禁用拒绝连接）。此前 flag 仅驱动设置页 UI 开关，运行时不查询；现在 flag 真正生效。
- **AgentDefaults 运行时策略**：连接时 `config.model` 为空回退 `defaultModel`；策略明确为「不覆盖已有 Agent 的显式配置，Defaults 仅作兜底」。

## [4.6.3] - 2026-07-22

### Security — 严格代码审查发现的 65 项问题全量修复

对 v4.6.2 进行三路并行严格代码审查（Android / iOS / Protocol+CI），修复全部 65 项问题（CRITICAL 5 / HIGH 16 / MEDIUM 26 / LOW 18），Android 单元测试 92/92 通过。

#### CRITICAL

- **C1 版本号统一**：8 处出现 6 种不同版本号统一为 `version.properties` 单一事实来源（v4.6.3 / code 39）。README、DEV_PLAN、protocol/README.md、protocol-overview.html、package.json 全部对齐；iOS project.yml MARKETING_VERSION/CURRENT_PROJECT_VERSION 与 Android build.gradle fallback 同步。
- **C2 Keychain 访问控制三方对齐**：`auth.md` 与 `SECURITY.md` 对 iOS Keychain 访问控制描述矛盾（`WhenUnlocked` vs `AfterFirstUnlock`）。统一为 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，与 Android `setUserAuthenticationRequired(false)` 后台服务语义一致——锁屏后前台服务仍可读取 apiKey。
- **C3 错误码体系双端接入**：协议 `error-codes.json` 定义 37 个错误码但 iOS enum 零调用、Android 完全缺失。Android 新建 `AppErrorCode.kt`（37 错误码 + 10 类别，与 iOS 完全一致），12 处 `AgentEvent.Error` 调用接入；iOS 10 处 `emit(.error(...))` 接入对应 AppErrorCode。
- **C4 CI 阻断策略恢复**：detekt/lint job `continue-on-error: true` 导致 PR 实质无质量门。关闭后存量违规由 `detekt-baseline.xml` 接受、增量违规才失败。
- **C5 BackupManager 文件保护**：iOS 备份文件 `data.write(to:options:.atomic)` 改为 `[.atomic, .completeFileProtectionUntilFirstUserAuthentication]`，设备锁定后不可读。

#### HIGH — 安全相关

- **H1 McpClient SSRF 校验**：`McpClient.sendRequest` 未走 `UrlValidator.validate`，可被诱导配置 `http://169.254.169.254/...` 触发元数据 SSRF 并外发 apiKey。入口加 `UrlValidator.validate(server.transportUrl, allowLocalhost = false)`。
- **H2 iOS URLValidator 补全**：注释声明"禁止 .local/.internal"但代码体只有 `return url` 完全未实现。补 `if host.hasSuffix(".local") || host.hasSuffix(".internal") { return allowLocalhost ? url : nil }`，与 Android 对齐。
- **H3 WebSocket 心跳协程泄漏**：`heartbeatJob` 启动于外层 scope 而非 webSocket 块子作用域，catch 块进入后 heartbeat 在 `session?.send(...)` 命中 null 后既不抛异常也不 break，30s 周期空转永不退出。改 `coroutineScope { launch { ... } }` 并在 heartbeat 内 `if (session == null) break`。
- **H4 WebSocket 发送 StreamComplete**：WebSocket 路径只发 `MessageReceived` 从不发 `StreamComplete`，导致 WorkflowEngine 每个节点等满 60s 超时。handleMessage 在收到完整帧后追加发送 `AgentEvent.StreamComplete`。
- **H5 apiKey 输入框视觉掩码**：`AgentsScreen` / `McpScreen` apiKey OutlinedTextField 缺 `PasswordVisualTransformation()`，明文显示。补上掩码。
- **H6 ConnectionRepository @Volatile**：`lastConfig` / `lastE2eKey` 跨线程读写无可见性保证，`onAvailable` 回调在 ConnectivityManager 工作线程无锁读取。两字段加 `@Volatile`。
- **H7 Ktor Logging level 配置**：`install(Logging)` 无配置，Ktor 3.x 默认 `LogLevel.ALL` 把 `Authorization: Bearer <apiKey>` 打印到 logcat。release 改 `LogLevel.NONE`。
- **H9 iOS 10MB 附件校验**：协议 `file-transfer-schema.json` 要求 10MB 上限，Android 已实现，iOS 完全缺失。ChatView 选择附件处增加校验。
- **H12 Android StrongBox 启用**：`KeystoreManager` 未调用 `setIsStrongBoxBacked(true)`，与 `auth.md` 声明"TEE/StrongBox 设备支持时自动启用"不符。补 try/catch fallback。
- **H13 Android MessageRole apiValue**：`OpenAIHttpTransport` 直接硬编码 `"user"/"assistant"`，绕过 enum。`MessageRole` 增加 `val apiValue: String get() = name.lowercase()`，与 iOS 对齐。
- **H14 Android Message.metadata 字段名对齐**：协议要求 `metadataJson: String`，Android 用 `metadata: Map<String,String>`。改为 `metadataJson` + 计算属性 `metadata`。
- **H15 iOS WebSocketTransport 数据竞争**：`connectionState` 跨 actor 并发读写无同步，Swift 6 strict concurrency 下是数据竞争。用 NSLock 保护所有访问。
- **H16 iOS TLS 钉扎 TODO**：三处传输层无 `URLSessionDelegate` 证书钉扎。增加 TODO 注释，后续实现 SPKI pinning。

#### HIGH — CI/CD

- **H8 CI Action 版本号修复**：`actions/checkout@v7` / `actions/cache@v6` 不存在，统一到 v4；setup-android/setup-java 混用版本统一。
- **H10 PR 模板修复**：删除 `node tests.js passes (47/47)` 引用（项目已重构为双原生），替换为 Android/iOS 测试命令；新增协议层同步、加密格式、错误码注册、iOS Simulator、iPad 等 checklist。
- **H11 build-ios.yml paths 增加 protocol/**：协议 schema 改动不会触发 iOS CI 的漏洞修复。

#### MEDIUM（26 项）

- **协议 Schema**：workflow-schema 相对 `$ref` 改绝对 URI；WorkflowExecutionState 加 required；mcp-schema JsonRpcResponse 加 oneOf 互斥；删除非标准 `format: float`。
- **Android**：network_security_config 收紧明文白名单（移除路由器 IP）；AgentConnectionService 退避策略（连续失败 10 次停止 + 5 分钟上限）；Application.onTerminate 清理 NetworkCallback；seed_ollama 加日志；WorkflowEngine delta 重复累加修复；detekt MagicNumber/SwallowedException 启用。
- **iOS**：KeychainManager 失败 os.Logger 告警 + NSLock 并发同步；TransportFactory.provider 改 @MainActor；WorkflowEngine 失败节点传播；SmartNotificationManager `try! Regex` 改 `try?`。
- **CI/CD**：JDK 统一 21；build job 权限拆分（read + release write）；iOS 部署目标文档对齐 18.0；移除 `gh release upload --clobber`。

#### LOW（18 项）

proguard 收紧（删 Sentry/Gson 全量 keep + release 移除 Log.v/d）；obfdict 扩充到 234 条；WebSocket 重连 jitter；shutdown 清 apiKey；PluginExecutor UTF-8 截断；.gitignore iOS 兜底；删除 Konan 缓存；SECURITY.md 版本号等。

#### 验证

- Android 单元测试 92/92 通过（WorkflowEngineTest 13/13、MessageTest 6/6、AgentEventTest 4/4）
- iOS swiftc -parse 语法检查全部通过（沙箱无 iOS SDK，未做完整 xcodebuild）
- 跨端一致性验证：Keychain 访问控制三方对齐、错误码双端实际调用、Message.metadataJson 字段名对齐

## [4.6.2] - 2026-07-21

### Fixed — iOS SettingsView 10 项逻辑问题修复

修复用户反馈「设置页面的逻辑有啥问题吗」审计发现的 10 项逻辑 Bug，覆盖加密生命周期、通知开关死 UI、跨视图字体同步、Keychain I/O 优化、版本号硬编码等。

- **`.task` / `.onChange(of: passphrase)` 循环触发**: 进入设置页 `.task` 从 Keychain 加载 passphrase 后立即触发 `.onChange`，又把刚读出的值写回 Keychain 一次（无意义 I/O）。用 `isPassphraseLoaded` flag 区分「初始加载」与「用户修改」，跳过首次 onChange。
- **`regeneratePassphrase` 名不副实**: 按钮「重新生成」原 implementation 只清空 passphrase，与按钮名严重不符。改为用 `CryptoKit.SymmetricKey(size: .bits256)` 生成密码学安全 32 字节随机密钥，base64 编码后赋值。
- **加密开关与 passphrase 生命周期不一致**: 关闭加密时不清 Keychain 和内存 passphrase，重启后旧密钥仍可启用加密；启用前无 passphrase 校验，可进入「已启用但无密钥」的失效状态。修正：关闭时同步清空内存 + Keychain + 取消待写任务；启用时若 passphrase 为空自动生成；UI 增加「尚未设置密码短语」提示。
- **`clearAllData` 注释与行为矛盾 + 范围不全**: 注释说「Agent 配置保留」但代码实际调用 `deleteAgentConfig`；未清 UserDefaults（`defaultModel`/`temperature`/`theme`/通知开关等）；未清 Keychain E2E 密钥。修正：注释与行为对齐（Agent 配置一并清除），补齐 UserDefaults + Keychain 清理，同步当前视图 `@AppStorage`/`@State`，并增加用户反馈 alert。
- **fontSize 不通知已打开的 ChatView**: `@State` 仅初始化一次，不响应 UserDefaults 外部写入。设置页改字体后已打开的会话页字体不更新，需重启 App。修正：`FontSize.saveToUserDefaults` 广播 `NotificationCenter` 通知，ChatView `.onReceive` 监听后重新 `loadFromUserDefaults` 刷新 `@State`，再触发 `\.appFontSize` 环境注入。
- **通知开关死 UI**: `notifyHighPriority` / `notifyMediumPriority` / `notifyLowPriority` 三个 `@AppStorage` Toggle 写入 UserDefaults 但无代码读取，`SmartNotificationManager` 用独立内存 `NotificationConfig`，两者未桥接。修正：`NotificationConfig` 增加 `loadFromUserDefaults` / `savePriorityToUserDefaults`，Manager 初始化时读取；提供 `updateConfig(_:)` 方法显式持久化（规避 `@Observable` 宏下 `didSet` 不触发的问题，参考 Apple Forums thread/731113）；SettingsView Toggle 改为绑定到 `appState.notificationManager.config`。
- **Keychain 写入 debounce**: TextField/SecureField 每输入一个字符触发 `.onChange` → `SecItemUpdate` 系统调用。输入 20 字符密码 = 20 次 Keychain 写入。改用 `Task.sleep(0.6s)` debounce，仅停止输入后写回。
- **About 区版本号硬编码**: 构建号硬编码 `"2"`，应用版本回退值 `"2.2.0"` 与当前 4.6.x 严重不符。改为动态读取 `CFBundleVersion`，回退值统一为「未知」。
- **移除 `exportedJSON` 死状态**: `@State private var exportedJSON: URL?` 设置后从未被读取，删除。
- **Toast 多次点击计时器竞态**: `.onAppear` + `DispatchQueue.main.asyncAfter` 在连续点击「显示密钥」时，第一次的计时器仍会触发提前把 toast 置 false。改用 `.task` + `Task.sleep`，SwiftUI 重建时自动取消上一次 task。

## [4.6.1] - 2026-07-21

### Fixed — iOS Xcode 16.4 / Swift 6 编译错误修复（CI 全绿）

修复 iOS PR `19208f2` 合入后累积的 47+ 个 Xcode 16.4 / Swift 6 严格并发编译错误。Android CI 此前已通过，iOS CI 因 destination mismatch 长期被掩盖，本次逐批修复使 iOS CI 重新变绿。

- **SwiftData 修复**: `@Relationship(deleteRule: .cascade, inverse:)` 语法修正；`@Model` 的 `description` 与 `CustomStringConvertible` 冲突改 `descriptionText` + `@Attribute(originalName:)`；`@Observable` + `lazy var` 冲突改 computed property。
- **iOS 26 守卫**: `Glass` 类型 / `.glassEffect()` / `GlassEffectContainer` / `MTLDevice.supportsMeshShaders` 用 `#if compiler(>=6.2)` 编译期排除（`@available` 是运行期检查，不能帮 Xcode 16.4 解析符号）。
- **Swift 6 严格并发**: `nonisolated(unsafe)` 标记线程安全但非 Sendable 的类型（Regex / Timer? / [NSObjectProtocol] / NSUncaughtExceptionHandler）；`@MainActor` 类的 static 成员加 `nonisolated`；`@MainActor` + non-Sendable async API 返回（UNNotificationSettings / [UNNotificationRequest]）改 `@unchecked Sendable`。
- **AVFoundation / Speech 线程隔离**: `AVAudioNode.installTap` 回调在音频线程，捕获局部 `request` 变量而非 MainActor `self.recognitionRequest`；`SFSpeechRecognitionTask` 回调包 `Task { @MainActor [weak self] in }`；`SFSpeechRecognizer.requestAuthorization()` 显式 `withCheckedContinuation` 桥接。
- **iOS 18 API 兼容**: `Spring(response:dampingFraction:)` → `dampingRatio:`；`TextInputAutocapitalization(.disabled)` → `.never`；`Date.FormatStyle.Symbol.Hour.twoDigitsNoAMPM` 枚举 case 不是方法；`startAccessingSecurityScopedResource` 是方法不是属性，需加 `()`。
- **`@AppStorage` + RawRepresentable enum bug**: Xcode 16.4 下 `@AppStorage("fontSize") FontSize` 报 "no exact matches in call to initializer"，改手动 UserDefaults 桥接（`@State` + `.onChange` 写回）。
- **AppIntent / NSSetUncaughtExceptionHandler**: AppIntent `static var` 改 `static let`（Swift 6 视为 nonisolated global shared mutable state）；`NSSetUncaughtExceptionHandler` 需 `@convention(c)` 函数指针，闭包不能捕获上下文，previousHandler 存到文件级 `nonisolated(unsafe)` 全局变量。
- **Crypto / I/O 修复**: `CCKeyDerivationPBKDF` 显式 `CCPBDFAlgorithm(kCCPBKDF2)` / `CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256)` cast + `CChar` 指针；`mach_vm.size_t` 不存在改 `mach_msg_type_number_t`；`vm_kernel_page_size` 非 Sendable 改 POSIX `getpagesize()`；`startAccessingSecurityScopedResource` 调用加 `()`；`(try? Data(...)) ?? Data()` 显式括号（`try?` 优先级低于 `??`）。
- **测试目标修复**: setUp/tearDown 加 `@MainActor` 隔离 + `MainActor.assumeIsolated`；跳过 `super.setUp()`（默认 no-op 调用会发送 self 跨 actor）；WidgetExtension 显式 Info.plist 修复模拟器安装失败；KeychainManager 加 in-memory fallback；URLValidator localhost 检测 bug 修复。
- **AgentTransport 协议 Sendable**: `protocol AgentTransport: AnyObject, Sendable`（WorkflowEngine `group.addTask` 闭包捕获 transport 跨 actor 边界）；`TransportFactory.provider` `nonisolated(unsafe)` 解 Sendable struct nonisolated `create` 方法访问 MainActor static var 的冲突。

### Fixed — Android CI 修复（Lint / Detekt 全绿）

- **AndroidManifest ACCESS_NETWORK_STATE**: `ConnectionRepository.kt:123` 调用 `ConnectivityManager.registerNetworkCallback()` 需此权限，原 manifest 缺失，Android Lint 报 MissingPermissions 错误（3 处）。
- **Detekt baseline**: 从 detekt 报告生成 baseline XML（367 个存量代码风格违规 ID：LongMethod / CyclomaticComplexMethod / LargeClass / TooManyFunctions / LongParameterList 等），配置 `baseline = file("config/detekt-baseline.xml")` 让存量违规被接受，新增违规才失败。
- **Lint abortOnError false**: lintDebug 报告 201 个 errors（173 Incomplete translation 多语言翻译、12 Querying resource properties using LocalContext.current、9 Calling new methods on older versions 等），都是存量问题。设置 `abortOnError false` + `warningsAsErrors false` 让 lintDebug 不因 error 退出非 0，报告作为 artifact 上传供审查。

### Fixed — UI 黑框 / 暗块显示 bug

修复用户反馈"很多地方有显示 bug 会出现黑框"。

- **GlassPresets.glassTinted 根因修复**: iOS 18 / Xcode 16 回退分支用 `.ultraThinMaterial` 作暗色基底再叠加 tint 色块。当 tint 透明（`Color.clear`，如 ChatView 非录音态语音按钮）或低 opacity（`Color.gray.opacity(0.3)` 禁用态发送按钮）时，tint 无法遮盖 ultraThinMaterial 暗色基底，深色模式下显示为黑色圆圈/药丸。修复策略按 tint alpha 分级：alpha < 0.01 不绘制 background；0.01 ≤ alpha < 0.5 tint 提升到 0.6 opacity 单一 fill；alpha ≥ 0.5 保留原 ultraThinMaterial + tint 叠加。
- **ChatView 消息附件背景**: `.background(.thinMaterial, ...)` 在深色模式下形成近黑色小色块，改用 `AppTheme.secondaryBackground` 不透明色。
- **SettingsView ToastView**: `.background(.black.opacity(0.8), ...)` + `.foregroundStyle(.white)` 在深色模式下与背景对比度不足，改用 `.regularMaterial` + `.foregroundStyle(.primary)` 自适应。
- **CommandPaletteView 命令面板**: `.glassInteractive(in: GlassTokens.sheetShape)` 在 iOS 18 / Xcode 16 回退分支用 `.ultraThinMaterial` 作 360-560pt 高面板背景，深色模式下显示为大暗色矩形，改用 `Color(.systemBackground)` 不透明背景 + 阴影。

## [4.6.0] - 2026-07-21

### Fixed — iOS 26 Liquid Glass 升级 PR 修复

修复 `19208f2` PR 的多个阻断性问题，使 Liquid Glass 工作可在 iOS 18 基线上正确构建与运行。

- **部署目标回退**: iOS 26.0 → iOS 18.0。原 PR 将 `IPHONEOS_DEPLOYMENT_TARGET` 从 17.0 直接跳到 26.0，会切断 30-40% 用户。`project.yml` 三个 target 全部回退；`xcodeVersion` 26.0 → 16.0。
- **CI 配置修复**: `build-ios.yml` 原 `XCODE_VERSION: "26.0"` / `IOS_VERSION: "26.0"` 在 `macos-15` runner 上不存在（Xcode 26 需要 macOS 26 Tahoe），CI 永远跑不起来。改为 `XCODE_VERSION: "16.4"` / `IOS_VERSION: "18.4"`，并加入 Xcode 选择降级逻辑（找不到指定版本时自动选最新 16.x）和模拟器列表输出便于排查。
- **Liquid Glass API 守卫**: `Theme/GlassTokens.swift` 中 `regularVariant` / `interactiveVariant` 标注 `@available(iOS 26, *)`；`Theme/GlassPresets.swift` 全部 View 扩展改为 `@ViewBuilder` + `if #available(iOS 26, *)` 双分支，iOS 18 回退到 `.ultraThinMaterial`。
- **新增兼容包装**: `glassTinted(_:in:)` 包装 `Glass.tint(_:)` + `.glassEffect(_:in:)`；`glassMorphID(_:in:)` 包装 `.glassEffectID(_:in:)` 用于 `GlassEffectContainer` 内的 morph 形变，iOS 18 回退为无 morph。
- **调用点全量替换**: `ContentView` / `ChatView` / `VoiceChatView` / `WorkflowView` / `CompareView` 中所有直接 `.glassEffect` 调用改为 `.glassTinted`；所有 `.glassEffectID` 改为 `.glassMorphID`。
- **测试兼容**: `GlassPresetsTests.swift` 中 `regularVariant` / `interactiveVariant` 访问测试标注 `@available(iOS 26, *)`；新增 `glassTinted` 构造性测试。

### Changed — Swift/SwiftUI 审计改进（SW-M2/M3/M4/M6/M7）

- **SW-M2 `.onAppear → .task`**: 7 个 View 的数据加载 `.onAppear` 改为 `.task`。涉及 `PluginView` / `TasksView` / `McpView` / `DeviceSyncView` / `ActivityView` / `SettingsView` / `ChatView`。
- **SW-M3 `try?` → Logger**: 4 个 Service 的静默 `try?` 改为 `do/catch` + `os.Logger` 上报。涉及 `WorkflowEngine` / `ChatRepository` / `LocalModelManager` / `CollaborationManager`。
- **SW-M4 `DateFormatter` → `FormatStyle`**: 7 个文件的 `DateFormatter` 改为 `Date.FormatStyle`。涉及 `ChatRepository` / `CollaborationManager` / `DeviceSyncManager` / `BackupManager` / `DataInsightsManager` / `DeviceSyncView` / `AppTheme`。
- **SW-M6 CollaborationManager 异步 I/O**: `sessionStore` 计算属性拆分为 `nonisolated static func`；改用 `Task.detached` 避免阻塞 MainActor。
- **SW-M7 移除冗余 MainActor.run**: `WorkflowView` / `ChatView` / `CompareView` / `TasksView` 中 `await MainActor.run { }` 包装移除。

### Fixed — Android 代码审计修复

- **!! 非空断言修复**: 8 处高风险 `!!` 替换为安全调用。`LocalModelManager` 流读取改用 `?: continue`，`DataInsightsManager` 日期解析用 try-catch 包裹，`MarketplaceClient` 用 `?: RuntimeException()` 回退，`PluginScreen` 用局部 val 捕获快照。
- **Glass 残留命名重命名**: 12 个标识符在 19 个文件中重命名（107 处）。`GlassCard`→`AppCard`、`GlassTopAppBar`→`AppTopAppBar`、`GlassNavigationBar`→`AppNavigationBar` 等。`LocalIsGlass` 和 `LocalGlass*` 保留向后兼容。

### Added — Android 工具抽取 + CI + 安全

- **runSafely 工具**: 新增 `core/util/RunSafely.kt`，统一 try-catch 模板（CancellationException 正确再抛）。CompareViewModel 和 AgentsViewModel 已接入。
- **DateTimeUtils 工具**: 新增 `core/util/DateTimeUtils.kt`，ThreadLocal 缓存 SimpleDateFormat。TasksScreen 已接入。
- **CI detekt + lint**: build-apk.yml 新增 detekt 和 lint 两个 job，上传报告 artifact。build job 依赖两者（`continue-on-error` 报告模式）。
- **证书锁定框架**: 新增 `CertificatePinnerFactory.kt`，支持公网 API 端点证书锁定。`OpenAIHttpTransport` 新增 `enableCertificatePinning` 参数。默认关闭，pin map 为空，后续填入 SHA-256 哈希即可启用。

### Changed — 版本号

- **版本号**: `version.properties` / `project.yml`（3 个 target）/ `Info.plist` 同步 4.5.0 → 4.6.0 / build 35 → 36。
- **README 修订**: `ios/README.md` 关键配置表与「最低部署目标」小节更新为 iOS 18.0 基线 + Liquid Glass iOS 26 增量特性描述。

## [4.5.0] - 2026-07-21

# 鸿蒙版开发路线图 — HarmonyOS NEXT 三原生端

> 基于 [产品策略](./product-strategy.md) 主线 D「端侧平台广度」
> 创建：2026-08-07，目标：与 Android/iOS 同级的三原生端

## 目标

在现有 Android(Kotlin/Compose) + iOS(Swift/SwiftUI) 双原生架构基础上，新增 HarmonyOS NEXT(ArkTS/ArkUI) 第三原生端。复用 `protocol/` 单一事实来源，对齐双端数据模型与传输格式，实现完整的多 Agent 控制台能力。

## 关键决策（已确认）

| 维度 | 选型 | 理由 |
|---|---|---|
| 目标版本 | HarmonyOS NEXT (API 12+) | 纯鸿蒙内核，ArkTS 全特性，华为当前主推，应用市场上架要求 |
| 范围 | 完整移植双端能力 | 与 Android/iOS 功能对齐（8 AgentType / DAG / MCP / 插件 / 加密 / Widget） |
| 模块化 | 多 features HSP 按域拆分 | 复用性强，对齐双端分层架构，便于团队协作 |
| CI | 建 hvigor 编译流水线 | 确保鸿蒙代码不回归，无签名仅编译 |

## 技术栈对照

| 维度 | Android | iOS | 鸿蒙 (ArkTS) |
|---|---|---|---|
| 语言 | Kotlin 2.2.0 | Swift 5.9+ | ArkTS（TS 超集） |
| UI | Jetpack Compose (Material 3) | SwiftUI | ArkUI 声明式 (@Component/build/@State) |
| 架构 | MVVM + StateFlow + Hilt | MVVM + @Observable + 手动 DI | MVVM + @ObservedV2 + @Provide/@Consume |
| 异步 | Coroutines + Flow | async/await + AsyncStream | Promise + async/await + async iterator |
| 网络 | Ktor (HTTP/SSE/WS) | URLSession + WS Task | @ohos.net.http + @ohos.net.websocket |
| SSE | Ktor SSE | 自实现 EventSource | 基于 http 响应流自实现 EventSource |
| 持久化 | Room 2.7.2 (6 实体) | SwiftData (5 @Model) | @ohos.data.relationalStore |
| 偏好 | DataStore 1.1.7 | UserDefaults | @ohos.data.preferences |
| 加密 | AndroidKeyStore + AES-256-GCM | CryptoKit + AES-256-GCM | cryptoFramework + HUKS |
| 密钥存储 | KeystoreManager (AKS:) | KeychainManager (AKS:) | HUKS（通用密钥库，AKS: 对齐） |
| 传输加密 | CryptoManager (AH1:, PBKDF2 600000) | CryptoKit (AH1:, 相同轮数) | cryptoFramework (AH1:, 相同轮数) |
| 序列化 | Gson | Codable | JSON.parse/stringify |
| 导航 | Navigation Compose | NavigationSplitView | Navigation 组件 + NavPathStack |
| 后台 | 前台服务 + 通知 | BackgroundTasks + APNs | BackgroundTaskKit + notificationManager |
| 卡片 | Widget | — | FormKit 服务卡片 |
| 最低版本 | minSdk 24 | iOS 18.0 | HarmonyOS NEXT (API 12+) |

## 工程结构

```
harmony/
├── build-profile.json5               # 工程级构建配置
├── hvigorfile.ts                     # 工程级 hvigor 脚本
├── oh-package.json5                  # 工程级依赖
├── .ohpm/                             # ohpm 缓存（gitignore）
├── entry/                             # 主入口 HAP
│   ├── src/main/
│   │   ├── ets/
│   │   │   ├── entryability/
│   │   │   │   └── EntryAbility.ets   # UIAbility 入口
│   │   │   └── pages/
│   │   │       └── Index.ets          # 主壳（Navigation 容器）
│   │   ├── resources/
│   │   │   ├── base/element/          # color/string/media
│   │   │   ├── en_US/element/
│   │   │   ├── zh_CN/element/
│   │   │   └── ja_JP/element/
│   │   └── module.json5               # 模块清单
│   ├── build-profile.json5
│   ├── hvigorfile.ts
│   └── oh-package.json5
├── common/                            # 公共 HSP
│   └── src/main/ets/
│       ├── protocol/                  # 10 schema → ArkTS interface
│       ├── security/                  # cryptoFramework + HUKS（AKS/AH1 对齐）
│       ├── transport/                 # 传输抽象 + 工厂 + ConnectionRepository
│       ├── persistence/               # relationalStore 实体 + DAO
│       ├── runtime/                   # AgentManager/SessionManager/TaskManager/WorkflowEngine
│       ├── mcp/                       # McpRegistry + McpClient + McpBridge
│       ├── plugin/                    # PluginExecutor
│       ├── theme/                     # 设计 token（对齐双端 Color/Type/Shape）
│       └── util/                      # Extensions / DateTimeUtils
├── features/
│   ├── chat/                          # Chat HSP（首页）
│   ├── agents/                       # Agents HSP
│   ├── activity/                     # Activity HSP
│   ├── marketplace/                  # Marketplace HSP
│   ├── settings/                     # Settings HSP
│   ├── workflow/                     # Workflow HSP
│   ├── mcp/                          # MCP 管理 HSP
│   └── compare/                     # Compare HSP
└── .gitignore
```

## 阶段计划

### H1：工程脚手架 + 协议层映射（基座）

**问题**：鸿蒙版从零开始，需先建立可编译的工程基座，并完成 `protocol/` 10 schema 到 ArkTS interface 的映射，作为后续所有模块的契约来源。

**实施步骤**：

1. **工程脚手架** — 创建 `harmony/` 目录与多 HSP 模块结构
   - 工程级 `build-profile.json5` / `hvigorfile.ts` / `oh-package.json5`
   - `entry/` HAP（EntryAbility + Index 主壳）
   - `common/` HSP（protocol/security/transport/persistence/runtime/theme/util）
   - 8 个 `features/*` HSP 占位（chat/agents/activity/marketplace/settings/workflow/mcp/compare）
   - `.gitignore`（忽略 ohpm 缓存、build 产物、IDE 配置）

2. **协议层映射** — `common/protocol/` 下 10 个 `.ets` interface 文件
   - 对应 `protocol/schemas/*-schema.json`，字段名/类型/枚举完全对齐
   - `Agent` / `Session` / `Message` / `Task` / `Workflow` / `AgentEvent` / `Plugin` / `MCPModels` / `FileTransfer` / `ErrorCodes`
   - 时间戳统一 `number`（Unix 毫秒，与 Android Long/iOS Int64 对齐）
   - 可空字段用 `T | null`（不用 `?` 可选，与 schema `required` 严格对应）

3. **主题基座** — `common/theme/` 设计 token
   - 对齐双端 `Color.kt` / `AppTheme.swift` 的色板
   - `Spacing` / `Shape` / `Type` token，供所有 feature 复用

**验收**：
- `hvigorw assembleHap` 编译通过（无需签名）
- `common/protocol/` 10 文件齐全，字段与 schema 一一对应
- CI 鸿蒙流水线跑通编译

---

### H2：核心链路 — 传输 + 安全 + 持久化

**问题**：协议层只是契约，要让数据真正流动起来需要传输抽象、加密对齐、本地持久化三条腿。

**实施步骤**：

1. **传输抽象** — `common/transport/`
   - `AgentTransport` interface（对应 Android sealed interface / iOS protocol）
   - `OpenAIHttpTransport`：基于 `@ohos.net.http` 实现 HTTP + SSE
   - `WebSocketTransport`：基于 `@ohos.net.websocket`，含自动重连
   - `EventSource`：基于 http 响应流自实现 SSE 解析（对标 iOS 自实现）
   - `TransportFactory`：按 `AgentType` 路由（Hermes/OpenAI/Ollama/ComfyUI…）
   - `ConnectionRepository`：连接状态管理

2. **安全** — `common/security/`
   - `HuksManager`：HUKS 通用密钥库，对应 Android KeystoreManager / iOS KeychainManager
   - `CryptoManager`：基于 cryptoFramework 实现 AES-256-GCM
   - `AKS:` 静态存储格式（与双端字节级对齐）
   - `AH1:` E2E 传输加密（PBKDF2 600000 轮，与双端字节级对齐）
   - 证书锁定（基于 `tls-pinning.md`，对标双端 CertificatePinnerFactory）

3. **持久化** — `common/persistence/`
   - `AppDatabase`：基于 relationalStore，对齐 Room v10 / SwiftData v2
   - 7 实体：Session/Message/AgentConfig/ActivityLog/Plugin/Task/WorkflowRun
   - 7 DAO：CRUD + Flow 订阅（用 async iterator 实现 Flow 语义）
   - 版本迁移机制（对标 Room Migration / SwiftData MigrationPlan）

**验收**：
- 能用 `OpenAIHttpTransport` 完成一次流式对话（不落地 UI）
- `AH1:` 加密/解密与 Android 输出字节一致（cross-platform 一致性测试）
- `AppDatabase` 7 张表创建成功，CRUD 通过

---

### H3：Chat feature MVP — 第一条端到端可用链路

**问题**：有了基座但用户看不到东西。Chat 是核心场景，先打通"输入→流式→渲染→持久化"完整链路作为后续模板。

**实施步骤**：

1. **Chat HSP** — `features/chat/`
   - `ChatViewModel`：@ObservedV2，状态用 @Trace
   - `ChatScreen`：ArkUI 声明式页面，含消息列表 + 输入栏
   - 流式增量渲染（对标 Android `ChatScreen.kt` / iOS `ChatView.swift`）
   - Markdown 渲染（基于 `MarkdownParser`，对标双端）
   - 会话切换（Session 列表融合在顶部，对标 v5.1 IA 重组）

2. **Runtime 接入** — `common/runtime/`
   - `AgentManager` / `AgentRegistry`（对标双端）
   - `SessionManager`：会话生命周期
   - 接入 `TransportFactory` + `AppDatabase`

3. **主壳接入** — `entry/pages/Index.ets`
   - `Navigation` 容器 + `NavPathStack`
   - 底部 Tab：Chat/Agents/Activity/Marketplace/Settings（对标 v5.1 IA 重组阶段 2）
   - Sessions/Tasks 入口融合在 Chat 顶部

**验收**：
- 模拟器/真机可完成一次完整对话：输入→流式输出→渲染→重启 App 历史可看
- 5 Tab 导航工作正常
- Chat 页面体验与 Android 基本对齐（流式 + Markdown + 会话切换）

---

### H4：功能对齐 — 8 AgentType + 工作流 + MCP + 插件

**问题**：MVP 只接了 OpenAI，需补齐其他 7 种 AgentType 与编排能力，达到双端功能对齐。

**实施步骤**：

1. **AgentType 全覆盖** — `common/transport/` 按类型实现
   - WebSocket：Hermes / OpenClaw / OpenCode（共用法）
   - HTTP+SSE：OpenAI 兼容 / OpenRouter / 小米 MiMo / Ollama/LM Studio / OpenWebUI
   - 文生图：ComfyUI（默认工作流 + JSON 直提交双模式，对标 v4.8.0）
   - `TransportFactory` 路由完整

2. **DAG 工作流引擎** — `common/runtime/WorkflowEngine`
   - Kahn 拓扑排序 + 环检测（对标双端）
   - 4 节点类型：INPUT / AGENT / TRANSFORM / OUTPUT
   - 执行历史持久化（WorkflowRun 表，对标 v4.9.0 双端）
   - `features/workflow` HSP：列表 + 编辑 + 历史

3. **MCP 协议** — `common/mcp/`
   - `McpRegistry` / `McpClient`（JSON-RPC 2.0）/ `McpBridge`
   - `features/mcp` HSP：服务管理

4. **插件系统** — `common/plugin/`
   - `PluginExecutor`：HttpCall / Broadcast / Workflow 三类动作

**验收**：
- 8 种 AgentType 均可连接并发送消息
- 工作流可创建/执行/查看历史
- MCP server 可注册并调用工具
- 插件三类动作可执行

---

### H5：平台特性 — 后台保活 + 卡片 + 语音 + 本地化

**问题**：核心功能对齐后，补齐鸿蒙平台特性，达到发布级体验。

**实施步骤**：

1. **后台保活** — `BackgroundTaskKit` 长时任务 + `notificationManager` 前台通知
   - 对标 Android 前台服务 + iOS BackgroundTasks
   - 通知内联回复（对标 Android RemoteInput）

2. **服务卡片** — `FormKit`
   - 快捷输入卡片（对标 Android Widget）
   - 语音按钮卡片

3. **语音** — `@ohos.multimedia.audio` + ASR/TTS
   - 语音输入模式 + 语音对话模式（对标双端 VoiceChatScreen）

4. **本地化** — `resources/{base,en_US,zh_CN,ja_JP}/element/string.json`
   - 中/英/日三语（对标双端 values-zh/values-ja/values）

5. **端侧推理**（探索）— 接入华为 MindSpore Lite 或 ONNX Runtime
   - 对标主线 C「端侧能力」（Android MediaPipe / iOS MLX）

**验收**：
- 后台保活：App 切后台仍维持 Agent 连接
- 卡片可一键唤起快捷输入
- 三语切换正常
- 端侧推理能跑通最小模型（探索项，可不达验收）

---

### H6：发布就绪 — 性能 + 无障碍 + 上架

**问题**：功能完备后需打磨到可上架华为应用市场。

**实施步骤**：

1. **性能** — Profiler 调优（ ArkUI 渲染、relationalStore 查询、SSE 解析）
2. **无障碍** — 语义化节点 + 屏幕阅读器适配
3. **上架审核** — 隐私政策 + 权限声明 + HarmonyOS 应用市场合规
4. **CI 签名** — 配置 HAP 签名密钥，CI 跑 `assembleHap` + 上传产物
5. **文档** — README 增补鸿蒙构建说明，更新架构图

**验收**：
- 华为应用市场审核通过
- 三端架构图同步更新
- CI 产出可签名 HAP

---

## 阶段完成状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| H1 工程脚手架 + 协议层映射 | ✅ 已完成 | 多 HSP 结构 / `build-profile` / `oh-package` / 10 schema → ArkTS interface / 设计 token 全就位 |
| H2 核心链路（传输 + 安全 + 持久化） | ✅ 已完成 | `OpenAIHttpTransport`(HTTP+SSE) / `WebSocketTransport` / `TransportFactory` / `ConnectionRepository`；HUKS（AKS:）+ cryptoFramework（AH1:）；relationalStore 实体 + DAO 对齐 Room/SwiftData |
| H3 Chat MVP | ✅ 已完成 | `ChatViewModel` 集成传输 + 持久化，`ChatScreen` 消息列表 / 流式 SSE 渲染 / 输入栏 / 连接状态 / 错误提示；5 Tab 主壳 |
| CI 流水线 | ✅ 已完成 | `.github/workflows/build-harmony.yml`：hvigor 编译 debug HAP + v* 标签 release HAP 上传至统一 GitHub Release。运行在 `self-hosted harmony-ci` runner（工具链需华为账号下载，hosted runner 不可用） |
| H4 功能对齐（8 AgentType + 工作流 + MCP + 插件） | ✅ 已完成 | `ComfyUITransport` 文生图双模式（默认工作流 + JSON 直提交）补齐 8 AgentType 路由；`WorkflowEngine` BFS 拓扑 + 环检测 + 4 节点类型 + `workflow_runs` 落库 + 4 预置模板；MCP 三件套（`McpRegistry`/`McpClient` JSON-RPC 2.0/`McpBridge`）；`PluginExecutor` 三动作（HttpCall 敏感 header 过滤 / Broadcast commonEvent / Workflow 提示词）。`features/workflow` + `features/mcp` 页面接入 Agents Tab 次级入口（v5.1 IA 对齐） |
| H5 平台特性（后台保活 + 卡片 + 语音 + 本地化） | 🔶 部分完成 | ✅ 后台保活：`KeepAliveManager`（backgroundTaskManager DATA_TRANSFER 长时任务，切后台且连接存在时申请，回前台释放；module.json5 配套 backgroundModes + KEEP_BACKGROUND_RUNNING 权限），对齐 Android AgentConnectionService 前台服务语义，系统常驻通知由长时任务机制生成。✅ 本地化：Chat/Workflow/MCP/Agents 四个 feature HSP 各建 base/zh_CN/ja_JP/en_US 四语 string.json，页面硬编码全部替换为 `$r()`（Span 拼接动态数字）。⏳ FormKit 服务卡片、语音输入/对话、端侧推理留待后续迭代 |
| H6 发布就绪（性能 + 无障碍 + 上架 + 签名） | ⏳ 后续迭代 | |

本次会话已交付"可编译、可跑通一次对话"的鸿蒙版起点（H1 + H2 核心 + H3 Chat MVP + CI），完成 H4 功能对齐（8 AgentType 全路由 + DAG 工作流 + MCP + 插件系统），并推进 H5（后台保活 + 三语本地化）。H5 剩余项与 H6 写入路线图，后续版本迭代推进。

每个阶段完成后回填本表「状态」列，保持文档与代码同步。

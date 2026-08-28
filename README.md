# Agent Control Center v5.3.0

**多端多 Agent 控制中心** —— Android（Kotlin + Jetpack Compose）、iOS（Swift + SwiftUI）、HarmonyOS（ArkTS + ArkUI）三移动原生端 + Windows/macOS/Linux 桌面端（Kotlin + Compose Multiplatform），共享永久统一协议层，连接并远程操控多种 AI Agent（Hermes / OpenCode / OpenAI 兼容 / ComfyUI / 本地模型）。

> 项目已从早期 PWA + Capacitor 架构完全重构为原生应用（v4.x 双端 → v5.x 三端 → v5.2 六端）。旧版说明见 [`docs/legacy-pwa.md`](docs/legacy-pwa.md)。

---

## 架构总览

```
┌───────────────────────────────────────────────────────────────────────────┐
│                              Protocol Layer                                │
│        11 JSON Schema + 5 Transport Protocol (六端共享)                     │
│  android/ ← ios/ ← harmony/ ← desktop/ ← protocol/ (单一事实来源)          │
├───────────────────┬──────────────────┬─────────────────┬─────────────────┤
│  Android (Kotlin) │   iOS (Swift)    │ HarmonyOS(ArkTS) │ Desktop (Kotlin)│
│  Jetpack Compose  │   SwiftUI        │  ArkUI           │  Compose MP      │
│  Hilt+Coroutines  │   @Observable    │  HSP 多模块      │  AppStore 状态流 │
│  Room + DataStore │   SwiftData      │  relationalStore │  JSON 文件存储   │
│  Ktor + OkHttp    │   URLSession     │  @ohos.net.http  │  Ktor + OkHttp   │
│  Android Keystore │   CryptoKit      │  HUKS+cryptoFW   │  javax.crypto    │
└───────────────────┴──────────────────┴─────────────────┴─────────────────┘
```

## 技术栈

| 层 | Android | iOS | HarmonyOS | Desktop (Win/Mac/Linux) |
|:---|:---|:---|:---|:---|
| 语言 | Kotlin 2.4.10 | Swift 6.0 | ArkTS | Kotlin 2.4.10 |
| UI | Jetpack Compose (Material 3) | SwiftUI | ArkUI (声明式) | Compose Multiplatform 1.12 (Material 3) |
| 架构 | MVVM + StateFlow | MVVM + @Observable | MVVM + @State 回调 | AppStore 单仓状态流 |
| 异步 | Coroutines + Flow | async/await + AsyncStream | Promise/async-await | Coroutines + Flow |
| 网络 | Ktor (HTTP/SSE/WS) | URLSession + WebSocketTask | @ohos.net.http/webSocket | Ktor + OkHttp 引擎（与 Android 同栈） |
| 持久化 | Room + DataStore | SwiftData + UserDefaults | relationalStore + preferences | JSON 文件存储（kotlinx-serialization） |
| 加密 | Android Keystore (AES-256-GCM) | CryptoKit (AES-256-GCM) | HUKS + cryptoFramework | javax.crypto (AES-256-GCM, AH1:) |
| DI | Hilt | 手动构造注入 | HSP 模块导入 | 构造注入 |
| 模块化 | 单 app 多包 | 单 target 分目录 | 多 HSP（common + 8 features） | 单模块分包 |
| 最低版本 | minSdk 24 | iOS 18.0 | HarmonyOS NEXT (API 12+) | JDK 17+ |

## 协议层（六端共享）

| 模块 | 文件 |
|:---|:---|
| Agent ID + 信息模型 | `protocol/schemas/agent-schema.json` |
| Session | `protocol/schemas/session-schema.json` |
| Message | `protocol/schemas/message-schema.json` |
| Task | `protocol/schemas/task-schema.json` |
| Workflow (DAG) | `protocol/schemas/workflow-schema.json` |
| Event | `protocol/schemas/event-schema.json` |
| Error Codes (37) | `protocol/schemas/error-codes.json` |
| Plugin | `protocol/schemas/plugin-schema.json` |
| MCP (JSON-RPC 2.0) | `protocol/schemas/mcp-schema.json` |
| File Transfer | `protocol/schemas/file-transfer-schema.json` |
| Collaboration | `protocol/schemas/collab-schema.json` |
| HTTP API | `protocol/transport/http-api.md` |
| SSE Protocol | `protocol/transport/sse-protocol.md` |
| WebSocket Protocol | `protocol/transport/websocket-protocol.md` |
| Auth (AKS:/AH1:) | `protocol/transport/auth.md` |
| TLS Pinning | `protocol/transport/tls-pinning.md` |

---

## 连接方式（多协议）

传输层统一抽象，按 `AgentType` 路由（8 种）：

| AgentType | 传输实现 | 协议 |
|:---|:---|:---|
| Hermes / OpenClaw | WebSocket | `ws://host/ws`（鉴权帧 + 自动重连） |
| OpenAI / OpenRouter / Xiaomi MiMo / OpenWebUI | HTTP + SSE | `POST /v1/chat/completions`（流式） |
| OpenCode | WebSocket | 同 Hermes |
| LocalModel (Ollama / LM Studio) | HTTP + SSE | 本地端点暴露 OpenAI 格式 |
| ComfyUI | HTTP | 文生图双模式（默认工作流 + JSON 直提交） |

> 加密：`AKS:` 前缀用于静态存储（Keychain/Keystore/HUKS），`AH1:` 前缀用于 E2E 传输加密（PBKDF2 600000 轮）。三端格式完全一致。

---

## 项目结构

```
agent-control-center/
├── protocol/                    # 永久统一协议层（三端共享）
│   ├── schemas/                 # 11 JSON Schema 契约
│   ├── transport/               # 5 传输协议文档
│   └── README.md
├── android/                     # Android 原生 (Kotlin + Compose)
│   ├── app/src/main/java/com/agentcontrolcenter/app/
│   │   ├── AgentControlCenterApplication.kt
│   │   ├── AgentControlCenterWidget.kt
│   │   ├── AgentConnectionService.kt      # 前台服务保活
│   │   ├── MainActivity.kt
│   │   ├── agent/               # Agent 模型（AgentType/协议/能力）
│   │   ├── core/
│   │   │   ├── vendor/          # 厂商适配（金标联盟四厂商保活 + 公平内存）
│   │   │   ├── hardware/        # SoC 芯片检测与推理优化
│   │   │   ├── security/        # Keystore + Crypto + UrlValidator
│   │   │   ├── common/          # PerformanceMonitor 等
│   │   │   ├── database/        # Room（8 实体 / 6 DAO）
│   │   │   └── datastore/       # 设置存储
│   │   ├── data/                # Repository 层（chat/sync/marketplace/collab/insights）
│   │   ├── transport/           # Transport 抽象 + 工厂 + http/websocket/comfyui
│   │   ├── runtime/             # AgentManager + WorkflowEngine + SessionManager
│   │   ├── mcp/                 # MCP 协议实现
│   │   ├── plugin/              # 插件执行器
│   │   ├── feature/             # Compose Screens + ViewModels（15 模块）
│   │   └── ui/                  # Theme + Components + Adaptive
│   ├── app/src/test/            # 单元测试
│   └── build.gradle
├── ios/                         # iOS 原生 (Swift + SwiftUI)
│   ├── project.yml              # XcodeGen 配置
│   ├── AgentControlCenter/
│   │   ├── Models/              # 匹配 11 JSON Schema
│   │   ├── Security/            # KeychainManager + CryptoManager
│   │   ├── Transport/           # HTTP/SSE + WebSocket
│   │   ├── Runtime/             # AgentManager + WorkflowEngine
│   │   ├── MCP/                 # McpRegistry + McpClient + McpBridge
│   │   ├── Plugin/              # PluginExecutor
│   │   ├── Persistence/         # SwiftData 实体
│   │   ├── Features/            # SwiftUI Views
│   │   └── Theme/               # AppTheme
│   └── README.md
├── harmony/                     # HarmonyOS NEXT 原生 (ArkTS + ArkUI)
│   ├── common/                  # common HSP（协议/传输/安全/持久化/运行时/MCP/插件/卡片数据桥）
│   ├── features/                # 8 feature HSP（chat/agents/activity/marketplace/settings/workflow/mcp/compare）
│   ├── entry/                   # entry HAP（主壳 + FormKit 服务卡片）
│   └── oh-package.json5
├── desktop/                     # 桌面三端 Windows/macOS/Linux (Kotlin + Compose Multiplatform)
│   └── src/main/kotlin/com/agentcontrolcenter/desktop/
│       ├── agent/model/         # 协议层（AgentConfig/AgentType/AgentProtocol/Agent）
│       ├── core/security/       # UrlValidator(SSRF 防护) + CryptoManager(E2E AH1:)
│       ├── data/model/          # Message/Session
│       ├── data/persistence/    # JsonStore（JSON 文件存储）
│       ├── transport/           # WebSocketTransport + OpenAIHttpTransport + TransportFactory
│       ├── app/                 # AppStore 状态编排 + Strings(en/zh)
│       └── ui/                  # AppRoot + ChatPane/AgentsPane/SettingsPane + Theme
├── docs/                        # 项目文档
│   ├── architecture.md          # 架构详解
│   ├── vendor-adaptation.md     # 厂商适配（四厂商保活 + 公平内存机制）
│   ├── harmony-roadmap.md       # 鸿蒙端路线图
│   └── ...
├── .github/workflows/           # CI/CD（Android/iOS/HarmonyOS 三流水线）
├── CHANGELOG.md
├── CONTRIBUTING.md
└── SECURITY.md
```

---

## 功能

- 💬 **多会话聊天**：流式增量渲染、消息回复、搜索、滑动切换
- 🔌 **多协议连接**：8 种 AgentType（WebSocket / HTTP+SSE / 文生图），向导式接入
- 🖥️ **本地模型**：自动发现 Ollama / LM Studio / llama.cpp 端点
- 🔧 **MCP 协议**：JSON-RPC 2.0，工具注册与调用
- ⚙️ **工作流引擎**：DAG 拓扑排序，多 Agent 编排（翻译链/代码审查/研究助手）
- 🧩 **插件系统**：HttpCall / Broadcast / Workflow 三类动作
- 🔔 **前台保活**：前台服务 + 通知内联回复（Android）/ 长时任务（HarmonyOS）
- 📱 **桌面卡片**：Android Widget 快捷输入 / HarmonyOS FormKit 服务卡片（2x2/2x4）
- 🎙️ **语音**：语音输入与语音对话模式（Android/iOS）
- 🎨 **主题**：浅色 / 深色 / Liquid Glass 三套
- 📤 **系统分享**：接收外部分享文本一键发问
- 🔐 **E2E 加密**：三端 `AH1:` 格式，PBKDF2 600000 轮
- 🏭 **厂商适配**：金标联盟四厂商（小米/华为荣耀/OPPO/vivo）后台保活引导 + 公平运行内存机制
- 🌐 **本地化**：Android 四语（en/zh/ja/ko）/ HarmonyOS 四语（base/zh_CN/en_US/ja_JP）/ 桌面双语（en/zh）
- 🖥️ **桌面三端**：Windows / macOS / Linux 一套 Compose Multiplatform 代码（系统托盘、close-to-tray、流式聊天、E2E 加密与移动端互操作）

### 厂商适配（Android）

面向国产 ROM 的系统特性适配（详见 [`docs/vendor-adaptation.md`](docs/vendor-adaptation.md)）：

- **四厂商保活引导**：小米 MIUI/HyperOS、华为/荣耀、OPPO ColorOS、vivo OriginOS 三重管控（自启动 / 省电策略 / 电池优化白名单）检测与设置跳转，设置 → 性能 → 厂商保活优化
- **公平运行内存机制**：金标联盟（vivo/小米/OPPO/荣耀）统一规范，响应 `itgsa.intent.action.TRIM/KILL` 广播并经 callback IBinder 回调（3s 超时）；标准 `onTrimMemory` 分级释放互补
- **查询投影瘦身**：消息列表 / Insights 统计 Room 投影排除 `attachmentData` 大字段，降低常驻内存，减少触达公平内存预警线
- **开机恢复**：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 拉起连接服务（仅已配置用户）

---

## 构建与运行

### Android

```bash
# 调试包
cd android && ./gradlew assembleDebug

# 发布包（需配置 agentcontrolcenter.keystore）
cd android && ./gradlew assembleRelease

# 运行测试
cd android && ./gradlew testDebugUnitTest
```

用 Android Studio 打开 `android/` 目录即可开发；最低要求 JDK 17 + Android SDK（compileSdk 37）。

### iOS

```bash
# 安装 XcodeGen
brew install xcodegen

# 生成 Xcode 工程
cd ios && xcodegen generate

# 打开 Xcode
open AgentControlCenter.xcodeproj
```

最低要求 macOS 14.0 + Xcode 16.0 + iOS 18.0 SDK。

### HarmonyOS

```bash
# 安装依赖（工程与全部模块）
cd harmony && ohpm install --all

# 调试 HAP
hvigorw assembleHap --mode project -p product=default -p buildMode=debug --no-daemon

# 发布 HAP（需在 build-profile.json5 配置签名）
hvigorw assembleHap --mode project -p product=default -p buildMode=release --no-daemon
```

用 DevEco Studio 打开 `harmony/` 目录即可开发；最低要求 HarmonyOS NEXT (API 12+) + Command Line Tools（含 hvigorw / ohpm / ohos-sdk）。

模块结构：`common` HSP（协议/传输/安全/持久化/运行时）+ 8 个 `features/*` HSP（chat/agents/activity/marketplace/settings/workflow/mcp/compare）+ `entry` HAP。

### Desktop（Windows / macOS / Linux）

```bash
# 开发运行（当前平台）
cd desktop && ./gradlew run

# 单元测试（23 个 JVM 测试）
cd desktop && ./gradlew test

# 当前平台安装包（Windows→msi / macOS→dmg / Linux→deb）
cd desktop && ./gradlew packageDistributionForCurrentOS
```

最低要求 JDK 17+（打包用 jpackage，Linux deb 需系统安装 `fakeroot`）。数据目录：`~/.agent-control-center/`。

详见 [`desktop/README.md`](desktop/README.md)。

---

## CI/CD

GitHub Actions：
- `.github/workflows/build-apk.yml` — Push to `main`：自动构建 Debug APK + 单元测试；Tag `v*`：构建 Release APK + 上传到 GitHub Releases
- `.github/workflows/build-ios.yml` — iOS 构建 + 单元测试；Tag `v*`：IPA 上传至同一 Release
- `.github/workflows/build-harmony.yml` — 鸿蒙 HAP 编译（需 `self-hosted harmony-ci` runner，工具链需华为开发者账号下载）；Tag `v*`：HAP 上传至同一 Release
- `.github/workflows/build-desktop.yml` — 桌面三端矩阵（windows/macos/ubuntu）测试 + 打包；Tag `v*`：msi/dmg/deb 上传至同一 Release

---

## 许可证

MIT © Hinana0325

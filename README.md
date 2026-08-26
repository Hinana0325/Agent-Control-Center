# Agent Control Center v4.8.0

**双原生多 Agent 移动端控制中心** —— Android（Kotlin + Jetpack Compose）与 iOS（Swift + SwiftUI）双原生实现，共享永久统一协议层，连接并远程操控多种 AI Agent（Hermes / OpenCode / OpenAI 兼容 / 本地模型）。

> 项目已从早期 PWA + Capacitor 架构完全重构为双原生应用。旧版说明见 [`docs/legacy-pwa.md`](docs/legacy-pwa.md)。

---

## 架构总览

```
┌─────────────────────────────────────────────────┐
│                  Protocol Layer                  │
│  10 JSON Schema + 4 Transport Protocol (共享)    │
│  android/ ←  ios/  ←  protocol/ (单一事实来源)   │
├────────────────────┬────────────────────────────┤
│   Android (Kotlin)  │      iOS (Swift)           │
│   Jetpack Compose   │      SwiftUI               │
│   Hilt + Coroutines │      @Observable + async   │
│   Room + DataStore  │      SwiftData + Keychain  │
│   Ktor + OkHttp     │      URLSession + WS Task  │
│   Android Keystore  │      CryptoKit             │
└────────────────────┴────────────────────────────┘
```

## 技术栈

| 层 | Android | iOS |
|:---|:---|:---|
| 语言 | Kotlin 2.2.0 | Swift 5.9+ |
| UI | Jetpack Compose (Material 3) | SwiftUI |
| 架构 | MVVM + StateFlow | MVVM + @Observable |
| 异步 | Coroutines + Flow | async/await + AsyncStream |
| 网络 | Ktor (HTTP/SSE/WS) | URLSession + WebSocketTask |
| 持久化 | Room + DataStore | SwiftData + UserDefaults |
| 加密 | Android Keystore (AES-256-GCM) | CryptoKit (AES-256-GCM) |
| DI | Hilt | 手动构造注入 |
| 最低版本 | minSdk 24 | iOS 18.0 |

## 协议层（双端共享）

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
| HTTP API | `protocol/transport/http-api.md` |
| SSE Protocol | `protocol/transport/sse-protocol.md` |
| WebSocket Protocol | `protocol/transport/websocket-protocol.md` |
| Auth (AKS:/AH1:) | `protocol/transport/auth.md` |

---

## 连接方式（多协议）

传输层统一抽象，按 `AgentType` 路由：

| AgentType | 传输实现 | 协议 |
|:---|:---|:---|
| Hermes / OpenClaw | WebSocket | `ws://host/ws`（鉴权帧 + 自动重连） |
| OpenAI / OpenRouter / Xiaomi MiMo | HTTP + SSE | `POST /v1/chat/completions`（流式） |
| OpenCode | WebSocket | 同 Hermes |
| LocalModel (Ollama / LM Studio) | HTTP + SSE | 本地端点暴露 OpenAI 格式 |

> 加密：`AKS:` 前缀用于静态存储（Keychain/Keystore），`AH1:` 前缀用于 E2E 传输加密（PBKDF2 600000 轮）。双端格式完全一致。

---

## 项目结构

```
agent-control-center/
├── protocol/                    # 永久统一协议层（双端共享）
│   ├── schemas/                 # 10 JSON Schema 契约
│   ├── transport/               # 4 传输协议文档
│   └── README.md
├── android/                     # Android 原生 (Kotlin + Compose)
│   ├── app/src/main/java/com/agentcontrolcenter/app/
│   │   ├── AgentControlCenterApplication.kt
│   │   ├── AgentControlCenterWidget.kt
│   │   ├── AgentConnectionService.kt
│   │   ├── MainActivity.kt
│   │   ├── data/                # Repository + Room + DataStore
│   │   ├── provider/            # Transport 抽象 + 工厂
│   │   ├── runtime/             # AgentManager + WorkflowEngine
│   │   ├── mcp/                 # MCP 协议实现
│   │   ├── plugin/              # 插件执行器
│   │   ├── feature/             # Compose Screens + ViewModels
│   │   └── ui/                  # Theme + Components + Adaptive
│   ├── app/src/test/            # 14 测试文件 / 75+ 用例
│   └── build.gradle
├── ios/                         # iOS 原生 (Swift + SwiftUI)
│   ├── project.yml              # XcodeGen 配置
│   ├── AgentControlCenter/
│   │   ├── Models/              # 8 文件匹配 10 JSON Schema
│   │   ├── Security/            # KeychainManager + CryptoManager
│   │   ├── Transport/           # HTTP/SSE + WebSocket
│   │   ├── Runtime/             # AgentManager + WorkflowEngine
│   │   ├── MCP/                 # McpRegistry + McpClient + McpBridge
│   │   ├── Plugin/              # PluginExecutor
│   │   ├── Persistence/         # SwiftData 5 实体
│   │   ├── Features/            # 6 SwiftUI Views
│   │   └── Theme/               # AppTheme
│   └── README.md
├── .github/workflows/           # CI/CD
├── CHANGELOG.md
├── CONTRIBUTING.md
└── SECURITY.md
```

---

## 功能

- 💬 **多会话聊天**：流式增量渲染、消息回复、搜索、滑动切换
- 🔌 **多协议连接**：WebSocket / HTTP+SSE，向导式接入，配置持久化
- 🖥️ **本地模型**：自动发现 Ollama / LM Studio / llama.cpp 端点
- 🔧 **MCP 协议**：JSON-RPC 2.0，工具注册与调用
- ⚙️ **工作流引擎**：DAG 拓扑排序，多 Agent 编排（翻译链/代码审查/研究助手）
- 🧩 **插件系统**：HttpCall / Broadcast / Workflow 三类动作
- 🔔 **前台保活**：前台服务 + 通知内联回复（Android）
- 📱 **桌面 Widget**：快捷输入弹窗 + 语音按钮（Android）
- 🎙️ **语音**：语音输入与语音对话模式
- 🎨 **主题**：浅色 / 深色 / Liquid Glass 三套
- 📤 **系统分享**：接收外部分享文本一键发问
- 🔐 **E2E 加密**：双端 `AH1:` 格式，PBKDF2 600000 轮

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

用 Android Studio 打开 `android/` 目录即可开发；最低要求 JDK 17 + Android SDK。

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

---

## CI/CD

GitHub Actions：
- `.github/workflows/build-apk.yml` — Push to `main`：自动构建 Debug APK + 单元测试；Tag `v*`：构建 Release APK + 上传到 GitHub Releases
- `.github/workflows/build-ios.yml` — iOS 构建 + 单元测试；Tag `v*`：IPA 上传至同一 Release
- `.github/workflows/build-harmony.yml` — 鸿蒙 HAP 编译（需 `self-hosted harmony-ci` runner，工具链需华为开发者账号下载）；Tag `v*`：HAP 上传至同一 Release

---

## 许可证

MIT © Hinana0325

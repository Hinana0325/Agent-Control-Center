# Agent Control Center — Desktop（Windows / macOS / Linux）

桌面端：**Kotlin + Compose Multiplatform Desktop**，一套代码打包三平台。与 Android 端同语言同栈（Kotlin 2.4.10 + Ktor 3 + Gson wire 协议），协议层逐字段对齐 `protocol/schemas/`。

## 功能

- **多会话聊天**：流式增量渲染（SSE delta 追加 / WebSocket 整帧）、会话侧栏、自动滚动
- **Agent 管理**：8 种 AgentType 配置（名称/类型/URL/ApiKey/模型/系统提示词/温度/MaxTokens）、一键连接、连接状态栏（延迟/服务器）
- **传输层**：`WebSocketTransport`（鉴权帧 + 指数退避重连 + 30s 心跳）+ `OpenAIHttpTransport`（SSE 流式 + 非 SSE 回退 + 滑动窗口历史 + 5xx 重试）；覆盖 Hermes/OpenCode/OpenClaw/OpenAI 兼容/Ollama/LM Studio/MiMo/OpenWebUI
- **E2E 加密**：`AH1:` 格式（AES-256-GCM + PBKDF2 600000 轮），与 Android/iOS 端互操作；设置页热更新口令
- **SSRF 防护**：`UrlValidator`（云 metadata/保留地址段/危险 scheme 拦截，本地端点放行）
- **系统托盘**：close-to-tray、Show/Quit 菜单
- **持久化**：`~/.agent-control-center/` JSON 文件存储（agents/sessions/messages/settings），临时文件 + 原子重命名写入
- **双语**：英语 / 简体中文

## 构建与运行

```bash
# 开发运行（当前平台）
./gradlew run

# 单元测试（23 个 JVM 测试：协议契约/安全/持久化）
./gradlew test

# 安装包：Windows→msi / macOS→dmg / Linux→deb
./gradlew packageDistributionForCurrentOS
```

要求：JDK 17+（CI 用 temurin 17）。Linux 打 deb 包需系统 `fakeroot`（GitHub Actions ubuntu runner 自带）。

## 架构

```
com.agentcontrolcenter.desktop/
├── agent/model/          # 协议层：AgentConfig/AgentType/AgentProtocol/Agent/ConnectionState
├── core/error/           # AppErrorCode（37 错误码，与三端对齐）
├── core/security/        # UrlValidator（SSRF 防护）+ CryptoManager（E2E AH1:）
├── data/model/           # Message/Session
├── data/persistence/     # JsonStore（JSON 文件存储 + AppSettings）
├── transport/
│   ├── protocol/         # AgentTransport 契约 + AgentEvent
│   ├── websocket/        # WebSocketTransport
│   ├── http/             # OpenAIHttpTransport
│   └── TransportFactory  # 按 AgentType 路由
├── app/                  # AppStore（状态编排）+ Strings（en/zh）
├── ui/                   # AppRoot + ChatPane/AgentsPane/SettingsPane + Theme
└── Main.kt               # 入口（Window + Tray）
```

## 设计取舍

| 决策 | 说明 |
|:---|:---|
| 与 Android 同栈 | Kotlin + Ktor(OkHttp) + Gson，transport 逻辑逐行移植（重连/心跳/历史窗口/错误码全保留），后续协议演进可低成本双向同步 |
| JSON 文件存储 | v1 无 Insights/多表 join 需求，kotlinx-serialization 默认值语义安全（Gson 反射绕过 Kotlin 非空校验）；SQLDelight 迁移列入路线图 |
| ComfyUI 优雅降级 | 桌面 v1 未移植图像生成工作流（连接时报错提示），路线图项 |
| jvmTarget 17 | jpackage 产物兼容性（构建机可为 JDK 17+） |

## 路线图

- [ ] ComfyUI 文生图传输（ComfyApiClient + 工作流提交 + 图片渲染）
- [ ] Markdown 渲染（移动端 ChatMarkdown 对齐）
- [ ] 开机自启动（Windows 注册表 / macOS LoginItems / Linux .desktop）
- [ ] SQLDelight 迁移（与移动端 Room v11 表结构对齐）
- [ ] 桌面通知（连接断开/Agent 回复）
- [ ] ja/ko 语言补齐

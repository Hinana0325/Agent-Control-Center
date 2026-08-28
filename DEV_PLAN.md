# Agent Control Center 开发计划

> 仓库：`Hinana0325/Agent-Control-Center`
> 当前版本：**v5.2.0**（versionCode 44）
> 本文件记录 **v5.3.0 起**的 Sprint 规划。v2.2 → v5.2 的版本演进历史见 [`CHANGELOG.md`](CHANGELOG.md)。
> 策略依据：[`docs/product-strategy.md`](docs/product-strategy.md)（单一事实来源）
> 架构依据：[`docs/architecture.md`](docs/architecture.md)

---

## 当前状态（v5.2.0）

六端原生格局已在 v5.2.0 铺完：Android / iOS / HarmonyOS + Windows / macOS / Linux。

| 维度 | 数据 |
|:-----|:-----|
| 版本 | v5.2.0（versionCode 44，单一事实来源 `version.properties`） |
| 架构 | 六端原生，共享 `protocol/` 永久统一协议层 |
| 协议层 | 11 JSON Schema + 5 传输协议文档 |
| 代码规模 | Android 261 / Harmony 139 / iOS 114 / Desktop 35 文件 |
| 测试 | Android 20 文件 · iOS 13 文件 · Desktop 4 文件（40 用例）· **Harmony 0** |
| Instrumented / UI 测试 | **四端全 0** |
| 已知 P0 问题 | 16.2 鸿蒙接线未做；16.1 代码侧已完成、**真实 pin 待可信网络填入**；16.3、16.4、16.9 已完成 |

### 六端能力矩阵（审计口径，非 README 功能列表）

| 能力 | Android | iOS | Harmony | Desktop |
|------|:-------:|:---:|:-------:|:-------:|
| 8 AgentType 连接 | ✅ | ✅ | ✅ | ⚠️ ComfyUI 降级 |
| 流式聊天 | ✅ | ✅ | ✅ | ✅ |
| WorkflowEngine | ✅ | ✅ | ✅ | ❌ 未移植 |
| Workflow 运行历史 | ✅ | ✅ | ✅ | ❌ 未移植 |
| **Workflow 可视化编辑** | ❌ 只读 | ❌ 只读 | ❌ 只读 | ❌ 未移植 |
| MCP / 插件 | ✅ | ✅ | ✅ | ❌ |
| E2E 加密 `AH1:` | ✅ | ✅ | ✅ | ✅ |
| 静态存储加密 `AKS:` | ✅ | ✅ | ✅ | ✅（v5.3.0 起，原明文） |
| 证书锁定 | ⚠️ 接线完整·pin 为空 | ⚠️ 接线完整·pin 为空 | ❌ | ❌ |
| 证书锁定开关 | ❌ **未实现** | ❌ **未实现** | ❌ | ❌ |
| 单元测试 | 20 文件 | 13 文件 | **0** | 40 用例 |

> **「接线完整·pin 为空」的含义**：双端均已实现完整的 pinning 链路（Android
> `TransportFactory` 动态启用 + OkHttp `CertificatePinner`；iOS 三处 transport 注入
> `TLSPinningDelegate`），但 pin 为占位值，运行时降级为系统默认 CA 校验——
> **不会导致连接失败，但也不提供任何 pin 保护**。填入真实 pin 前，不应对外宣称
> 本项目具备证书锁定能力。详见 `protocol/transport/tls-pinning.md` §4。

---

## Sprint 16：v5.3.0 — 可信化

> **主题：停止扩端，偿还叙事债。**
> 不新增平台、不新增 AgentType。目标是让「已宣发」与「已坐实」重新对齐。
> 策略理由见 `docs/product-strategy.md` §〇。

| # | 优先级 | 任务 | 主要文件 | 验收标准 |
|:-:|:------:|:-----|:---------|:---------|
| 16.1 | P0 | 证书锁定填真实 pin（**代码侧已完成，pin 待填入**） | `android/.../transport/http/CertificatePinnerFactory.kt`<br>`ios/.../Transport/TLSPinningDelegate.swift`<br>`scripts/fetch-tls-pins.sh`（新增）<br>`scripts/check-tls-pins.sh`（新增） | ✅ 接线缺陷已修复 + 工具链已交付（详见下方 16.1）<br>⬜ **待维护者在可信网络执行**：`bash scripts/fetch-tls-pins.sh api.openai.com`，回填双端与 `tls-pinning.md` §4；移除 `TODO_GET_REAL_PIN`；`npm run check:pins:strict` 通过 |
| 16.10 | P1 | **新增证书锁定用户开关**（建议作为 16.1 填 pin 的前置） | Android Settings / iOS Settings 各一处 | 用户可临时关闭锁定（降级系统 CA）。缺失时 pin 出错 = 全量用户功能不可用且无自救手段，见 `tls-pinning.md` §7 |
| 16.2 | P0 | 鸿蒙 3 个 feature 接入主壳导航 | `harmony/entry/src/main/ets/pages/Index.ets` | `workflow` / `mcp` / `compare` 可从主壳到达；`oh-package.json5` 依赖与实际导入一致，无未使用依赖 |
| 16.3 | P0 | **文档体系对齐（已完成）** | `DEV_PLAN.md`<br>`docs/product-strategy.md`<br>`docs/architecture.md` | 三份文档锚定 v5.2.0 六端现实；技术栈版本与构建文件逐项核对一致 |
| 16.4 | P0 | 版本漂移防护补全（已完成） | `scripts/check-version-sync.sh`<br>`package.json`（根）<br>`desktop/build.gradle.kts` | 脚本覆盖 `desktop` + `package.json`；根 `package.json` 从漂移的 4.8.0 修正为 5.2.0；注入错误版本时脚本退出码 1 |
| 16.5 | P1 | 桌面端 Workflow 协议层 + 引擎移植 | `desktop/src/main/kotlin/.../runtime/workflow/WorkflowEngine.kt`<br>`.../data/model/Workflow.kt`<br>`.../data/persistence/JsonStore.kt` | 按 `protocol/schemas/workflow-schema.json` 逐字段移植；拓扑排序 + 环检测 + 8 种变换与 Android 行为一致；新增 JVM 单测 ≥ 10 例 |
| 16.6 | P1 | 鸿蒙端测试从 0 起步 | `harmony/common/src/test/`（新建） | ≥ 5 用例，优先 `WorkflowEngine.ets` 与 `OpenAIHttpTransport.ets`；CI 可跑 |
| 16.7 | P2 | 清理 iOS WorkflowEngine 陈旧注释 | `ios/.../Runtime/WorkflowEngine.swift:46,375` | 注释与实际行为一致（C9 修复后已通过 `DataController` 查真实配置），避免后续审计误判 |
| 16.8 | P2 | 新增协议一致性校验脚本 | `scripts/check-protocol-sync.sh`（新建） | 比对 `protocol/schemas/*.json` 与四端模型字段集合；漂移时 CI 失败 |
| 16.9 | P0 | **桌面端 API Key 静态加密（已完成）** | `desktop/.../core/security/CredentialVault.kt`（新增）<br>`desktop/.../app/AppStore.kt` | ✅ 落盘前加密 `apiKey`（`AKS:` 前缀，对齐 `SECURITY.md` §4.1）；读取时解密；启动一次性迁移历史明文；17 个新用例全绿（桌面端 23 → 40） |

### 已完成（16.1 代码侧、16.3、16.4、16.9）

- **16.1 证书锁定（部分完成——代码侧缺陷已修复，真实 pin 待填入）**：

  **修复的两个真实缺陷**（均为双端分歧，比单纯的「pin 为空」更危险）：

  - **iOS 主机名大小写导致 pinning 静默降级**：`TLSPinningDelegate` 用 `challenge.protectionSpace.host`（保留 URL 原始大小写）直接查 `PUBLIC_API_PINS`。用户把 serverUrl 配成 `https://Api.OpenAI.com/v1` 时查表失败 → 静默降级为系统默认校验；而 Android 侧 OkHttp `HttpUrl` 解析时会规范化小写，同一配置会正常锁定。**iOS 静默失去保护而连接一切正常**，用户与审计者都无从察觉。已改为查表前 `lowercased()`。
  - **Android `isPublicEndpoint` locale 陷阱**：改用 `lowercase(Locale.ROOT)`。默认 locale 下土耳其语区域会把 `I` 转成无点 `ı`（U+0131），与 iOS 的 locale-independent `lowercased()` 产生分歧。

  **交付的工具链**（让「填 pin」从手工操作变为可复现的一步命令）：

  - `scripts/fetch-tls-pins.sh`：一键获取 pin 并产出双端代码片段。**自带三道 MITM 拦截**（代理环境变量 / DNS 私有保留地址 / 证书链校验），任一触发即中止且零产出；`--self-test` 可离线验证 pin 算法与三级链拆分逻辑。
  - `scripts/check-tls-pins.sh`：CI 校验双端一致性（host 集合、pin 集合、key 小写、格式、至少 2 个互不相同）。**已用 10 个正负例验证**能正确拦截漂移；占位 pin 默认仅告警，`--strict` 用作发布门禁。
  - `npm run check` 组合版本 + pin 两项校验。

  **为什么 pin 本身没有填入**：沙箱内 `api.openai.com` 被解析到 `198.18.0.29`（RFC 2544 保留段，fake-ip 特征），直连握手 EOF、代理仅放行白名单、证书透明度日志不可达。此环境下拿到的证书可能来自 MITM 代理——**把代理证书的 pin 写进源码会导致全球用户连接被拒且只能发版修复**。填入一个自己无法验证的安全常量，比留空更糟。

- **16.3 文档体系对齐**：三份规划文档全部重写并锚定 v5.2.0。核对中修正了 `docs/architecture.md` 的多处技术栈错误（Kotlin 2.2.0→2.4.10、AGP 8.9→9、compileSdk 36→37、iOS 17.0→18.0、Swift 5.9→6.0），并补齐了完全缺失的 HarmonyOS 与 Desktop 两章。
- **16.4 版本漂移防护**：`scripts/check-version-sync.sh` 扩展覆盖 `desktop/build.gradle.kts` 与根 `package.json`；根 `package.json` 版本号从漂移的 `4.8.0` 修正为 `5.2.0` 并更新为六端描述。
- **16.9 桌面端 API Key 静态加密**：新增 `core/security/CredentialVault.kt`，实现 `AKS:` 格式（AES-256-GCM，随机 IV，与 Android/iOS 逐字节同构）。`AppStore` 在持久化边界加解密——**内存态保持明文**供传输层建连使用，与 Android「内存 domain model 明文 / 落库 entity 密文」同构。启动时一次性迁移历史明文。桌面端测试 23 → 40（新增 17 个）。

  主密钥为 `~/.agent-control-center/master.key`（256 位随机，POSIX 600）。**纯 JVM 下无跨平台硬件密钥库**（DPAPI/Keychain/libsecret 均需 JNI），故强度低于移动端 TEE/Keychain：可防「文件被拷走后离线破解」，防不住「同用户身份的恶意进程」。已在 `SECURITY.md` §4.4 记录完整威胁模型，不以「已加密」四字掩盖强度差异。

  设计取舍：密钥文件损坏时抛 `CredentialVaultException` 而**不静默重新生成密钥**——静默重生成会让既有凭据永久不可解密。宁可失败可见，不可静默丢数据（用例 `密钥文件损坏时抛异常且不静默重生成` 锁定此行为）。

### 审计中修正的误判（记录以防复发）

初版审计曾依据 `ios/.../WorkflowEngine.swift` 的文档注释，判定「iOS 工作流 AGENT 节点使用默认配置占位、不调用用户真实配置」。**该判断错误**：C9 修复后引擎通过注入的 `DataController.fetchAgentConfigs()` 查询真实配置，仅在空库时回落默认配置，行为与 Android 对齐。

教训：六端架构下，**注释不可作为审计依据，必须以代码为准**。已在 `docs/architecture.md` §9.1 记录该陷阱。

### 审计中新发现的问题

- **桌面端 API Key 明文落盘（升级为 P0，任务 16.9）**：逐端核对静态存储加密实现时发现。三端（Android / iOS / HarmonyOS）均实现了 `AKS:` 前缀加密，唯独桌面端 `AppStore.saveAgent()` 直接明文序列化 `apiKey`。桌面端是 v5.2.0 已发布产物（msi / dmg / deb 已上 GitHub Releases），且 `SECURITY.md` §4 明确要求 `AKS:` 格式——属于规范冲突而非单纯功能缺失。**已修复。**

- **证书锁定用户开关从未实现（新增任务 16.10，建议作为 16.1 填 pin 的前置）**：`protocol/transport/tls-pinning.md` §5.1 与 §7 均记载「Settings → 安全 → 证书锁定」开关，但全仓检索（`.kt` / `.swift` / `.xml` / `.ets`，2026-08）确认**双端均无相关 UI**，pinning 代码只存在于 transport 层。

  这改变了 pin 出错的后果量级：

  | 情形 | 有开关时 | 当前（无开关） |
  |------|---------|---------------|
  | pin 填错 | 用户关闭锁定即可恢复 | **全量用户功能不可用，只能等发版** |
  | 服务端轮换密钥 | 同上 | 同上 |

  建议在填入真实 pin 之前先补上该开关（任务 16.10），否则 pin 出错从「可降级的问题」变成「线上事故」。文档 §5.1 / §7 已就地更正。

- **文档 §5.1「默认开启」与实际不符**：实际启用与否由 `CertificatePinnerFactory.hasRealPins()` 动态决定（占位 pin 期间自动不锁定），并非「默认开启」。已更正。

---

## Sprint 17：v5.4.0 — 分水岭（Workflow 可视化拖拽编辑器）

> 桌面优先落地，再回迁 Android。详细设计另立设计文档（见 `GLASS_UPGRADE_PLAN.md` 的「冻结后新建独立设计文档」约定）。

| # | 优先级 | 任务 | 说明 |
|:-:|:------:|:-----|:-----|
| 17.1 | P0 | 桌面端 DAG 可视化编辑器 | Canvas + 节点拖拽 + 端口连线 + 属性面板 + 执行态实时高亮 + 运行历史 |
| 17.2 | P0 | 图变更安全性 | 环检测即时拦截、孤立节点提示、非法连线拒绝 |
| 17.3 | P0 | 回迁 Android | Compose 代码近乎 1:1；移动端改为触控友好的长按拖拽 |
| 17.4 | P1 | 工作流模板导入/导出 | 按 `workflow-schema.json` 序列化为可分享 JSON |
| 17.5 | P2 | iOS / 鸿蒙保持只读画布 | 移动端编辑体验受限，暂不投入 |

---

## Sprint 18+：v5.5.0 及以后

| # | 优先级 | 任务 |
|:-:|:------:|:-----|
| 18.1 | P1 | 端侧推理 MVP（Android MediaPipe / iOS MLX） |
| 18.2 | P1 | MCP 工具市场 |
| 18.3 | P1 | Workflow 并行分支 + 条件节点 |
| 18.4 | P2 | 桌面端路线图收尾（ComfyUI / Markdown 渲染 / 开机自启 / 通知 / ja-ko） |
| 18.5 | P2 | 轻量团队协作 |

---

## 依赖关系

```
v5.2.0（当前：六端铺完，深度不足）
  │
  ▼
Sprint 16 — v5.3.0 可信化
  ├──► 16.1 证书锁定        ┐
  ├──► 16.2 鸿蒙接线        │ 互不依赖，可并行
  ├──► 16.3 文档对齐   ✅   │
  └──► 16.4 版本防护   ✅   ┘
  │
  ├──► 16.5 桌面端 Workflow 引擎移植 ──┐
  │                                    │
  ▼                                    ▼
Sprint 17 — v5.4.0 分水岭（依赖 16.5 的引擎基座）
  │
  ▼
Sprint 18 — v5.5.0 端侧推理 + MCP 市场（依赖 17 的编排基座）
```

---

## 风险与缓解

| 风险 | 概率 | 缓解 |
|:-----|:----:|:-----|
| **六端协议漂移** | 高 | 以 `protocol/` 为单一事实来源；16.8 建 `check-protocol-sync.sh` 在 CI 卡死 |
| **桌面端凭据明文落盘** | 高（已发生） | 任务 16.9：补 `AKS:` 静态加密 + 迁移既有明文文件；卡片式安全审计须覆盖每一端而非参照既有端推断 |
| **继续扩端偏离主线** | 中 | 本 Sprint 明确「不新增平台、不新增 AgentType」；v5.4 前不启动新端 |
| **注释与实现背离误导决策** | 中 | 已在 `architecture.md` §9.1 记录；审计以代码为准（16.7 清理已知陈旧注释） |
| **鸿蒙 CI 形同虚设** | 高 | `build-harmony.yml` 依赖 self-hosted runner，实际未验证；16.6 补测试并评估 runner 可用性 |
| **可视化编辑器工期失控** | 中 | 桌面优先（唯一可本地完整编译 + 跑 JVM 测试的一端），先出引擎再出 UI |
| **本地推理包体积** | 中 | 模型动态下载，包内仅含推理引擎 |
| **测试覆盖回归** | 低 | CI 每次提交自动验证；桌面端 23 用例为基线，只增不减 |

---

## 环境备注（沙箱内可验证性）

本仓库六端中，**只有桌面端可在通用 Linux 沙箱内完整验证**：

| 端 | 可构建 | 阻塞原因 |
|:---|:------:|:---------|
| Desktop | ✅ | JDK 17+ 即可；`gradle compileKotlin` / `gradle test` 可跑 |
| Android | ❌ | 需 Android SDK（沙箱内 `dl.google.com` 不可达） |
| iOS | ❌ | 需 macOS + Xcode |
| HarmonyOS | ❌ | 需 DevEco + 华为工具链 |

桌面端构建注意：`org.jetbrains.compose.hot-reload` 为 compose-gradle-plugin 的 **optional** 依赖，部分 Maven 镜像未同步。本地构建可加 `-Porg.jetbrains.compose.hot.reload.disable=true` 跳过（纯开发期热重载功能，不影响产物）。

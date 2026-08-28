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
| 测试 | Android 20 文件 · iOS 13 文件 · Desktop 3 文件（23 用例）· **Harmony 0** |
| Instrumented / UI 测试 | **四端全 0** |
| 已知 P0 问题 | 2 项未修复（16.1 空 pin / 16.2 鸿蒙接线）；16.3、16.4、16.9 已修复 |

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
| 静态存储加密 `AKS:` | ✅ | ✅ | ✅ | ❌ **明文** |
| 证书锁定 | ⚠️ 空 pin | ⚠️ 空 pin | ❌ | ❌ |
| 单元测试 | 20 文件 | 13 文件 | **0** | 23 用例 |

---

## Sprint 16：v5.3.0 — 可信化

> **主题：停止扩端，偿还叙事债。**
> 不新增平台、不新增 AgentType。目标是让「已宣发」与「已坐实」重新对齐。
> 策略理由见 `docs/product-strategy.md` §〇。

| # | 优先级 | 任务 | 主要文件 | 验收标准 |
|:-:|:------:|:-----|:---------|:---------|
| 16.1 | P0 | 证书锁定填真实 pin（Android + iOS） | `android/.../transport/http/CertificatePinnerFactory.kt`<br>`ios/.../Transport/TLSPinningDelegate.swift` | 按 `protocol/transport/tls-pinning.md` §3.3 获取真实 pin 填入；移除 `TODO_GET_REAL_PIN`；pin map 非空且 CI 校验非空 |
| 16.2 | P0 | 鸿蒙 3 个 feature 接入主壳导航 | `harmony/entry/src/main/ets/pages/Index.ets` | `workflow` / `mcp` / `compare` 可从主壳到达；`oh-package.json5` 依赖与实际导入一致，无未使用依赖 |
| 16.3 | P0 | **文档体系对齐（已完成）** | `DEV_PLAN.md`<br>`docs/product-strategy.md`<br>`docs/architecture.md` | 三份文档锚定 v5.2.0 六端现实；技术栈版本与构建文件逐项核对一致 |
| 16.4 | P0 | 版本漂移防护补全（已完成） | `scripts/check-version-sync.sh`<br>`package.json`（根）<br>`desktop/build.gradle.kts` | 脚本覆盖 `desktop` + `package.json`；根 `package.json` 从漂移的 4.8.0 修正为 5.2.0；注入错误版本时脚本退出码 1 |
| 16.5 | P1 | 桌面端 Workflow 协议层 + 引擎移植 | `desktop/src/main/kotlin/.../runtime/workflow/WorkflowEngine.kt`<br>`.../data/model/Workflow.kt`<br>`.../data/persistence/JsonStore.kt` | 按 `protocol/schemas/workflow-schema.json` 逐字段移植；拓扑排序 + 环检测 + 8 种变换与 Android 行为一致；新增 JVM 单测 ≥ 10 例 |
| 16.6 | P1 | 鸿蒙端测试从 0 起步 | `harmony/common/src/test/`（新建） | ≥ 5 用例，优先 `WorkflowEngine.ets` 与 `OpenAIHttpTransport.ets`；CI 可跑 |
| 16.7 | P2 | 清理 iOS WorkflowEngine 陈旧注释 | `ios/.../Runtime/WorkflowEngine.swift:46,375` | 注释与实际行为一致（C9 修复后已通过 `DataController` 查真实配置），避免后续审计误判 |
| 16.8 | P2 | 新增协议一致性校验脚本 | `scripts/check-protocol-sync.sh`（新建） | 比对 `protocol/schemas/*.json` 与四端模型字段集合；漂移时 CI 失败 |
| 16.9 | P0 | **桌面端 API Key 静态加密（已完成）** | `desktop/.../core/security/CredentialVault.kt`（新增）<br>`desktop/.../app/AppStore.kt` | ✅ 落盘前加密 `apiKey`（`AKS:` 前缀，对齐 `SECURITY.md` §4.1）；读取时解密；启动一次性迁移历史明文；17 个新用例全绿（桌面端 23 → 40） |

### 已完成（16.3、16.4）

- **16.3 文档体系对齐**：三份规划文档全部重写并锚定 v5.2.0。核对中修正了 `docs/architecture.md` 的多处技术栈错误（Kotlin 2.2.0→2.4.10、AGP 8.9→9、compileSdk 36→37、iOS 17.0→18.0、Swift 5.9→6.0），并补齐了完全缺失的 HarmonyOS 与 Desktop 两章。
- **16.4 版本漂移防护**：`scripts/check-version-sync.sh` 扩展覆盖 `desktop/build.gradle.kts` 与根 `package.json`；根 `package.json` 版本号从漂移的 `4.8.0` 修正为 `5.2.0` 并更新为六端描述。
- **16.9 桌面端 API Key 静态加密**：新增 `core/security/CredentialVault.kt`，实现 `AKS:` 格式（AES-256-GCM，随机 IV，与 Android/iOS 逐字节同构）。`AppStore` 在持久化边界加解密——**内存态保持明文**供传输层建连使用，与 Android「内存 domain model 明文 / 落库 entity 密文」同构。启动时一次性迁移历史明文。桌面端测试 23 → 40（新增 17 个）。

  主密钥为 `~/.agent-control-center/master.key`（256 位随机，POSIX 600）。**纯 JVM 下无跨平台硬件密钥库**（DPAPI/Keychain/libsecret 均需 JNI），故强度低于移动端 TEE/Keychain：可防「文件被拷走后离线破解」，防不住「同用户身份的恶意进程」。已在 `SECURITY.md` §4.4 记录完整威胁模型，不以「已加密」四字掩盖强度差异。

  设计取舍：密钥文件损坏时抛 `CredentialVaultException` 而**不静默重新生成密钥**——静默重生成会让既有凭据永久不可解密。宁可失败可见，不可静默丢数据（用例 `密钥文件损坏时抛异常且不静默重生成` 锁定此行为）。

### 审计中修正的误判（记录以防复发）

初版审计曾依据 `ios/.../WorkflowEngine.swift` 的文档注释，判定「iOS 工作流 AGENT 节点使用默认配置占位、不调用用户真实配置」。**该判断错误**：C9 修复后引擎通过注入的 `DataController.fetchAgentConfigs()` 查询真实配置，仅在空库时回落默认配置，行为与 Android 对齐。

教训：六端架构下，**注释不可作为审计依据，必须以代码为准**。已在 `docs/architecture.md` §9.1 记录该陷阱。

### 审计中新发现的问题

- **桌面端 API Key 明文落盘（升级为 P0，任务 16.9）**：逐端核对静态存储加密实现时发现。三端（Android / iOS / HarmonyOS）均实现了 `AKS:` 前缀加密，唯独桌面端 `AppStore.saveAgent()` 直接明文序列化 `apiKey`。桌面端是 v5.2.0 已发布产物（msi / dmg / deb 已上 GitHub Releases），且 `SECURITY.md` §4 明确要求 `AKS:` 格式——属于规范冲突而非单纯功能缺失。

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

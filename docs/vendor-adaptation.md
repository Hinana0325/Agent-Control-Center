# Android 厂商适配指南（金标联盟四厂商）

本文档记录 Android 端面向国产 ROM 的系统特性适配，覆盖两大体系：

1. **后台保活引导**（四厂商三重管控：自启动 + 省电策略 + 电池优化白名单）
2. **公平运行内存机制**（金标联盟统一内存管理规范：TRIM 预警 + KILL 查杀回调）
3. **内存占用优化**（消息/统计查询投影瘦身——公平内存机制的应用侧配合）

适配厂商：**小米（MIUI/HyperOS）、华为/荣耀（HarmonyOS）、OPPO（ColorOS）、vivo（OriginOS）**。

代码位置：`android/app/src/main/java/com/agentcontrolcenter/app/core/vendor/`

---

## 一、背景

### 1.1 为什么需要厂商适配

本项目核心场景是「前台服务维持 Agent WebSocket/SSE 长连接」。标准 AOSP 生态下，
前台服务（`dataSync` 类型）+ `START_STICKY` + 指数退避重连即可保证较高存活率；
但国产 ROM 在 AOSP 之上叠加了更激进的厂商侧管控：

| 管控层 | 四厂商默认行为 | 影响 |
|---|---|---|
| 自启动 | 默认关闭 | 重启后收不到 `BOOT_COMPLETED`，Agent 连接不恢复 |
| 省电策略 | 「智能限制后台运行」 | 限制后台 CPU 调度与网络访问 |
| 电池优化 | 未豁免（Doze 生效） | 深度睡眠时段连接中断 |
| 链式启动控制 | 限制 A 启动 B | 厂商推送通道不可用 |

同时，金标联盟（vivo/小米/OPPO/荣耀）于 2026-04 发布**公平运行内存机制**，
对齐 Android 17 内存管控方向，要求应用响应系统内存预警/查杀广播，未适配的
应用可能被直接查杀且无常规崩溃堆栈。

### 1.2 架构分层

```
core/vendor/
├── VendorRomAdapter.kt        # ROM 厂商检测（四厂商品牌关键词 + 小米系 ROM 属性兜底）
├── VendorKeepAliveHelper.kt   # 四厂商保活引导（按 ROM 分发自启动跳转 + 白名单申请）
├── BootCompletedReceiver.kt   # 开机/更新后恢复 Agent 连接
└── FairMemoryManager.kt       # 公平运行内存机制（TRIM/KILL 广播 + binder 回调）

core/database/dao/MessageDao.kt  # 消息/统计轻量投影（公平内存的应用侧配合）
```

> 与 `core/hardware/SoCHardwareDetector` 的区别：SoC 层识别**芯片厂商**
> （Snapdragon/Dimensity 等，用于推理优化）；`core/vendor` 识别**整机 ROM 厂商**
> （用于系统行为适配）。

---

## 二、后台保活引导（VendorKeepAliveHelper）

### 2.1 ROM 检测（VendorRomAdapter）

按品牌关键词分组匹配（含子品牌），小米系另有 ROM 信号兜底：

| VendorRom | 品牌关键词（MANUFACTURER/BRAND，小写 contains） | ROM 信号兜底 |
|---|---|---|
| `Xiaomi` | xiaomi / redmi / poco | `ro.miui.ui.version.name` / `ro.mi.os.version.name` |
| `Honor`（含华为） | huawei / honor / hihonor | — |
| `Oppo` | oppo / oneplus / realme | — |
| `Vivo` | vivo / iqoo | — |
| `Other` | （不匹配） | — |

- 荣耀 2020 年从华为独立后新品牌为 `hihonor`，但系统管控沿用华为
  systemmanager 体系，故与华为合并为 `Honor`，跳转候选同时覆盖
  `com.huawei.*` 与 `com.hihonor.*`
- 小米系 ROM 信号通过反射读取 `android.os.SystemProperties`（@hide API），
  覆盖非小米硬件刷 MIUI 的场景；读取失败按 null 处理
- `VendorRomInfo.isManagedVendor`：是否为已知需保活引导的厂商（UI 渲染条件）

### 2.2 三项保活设置引导

| 设置项 | 实现方式 | 可查询状态 |
|---|---|---|
| 电池优化白名单 | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 系统 Intent（全厂商通用） | ✅ `PowerManager.isIgnoringBatteryOptimizations` |
| 自启动权限 | 按 `VendorRom` 分发显式 Intent 跳转厂商自启动管理页（多候选 + 应用详情页兜底） | ❌ 无公开 API |
| 省电策略（无限制） | 跳转本应用系统详情页，用户手动改 | ❌ 无公开 API |

设置入口位于 **设置 → 性能 → 厂商保活优化** 区块，检测到四厂商设备时渲染；
电池白名单状态在从系统设置页返回（`ON_RESUME`）时自动刷新。

### 2.3 各厂商自启动跳转候选（AUTO_START_CANDIDATES）

版本碎片化适配——逐候选 `resolveActivity` 探测 + try-catch 兜底，全部失败降级
应用详情页：

| 厂商 | 候选 Activity（包名/类名） |
|---|---|
| Xiaomi | `com.miui.securitycenter/…permcenter.autostart.AutoStartManagementActivity`、`…permcenter.permissions.PermissionsMainActivity`、`…permcenter.MainAcitvity` |
| Honor | `com.huawei.systemmanager/…startupmgr.ui.StartupNormalAppListActivity`、`com.hihonor.systemmanager/…StartupNormalAppListActivity`、`com.huawei.systemmanager/…optimize.bootstart.BootStartActivity` |
| Oppo | `com.coloros.safecenter/…permission.startup.StartupAppListActivity`、`com.coloros.safecenter/…startupapp.StartupAppListActivity`、`com.oppo.safe/…permission.startup.StartupAppListActivity` |
| Vivo | `com.vivo.permissionmanager/…activity.BgStartUpManagerActivity`、`…activity.PurviewTabActivity` |

### 2.4 开机恢复（BootCompletedReceiver）

监听 `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`，在用户已授予自启动权限的
前提下开机/更新后拉起 `AgentConnectionService`。**克制性设计**：仅当本地
已保存可用 Agent 配置（`serverUrl` 非空且非 seed）时才拉起，未配置的新用户
不会被常驻通知打扰。

---

## 三、公平运行内存机制（FairMemoryManager）

### 3.1 机制概述

金标联盟（vivo/小米/OPPO/荣耀）2026-04 发布，对齐 Android 17 内存管控：

- **统一标准**：按设备总内存设定应用内存上限（PSS 口径）
- **智能通知**：触达预警线时系统发 TRIM 广播，应用应释放内存
- **查杀通知**：触达查杀线时系统发 KILL 广播，应用须**保存现场数据**并在
  **3 秒内**通过广播携带的 callback IBinder 回调结果；系统收到回调或超时后
  执行查杀

### 3.2 广播契约

```kotlin
// 动态注册（Application.onCreate），API 33+ 需 RECEIVER_EXPORTED
IntentFilter("itgsa.intent.action.TRIM").apply { addAction("itgsa.intent.action.KILL") }

// 广播数据布局（extras）
Bundle("common") {
    notifyType: Int    // 1000 = 物理内存(PSS)，2000 = Java 堆
    notifyId: Int
    reason: String?
    callback: IBinder // 系统回调通道
}
Bundle("extra") {
    // notifyType == 1000 时
    pss: Int, pssLimit: Int
    // notifyType == 2000 时
    heapAlloc: Int, heapCapacity: Int
}
```

### 3.3 回调协议

```kotlin
// 通过 common.callback 的 IBinder：
remote.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, IBinder.FLAG_ONEWAY)
// Parcel 布局：writeInt(notifyType) → writeInt(notifyId) → writeInt(result) → writeBundle(extra)
// result = 0 表示处理完成；系统超时 3s
```

`FairMemoryManager` 同时实现 `IBinder.DeathRecipient`：系统服务死亡时置空
缓存的 binder，避免向已死亡的系统服务 transact。

### 3.4 本应用的释放策略

设计原则：**只释放可从 Room 重建的内存副本，Agent 连接本身不动**。

| 释放点 | 钩子 | 说明 |
|---|---|---|
| 传输层内存历史 | `transport_history` | `WebSocketTransport.localMessageCache` + `OpenAIHttpTransport.conversationHistory`，均可从 Room 重建，连接不受影响 |
| 埋点环形缓冲 | `analytics_events` | `AnalyticsManager.ringBuffer`（上限 1000 条），导出前丢失可接受 |

KILL 广播（保存现场）：本应用消息/会话**实时落库**（Room Flow 直通），
无额外内存态现场需要备份，收到广播后执行释放钩子并立即回调 `result=0`。

### 3.5 与标准 onTrimMemory 的关系

| 通道 | 触发方 | 本应用响应 |
|---|---|---|
| `itgsa.intent.action.TRIM/KILL` | 厂商公平内存机制（触达 PSS/堆预警线） | `FairMemoryManager` 接收 → 钩子释放 → binder 回调 |
| `ComponentCallbacks2.onTrimMemory` | AOSP 原生（`RUNNING_LOW` 等分级） | `AgentControlCenterApplication` 分级：`UI_HIDDEN` 清性能采样；`RUNNING_LOW` 清埋点；`COMPLETE` 全量清理 |

### 3.6 验证方法

JVM 单测覆盖契约常量（`FairMemoryManagerTest`）与四厂商检测
（`VendorRomAdapterTest`）；真机验证：

```bash
# 模拟公平内存 TRIM 广播（需系统侧配合构造 callback binder，真机厂商 ROM 有效）
adb shell am broadcast -a itgsa.intent.action.TRIM

# 验证接收器已注册
adb shell dumpsys package com.agentcontrolcenter.app | grep -A2 itgsa
```

四家厂商官方文档：

- 小米：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2304
- vivo：https://dev.vivo.com.cn/wap/documentCenter/doc/1013
- 荣耀：https://developer.honor.com/cn/docs/adaptation_guide/guides/fair_memory_scheduling
- OPPO：https://open.oppomobile.com/documentation/page/info?id=13825

---

## 四、内存占用优化（查询投影瘦身）

公平内存机制的**应用侧配合**：主动降低常驻内存，减少触达预警线的概率。

### 4.1 消息列表投影（MessageListItem）

聊天页 `getMessagesBySession` Room Flow 每次表变更全量重发列表。原查询
`SELECT *` 携带 `attachmentData`（MB 级 Base64 字符串，消息 UI 零消费，
纯死重量）——长会话 + 带附件场景下内存占用被放大一个数量级。

投影排除 `attachmentData` 列（其余字段全保留，`metadataJson` 为
`Message.metadata` 计算属性所需）：

- 备份导出走独立全量查询（`getMessagesBySessionOnce`），附件数据不丢失
- 需要单条附件完整数据时按 `getMessageById` 获取

### 4.2 Insights 统计投影（MessageStat）

`DataInsightsManager.generateInsights` 原实现 `SELECT *` **全表**载入后内存
聚合（filter/groupBy/SimpleDateFormat），附件列把瞬时内存峰值放大一个数量级。
改为三列投影（`role`/`content`/`timestamp`），聚合逻辑不变。

### 4.3 附件大小常量

`ChatViewModel.MAX_ATTACHMENT_BYTES`（10MB，压缩前）提取为命名常量，
与 `AppErrorCode.FILE_TOO_LARGE` 错误码描述对齐。

### 4.4 设计取舍说明

- **渲染侧无需分页**：消息列表 `LazyColumn` 天然懒渲染，投影瘦身后
  常驻内存主要为消息文本（单条 KB 级），当前量级可控
- **附件 Base64 保留在库**：聊天消息 UI 不渲染附件内容（仅存档），完整
  文件化改造（外置存储 + DB migration）收益有限，列为后续可选项

---

## 五、后续扩展

- [ ] 附件文件化存储：`attachmentData` 列改存外置文件相对路径（DB migration，
      消除 Room 内 MB 级字符串；当前投影已让 UI 路径不感知该字段）
- [ ] `WebSocketTransport.localMessageCache` 加滑动窗口上限（当前仅在公平内存
      TRIM/KILL 时全量清理）
- [ ] 厂商推送通道（华为 Push Kit / 小米 Push）替代长连接保活的补充方案

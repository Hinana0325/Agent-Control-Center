# Android 厂商适配指南（小米 HyperOS/MIUI）

本文档记录 Android 端面向国产 ROM（当前以小米系为主）的系统特性适配，覆盖两大体系：

1. **后台保活引导**（MIUI/HyperOS 三重管控：自启动 + 省电策略 + 电池优化白名单）
2. **公平运行内存机制**（金标联盟统一内存管理规范：TRIM 预警 + KILL 查杀回调）

代码位置：`android/app/src/main/java/com/agentcontrolcenter/app/core/vendor/`

---

## 一、背景

### 1.1 为什么需要厂商适配

本项目核心场景是「前台服务维持 Agent WebSocket/SSE 长连接」。标准 AOSP 生态下，
前台服务（`dataSync` 类型）+ `START_STICKY` + 指数退避重连即可保证较高存活率；
但 MIUI/HyperOS 等国产 ROM 在 AOSP 之上叠加了更激进的厂商侧管控：

| 管控层 | MIUI/HyperOS 默认行为 | 影响 |
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
├── VendorRomAdapter.kt        # ROM 厂商检测（双信号：品牌 + 系统属性）
├── MiuiKeepAliveHelper.kt     # MIUI/HyperOS 保活引导（跳转 + 白名单申请）
├── BootCompletedReceiver.kt   # 开机/更新后恢复 Agent 连接
└── FairMemoryManager.kt       # 公平运行内存机制（TRIM/KILL 广播 + binder 回调）
```

> 与 `core/hardware/SoCHardwareDetector` 的区别：SoC 层识别**芯片厂商**
> （Snapdragon/Dimensity 等，用于推理优化）；`core/vendor` 识别**整机 ROM 厂商**
> （用于系统行为适配）。

---

## 二、后台保活引导（MiuiKeepAliveHelper）

### 2.1 ROM 检测（VendorRomAdapter）

双信号判定，任一命中即视为小米系设备：

1. **硬件信号**：`Build.MANUFACTURER` / `Build.BRAND` 含 `xiaomi` / `redmi` / `poco`
2. **ROM 信号**：系统属性 `ro.miui.ui.version.name`（MIUI）或
   `ro.mi.os.version.name`（HyperOS）存在——覆盖非小米硬件刷 MIUI 的场景

系统属性通过反射读取 `android.os.SystemProperties`（@hide API），
读取失败按 null 处理，不影响判定。

### 2.2 三项保活设置引导

| 设置项 | 实现方式 | 可查询状态 |
|---|---|---|
| 电池优化白名单 | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 系统 Intent | ✅ `PowerManager.isIgnoringBatteryOptimizations` |
| 自启动权限 | 显式 Intent 跳转 MIUI 自启动管理页（3 候选 Activity + 应用详情页兜底） | ❌ 无公开 API |
| 省电策略（无限制） | 跳转本应用系统详情页，用户手动改 | ❌ 无公开 API |

设置入口位于 **设置 → 性能 → 厂商保活优化** 区块，仅检测到小米系设备时渲染；
电池白名单状态在从系统设置页返回（`ON_RESUME`）时自动刷新。

### 2.3 开机恢复（BootCompletedReceiver）

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
| 传输层内存历史 | `transport_history` | `WebSocketTransport.localMessageCache`（无上限冗余副本）+ `OpenAIHttpTransport.conversationHistory`（已有 20 条滑动窗口），均可从 Room 重建，连接不受影响 |
| 埋点环形缓冲 | `analytics_events` | `AnalyticsManager.ringBuffer`（上限 1000 条），导出前丢失可接受 |

KILL 广播（保存现场）：本应用消息/会话**实时落库**（Room Flow 直通），
无额外内存态现场需要备份，收到广播后执行释放钩子并立即回调 `result=0`。

### 3.5 与标准 onTrimMemory 的关系

| 通道 | 触发方 | 本应用响应 |
|---|---|---|
| `itgsa.intent.action.TRIM/KILL` | 厂商公平内存机制（触达 PSS/堆预警线） | `FairMemoryManager` 接收 → 钩子释放 → binder 回调 |
| `ComponentCallbacks2.onTrimMemory` | AOSP 原生（`RUNNING_LOW` 等分级） | `AgentControlCenterApplication` 分级：`UI_HIDDEN` 清性能采样；`RUNNING_LOW` 清埋点；`COMPLETE` 全量清理 |

### 3.6 验证方法

JVM 单测覆盖契约常量（`FairMemoryManagerTest`）；真机验证：

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

## 四、后续扩展

- [ ] vivo/OPPO/荣耀保活引导（跳转路径各异，`MiuiKeepAliveHelper` 模式可复用）
- [ ] `ChatUiState.messages` 分页加载（当前 Room Flow 全量发射，长会话内存增长点）
- [ ] 消息附件 Base64 外置存储（单附件上限 10MB，Base64 后约 13.3MB 常驻 StateFlow）
- [ ] `DataInsightsManager` 全表聚合改 SQL GROUP BY（避免全库消息瞬时载入）

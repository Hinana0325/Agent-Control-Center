package com.agentcontrolcenter.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.agentcontrolcenter.app.core.analytics.AnalyticsManager
import com.agentcontrolcenter.app.core.common.PerformanceMonitor
import com.agentcontrolcenter.app.core.vendor.FairMemoryManager
import com.agentcontrolcenter.app.transport.ConnectionRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class AgentControlCenterApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 埋点管理器 — 通过 Hilt 注入。
     *
     * Hilt 在 [super.onCreate] 返回后完成字段注入，因此在 onCreate 中
     * 引用 [analyticsManager] 即可触发 @Singleton 的惰性初始化（首次访问时构造）。
     * 后续 ViewModel / Service 通过 Hilt 注入时复用同一实例。
     */
    @Inject
    lateinit var analyticsManager: AnalyticsManager

    /**
     * M-13: ConnectionRepository (@Singleton) 持有 ConnectivityManager 网络
     * 回调与协程作用域。Application 进程真正终止时需主动调用 onDispose()
     * 注销回调、释放资源，避免系统在回收进程时报已注册 callback 的泄漏警告。
     *
     * 注意：[Application.onTerminate] 在真实设备上不会被调用（系统直接杀进程），
     * 仅在模拟器/Robolectric 测试中触发；这里仍然补齐，便于测试覆盖与未来
     * 通过 androidx ProcessLifecycleOwner 显式调用的扩展。
     */
    @Inject
    lateinit var connectionRepository: ConnectionRepository

    override fun onCreate() {
        super.onCreate()

        // Install local crash logger (chains with Sentry's handler).
        // Sentry auto-initializes via manifest meta-data before Application.onCreate,
        // so getDefaultUncaughtExceptionHandler() here returns Sentry's handler.
        // Our logger writes to a local file first, then delegates to Sentry.
        installLocalCrashLogger()

        // 公平运行内存机制（金标联盟统一规范）：注册 TRIM/KILL 广播接收器 +
        // 释放钩子。钩子内仅做轻量内存清理，IO 落库工作自行走 appScope。
        setupFairMemory()

        // 骁龙硬件检测和优化初始化（在 IO 线程，避免阻塞启动）
        appScope.launch {
            PerformanceMonitor.initializeHardware(this@AgentControlCenterApplication)
        }

        // 初始化 AnalyticsManager — Hilt 在 super.onCreate() 返回后已完成
        // @Inject 字段注入（@Singleton 实例此时已创建）。此处记录应用启动事件，
        // 埋点开关由 AnalyticsManager 内部从 SettingsDataStore 异步同步，
        // 关闭时此事件不会写入 ring buffer。
        analyticsManager.logEvent("app_launch")
    }

    /**
     * 公平运行内存机制适配（金标联盟：vivo/小米/OPPO/荣耀统一规范）。
     *
     * 注册两类钩子（广播触发时依次执行）：
     *  - 传输层内存历史清理：WebSocket localMessageCache / OpenAI
     *    conversationHistory 均为可从 Room 重建的副本，连接本身不受影响
     *  - Analytics 环形缓冲清空：埋点历史，导出前丢失可接受
     *
     * 消息/会话实时落库（Room），查杀广播到达时无需额外备份现场，
     * FairMemoryManager 收到广播后即回调系统 result=0。
     */
    private fun setupFairMemory() {
        FairMemoryManager.addReleaseHook("transport_history") {
            // suspend 转同步：钩子跑在广播专用线程，launch 到 IO 池即返回
            appScope.launch { connectionRepository.clearAllHistory() }
        }
        FairMemoryManager.addReleaseHook("analytics_events") {
            analyticsManager.clearEvents()
        }
        FairMemoryManager.initialize(this)
    }

    /**
     * 标准 Android 内存分级回调 — 与公平内存广播互补：
     *  - 厂商公平内存 TRIM 广播：厂商侧触达预警线时（PSS/Java 堆），
     *    本应用通过 FairMemoryManager 接收并 3s 内回调
     *  - 本回调（ComponentCallbacks2）：AOSP 原生内存压力分级，
     *    RUNNING_LOW 起清理缓冲，UI_HIDDEN 清理采样窗口
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // 系统内存危急且本进程在后备被杀列表：全量清理
                PerformanceMonitor.reset()
                analyticsManager.clearEvents()
                appScope.launch { connectionRepository.clearAllHistory() }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // 前台运行且系统内存低：清埋点缓冲（可丢）
                analyticsManager.clearEvents()
            }
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // 所有 UI 均不可见：清性能采样（重建零成本）
                PerformanceMonitor.reset()
            }
        }
    }

    /**
     * 安装本地崩溃日志器 — 作为 Sentry 的二级安全保障。
     *
     * Sentry 通过 manifest auto-init 已安装其 UncaughtExceptionHandler。
     * 此方法在 Sentry 的 handler 之前插入本地文件日志，形成链式调用：
     *   1. 本地写入 crash_*.log（即使 Sentry DSN 未配置也能保留崩溃记录）
     *   2. 委托给 Sentry handler 上报到云端
     *   3. 委托给默认 handler 终止进程
     */
    private fun installLocalCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = File(filesDir, "crashes").apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val crashFile = File(crashDir, "crash_$timestamp.log")
                crashFile.writeText(buildString {
                    append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
                    append("Thread: ${thread.name}\n")
                    append("Exception: ${throwable.javaClass.name}\n")
                    append("Message: ${throwable.message}\n")
                    append("Stack:\n")
                    append(throwable.stackTraceToString())
                })
            } catch (_: Exception) {
                // Best-effort logging; never swallow the original exception
            }
            // Delegate to previous handler (Sentry's, then default)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * M-13: 进程终止时主动释放 [ConnectionRepository] 持有的系统资源
     * （ConnectivityManager 回调、协程作用域、transport 实例）。
     *
     * 真机正常退出不会触发本方法（系统直接 kill 进程），仅在模拟器/Robolectric
     * 测试中回调；此处补齐以保证测试场景下不残留网络回调与连接。
     */
    override fun onTerminate() {
        super.onTerminate()
        try {
            connectionRepository.onDispose()
        } catch (e: Exception) {
            // Best-effort：onTerminate 中不应抛异常影响系统退出流程
            android.util.Log.w("AgentControlCenterApplication", "onTerminate onDispose failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

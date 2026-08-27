package com.agentcontrolcenter.app.core.vendor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.util.Log
import com.agentcontrolcenter.app.AgentConnectionService
import com.agentcontrolcenter.app.data.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BootCompletedReceiver — 开机自启动 / 应用更新后恢复 Agent 连接。
 *
 * 背景（小米适配调研）：MIUI/HyperOS 默认关闭第三方应用自启动，重启后应用
 * 收不到 BOOT_COMPLETED，Agent 长连接随之中断且不会恢复。本 Receiver 的
 * 作用是在「用户已手动开启自启动权限」的前提下，开机后自动拉起
 * [AgentConnectionService] 前台服务恢复连接；更新后（PACKAGE_REPLACED）
 * 同样恢复（进程被升级杀掉的场景）。
 *
 * 克制性设计：仅当本地已保存可用的 Agent 配置（用户真实配置过连接）时才拉起，
 * 未配置的新用户开机不会被常驻通知打扰 —— 与 AgentConnectionService 内部
 * 的配置过滤条件（serverUrl 非空且非 seed_ 前缀）保持一致。
 *
 * 注：BOOT_COMPLETED 属于 Android 12+ 后台前台服务启动限制的豁免场景，
 * 可直接 startForegroundService。
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: ChatRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // goAsync：广播接收器主线程返回后进程仍可能被回收，需保持处理进行中
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val hasConfig = repository.getAllConfigs().let { flow ->
                    flow.first().any {
                        it.serverUrl.isNotBlank() && !it.id.startsWith("seed_")
                    }
                }
                if (hasConfig) {
                    Log.i(TAG, "$action: restoring agent connection service")
                    AgentConnectionService.start(context, "Agent Control Center")
                } else {
                    Log.i(TAG, "$action: no saved agent config, skip auto start")
                }
            } catch (e: CancellationException) {
                // scope.cancel() 触发的正常取消不是启动失败；finally 保证 pendingResult.finish()
                throw e
            } catch (e: SQLiteException) {
                // 配置查询失败（数据库损坏/磁盘异常）：无配置视为未配置，跳过自启动
                Log.w(TAG, "$action: auto start check failed: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: IllegalStateException) {
                // 前台服务启动受限（Android 12+ FGS 限制的各类子类异常）或 Room 迁移缺失
                Log.w(TAG, "$action: auto start check failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.invokeOnCompletion {
            // goAsync 的 10s 限制之外也避免作用域泄漏
            scope.cancel()
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}

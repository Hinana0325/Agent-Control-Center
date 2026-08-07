package com.agentcontrolcenter.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.ui.graphics.vector.ImageVector
import com.agentcontrolcenter.app.R

sealed class Screen(
    val route: String,
    @StringRes val stringResId: Int,
    val icon: ImageVector
) {
    data object Chat : Screen("chat", R.string.nav_chat, Icons.Default.Chat)
    data object Sessions : Screen("sessions", R.string.nav_sessions, Icons.Default.History)
    data object Activity : Screen("activity", R.string.nav_activity, Icons.Default.Schedule)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
    data object Agents : Screen("agents", R.string.nav_agents, Icons.Default.Hub)
    data object Marketplace : Screen("marketplace", R.string.nav_marketplace, Icons.Default.Storefront)
    data object Insights : Screen("insights", R.string.insights_title, Icons.Default.BarChart)
    data object Workflow : Screen("workflow", R.string.nav_workflow, Icons.Default.AccountTree)
    data object Compare : Screen("compare", R.string.compare_title, Icons.Default.CompareArrows)
    data object DeviceSync : Screen("device_sync", R.string.device_sync_title, Icons.Default.Sync)
    data object Plugins : Screen("plugins", R.string.plugin_title, Icons.Default.Extension)
    data object Tasks : Screen("tasks", R.string.nav_tasks, Icons.Default.TaskAlt)
    data object Mcp : Screen("mcp", R.string.nav_mcp, Icons.Default.Dns)
    /**
     * P0 IA 重组：新增 More 主 Tab，承载次级入口（Activity/Marketplace/Workflow/
     * Insights/Plugins/Mcp/Compare/DeviceSync/Settings）。
     * 路由 "more" 不与任何已有 Screen 冲突。
     */
    data object More : Screen("more", R.string.tab_more, Icons.Default.Apps)

    companion object {
        /**
         * Returns the list of primary tab screens shown in the bottom bar / navigation rail.
         *
         * v5.1 信息架构重组（阶段 2）：
         * - 阶段 1：Chat / Sessions / Agents / Tasks / More
         * - 阶段 2：Chat / Agents / Activity / Marketplace / Settings
         *
         * Sessions / Tasks 从主 Tab 下沉为首页(ChatScreen)顶部入口融合进首页；
         * Activity / Marketplace / Settings 从 More 上提为主 Tab；
         * More Tab 移除，其次级入口(Workflow/Insights/Plugins/Mcp/Compare/DeviceSync)
         * 通过 Settings 或 ChatScreen 入口可达。
         */
        fun getTabs(): List<Screen> = listOf(Chat, Agents, Activity, Marketplace, Settings)
    }
}

package com.agentcontrolcenter.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.agentcontrolcenter.desktop.app.AppStore
import com.agentcontrolcenter.desktop.app.Strings
import com.agentcontrolcenter.desktop.data.persistence.JsonStore
import com.agentcontrolcenter.desktop.ui.AppRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Dimension
import java.awt.SystemTray

/**
 * 桌面端入口 — Compose Multiplatform Desktop 单窗口应用。
 *
 * 平台特性：
 *  - 系统托盘（Show / Quit；close-to-tray 可在设置中开关）；
 *    托盘不可用的最小化 Linux WM 下自动跳过（SystemTray.isSupported 守卫）
 *  - 单窗口 1200x800 起步（rememberWindowState，用户缩放不被重置）
 *  - 状态持久化至 ~/.agent-control-center/（JsonStore）
 */
fun main() = application {
    val icon = painterResource("icons/app.svg")
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val store = remember { AppStore(JsonStore(), scope) }
    val windowVisible = remember { mutableStateOf(true) }
    val settings by store.settings.collectAsState()

    // 应用退出时释放传输层连接
    DisposableEffect(Unit) {
        onDispose { store.disconnect() }
    }

    // 初始尺寸经由 WindowState 管理：content 重组（如托盘显隐切换 visible）时
    // 不会重设 window.size 覆盖用户手动缩放（扫描修复项 C1）
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

    Window(
        onCloseRequest = {
            if (settings.closeToTray) {
                windowVisible.value = false
            } else {
                exitApplication()
            }
        },
        visible = windowVisible.value,
        state = windowState,
        title = "Agent Control Center",
        icon = icon
    ) {
        // 幂等设置（不参与尺寸争用），仅保证最小可用尺寸
        window.minimumSize = Dimension(960, 600)
        AppRoot(store)
    }

    // 部分裁剪型 Linux WM / 无托盘环境：SystemTray 不支持时跳过托盘，
    // 主窗口关闭行为退化为直接退出（设置中的 close-to-tray 无效但不崩溃）
    if (SystemTray.isSupported()) {
        Tray(
            icon = icon,
            tooltip = Strings.t("app.title"),
            onAction = { windowVisible.value = true }
        ) {
            Item(Strings.t("tray.show")) { windowVisible.value = true }
            Item(Strings.t("tray.quit")) {
                store.disconnect()
                exitApplication()
            }
        }
    }
}

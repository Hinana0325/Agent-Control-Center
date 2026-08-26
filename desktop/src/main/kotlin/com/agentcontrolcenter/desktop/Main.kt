package com.agentcontrolcenter.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.agentcontrolcenter.desktop.app.AppStore
import com.agentcontrolcenter.desktop.app.Strings
import com.agentcontrolcenter.desktop.data.persistence.JsonStore
import com.agentcontrolcenter.desktop.ui.AppRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Dimension

/**
 * 桌面端入口 — Compose Multiplatform Desktop 单窗口应用。
 *
 * 平台特性：
 *  - 系统托盘（Show / Quit；close-to-tray 可在设置中开关）
 *  - 单窗口 1200x800 起步，可自由缩放
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

    Window(
        onCloseRequest = {
            if (settings.closeToTray) {
                windowVisible.value = false
            } else {
                exitApplication()
            }
        },
        visible = windowVisible.value,
        title = "Agent Control Center",
        icon = icon
    ) {
        window.minimumSize = Dimension(960, 600)
        window.size = Dimension(1200, 800)
        AppRoot(store)
    }

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

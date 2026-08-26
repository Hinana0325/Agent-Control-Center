package com.agentcontrolcenter.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.desktop.app.AppStore
import com.agentcontrolcenter.desktop.app.Strings
import com.agentcontrolcenter.desktop.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.desktop.ui.theme.AppTheme

private enum class NavTab { Chat, Agents, Settings }

/**
 * 主窗口内容：左侧 NavigationRail（对齐移动端底部导航三主入口）+
 * 右侧当前面板 + 底部连接状态栏 + 全局错误 Snackbar。
 */
@Composable
fun AppRoot(store: AppStore) {
    val settings by store.settings.collectAsState()
    val connection by store.connectionState.collectAsState()
    val error by store.errorMessage.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // 语言切换时以 locale 为 key 强制重组全部文案
    key(settings.locale) {
        Strings.locale = settings.locale
        AppTheme(darkTheme = settings.darkTheme) {
            var tab by remember { mutableStateOf(NavTab.Chat) }

            // 全局错误：一次性弹出
            LaunchedEffect(error) {
                error?.let {
                    snackbar.showSnackbar(it)
                    store.clearError()
                }
            }

            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    NavigationRail(header = {
                        Text(
                            "ACC",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }) {
                        NavigationRailItem(
                            selected = tab == NavTab.Chat,
                            onClick = { tab = NavTab.Chat },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = Strings.t("nav.chat")) },
                            label = { Text(Strings.t("nav.chat")) }
                        )
                        NavigationRailItem(
                            selected = tab == NavTab.Agents,
                            onClick = { tab = NavTab.Agents },
                            icon = { Icon(Icons.Filled.SmartToy, contentDescription = Strings.t("nav.agents")) },
                            label = { Text(Strings.t("nav.agents")) }
                        )
                        NavigationRailItem(
                            selected = tab == NavTab.Settings,
                            onClick = { tab = NavTab.Settings },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = Strings.t("nav.settings")) },
                            label = { Text(Strings.t("nav.settings")) }
                        )
                    }
                    VerticalDivider(Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                NavTab.Chat -> ChatPane(store)
                                NavTab.Agents -> AgentsPane(store)
                                NavTab.Settings -> SettingsPane(store)
                            }
                        }
                        StatusBar(connection)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBar(connection: AgentConnectionState) {
    val dotColor = if (connection.isConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(dotColor, androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            if (connection.isConnected) Strings.t("agents.connected") else Strings.t("agents.disconnected"),
            style = MaterialTheme.typography.labelSmall
        )
        if (connection.isConnected) {
            Text("·", style = MaterialTheme.typography.labelSmall)
            Text(connection.serverUrl, style = MaterialTheme.typography.labelSmall)
            if (connection.latency > 0) {
                Text("·", style = MaterialTheme.typography.labelSmall)
                Text("${Strings.t("status.latency")} ${connection.latency}ms", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

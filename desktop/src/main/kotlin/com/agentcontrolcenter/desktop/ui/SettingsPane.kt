package com.agentcontrolcenter.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.desktop.app.AppStore
import com.agentcontrolcenter.desktop.app.Strings
import com.agentcontrolcenter.desktop.data.persistence.AppSettings

/**
 * 设置面板：外观 / 安全（E2E）/ 行为 / 关于。
 */
@Composable
fun SettingsPane(store: AppStore) {
    val settings by store.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(Strings.t("settings.title"), style = MaterialTheme.typography.titleLarge)

        SettingsSection(Strings.t("settings.appearance")) {
            SwitchRow(
                title = Strings.t("settings.dark_theme"),
                checked = settings.darkTheme,
                onChange = { checked -> store.updateSettings { it.copy(darkTheme = checked) } }
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(Strings.t("settings.language"), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(16.dp))
                androidx.compose.material3.FilterChip(
                    selected = settings.locale == "en",
                    onClick = { store.updateSettings { it.copy(locale = "en") } },
                    label = { Text(Strings.t("settings.language_en")) }
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilterChip(
                    selected = settings.locale == "zh",
                    onClick = { store.updateSettings { it.copy(locale = "zh") } },
                    label = { Text(Strings.t("settings.language_zh")) }
                )
            }
        }

        SettingsSection(Strings.t("settings.security")) {
            // 防抖 500ms：避免每个按键触发一次磁盘写 + E2E 热更新（扫描修复项 M1）
            var passphrase by remember { mutableStateOf(settings.e2ePassphrase) }
            LaunchedEffect(passphrase) {
                if (passphrase != settings.e2ePassphrase) {
                    kotlinx.coroutines.delay(500)
                    store.updateSettings { it.copy(e2ePassphrase = passphrase) }
                }
            }
            Column(Modifier.padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(Strings.t("settings.e2e_passphrase")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    Strings.t("settings.e2e_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsSection(Strings.t("settings.behavior")) {
            SwitchRow(
                title = Strings.t("settings.close_to_tray"),
                checked = settings.closeToTray,
                onChange = { checked -> store.updateSettings { it.copy(closeToTray = checked) } }
            )
        }

        SettingsSection(Strings.t("settings.about")) {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Strings.t("settings.version"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    Strings.t("settings.open_source"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

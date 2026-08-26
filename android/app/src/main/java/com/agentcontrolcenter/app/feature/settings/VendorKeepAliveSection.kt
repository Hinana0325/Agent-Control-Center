package com.agentcontrolcenter.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.core.vendor.MiuiKeepAliveHelper
import com.agentcontrolcenter.app.core.vendor.VendorRomAdapter

/**
 * 厂商保活优化区块 — 设置页「性能」分类的小米（MIUI/HyperOS）专属设置项。
 *
 * 背景：MIUI/HyperOS 默认关闭自启动、默认「智能限制后台运行」省电策略，
 * 前台服务 + START_STICKY 无法穿透这些厂商侧管控。本区块引导用户完成
 * 三项厂商设置（电池白名单 / 自启动 / 省电策略），使 Agent 后台长连接
 * 在这些 ROM 上可靠保活。
 *
 * 仅检测到小米系设备时渲染（VendorRomAdapter 双信号判定），其他设备返回空。
 * 电池白名单状态在从系统设置页返回（ON_RESUME）时自动刷新。
 */
@Composable
internal fun VendorKeepAliveSection() {
    val context = LocalContext.current
    val romInfo = remember { VendorRomAdapter.detect() }
    if (!romInfo.isXiaomi) return

    // 电池白名单状态：从系统设置页返回时刷新（ON_RESUME）
    var batteryExcluded by remember {
        mutableStateOf(MiuiKeepAliveHelper.isIgnoringBatteryOptimizations(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExcluded = MiuiKeepAliveHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 标题携带 ROM 标识（HyperOS OS1.0.3 / MIUI V14.0.5 / Xiaomi），便于用户确认机型
    val keepAliveTitle = stringResource(R.string.vendor_keepalive_title)
    SettingsHeader("$keepAliveTitle · ${romInfo.romLabel}")

    // 区块说明：为何需要这些设置（与 Android 端前台服务保活互补的厂商侧配置）
    Text(
        text = stringResource(R.string.vendor_keepalive_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // 1. 电池优化白名单：可直接弹系统申请对话框，状态实时可查
    SettingsItem(
        title = stringResource(R.string.vendor_battery_title),
        subtitle = stringResource(
            if (batteryExcluded) R.string.vendor_battery_ok else R.string.vendor_battery_limited
        ),
        icon = if (batteryExcluded) Icons.Default.BatteryFull else Icons.Default.BatteryAlert,
        onClick = { MiuiKeepAliveHelper.requestIgnoreBatteryOptimizations(context) }
    )

    // 2. 自启动：跳转 MIUI 自启动管理页（多候选 + 应用详情页兜底）；
    //    自启动状态无公开 API 可查，始终可点（重复跳转无副作用）
    SettingsItem(
        title = stringResource(R.string.vendor_autostart_title),
        subtitle = stringResource(R.string.vendor_autostart_desc),
        icon = Icons.Default.PlayArrow,
        onClick = { MiuiKeepAliveHelper.openAutoStartSettings(context) }
    )

    // 3. 省电策略：无公开 API，引导到本应用系统详情页手动改「无限制」
    SettingsItem(
        title = stringResource(R.string.vendor_power_title),
        subtitle = stringResource(R.string.vendor_power_desc),
        icon = Icons.Default.Bolt,
        onClick = { MiuiKeepAliveHelper.openAppDetailsSettings(context) }
    )
}

/**
 * 厂商保活区块容器（LazyListScope 扩展）：
 * 非小米设备渲染空内容，保持调用方（settingsDetail）分支结构统一。
 */
@Composable
internal fun VendorKeepAliveCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            VendorKeepAliveSection()
        }
    }
}

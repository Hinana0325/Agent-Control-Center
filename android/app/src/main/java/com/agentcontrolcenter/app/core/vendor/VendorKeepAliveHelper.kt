package com.agentcontrolcenter.app.core.vendor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * VendorKeepAliveHelper — 国产 ROM（金标联盟四厂商）后台保活引导。
 *
 * 背景（调查结论，见 docs/vendor-adaptation.md）：
 *  MIUI/HyperOS、HarmonyOS（华为/荣耀）、ColorOS（OPPO）、OriginOS（vivo）
 *  的后台管控由多个相互独立的子系统构成，仅靠应用侧的前台服务 +
 *  START_STICKY（AgentConnectionService 现有方案）无法穿透：
 *
 *   1. 自启动权限 —— 默认关闭。关闭时应用收不到 BOOT_COMPLETED 等系统广播，
 *      重启后 Agent 连接无法自动恢复（链式启动控制，A 启动 B 不受限）。
 *   2. 省电策略 —— 默认「智能限制后台运行」，会限制后台 CPU 调度与网络访问；
 *      需用户改为「无限制」（该设置无公开 API，只能引导用户跳转详情页手动改）。
 *   3. 电池优化 —— 标准 Android Doze 白名单，可通过系统 Intent 直接申请。
 *
 *  本类提供三项能力：
 *   - [isIgnoringBatteryOptimizations] / [requestIgnoreBatteryOptimizations]：
 *     标准 API，可查询、可直接弹系统白名单申请对话框（全厂商通用）
 *   - [openAutoStartSettings]：按 [VendorRom] 分发显式 Intent 跳转各厂商
 *     自启动管理页（多候选 Activity + 兜底应用详情页，适配版本碎片化）
 *   - [openAppDetailsSettings]：跳转本应用系统详情页（省电策略入口就在该页）
 *
 *  所有跳转方法返回 Boolean 表示是否成功发起（调用方可据此提示手动路径）。
 */
object VendorKeepAliveHelper {

    /**
     * 各厂商自启动管理页候选 Activity（按 [VendorRom] 分发）。
     *
     * 不同 ROM 版本的入口 ComponentName 存在碎片化，按优先级逐个尝试：
     *  - Xiaomi：经典 MIUI permcenter 三入口（AutoStartManagement 为主）
     *  - Honor：华为 systemmanager 启动管理（StartupNormalAppList 为主），
     *    荣耀独立后新机型为 com.hihonor.* 包，均列出
     *  - Oppo：ColorOS safecenter 启动管理（startupapp / permission.startup 两代路径）+ 旧版 oppo.safe
     *  - Vivo：permissionmanager 后台弹出管理（BgStartUpManager 为主）+ PurviewTab
     *  - 兜底：跳转本应用系统详情页（所有 ROM 均可达）
     */
    private val AUTO_START_CANDIDATES: Map<VendorRom, List<ComponentName>> = mapOf(
        VendorRom.Xiaomi to listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsMainActivity"
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.MainAcitvity"
            )
        ),
        VendorRom.Honor to listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.bootstart.BootStartActivity"
            )
        ),
        VendorRom.Oppo to listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        ),
        VendorRom.Vivo to listOf(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity"
            )
        )
    )

    /**
     * 查询应用是否已在电池优化白名单（Doze 豁免）。
     * API 23 以下无 Doze 机制，视为已豁免。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 弹出系统「忽略电池优化」申请对话框（标准 Settings Intent，全厂商通用）。
     *
     * 需在 Manifest 声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限；
     * 未在白名单时才发起，已豁免时返回 true 但不跳转。
     *
     * @return 是否成功发起（或已豁免无需跳转）
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // 极少数 ROM 禁止第三方调用该 Intent（Play 政策限制场景），降级到电池优化列表页
            openBatteryOptimizationList(context)
        }
    }

    /**
     * 跳转系统电池优化列表页（REQUEST 对话框被拒绝时的降级路径）。
     */
    fun openBatteryOptimizationList(context: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 跳转指定厂商的自启动管理页。
     *
     * 按 [VendorRom] 取候选列表（[AUTO_START_CANDIDATES]），逐个
     * resolveActivity 探测 + try-catch 兜底启动失败，全部失败时降级
     * 跳转本应用系统详情页 —— 用户可从那里找到「自启动」入口。
     *
     * @param rom 厂商标识（[VendorRomAdapter.detect] 结果）
     * @return 是否成功发起任意一个跳转
     */
    fun openAutoStartSettings(context: Context, rom: VendorRom): Boolean {
        val candidates = AUTO_START_CANDIDATES[rom] ?: return openAppDetailsSettings(context)
        for (candidate in candidates) {
            val intent = Intent().apply {
                component = candidate
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    // resolveActivity 通过但启动仍失败（权限/裁剪 ROM），尝试下一个候选
                    continue
                }
            }
        }
        // 兜底：应用详情页（自启动入口也在该页的「权限管理」子页内）
        return openAppDetailsSettings(context)
    }

    /**
     * 跳转本应用系统详情页。
     *
     * 该页同时是「省电策略」入口（设置 → 应用管理 → 本应用 → 省电策略 → 无限制）。
     * 省电策略无公开 API 可查询/修改，只能引导用户到此页手动设置。
     */
    fun openAppDetailsSettings(context: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

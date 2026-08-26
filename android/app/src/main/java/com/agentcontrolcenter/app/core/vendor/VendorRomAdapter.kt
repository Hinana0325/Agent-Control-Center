package com.agentcontrolcenter.app.core.vendor

import android.os.Build

/**
 * VendorRom — ROM 厂商标识。
 *
 * 与 [com.agentcontrolcenter.app.core.hardware.SoCHardwareDetector] 的区别：
 * SoCHardwareDetector 识别的是「SoC 芯片厂商」（Snapdragon/Dimensity 等，用于推理优化），
 * 本类识别的是「整机 ROM 厂商」（MIUI/HyperOS 等，用于后台保活与系统行为适配）。
 */
enum class VendorRom(val displayName: String) {
    /** 小米系（Xiaomi / Redmi / POCO，MIUI 或 HyperOS）。 */
    Xiaomi("Xiaomi MIUI/HyperOS"),

    /** 原生或其他 ROM（无厂商特化路径）。 */
    Other("Standard")
}

/**
 * 厂商 ROM 检测结果快照。
 *
 * @property rom ROM 厂商标识
 * @property miuiVersion MIUI 版本名（如 "V14.0.5"），非 MIUI 为 null
 * @property hyperOsVersion HyperOS 版本名（如 "OS1.0.3"），非 HyperOS 为 null
 */
data class VendorRomInfo(
    val rom: VendorRom,
    val miuiVersion: String? = null,
    val hyperOsVersion: String? = null
) {
    /** 是否为小米系设备（MIUI 或 HyperOS）。 */
    val isXiaomi: Boolean get() = rom == VendorRom.Xiaomi

    /** 人类可读的 ROM 描述（用于设置页展示），如 "HyperOS OS1.0.3" / "MIUI V14.0.5"。 */
    val romLabel: String
        get() = when {
            hyperOsVersion != null -> "HyperOS $hyperOsVersion"
            miuiVersion != null -> "MIUI $miuiVersion"
            isXiaomi -> "Xiaomi"
            else -> "Standard"
        }
}

/**
 * VendorRomAdapter — ROM 厂商检测层。
 *
 * 背景：MIUI/HyperOS 对后台应用的管控（默认关闭自启动、默认「智能限制后台运行」
 * 省电策略、链式启动控制等）远比原生 Android 激进。本应用的核心场景——前台服务
 * 维持 Agent WebSocket/SSE 长连接——在这些 ROM 上必须引导用户完成厂商侧设置
 * 才能可靠保活，因此需要先准确识别 ROM。
 *
 * 检测策略（双信号，任一命中即判定为小米系）：
 *  1. 硬件信号：Build.MANUFACTURER / Build.BRAND 含 Xiaomi / Redmi / POCO
 *     （国内行货与海外 POCO 均覆盖；少数刷机设备 hardware 名不含品牌，靠信号 2 兜底）
 *  2. ROM 信号：系统属性 ro.miui.ui.version.name（MIUI）或
 *     ro.mi.os.version.name（HyperOS）存在——即使在非小米硬件上刷了 MIUI
 *     也能正确识别。
 *
 * SystemProperties 为 @hide API，通过反射读取（业界通用做法，无需引入
 * hidden API 依赖），读取失败按 null 处理，不影响判定。
 */
object VendorRomAdapter {

    /** MIUI 版本系统属性（MIUI 12 及以前为 ro.miui.ui.version.code）。 */
    private const val PROP_MIUI_VERSION = "ro.miui.ui.version.name"

    /** HyperOS 版本系统属性（HyperOS 起新增）。 */
    private const val PROP_HYPEROS_VERSION = "ro.mi.os.version.name"

    /** 小米系品牌关键词（小写匹配）。 */
    private val XIAOMI_BRAND_KEYWORDS = listOf("xiaomi", "redmi", "poco")

    /**
     * 检测当前设备的 ROM 厂商。
     *
     * 参数全部可注入，便于单元测试模拟不同机型（默认取 Build 常量与真实系统属性）。
     *
     * @param manufacturer Build.MANUFACTURER（测试注入）
     * @param brand Build.BRAND（测试注入）
     * @param readProp 系统属性读取函数（测试注入；生产环境走反射 SystemProperties）
     */
    fun detect(
        manufacturer: String = Build.MANUFACTURER ?: "",
        brand: String = Build.BRAND ?: "",
        readProp: (String) -> String? = ::readSystemProperty
    ): VendorRomInfo {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        val isXiaomiHardware = XIAOMI_BRAND_KEYWORDS.any {
            m.contains(it) || b.contains(it)
        }

        val miuiVersion = readProp(PROP_MIUI_VERSION)?.takeIf { it.isNotBlank() }
        val hyperOsVersion = readProp(PROP_HYPEROS_VERSION)?.takeIf { it.isNotBlank() }
        val isXiaomiRom = miuiVersion != null || hyperOsVersion != null

        return if (isXiaomiHardware || isXiaomiRom) {
            VendorRomInfo(VendorRom.Xiaomi, miuiVersion, hyperOsVersion)
        } else {
            VendorRomInfo(VendorRom.Other)
        }
    }

    /**
     * 反射读取系统属性（SystemProperties 为 @hide API）。
     * 任何失败（类不存在 / 反射受限 / 非字符串返回值）均返回 null，不影响调用方逻辑。
     */
    fun readSystemProperty(key: String): String? = try {
        @Suppress("PrivateApi")
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, key) as? String
    } catch (e: Exception) {
        null
    }
}

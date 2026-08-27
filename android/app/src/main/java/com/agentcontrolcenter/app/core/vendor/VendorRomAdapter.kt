package com.agentcontrolcenter.app.core.vendor

import android.os.Build

/**
 * VendorRom — ROM 厂商标识（金标联盟四厂商 + 其他）。
 *
 * 与 [com.agentcontrolcenter.app.core.hardware.SoCHardwareDetector] 的区别：
 * SoCHardwareDetector 识别的是「SoC 芯片厂商」（Snapdragon/Dimensity 等，用于推理优化），
 * 本类识别的是「整机 ROM 厂商」（MIUI/HyperOS 等，用于后台保活与系统行为适配）。
 *
 * 荣耀（Honor）2020 年从华为独立，新机型品牌为 hihonor，但其系统管控路径
 * 沿用华为 systemmanager 体系；华为本体（huawei）亦同。两者合并为 [Honor]，
 * 跳转候选同时覆盖 com.huawei.* 与 com.hihonor.* 包名。
 */
enum class VendorRom(val displayName: String) {
    /** 小米系（Xiaomi / Redmi / POCO，MIUI 或 HyperOS）。 */
    Xiaomi("Xiaomi MIUI/HyperOS"),

    /** 华为/荣耀系（Huawei / Honor / HONOR，HarmonyOS / EMUI / Magic UI）。 */
    Honor("Huawei/Honor HarmonyOS"),

    /** OPPO 系（OPPO / OnePlus / realme，ColorOS）。 */
    Oppo("OPPO ColorOS"),

    /** vivo 系（vivo / iQOO，OriginOS / Funtouch OS）。 */
    Vivo("vivo OriginOS"),

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

    /** 是否为已知需要保活引导的厂商（金标联盟四厂商：小米/vivo/OPPO/荣耀）。 */
    val isManagedVendor: Boolean get() = rom != VendorRom.Other

    /** 人类可读的 ROM 描述（用于设置页展示），如 "HyperOS OS1.0.3" / "MIUI V14.0.5"。 */
    val romLabel: String
        get() = when {
            hyperOsVersion != null -> "HyperOS $hyperOsVersion"
            miuiVersion != null -> "MIUI $miuiVersion"
            rom != VendorRom.Other -> rom.displayName
            else -> "Standard"
        }
}

/**
 * VendorRomAdapter — ROM 厂商检测层（金标联盟四厂商：小米/vivo/OPPO/荣耀）。
 *
 * 背景：国产 ROM 对后台应用的管控（默认关闭自启动、默认「智能限制后台运行」
 * 省电策略、链式启动控制等）远比原生 Android 激进。本应用的核心场景——前台服务
 * 维持 Agent WebSocket/SSE 长连接——在这些 ROM 上必须引导用户完成厂商侧设置
 * 才能可靠保活，因此需要先准确识别 ROM。
 *
 * 检测策略（按品牌关键词分组匹配，华为/荣耀另靠 ROM 信号兜底）：
 *  1. 硬件信号：Build.MANUFACTURER / Build.BRAND 含品牌关键词
 *     （xiaomi/redmi/poco、huawei/honor/hihonor、oppo/oneplus/realme、vivo/iqoo）
 *  2. ROM 信号（仅小米系）：系统属性 ro.miui.ui.version.name（MIUI）或
 *     ro.mi.os.version.name（HyperOS）存在——覆盖非小米硬件刷 MIUI 的场景
 *
 * SystemProperties 为 @hide API，通过反射读取（业界通用做法，无需引入
 * hidden API 依赖），读取失败按 null 处理，不影响判定。
 */
object VendorRomAdapter {

    /** MIUI 版本系统属性（MIUI 12 及以前为 ro.miui.ui.version.code）。 */
    private const val PROP_MIUI_VERSION = "ro.miui.ui.version.name"

    /** HyperOS 版本系统属性（HyperOS 起新增）。 */
    private const val PROP_HYPEROS_VERSION = "ro.mi.os.version.name"

    /** 各厂商品牌关键词（小写匹配，含子品牌）。 */
    private val XIAOMI_BRAND_KEYWORDS = listOf("xiaomi", "redmi", "poco")
    private val HONOR_BRAND_KEYWORDS = listOf("huawei", "honor", "hihonor")
    private val OPPO_BRAND_KEYWORDS = listOf("oppo", "oneplus", "realme")
    private val VIVO_BRAND_KEYWORDS = listOf("vivo", "iqoo")

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

        // ROM 信号（仅小米系）：MIUI/HyperOS 版本属性存在即判定，覆盖刷机场景
        val miuiVersion = readProp(PROP_MIUI_VERSION)?.takeIf { it.isNotBlank() }
        val hyperOsVersion = readProp(PROP_HYPEROS_VERSION)?.takeIf { it.isNotBlank() }
        if (miuiVersion != null || hyperOsVersion != null) {
            return VendorRomInfo(VendorRom.Xiaomi, miuiVersion, hyperOsVersion)
        }

        // 硬件信号：品牌关键词分组匹配（不同厂商关键词无交集，首中即返回）
        return when {
            matchesAny(m, b, XIAOMI_BRAND_KEYWORDS) ->
                VendorRomInfo(VendorRom.Xiaomi)
            matchesAny(m, b, HONOR_BRAND_KEYWORDS) ->
                VendorRomInfo(VendorRom.Honor)
            matchesAny(m, b, OPPO_BRAND_KEYWORDS) ->
                VendorRomInfo(VendorRom.Oppo)
            matchesAny(m, b, VIVO_BRAND_KEYWORDS) ->
                VendorRomInfo(VendorRom.Vivo)
            else ->
                VendorRomInfo(VendorRom.Other)
        }
    }

    /** manufacturer 或 brand 是否含任一品牌关键词。 */
    private fun matchesAny(m: String, b: String, keywords: List<String>): Boolean =
        keywords.any { m.contains(it) || b.contains(it) }

    /**
     * 反射读取系统属性（SystemProperties 为 @hide API）。
     * 任何失败（类不存在 / 反射受限 / 非字符串返回值）均返回 null，不影响调用方逻辑。
     */
    fun readSystemProperty(key: String): String? = try {
        @Suppress("PrivateApi")
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, key) as? String
    } catch (_: ReflectiveOperationException) {
        // Class.forName / getMethod / invoke 的全部失败模式：
        // 类不存在（非标准 ROM）、反射受限、方法调用失败，统一返回 null
        null
    }
}

package com.agentcontrolcenter.app.core.vendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VendorRomAdapter 单元测试 — ROM 厂商检测逻辑（纯 JVM，参数注入）。
 *
 * 覆盖三类判定信号：
 *  1. 硬件信号（MANUFACTURER/BRAND 含小米系关键词）
 *  2. ROM 信号（系统属性 ro.miui.ui.version.name / ro.mi.os.version.name）
 *  3. 大小写与空白容错、非小米设备负例
 */
class VendorRomAdapterTest {

    // ── 硬件信号判定 ──

    @Test
    fun `detect xiaomi manufacturer returns Xiaomi`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            readProp = { null }
        )
        assertTrue(info.isXiaomi)
        assertEquals(VendorRom.Xiaomi, info.rom)
    }

    @Test
    fun `detect redmi brand returns Xiaomi`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            readProp = { null }
        )
        assertTrue(info.isXiaomi)
    }

    @Test
    fun `detect poco brand returns Xiaomi`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "POCO",
            brand = "POCO",
            readProp = { null }
        )
        assertTrue(info.isXiaomi)
    }

    @Test
    fun `detect manufacturer is case insensitive`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "XIAOMI",
            brand = "xiaomi",
            readProp = { null }
        )
        assertTrue(info.isXiaomi)
    }

    // ── ROM 信号判定（刷机场景：非小米硬件跑 MIUI） ──

    @Test
    fun `detect miui property returns Xiaomi with version`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "Google",
            brand = "google",
            readProp = { key -> if (key == "ro.miui.ui.version.name") "V14.0.5" else null }
        )
        assertTrue(info.isXiaomi)
        assertEquals("V14.0.5", info.miuiVersion)
        assertNull(info.hyperOsVersion)
        assertEquals("MIUI V14.0.5", info.romLabel)
    }

    @Test
    fun `detect hyperos property returns Xiaomi with version`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            readProp = { key -> if (key == "ro.mi.os.version.name") "OS1.0.3" else null }
        )
        assertTrue(info.isXiaomi)
        assertEquals("OS1.0.3", info.hyperOsVersion)
        assertNull(info.miuiVersion)
        assertEquals("HyperOS OS1.0.3", info.romLabel)
    }

    @Test
    fun `detect blank property is ignored`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "Google",
            brand = "google",
            readProp = { "  " }
        )
        assertFalse(info.isXiaomi)
        assertEquals(VendorRom.Other, info.rom)
    }

    // ── 非小米设备负例 ──

    @Test
    fun `detect non-xiaomi device returns Other`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "Samsung",
            brand = "samsung",
            readProp = { null }
        )
        assertFalse(info.isXiaomi)
        assertFalse(info.isManagedVendor)
        assertEquals(VendorRom.Other, info.rom)
        assertNull(info.miuiVersion)
        assertNull(info.hyperOsVersion)
        assertEquals("Standard", info.romLabel)
    }

    // ── 华为/荣耀系（Honor）检测 ──

    @Test
    fun `detect honor brand returns Honor`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "HONOR",
            brand = "honor",
            readProp = { null }
        )
        assertEquals(VendorRom.Honor, info.rom)
        assertTrue(info.isManagedVendor)
        assertFalse(info.isXiaomi)
    }

    @Test
    fun `detect huawei brand returns Honor`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            readProp = { null }
        )
        assertEquals(VendorRom.Honor, info.rom)
        assertTrue(info.isManagedVendor)
    }

    @Test
    fun `detect hihonor brand returns Honor`() {
        // 荣耀独立后新机型品牌为 hihonor
        val info = VendorRomAdapter.detect(
            manufacturer = "hihonor",
            brand = "HIHONOR",
            readProp = { null }
        )
        assertEquals(VendorRom.Honor, info.rom)
    }

    // ── OPPO 系检测 ──

    @Test
    fun `detect oppo brand returns Oppo`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "OPPO",
            brand = "OPPO",
            readProp = { null }
        )
        assertEquals(VendorRom.Oppo, info.rom)
        assertTrue(info.isManagedVendor)
    }

    @Test
    fun `detect oneplus brand returns Oppo`() {
        // 一加国内版采用 ColorOS
        val info = VendorRomAdapter.detect(
            manufacturer = "OnePlus",
            brand = "OnePlus",
            readProp = { null }
        )
        assertEquals(VendorRom.Oppo, info.rom)
    }

    @Test
    fun `detect realme brand returns Oppo`() {
        // realme 为 OPPO 子品牌（ColorOS 系）
        val info = VendorRomAdapter.detect(
            manufacturer = "realme",
            brand = "realme",
            readProp = { null }
        )
        assertEquals(VendorRom.Oppo, info.rom)
    }

    // ── vivo 系检测 ──

    @Test
    fun `detect vivo brand returns Vivo`() {
        val info = VendorRomAdapter.detect(
            manufacturer = "vivo",
            brand = "vivo",
            readProp = { null }
        )
        assertEquals(VendorRom.Vivo, info.rom)
        assertTrue(info.isManagedVendor)
    }

    @Test
    fun `detect iqoo brand returns Vivo`() {
        // iQOO 为 vivo 子品牌（OriginOS 系）
        val info = VendorRomAdapter.detect(
            manufacturer = "iQOO",
            brand = "IQOO",
            readProp = { null }
        )
        assertEquals(VendorRom.Vivo, info.rom)
    }

    // ── romLabel 展示 ──

    @Test
    fun `romLabel uses displayName for non-xiaomi managed vendors`() {
        val honor = VendorRomAdapter.detect("HONOR", "honor") { null }
        assertEquals("Huawei/Honor HarmonyOS", honor.romLabel)

        val oppo = VendorRomAdapter.detect("OPPO", "OPPO") { null }
        assertEquals("OPPO ColorOS", oppo.romLabel)

        val vivo = VendorRomAdapter.detect("vivo", "vivo") { null }
        assertEquals("vivo OriginOS", vivo.romLabel)
    }

    // ── 反射读取系统属性 ──

    @Test
    fun `readSystemProperty returns null when unavailable`() {
        // JVM 单测环境无 android.os.SystemProperties（Android 运行时类），
        // 反射必然失败 —— 验证容错路径返回 null 而非抛异常
        assertNull(VendorRomAdapter.readSystemProperty("ro.miui.ui.version.name"))
    }
}

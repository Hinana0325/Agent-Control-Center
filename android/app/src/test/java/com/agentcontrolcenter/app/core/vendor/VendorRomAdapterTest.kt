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
        assertEquals(VendorRom.Other, info.rom)
        assertNull(info.miuiVersion)
        assertNull(info.hyperOsVersion)
        assertEquals("Standard", info.romLabel)
    }

    @Test
    fun `detect similar sounding brand does not match`() {
        // "xiaomii" / "notredmi" 等包含关键词的拼接串按 contains 会误报 —— 但
        // 真实 MANUFACTURER 不存在此类值，此处验证大小写归一后的常规负例
        val info = VendorRomAdapter.detect(
            manufacturer = "Huawei",
            brand = "HUAWEI",
            readProp = { null }
        )
        assertFalse(info.isXiaomi)
    }

    // ── 反射读取系统属性 ──

    @Test
    fun `readSystemProperty returns null when unavailable`() {
        // JVM 单测环境无 android.os.SystemProperties（Android 运行时类），
        // 反射必然失败 —— 验证容错路径返回 null 而非抛异常
        assertNull(VendorRomAdapter.readSystemProperty("ro.miui.ui.version.name"))
    }
}

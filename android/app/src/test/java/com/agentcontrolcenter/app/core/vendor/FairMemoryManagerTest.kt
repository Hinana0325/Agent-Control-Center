package com.agentcontrolcenter.app.core.vendor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FairMemoryManager 单元测试 — 契约常量验证（纯 JVM）。
 *
 * Binder transact / linkToDeath / Bundle 解析链路依赖 Android 运行时，
 * JVM 单测（无 Robolectric）只能验证与官方文档对齐的常量契约：
 * 广播 action、notifyType 语义值、result 码。数据布局与回调链路
 * 需在小米/vivo/OPPO/荣耀真机上以 `adb shell am broadcast` 验证。
 *
 * 规范来源：金标联盟《公平运行内存机制》+ 小米澎湃OS开发者平台
 * 《公平运行内存适配：开发者文档》(pId=2304)。
 */
class FairMemoryManagerTest {

    // ── 广播 action 契约（金标联盟统一规范，四家厂商一致） ──

    @Test
    fun `trim action matches itgsa specification`() {
        assertEquals("itgsa.intent.action.TRIM", FairMemoryManager.ACTION_TRIM)
    }

    @Test
    fun `kill action matches itgsa specification`() {
        assertEquals("itgsa.intent.action.KILL", FairMemoryManager.ACTION_KILL)
    }

    // ── notifyType 契约（common Bundle 语义字段值） ──

    @Test
    fun `notify type pss is 1000`() {
        // 官方文档：notifyType=1000 表示物理内存（PSS）异常，
        // extra 携带 pss/pssLimit
        assertEquals(1000, FairMemoryManager.NOTIFY_TYPE_PSS)
    }

    @Test
    fun `notify type heap is 2000`() {
        // 官方文档：notifyType=2000 表示 Java 堆内存异常，
        // extra 携带 heapAlloc/heapCapacity
        assertEquals(2000, FairMemoryManager.NOTIFY_TYPE_HEAP)
    }

    // ── 回调 result 契约 ──

    @Test
    fun `reply result ok is zero`() {
        // 官方示例：reply(notifyType, notifyId, 0, data) —— result=0 表示处理成功
        assertEquals(0, FairMemoryManager.RESULT_OK)
    }

    // ── 释放钩子注册 ──

    @Test
    fun `release hooks register without throwing`() {
        // 注册 API 契约：任意时刻可注册（CopyOnWriteArrayList，线程安全），
        // 注册本身不抛异常。钩子执行链路由厂商真机广播触发验证。
        FairMemoryManager.addReleaseHook("test_hook") { /* no-op */ }
    }
}

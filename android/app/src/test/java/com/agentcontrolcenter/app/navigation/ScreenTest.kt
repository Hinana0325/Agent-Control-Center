package com.agentcontrolcenter.app.navigation

import org.junit.Assert.*
import org.junit.Test

class ScreenTest {
    @Test
    fun `all screens have unique routes`() {
        val screens = listOf(
            Screen.Chat, Screen.Sessions, Screen.Activity, Screen.Settings,
            Screen.Agents, Screen.Marketplace, Screen.Insights, Screen.Workflow, Screen.Compare
        )
        val routes = screens.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `tabs contain exactly 5 screens`() {
        // v5.1 IA 重组（阶段 2）：主 Tab 由阶段 1 的 Chat/Sessions/Agents/Tasks/More
        // 改为 Chat/Agents/Activity/Marketplace/Settings。
        // Sessions/Tasks 从主 Tab 下沉为首页(ChatScreen)顶部入口融合；
        // Activity/Marketplace/Settings 从 More 上提为主 Tab；More Tab 移除。
        val expectedRoutes = setOf("chat", "agents", "activity", "marketplace", "settings")
        val actualRoutes = Screen.getTabs().map { it.route }.toSet()
        assertEquals("tabs should have 5 routes", 5, actualRoutes.size)
        assertEquals("tabs routes should match expected", expectedRoutes, actualRoutes)
    }

    @Test
    fun `all screens have string resource ids`() {
        val screens = listOf(
            Screen.Chat, Screen.Sessions, Screen.Activity, Screen.Settings,
            Screen.Agents, Screen.Marketplace, Screen.Insights, Screen.Workflow, Screen.Compare
        )
        screens.forEach { screen ->
            assertTrue("Screen ${screen.route} should have stringResId > 0", screen.stringResId > 0)
        }
    }
}

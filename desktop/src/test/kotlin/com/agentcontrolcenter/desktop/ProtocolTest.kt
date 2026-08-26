package com.agentcontrolcenter.desktop

import com.agentcontrolcenter.desktop.agent.model.AgentProtocol
import com.agentcontrolcenter.desktop.core.error.AppErrorCode
import com.agentcontrolcenter.desktop.data.model.MessageRole
import com.agentcontrolcenter.desktop.transport.TransportFactory
import com.agentcontrolcenter.desktop.transport.http.OpenAIHttpTransport
import com.agentcontrolcenter.desktop.transport.protocol.AgentTransport
import com.agentcontrolcenter.desktop.transport.websocket.WebSocketTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 协议层契约测试 — 与 Android 端同构用例对齐（AgentProtocolTest /
 * TransportFactoryTest / AppErrorCode 语义）。
 */
class ProtocolTest {

    // MARK: - AgentProtocol

    @Test
    fun `rawValue 与 iOS-Android 跨端 schema 一致`() {
        assertEquals("WebSocket", AgentProtocol.WebSocket.rawValue)
        assertEquals("HttpSSE", AgentProtocol.HttpSSE.rawValue)
        assertEquals("MCP", AgentProtocol.MCP.rawValue)
        assertEquals("Local", AgentProtocol.Local.rawValue)
    }

    @Test
    fun `fromRawValue 未匹配回退 WebSocket`() {
        assertEquals(AgentProtocol.WebSocket, AgentProtocol.fromRawValue(null))
        assertEquals(AgentProtocol.WebSocket, AgentProtocol.fromRawValue(""))
        assertEquals(AgentProtocol.WebSocket, AgentProtocol.fromRawValue("webSocket")) // iOS 历史 typo 统一回退
        assertEquals(AgentProtocol.HttpSSE, AgentProtocol.fromRawValue("HttpSSE"))
    }

    // MARK: - MessageRole

    @Test
    fun `apiValue 返回协议要求的 lowercase 角色名`() {
        assertEquals("user", MessageRole.User.apiValue)
        assertEquals("assistant", MessageRole.Assistant.apiValue)
        assertEquals("system", MessageRole.System.apiValue)
        assertEquals("tool", MessageRole.Tool.apiValue)
    }

    // MARK: - AppErrorCode

    @Test
    fun `错误码按 code 反查`() {
        assertEquals(AppErrorCode.TRANSPORT_CONNECT_FAILED, AppErrorCode.fromCode(1001))
        assertEquals(AppErrorCode.CRYPTO_E2E_KEY_MISMATCH, AppErrorCode.fromCode(10003))
        assertNull(AppErrorCode.fromCode(99999))
    }

    @Test
    fun `错误码无重复且总数为 37`() {
        val codes = AppErrorCode.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(37, codes.size)
    }

    // MARK: - TransportFactory

    @Test
    fun `WebSocket 类型路由到 WebSocketTransport`() {
        val factory = TransportFactory()
        assertIs<WebSocketTransport>(factory.create(com.agentcontrolcenter.desktop.agent.model.AgentType.Hermes))
        assertIs<WebSocketTransport>(factory.create(com.agentcontrolcenter.desktop.agent.model.AgentType.OpenCode))
        assertIs<WebSocketTransport>(factory.create(com.agentcontrolcenter.desktop.agent.model.AgentType.OpenClaw))
    }

    @Test
    fun `OpenAI 兼容类型路由到 OpenAIHttpTransport`() {
        val factory = TransportFactory()
        assertIs<OpenAIHttpTransport>(factory.create(com.agentcontrolcenter.desktop.agent.model.AgentType.OpenAI))
        assertIs<OpenAIHttpTransport>(factory.create(com.agentcontrolcenter.desktop.agent.model.AgentType.XiaomiMiMo))
        assertIs<OpenAIHttpTransport>(factory.create(com.agentcontrolcenter.desktop.agent.model.AgentType.LocalModel))
        assertIs<OpenAIHttpTransport>(factory.create(com.agentcontrolcenter.desktop.agent.model.AgentType.OpenWebUI))
    }

    @Test
    fun `ComfyUI 桌面 v1 优雅降级为可连接的占位传输`() {
        val transport: AgentTransport = TransportFactory()
            .create(com.agentcontrolcenter.desktop.agent.model.AgentType.ComfyUI)
        assertNotNull(transport)
    }
}

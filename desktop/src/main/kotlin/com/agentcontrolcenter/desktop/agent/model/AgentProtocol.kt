package com.agentcontrolcenter.desktop.agent.model

/**
 * Agent 通信协议类型 — 与 Android/iOS 端 `AgentProtocol` 完全对齐：
 * rawValue 字符串相同（"WebSocket" / "HttpSSE" / "MCP" / "Local"）。
 *
 * 用于 [com.agentcontrolcenter.desktop.transport.TransportFactory] 路由：
 * 决定走 WebSocket / OpenAI HTTP-SSE / MCP / 本地进程。
 */
@kotlinx.serialization.Serializable
enum class AgentProtocol(val rawValue: String, val displayName: String) {
    WebSocket("WebSocket", "WebSocket"),
    HttpSSE("HttpSSE", "HTTP SSE"),
    MCP("MCP", "MCP"),
    Local("Local", "Local");

    companion object {
        /**
         * 从存储的 rawValue 字符串解析，未匹配时回退到 [WebSocket]。
         */
        fun fromRawValue(value: String?): AgentProtocol {
            if (value.isNullOrBlank()) return WebSocket
            return entries.firstOrNull { it.rawValue == value } ?: WebSocket
        }
    }
}

package com.agentcontrolcenter.desktop.agent.model

/**
 * Agent 连接配置 — 与 Android `com.agentcontrolcenter.app.agent.model.AgentConfig`
 * 及 iOS `AgentConfig` 逐字段对齐（跨端 schema 兼容）。
 *
 * 桌面端持久化走 kotlinx-serialization（[com.agentcontrolcenter.desktop.data.persistence.JsonStore]），
 * 字段名即 JSON key，与协议 JSON Schema 一致。
 */
@kotlinx.serialization.Serializable
data class AgentConfig(
    val id: String = "default",
    val name: String = "Default Agent",
    val type: AgentType = AgentType.Hermes,
    val serverUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    // 跨端 schema 对齐：与 iOS AgentConfig.protocolType 字段一致
    // （AgentProtocol.rawValue：WebSocket / HttpSSE / MCP / Local）
    val protocolType: AgentProtocol = AgentProtocol.WebSocket
)

/**
 * Agent 类型枚举 — 用于 Transport 路由，与 Android/iOS 端 8 种类型完全对齐。
 */
@kotlinx.serialization.Serializable
enum class AgentType(val displayName: String) {
    Hermes("Hermes"),
    OpenCode("OpenCode"),
    OpenClaw("OpenClaw"),
    OpenAI("OpenAI Compatible"),
    XiaomiMiMo("Xiaomi MiMo"),
    LocalModel("Local Model"),
    ComfyUI("ComfyUI"),
    OpenWebUI("OpenWebUI")
}

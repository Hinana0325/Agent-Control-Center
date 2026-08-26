package com.agentcontrolcenter.desktop.agent.model

/**
 * Agent — 与 Android 端 `Agent` 对齐的统一模型。
 *
 * UI 与 Runtime 通过能力集调度，而非硬编码 Agent 类型。
 */
@kotlinx.serialization.Serializable
data class Agent(
    val id: String,
    val name: String,
    /** 连接端点（URL 或 host:port）。 */
    val endpoint: String = "",
    /** 当前连接状态。 */
    val status: AgentStatus = AgentStatus.Offline,
    /** 该 Agent 支持的能力集。 */
    val capabilities: List<AgentCapability> = listOf(AgentCapability.CHAT),
    /** 传输协议类型。 */
    val protocol: AgentProtocol = AgentProtocol.WebSocket
)

/** Agent 当前状态。 */
@kotlinx.serialization.Serializable
enum class AgentStatus(val displayName: String) {
    Online("Online"),
    Offline("Offline"),
    Connecting("Connecting"),
    Error("Error")
}

/** Agent 能力集。 */
@kotlinx.serialization.Serializable
enum class AgentCapability(val displayName: String) {
    CHAT("Chat"),
    TASK("Task Execution"),
    WORKFLOW("Workflow"),
    MCP("MCP Support"),
    FILESYSTEM("Filesystem"),
    TERMINAL("Terminal"),
    VOICE("Voice"),
    IMAGE_GEN("Image Generation"),
    CODE_EXECUTION("Code Execution")
}

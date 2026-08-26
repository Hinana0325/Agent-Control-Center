package com.agentcontrolcenter.desktop.agent.model

/**
 * Agent 连接状态（含运行时元数据）— 与 Android 端 `ConnectionState` 对齐。
 */
@kotlinx.serialization.Serializable
data class ConnectionState(
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val agentType: AgentType = AgentType.Hermes,
    val latency: Long = 0,
    val modelName: String = "",
    val sessionToken: String = "",
    val totalTokens: Long = 0
)

package com.agentcontrolcenter.desktop.transport.protocol

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.core.error.AppErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一的 Agent 传输层契约 — 与 Android 端 `AgentTransport` 完全对齐。
 *
 * 不同 [AgentType] 由 TransportFactory 路由到具体实现：
 *  - [com.agentcontrolcenter.desktop.transport.websocket.WebSocketTransport]
 *    : Hermes / OpenClaw / OpenCode（WebSocket 协议）
 *  - [com.agentcontrolcenter.desktop.transport.http.OpenAIHttpTransport]
 *    : OpenAI / OpenRouter / Ollama / LM Studio / Xiaomi MiMo / OpenWebUI（HTTP + SSE）
 *
 * 多轮对话历史管理：
 *  - WebSocket 传输：服务端通过 `sessionId` 维护会话状态，客户端仅需在每条消息中
 *    携带正确的 `sessionId`。[clearHistory] / [clearAllHistory] 仅清空本地展示缓存。
 *  - HTTP 传输：服务端无状态，多轮上下文由客户端维护并随请求一起发送。
 */
interface AgentTransport {
    val events: Flow<AgentEvent>
    val connectionState: StateFlow<AgentConnectionState>
    fun connect(config: AgentConfig, e2eKey: String? = null)
    suspend fun sendMessage(sessionId: String, content: String)
    fun disconnect()

    /**
     * 运行时热更新 E2E 密钥，无需断开重连。
     *
     * - `key == null`：关闭加密，后续消息以明文收发。
     * - `key != null`：开启加密，后续外发消息加密、收到的消息尝试解密。
     *
     * 默认实现为空操作：不适用 E2E 的传输（如 OpenAIHttpTransport）无需关心。
     */
    fun updateE2eKey(key: String?) { }

    /**
     * 彻底释放底层资源：取消协程作用域、关闭 HttpClient、关闭事件 Channel。
     *
     * 与 [disconnect] 的区别：[disconnect] 仅断开当前连接但保留实例以便重连；
     * [shutdown] 释放所有底层资源，调用后 transport 不再可用。
     */
    fun shutdown()

    /** 清空指定 [sessionId] 的本地会话历史（默认空操作）。 */
    suspend fun clearHistory(sessionId: String) { }

    /** 清空所有会话的本地历史（默认空操作）。 */
    suspend fun clearAllHistory() { }
}

sealed class AgentEvent {
    data class Connected(val serverUrl: String, val agentType: AgentType) : AgentEvent()
    data class Disconnected(val reason: String = "") : AgentEvent()
    data class MessageReceived(val content: String, val isDelta: Boolean = false) : AgentEvent()
    // 与 iOS AgentEvent.swift / Android AgentEvent 对齐：code/agentId 均带默认值
    data class Error(val message: String, val code: AppErrorCode? = null, val agentId: String? = null) : AgentEvent()
    data object Reconnecting : AgentEvent()

    /**
     * 流式响应已结束 — 上层收到此事件后应将 isStreaming 置为 false。
     */
    data object StreamComplete : AgentEvent()
}

data class AgentConnectionState(
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val agentType: AgentType = AgentType.Hermes,
    val latency: Long = 0
)

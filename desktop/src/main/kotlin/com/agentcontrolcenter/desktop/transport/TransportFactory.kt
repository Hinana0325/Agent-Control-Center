package com.agentcontrolcenter.desktop.transport

import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.core.error.AppErrorCode
import com.agentcontrolcenter.desktop.transport.http.OpenAIHttpTransport
import com.agentcontrolcenter.desktop.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.desktop.transport.protocol.AgentEvent
import com.agentcontrolcenter.desktop.transport.protocol.AgentTransport
import com.agentcontrolcenter.desktop.transport.websocket.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 按 [AgentType] 路由到具体的传输实现（与 Android 端 TransportFactory 对齐）。
 *
 * - Hermes / OpenClaw / OpenCode → [WebSocketTransport]（WebSocket 协议）
 * - OpenAI / XiaomiMiMo / LocalModel / OpenWebUI → [OpenAIHttpTransport]（HTTP + SSE）
 * - ComfyUI → [UnsupportedTransport]（桌面 v1 未移植图像生成工作流，
 *   优雅降级为连接时错误事件，见 docs/desktop-*.md 路线图）
 */
class TransportFactory {

    fun create(type: AgentType): AgentTransport = when (type) {
        AgentType.Hermes,
        AgentType.OpenClaw,
        AgentType.OpenCode -> WebSocketTransport()

        AgentType.OpenAI,
        AgentType.XiaomiMiMo,
        AgentType.LocalModel,
        AgentType.OpenWebUI -> OpenAIHttpTransport()

        AgentType.ComfyUI -> UnsupportedTransport(type)
    }
}

/**
 * 未支持类型的占位传输：connect() 立即发出带错误码的 Error 事件，
 * 不阻塞 UI 流程（用户在 Agents 页可见明确提示）。
 */
private class UnsupportedTransport(private val type: AgentType) : AgentTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    override fun connect(config: com.agentcontrolcenter.desktop.agent.model.AgentConfig, e2eKey: String?) {
        scope.launch {
            _events.send(
                AgentEvent.Error(
                    "${type.displayName} transport is not available on desktop yet (planned in a follow-up release)",
                    code = AppErrorCode.AGENT_NO_CAPABILITY,
                    agentId = config.id
                )
            )
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        _events.send(
            AgentEvent.Error(
                "${type.displayName} transport is not available on desktop yet",
                code = AppErrorCode.AGENT_NO_CAPABILITY
            )
        )
    }

    override fun disconnect() { }
    override fun shutdown() {
        scope.cancel()
        _events.close()
    }
}

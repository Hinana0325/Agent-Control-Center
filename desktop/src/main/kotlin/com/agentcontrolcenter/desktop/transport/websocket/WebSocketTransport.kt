package com.agentcontrolcenter.desktop.transport.websocket

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.core.error.AppErrorCode
import com.agentcontrolcenter.desktop.core.security.UrlValidator
import com.agentcontrolcenter.desktop.core.security.CryptoManager
import com.agentcontrolcenter.desktop.data.model.MessageRole
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.agentcontrolcenter.desktop.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.desktop.transport.protocol.AgentEvent
import com.agentcontrolcenter.desktop.transport.protocol.AgentTransport
import kotlin.random.Random

/**
 * WebSocket 传输层 — 与 Android 端 `WebSocketTransport` 逐逻辑对齐
 * （Ktor 同栈移植，鉴权帧 / 指数退避重连 / 心跳 / E2E 加解密全部保留）。
 *
 * 对应 Hermes / OpenClaw / OpenCode 等基于 `ws://host/ws` 的 Agent 服务。
 *
 * 多轮对话：WebSocket 服务端通过 `sessionId` 维护完整会话状态，客户端
 * 每条消息帧携带正确 `sessionId` 即可，无需重放历史。
 */
class WebSocketTransport(
    private val gson: Gson = Gson()
) : AgentTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    /**
     * Long-lived HttpClient：disconnect 不关闭（可反复重连），shutdown 才释放。
     */
    private val client = HttpClient {
        install(WebSockets)
    }

    /** 活跃 WebSocket 会话，[sessionMutex] 保护并发 connect/send/disconnect。 */
    private var session: WebSocketSession? = null
    private val sessionMutex = Mutex()

    private var connectJob: kotlinx.coroutines.Job? = null

    /**
     * 非空时对消息内容做 E2E 加解密（对等模式）。
     * @Volatile：connect/updateE2eKey 写入，receive 协程读取。
     */
    @Volatile
    private var e2eKey: String? = null

    /** 本地消息缓存（按 sessionId 分组），仅用于客户端展示与调试。 */
    private val localMessageCache: MutableMap<String, MutableList<CachedMessage>> = mutableMapOf()
    private val cacheLock = Any()

    /** 缓存条目：仅记录角色与（解密后的）明文内容。 */
    private data class CachedMessage(val role: MessageRole, val content: String)

    override fun connect(config: AgentConfig, e2eKey: String?) {
        this.e2eKey = e2eKey?.takeIf { it.isNotBlank() }
        connectJob?.cancel()
        connectJob = scope.launch {
            _connectionState.value = _connectionState.value.copy(
                serverUrl = config.serverUrl,
                agentType = config.type
            )
            // 先在锁内换出旧会话再开始新连接，保证清理先于新会话安装
            val old = sessionMutex.withLock {
                val cur = session
                session = null
                cur
            }
            old?.let { try { it.close() } catch (_: Exception) {} }
            connectLoop(config.serverUrl, config.apiKey)
        }
    }

    override fun updateE2eKey(key: String?) {
        e2eKey = key?.takeIf { it.isNotBlank() }
    }

    private suspend fun connectLoop(serverUrl: String, apiKey: String) {
        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws"

        // SSRF 防护：连接前校验目标 URL（防 metadata 服务探测 + apiKey 泄漏）
        if (UrlValidator.validate(wsUrl, allowLocalhost = true) == null) {
            _events.send(AgentEvent.Error("Invalid or blocked WebSocket URL", code = AppErrorCode.TRANSPORT_CONNECT_FAILED))
            return
        }

        var retryCount = 0
        val maxRetries = 3
        // 指数退避重连：1s 起步，每次翻倍，封顶 30s
        var retryDelay = 1000L

        while (currentCoroutineContext().isActive && retryCount < maxRetries) {
            var currentSession: WebSocketSession? = null
            try {
                _events.send(AgentEvent.Reconnecting)
                val startTime = System.currentTimeMillis()

                client.webSocket(wsUrl) {
                    currentSession = this
                    sessionMutex.withLock { session = this }
                    if (apiKey.isNotBlank()) {
                        // Gson 构造鉴权帧，防 apiKey 含引号时的 JSON 注入
                        val authFrame = JsonObject().apply {
                            addProperty("type", "auth")
                            addProperty("key", apiKey)
                        }
                        send(Frame.Text(gson.toJson(authFrame)))
                    }
                    val latency = System.currentTimeMillis() - startTime
                    _connectionState.value = _connectionState.value.copy(
                        isConnected = true,
                        latency = latency
                    )
                    _events.send(AgentEvent.Connected(serverUrl, _connectionState.value.agentType))

                    // 心跳：每 30s 发 ping 帧检测连接活性
                    val heartbeatJob = scope.launch {
                        while (currentCoroutineContext().isActive) {
                            delay(30_000)
                            if (session == null) break
                            try {
                                val pingFrame = JsonObject().apply {
                                    addProperty("type", "ping")
                                }
                                sessionMutex.withLock {
                                    session?.send(Frame.Text(gson.toJson(pingFrame)))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleMessage(frame.readText())
                            }
                        }
                    } finally {
                        heartbeatJob.cancel()
                    }
                }

                _events.send(AgentEvent.Disconnected())
                _connectionState.value = _connectionState.value.copy(isConnected = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retryCount++
                _events.send(AgentEvent.Error(
                    if (retryCount >= maxRetries) "Connection failed after $maxRetries attempts: ${e.message}"
                    else "Connection failed (retry $retryCount/$maxRetries): ${e.message}",
                    code = if (retryCount >= maxRetries) AppErrorCode.TRANSPORT_RECONNECT_FAILED else AppErrorCode.TRANSPORT_CONNECT_FAILED
                ))
                _connectionState.value = _connectionState.value.copy(isConnected = false)
                if (retryCount < maxRetries) {
                    // 指数退避 + 随机抖动（防惊群），100ms 切片保持可取消
                    val jitter = Random.nextLong(0, (retryDelay / 2).coerceAtLeast(1L))
                    var remaining = retryDelay + jitter
                    while (remaining > 0 && currentCoroutineContext().isActive) {
                        delay(minOf(remaining, 100L))
                        remaining -= 100L
                    }
                    retryDelay = (retryDelay * 2).coerceAtMost(30000L)
                }
            } finally {
                // 只清理本轮安装的会话，不误伤新 connect() 已安装的会话
                val mine = currentSession
                sessionMutex.withLock {
                    if (session === mine) session = null
                }
            }
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString
            when (type) {
                "message", "response" -> {
                    val raw = json.get("content")?.asString ?: ""
                    val delta = json.get("delta")?.asBoolean ?: false
                    // E2E：尝试解密；失败则原样展示（兼容非 E2E 对端）
                    val content = e2eKey?.let { CryptoManager.decrypt(raw, it) } ?: raw
                    _events.send(AgentEvent.MessageReceived(content, delta))
                    // 仅缓存完整（非增量）的助手回复，且帧须携带 sessionId
                    if (!delta && content.isNotEmpty()) {
                        val msgSessionId = json.get("sessionId")?.asString
                        if (!msgSessionId.isNullOrBlank()) {
                            cacheMessage(msgSessionId, MessageRole.Assistant, content)
                        }
                        // 完整帧后追加 StreamComplete，与 OpenAIHttpTransport 对齐
                        _events.send(AgentEvent.StreamComplete)
                    }
                }
                "error" -> {
                    val msg = json.get("message")?.asString ?: "Unknown error"
                    _events.send(AgentEvent.Error(msg))
                }
                "ping" -> { }
                else -> {
                    // 未知 type 不回显原文（防对端注入冒充 Agent 回复）
                    _events.send(AgentEvent.Error("Unknown frame type: $type", code = AppErrorCode.PROTOCOL_UNKNOWN_TYPE))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _events.send(AgentEvent.Error("Malformed frame from server: ${e.message ?: e.javaClass.simpleName}", code = AppErrorCode.PROTOCOL_PARSE_ERROR))
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        // E2E：发送前加密正文（对等模式；对端需持有相同密钥）
        val outContent = e2eKey?.let { CryptoManager.encrypt(content, it) } ?: content
        val message = JsonObject().apply {
            addProperty("type", "message")
            addProperty("sessionId", sessionId)
            addProperty("content", outContent)
            addProperty("role", MessageRole.User.name)
        }
        if (sessionId.isNotBlank() && content.isNotEmpty()) {
            cacheMessage(sessionId, MessageRole.User, content)
        }
        try {
            // 全程持锁，防并发 disconnect()/connect() 关闭会话
            sessionMutex.withLock {
                session?.send(Frame.Text(gson.toJson(message)))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _events.send(
                AgentEvent.Error("Failed to send message: ${e.message ?: e.javaClass.simpleName}", code = AppErrorCode.TRANSPORT_DISCONNECTED)
            )
        }
    }

    private fun cacheMessage(sessionId: String, role: MessageRole, content: String) {
        synchronized(cacheLock) {
            localMessageCache.getOrPut(sessionId) { mutableListOf() }
                .add(CachedMessage(role, content))
        }
    }

    override suspend fun clearHistory(sessionId: String) {
        synchronized(cacheLock) {
            localMessageCache.remove(sessionId)
        }
    }

    override suspend fun clearAllHistory() {
        synchronized(cacheLock) {
            localMessageCache.clear()
        }
    }

    override fun disconnect() {
        scope.launch {
            val old = sessionMutex.withLock {
                val cur = session
                session = null
                cur
            }
            try { old?.close() } catch (_: Exception) { }
            _connectionState.value = AgentConnectionState()
            _events.send(AgentEvent.Disconnected())
        }
    }

    override fun shutdown() {
        scope.cancel()
        _events.close()
        client.close()
        _connectionState.value = AgentConnectionState()
    }
}

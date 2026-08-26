package com.agentcontrolcenter.desktop.transport.http

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.core.error.AppErrorCode
import com.agentcontrolcenter.desktop.core.security.UrlValidator
import com.agentcontrolcenter.desktop.data.model.MessageRole
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.preparePost
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import com.agentcontrolcenter.desktop.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.desktop.transport.protocol.AgentEvent
import com.agentcontrolcenter.desktop.transport.protocol.AgentTransport
import java.util.logging.Level
import java.util.logging.Logger

/**
 * OpenAI 兼容 HTTP + SSE 传输层 — 与 Android 端 `OpenAIHttpTransport`
 * 逐逻辑对齐（Ktor + OkHttp 引擎同栈移植）。
 *
 * 覆盖 OpenAI / OpenRouter / Ollama / LM Studio / vLLM / Xiaomi MiMo / OpenWebUI —
 * 它们都暴露 OpenAI 格式的 `/v1/chat/completions` 端点。
 *
 * - 优先以 `stream:true` 发送，解析 SSE `data:` 行得到增量（delta）。
 * - 不支持 SSE 的端点（返回单个 JSON 完成包）自动回退为整段解析。
 * - HTTP 传输无持久连接：connect() 仅探活并标记就绪；disconnect() 置为离线。
 */
class OpenAIHttpTransport(
    private val gson: Gson = Gson()
) : AgentTransport {

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    private val client = HttpClient(OkHttp) {
        // 超时：connect 10s / 整请求（含 SSE 流）120s / socket 30s，
        // 防网络卡顿时 sendMessage 无限挂起导致 isStreaming 永久为 true
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 30_000
        }
    }

    private var currentConfig: AgentConfig? = null

    /** 内存中的对话消息结构，仅含 role 与 content，用于拼装请求体。 */
    private data class ConversationMessage(val role: String, val content: String)

    /** 按 sessionId 隔离的多轮对话历史（滑动窗口裁剪）。 */
    private val conversationHistory = mutableMapOf<String, MutableList<ConversationMessage>>()
    private val historyMutex = Mutex()

    /** 当前请求的助手回复累加器（sendMutex 串行化保护）。 */
    private val responseAccumulator = StringBuilder()

    /** 串行化 sendMessage，防并发请求撕裂累加器。 */
    private val sendMutex = Mutex()

    override fun connect(config: AgentConfig, e2eKey: String?) {
        // HTTP 传输：对端是 LLM 服务，需要明文请求体，E2E 不适用（忽略 e2eKey）
        currentConfig = config
        eventScope.launch {
            val reachable = probeEndpoint(config)
            if (reachable) {
                _connectionState.value = _connectionState.value.copy(
                    isConnected = true,
                    serverUrl = config.serverUrl,
                    agentType = config.type
                )
                _events.send(AgentEvent.Connected(config.serverUrl, config.type))
            } else {
                _connectionState.value = _connectionState.value.copy(isConnected = false)
                _events.send(AgentEvent.Error(
                    "Cannot connect to ${config.serverUrl}",
                    code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                ))
            }
        }
    }

    /**
     * 探测端点是否可达：GET {base}/v1/models。
     *  - 2xx：健康；401/403：需鉴权但端点存在；404：路径不存在但 chat 端点可能可用
     *  - 5xx / 异常：不可用
     */
    private suspend fun probeEndpoint(config: AgentConfig): Boolean {
        val base = config.serverUrl.trimEnd('/')
        // 兼容 /v1、/api/v1、/api 前缀（OpenWebUI 用 /api/v1/models）
        val probeUrl = when {
            base.endsWith("/v1") -> "$base/models"
            base.endsWith("/api/v1") -> "$base/models"
            base.endsWith("/api") -> "$base/v1/models"
            else -> "$base/v1/models"
        }
        // SSRF 防护：探活请求同样校验 URL（防 metadata 探测 + apiKey 泄漏）
        if (UrlValidator.validate(probeUrl, allowLocalhost = true) == null) {
            return false
        }
        return try {
            val response = withTimeout(5000) {
                client.get(probeUrl) {
                    if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            val status = response.status.value
            status in 200..299 || status == 401 || status == 403 || status == 404
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 构造 chat completions 端点 URL（兼容 /v1、/api/v1、/api 前缀）。
     */
    private fun buildChatCompletionsUrl(serverUrl: String): String {
        val base = serverUrl.trimEnd('/')
        return when {
            base.endsWith("/v1") -> "$base/chat/completions"
            base.endsWith("/api/v1") -> "$base/chat/completions"
            base.endsWith("/api") -> "$base/v1/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        sendMutex.withLock {
            val config = currentConfig ?: run {
                _events.send(AgentEvent.Error("Not connected", code = AppErrorCode.AGENT_CONFIG_MISSING))
                return
            }

            // 1. 追加本轮用户消息到历史，滑动窗口裁剪后取快照（同一把锁内原子完成）
            val messagesPayload: List<Map<String, String>> = historyMutex.withLock {
                val history = conversationHistory.getOrPut(sessionId) { mutableListOf() }
                history.add(ConversationMessage(MessageRole.User.apiValue, content))
                trimHistory(history)
                history.map { msg ->
                    mapOf("role" to msg.role, "content" to msg.content)
                }
            }

            // 2. 重置助手回复累加器
            responseAccumulator.setLength(0)

            val url = buildChatCompletionsUrl(config.serverUrl)
            // SSRF 防护：发送路径同样校验 URL（防 apiKey 泄漏）
            if (UrlValidator.validate(url, allowLocalhost = true) == null) {
                _events.send(AgentEvent.Error("Invalid or blocked server URL", code = AppErrorCode.TRANSPORT_CONNECT_FAILED))
                return
            }
            val requestBody = mapOf(
                "model" to config.model,
                "messages" to messagesPayload,
                "stream" to true,
                "temperature" to config.temperature,
                "max_tokens" to config.maxTokens
            )
            var streamSucceeded = false
            var attempt = 0
            val maxAttempts = 3
            var lastError: String? = null
            while (attempt < maxAttempts && !streamSucceeded) {
                attempt++
                try {
                    client.preparePost(url) {
                        header("Authorization", "Bearer ${config.apiKey}")
                        header("Content-Type", "application/json")
                        setBody(gson.toJson(requestBody))
                    }.execute { response ->
                        if (response.status != HttpStatusCode.OK) {
                            val errBody = response.bodyAsText().take(2000)
                            val code = response.status.value
                            // 4xx 客户端错误不重试
                            if (code in 400..499) {
                                _events.send(AgentEvent.Error(
                                    "HTTP $code: $errBody",
                                    code = if (code == 401 || code == 403) AppErrorCode.TRANSPORT_AUTH_FAILED else null
                                ))
                                _connectionState.value = _connectionState.value.copy(isConnected = false)
                                return@execute
                            }
                            // 5xx 服务端错误：可重试
                            if (attempt < maxAttempts) {
                                lastError = "HTTP $code (retry $attempt/$maxAttempts)"
                            } else {
                                _events.send(AgentEvent.Error("HTTP $code: $errBody"))
                                _connectionState.value = _connectionState.value.copy(isConnected = false)
                            }
                            return@execute
                        }
                        // 逐行流式解析 SSE，而非整体缓冲（降低首 token 延迟）
                        parseStream(response.bodyAsChannel())
                        streamSucceeded = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt < maxAttempts) {
                        lastError = "${e.message ?: e.javaClass.simpleName} (retry $attempt/$maxAttempts)"
                    } else {
                        _events.send(AgentEvent.Error(e.message ?: "Request failed", code = AppErrorCode.TRANSPORT_CONNECT_FAILED))
                    }
                }
                // 指数退避：1s, 2s, 4s...（仅还有下一次尝试时等待）
                if (!streamSucceeded && attempt < maxAttempts) {
                    val backoff = (1000L * (1 shl (attempt - 1))).coerceAtMost(8000L)
                    try {
                        delay(backoff)
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    }
                }
            }
            // 3. 流结束后把累加的助手回复写入历史
            saveAssistantResponse(sessionId)
            // 4. 通知上层流式响应已结束（重置 isStreaming）
            if (streamSucceeded) {
                _events.send(AgentEvent.StreamComplete)
            } else if (lastError != null) {
                _events.send(AgentEvent.Error("Request failed after $maxAttempts attempts: $lastError", code = AppErrorCode.TRANSPORT_CONNECT_FAILED))
            }
        }
    }

    private suspend fun saveAssistantResponse(sessionId: String) {
        val text = responseAccumulator.toString()
        responseAccumulator.setLength(0)
        if (text.isEmpty()) return
        historyMutex.withLock {
            val history = conversationHistory.getOrPut(sessionId) { mutableListOf() }
            history.add(ConversationMessage(MessageRole.Assistant.apiValue, text))
            trimHistory(history)
        }
    }

    /** 滑动窗口裁剪：保留最近 [MAX_HISTORY_MESSAGES] 条（必须在 historyMutex 内调用）。 */
    private fun trimHistory(messages: MutableList<ConversationMessage>) {
        val overflow = messages.size - MAX_HISTORY_MESSAGES
        if (overflow > 0) {
            repeat(overflow) { messages.removeAt(0) }
        }
    }

    override suspend fun clearHistory(sessionId: String) {
        historyMutex.withLock {
            conversationHistory.remove(sessionId)
        }
    }

    override suspend fun clearAllHistory() {
        historyMutex.withLock {
            conversationHistory.clear()
        }
        responseAccumulator.setLength(0)
    }

    /**
     * 逐行解析 SSE 流：
     *  - 连续 `data:` 行累积为单个事件，空行分发（SSE 规范）
     *  - `:` 开头为注释；`data: [DONE]` 终止
     *  - 非 `data:` 行回退为单个 JSON 缓冲（服务端忽略 stream:true 的场景）
     */
    private suspend fun parseStream(channel: ByteReadChannel) {
        val dataBuffer = StringBuilder()
        val singleJson = StringBuilder()
        var sawData = false

        suspend fun flushData(): Boolean {
            if (dataBuffer.isEmpty()) return true
            val data = dataBuffer.toString()
            dataBuffer.setLength(0)
            if (data == "[DONE]") return false
            emitDelta(data)
            return true
        }

        var keepGoing = true
        while (keepGoing && !channel.isClosedForRead) {
            @Suppress("DEPRECATION") // readUTF8Line handles CR/LF/CRLF per SSE spec.
            val rawLine = channel.readUTF8Line() ?: break
            val line = rawLine.removeSuffix("\r")

            if (line.isEmpty()) {
                keepGoing = flushData()
                continue
            }
            if (line.startsWith(":")) {
                continue
            }
            if (line.startsWith("data:")) {
                sawData = true
                var data = line.removePrefix("data:")
                if (data.startsWith(" ")) data = data.removePrefix(" ")
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(data)
            } else {
                singleJson.append(rawLine).append('\n')
            }
        }
        if (keepGoing) flushData()
        if (!sawData && singleJson.isNotBlank()) {
            emitFull(singleJson.toString())
        }
    }

    private suspend fun emitDelta(data: String) {
        try {
            val json = gson.fromJson(data, JsonObject::class.java) ?: return
            val choices = json.getAsJsonArray("choices") ?: return
            if (choices.size() == 0) return
            val choice = choices[0].asJsonObject
            val delta = choice.getAsJsonObject("delta")
            val text = delta?.get("content")?.asString ?: ""
            if (text.isNotEmpty()) {
                responseAccumulator.append(text)
                _events.send(AgentEvent.MessageReceived(text, isDelta = true))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 截断到 200 字符，防敏感内容全量落日志
            logger.log(Level.WARNING, "Failed to parse SSE delta: ${e.message}; data=${sanitizeForLog(data)}")
        }
    }

    private suspend fun emitFull(jsonText: String) {
        try {
            val json = gson.fromJson(jsonText, JsonObject::class.java) ?: return
            val choices = json.getAsJsonArray("choices") ?: return
            if (choices.size() == 0) return
            val choice = choices[0].asJsonObject
            val message = choice.getAsJsonObject("message")
            val text = message?.get("content")?.asString ?: ""
            if (text.isNotEmpty()) {
                responseAccumulator.append(text)
                _events.send(AgentEvent.MessageReceived(text, isDelta = false))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.log(Level.WARNING, "Failed to parse full JSON: ${e.message}; json=${sanitizeForLog(jsonText)}")
        }
    }

    override fun disconnect() {
        // 不关闭 client（connect() 不重建它，close 后将永久不可用）
        _connectionState.value = AgentConnectionState()
        eventScope.launch {
            clearAllHistory()
            _events.send(AgentEvent.Disconnected())
        }
    }

    override fun shutdown() {
        eventScope.cancel()
        _events.close()
        client.close()
        _connectionState.value = AgentConnectionState()
    }

    companion object {
        private val logger = Logger.getLogger(OpenAIHttpTransport::class.java.name)

        /** 每个 session 最多保留的历史消息条数（滑动窗口防 token 超限）。 */
        private const val MAX_HISTORY_MESSAGES = 20

        private const val MAX_LOG_PAYLOAD = 200

        internal fun sanitizeForLog(raw: String): String {
            val collapsed = raw.replace("\r", "\\r").replace("\n", "\\n")
            return if (collapsed.length <= MAX_LOG_PAYLOAD) {
                collapsed
            } else {
                collapsed.substring(0, MAX_LOG_PAYLOAD) + "..."
            }
        }
    }
}

package com.agentcontrolcenter.desktop.app

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.data.model.Message
import com.agentcontrolcenter.desktop.data.model.MessageRole
import com.agentcontrolcenter.desktop.data.model.MessageStatus
import com.agentcontrolcenter.desktop.data.model.Session
import com.agentcontrolcenter.desktop.core.security.CredentialVault
import com.agentcontrolcenter.desktop.core.security.CredentialVaultException
import com.agentcontrolcenter.desktop.data.persistence.AppSettings
import com.agentcontrolcenter.desktop.data.persistence.JsonStore
import com.agentcontrolcenter.desktop.transport.TransportFactory
import com.agentcontrolcenter.desktop.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.desktop.transport.protocol.AgentEvent
import com.agentcontrolcenter.desktop.transport.protocol.AgentTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 桌面端应用状态编排层 — 对应移动端 ChatViewModel + ConnectionRepository +
 * ChatRepository 三者的职责合并（单窗口桌面形态无需拆分到 Hilt 图）。
 *
 * 职责：
 *  - 会话/消息的内存态 + JsonStore 持久化
 *  - Agent 配置管理与活动连接的 transport 生命周期
 *  - 传输事件 → 消息流式增量拼装（delta 追加 / 整帧替换 / StreamComplete 落库）
 */
class AppStore(
    private val store: JsonStore,
    private val scope: CoroutineScope,
    private val transportFactory: TransportFactory = TransportFactory(),
    private val vault: CredentialVault = CredentialVault()
) {
    val settings = MutableStateFlow(store.loadSettings())

    /**
     * Agent 配置的内存态。**apiKey 在此保持明文**——传输层建连时直接使用它，
     * 与 Android「内存 domain model 明文 / 落库 entity 密文」同构。
     * 加解密只发生在持久化边界（[toPersisted] / [fromPersisted]）。
     */
    /** 落盘态快照（密文），供启动期的完整性检查与迁移复用，避免重复读文件。 */
    private val persistedAgents = store.loadAgents()
    val agents = MutableStateFlow(persistedAgents.map { it.fromPersisted() })
    val sessions = MutableStateFlow(store.loadSessions())

    val currentSessionId = MutableStateFlow(sessions.value.firstOrNull()?.id)
    val messages = MutableStateFlow<List<Message>>(emptyList())

    val connectionState = MutableStateFlow(AgentConnectionState())
    val activeAgentId = MutableStateFlow<String?>(null)
    val isStreaming = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)

    /** 流式中的助手消息 id（delta 追加目标）。 */
    private var streamingMessageId: String? = null

    private var transport: AgentTransport? = null
    private var eventJob: Job? = null
    private var stateJob: Job? = null

    init {
        // 已加密但解密失败的凭据：decryptOrRaw 已将其清空为 ""，
        // 这里显式告知用户需要重新填写，避免「API Key 莫名变空」无从排查。
        val undecryptable = persistedAgents
            .filter { it.apiKey.isNotBlank() && vault.isEncrypted(it.apiKey) && vault.decrypt(it.apiKey) == null }
        if (undecryptable.isNotEmpty()) {
            errorMessage.value = Strings.t("error.credential_decrypt")
        }
        scope.launch {
            reloadMessages()
            migratePlaintextCredentials()
        }
    }

    // MARK: - 凭据加解密（持久化边界）

    /** 落盘前：把明文 apiKey 转为 `AKS:` 密文（空白不做处理）。 */
    private fun List<AgentConfig>.toPersisted(): List<AgentConfig> = map { config ->
        if (config.apiKey.isBlank()) config else config.copy(apiKey = vault.encrypt(config.apiKey))
    }

    /** 载入后：把 `AKS:` 密文解回明文（旧版明文按 decryptOrRaw 原样保留）。 */
    private fun AgentConfig.fromPersisted(): AgentConfig =
        if (apiKey.isBlank()) this else copy(apiKey = vault.decryptOrRaw(apiKey))

    /**
     * 一次性迁移：把 v5.2.0 及更早明文落盘的 apiKey 转为 `AKS:` 密文。
     *
     * 依据 `SECURITY.md` §4.6 —— 旧版明文首次读取时保留，写入时自动转密文。
     * 这里在启动后主动重写一次，不必等用户下次编辑 Agent 才完成迁移。
     */
    private suspend fun migratePlaintextCredentials() {
        if (persistedAgents.none { it.apiKey.isNotBlank() && !vault.isEncrypted(it.apiKey) }) return
        runCatching { store.saveAgents(persistedAgents.toPersisted()) }
    }

    // MARK: - Settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.value)
        settings.value = next
        Strings.locale = next.locale
        scope.launch { store.saveSettings(next) }
        // E2E 口令热更新：不断开连接，后续收发立即使用新密钥
        transport?.updateE2eKey(next.e2ePassphrase.takeIf { it.isNotBlank() })
    }

    // MARK: - Agents

    fun saveAgent(config: AgentConfig) {
        val updated = (agents.value.filter { it.id != config.id } + config).sortedBy { it.name }
        // 加密失败 = 无法安全落盘。此时**拒绝保存**并上报，绝不降级为明文写入。
        val persisted = try {
            updated.toPersisted()
        } catch (_: CredentialVaultException) {
            errorMessage.value = Strings.t("error.credential_vault")
            return
        }
        agents.value = updated
        scope.launch { store.saveAgents(persisted) }
    }

    fun deleteAgent(id: String) {
        agents.value = agents.value.filter { it.id != id }
        if (activeAgentId.value == id) disconnect()
        val persisted = try {
            agents.value.toPersisted()
        } catch (_: CredentialVaultException) {
            errorMessage.value = Strings.t("error.credential_vault")
            return
        }
        scope.launch { store.saveAgents(persisted) }
    }

    fun connectAgent(config: AgentConfig) {
        disconnect()
        val t = transportFactory.create(config.type)
        transport = t
        activeAgentId.value = config.id
        eventJob = scope.launch {
            t.events.collect { handleEvent(it) }
        }
        stateJob = scope.launch {
            t.connectionState.collect { connectionState.value = it }
        }
        t.connect(config, settings.value.e2ePassphrase.takeIf { it.isNotBlank() })
    }

    fun disconnect() {
        eventJob?.cancel(); eventJob = null
        stateJob?.cancel(); stateJob = null
        transport?.let {
            try {
                it.disconnect()
            } catch (_: Exception) { }
            try {
                it.shutdown()
            } catch (_: Exception) { }
        }
        transport = null
        activeAgentId.value = null
        connectionState.value = AgentConnectionState()
        // 仅重置流式状态（无落库动作）
        streamingMessageId = null
        isStreaming.value = false
    }

    // MARK: - Sessions

    fun selectSession(id: String) {
        currentSessionId.value = id
        scope.launch { reloadMessages() }
    }

    fun createSession(): Session {
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = "Session ${sessions.value.size + 1}",
            createdAt = now,
            updatedAt = now
        )
        sessions.value = listOf(session) + sessions.value
        currentSessionId.value = session.id
        messages.value = emptyList()
        scope.launch {
            store.saveSessions(sessions.value)
            reloadMessages()
        }
        return session
    }

    fun deleteSession(id: String) {
        sessions.value = sessions.value.filter { it.id != id }
        if (currentSessionId.value == id) {
            currentSessionId.value = sessions.value.firstOrNull()?.id
            scope.launch { reloadMessages() }
        }
        scope.launch {
            store.saveSessions(sessions.value)
            store.deleteMessages(id)
        }
    }

    private suspend fun reloadMessages() {
        val sid = currentSessionId.value ?: run { messages.value = emptyList(); return }
        messages.value = store.loadMessages(sid)
    }

    // MARK: - Chat

    fun sendMessage(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val sid = currentSessionId.value ?: run {
            errorMessage.value = Strings.t("chat.no_session")
            return
        }
        val t = transport ?: run {
            errorMessage.value = Strings.t("error.not_connected")
            return
        }
        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            sessionId = sid,
            role = MessageRole.User,
            content = text,
            status = MessageStatus.Sent
        )
        messages.value = messages.value + userMessage
        isStreaming.value = true
        scope.launch {
            sessions.value = store.appendMessage(sid, userMessage, sessions.value)
            store.saveSessions(sessions.value)
            try {
                t.sendMessage(sid, text)
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "Send failed"
                finalizeStreaming(persist = false)
            }
        }
    }

    // MARK: - Event handling

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Connected -> errorMessage.value = null

            is AgentEvent.Reconnecting -> Unit

            is AgentEvent.Disconnected ->
                connectionState.value = connectionState.value.copy(isConnected = false)

            is AgentEvent.MessageReceived -> {
                val sid = currentSessionId.value ?: return
                if (event.isDelta) {
                    // 增量：追加到流式消息（懒创建占位条目）
                    val mid = streamingMessageId ?: UUID.randomUUID().toString()
                        .also { streamingMessageId = it }
                    val idx = messages.value.indexOfFirst { it.id == mid }
                    messages.value = if (idx >= 0) {
                        messages.value.toMutableList().apply {
                            set(idx, this[idx].copy(content = this[idx].content + event.content))
                        }
                    } else {
                        messages.value + Message(
                            id = mid, sessionId = sid,
                            role = MessageRole.Assistant, content = event.content,
                            status = MessageStatus.Received
                        )
                    }
                } else {
                    // 整帧：替换流式占位（或直接追加）
                    val mid = streamingMessageId
                    messages.value = if (mid != null) {
                        messages.value.map { if (it.id == mid) it.copy(content = event.content) else it }
                    } else {
                        messages.value + Message(
                            id = UUID.randomUUID().toString(), sessionId = sid,
                            role = MessageRole.Assistant, content = event.content,
                            status = MessageStatus.Received
                        )
                    }
                }
            }

            is AgentEvent.StreamComplete -> finalizeStreaming(persist = true)

            is AgentEvent.Error -> {
                errorMessage.value = event.message
                // 流中出错：终止流式状态（半截内容仍落库保留）
                if (isStreaming.value) finalizeStreaming(persist = true)
            }
        }
    }

    /**
     * 收尾流式消息：把累积的助手回复落库并重置流式状态。
     *
     * @param persist true=落库（正常完成/流中出错保留半截内容）；false=仅重置状态
     */
    private suspend fun finalizeStreaming(persist: Boolean) {
        val mid = streamingMessageId
        streamingMessageId = null
        isStreaming.value = false
        if (!persist) return
        val msg = mid?.let { id -> messages.value.firstOrNull { it.id == id } } ?: return
        if (msg.content.isNotBlank()) {
            sessions.value = store.appendMessage(msg.sessionId, msg, sessions.value)
            store.saveSessions(sessions.value)
        } else {
            // 空回复：从内存中移除占位条目
            messages.value = messages.value.filterNot { it.id == mid }
        }
    }

    fun clearError() {
        errorMessage.value = null
    }
}

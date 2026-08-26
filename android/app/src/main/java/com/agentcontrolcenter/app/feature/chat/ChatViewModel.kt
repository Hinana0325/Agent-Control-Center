package com.agentcontrolcenter.app.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentProtocol
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.agent.model.ConnectionState
import com.agentcontrolcenter.app.data.model.Message
import com.agentcontrolcenter.app.data.model.MessageRole
import com.agentcontrolcenter.app.data.model.MessageStatus
import com.agentcontrolcenter.app.data.model.Session
import com.agentcontrolcenter.app.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.app.transport.protocol.AgentEvent
import com.agentcontrolcenter.app.transport.ConnectionRepository
import com.agentcontrolcenter.app.core.datastore.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import com.agentcontrolcenter.app.AgentControlCenterWidget
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.core.common.PerformanceMonitor
import com.agentcontrolcenter.app.core.ui.VoiceInputManager
import com.agentcontrolcenter.app.runtime.notification.LocalNotificationManager
import com.agentcontrolcenter.app.widget.WidgetInputActivity
import com.agentcontrolcenter.app.widget.WidgetDataProvider
import javax.inject.Inject
import com.agentcontrolcenter.app.core.ui.VoiceChatManager
import com.agentcontrolcenter.app.localmodel.LocalModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val sessions: List<Session> = emptyList(),
    // 会话列表首次加载状态：首次从 Room 拿到数据前为 true，用于驱动骨架屏
    val isLoadingSessions: Boolean = true,
    val currentSessionId: String? = null,
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val connectionState: ConnectionState = ConnectionState(),
    val agentConfig: AgentConfig = AgentConfig(),
    val agentConfigs: List<AgentConfig> = emptyList(),
    val showWizard: Boolean = true,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    // Search state
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    // Voice state
    val isVoiceListening: Boolean = false,
    // Voice chat mode
    val isVoiceChatMode: Boolean = false,
    val voiceChatLastUserText: String = "",
    val voiceChatLastAgentText: String = "",
    // Local model state
    val localModels: List<LocalModelManager.LocalModel> = emptyList(),
    val isDiscoveringLocalModels: Boolean = false,
    val localModelError: String? = null,
    // Attachment state
    val pendingAttachmentType: String? = null,
    val pendingAttachmentData: String? = null,
    val pendingAttachmentName: String? = null,
    // Edit state
    val editingMessageId: String? = null,
    // Compare mode navigation trigger
    val comparePending: Boolean = false,
    // Reply state
    val replyingToMessageId: String? = null,
    val replyingToMessageContent: String? = null,
    // 操作结果反馈（用于 Snackbar 展示：复制/删除/清空等）
    val lastAction: String? = null
)


@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application,
    private val repository: com.agentcontrolcenter.app.data.repository.ChatRepository,
    private val settingsDataStore: SettingsDataStore,
    private val connectionRepository: ConnectionRepository
) : AndroidViewModel(application) {
    private var voiceInputManager: VoiceInputManager? = null
    private var voiceChatManager: VoiceChatManager? = null
    private val localModelManager = LocalModelManager()

    companion object {
        /**
         * I3: Agent 流式响应 Live Updates 通知使用的固定 ID。
         * 使用固定 ID 可在流式过程中反复更新同一条通知，结束时按此 ID 取消。
         */
        private const val LIVE_UPDATE_NOTIFICATION_ID = 1001

        /**
         * 单附件大小上限（压缩前，字节）。
         * 与 AppErrorCode.FILE_TOO_LARGE 的错误码描述文案保持一致；
         * 超限时拒绝挂载（聊天消息 UI 不渲染附件内容，仅落库存档）。
         */
        private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)

    /**
     * Phase 3.4: 统一 currentSessionId 更新入口。
     * 同时更新 [_currentSessionId]（驱动消息流 flatMapLatest）和
     * [_uiState] 的 currentSessionId（驱动 UI 显示），消除双源不一致风险。
     * 所有需要修改当前会话的位置必须通过本方法，禁止直接写 [_currentSessionId]
     * 或 `_uiState.update { it.copy(currentSessionId = ...) }`。
     */
    private fun setCurrentSession(sessionId: String?) {
        _currentSessionId.value = sessionId
        _uiState.update { it.copy(currentSessionId = sessionId) }
    }

    /**
     * 当前流式请求对应的 Coroutine Job，用于在用户点击 Stop 按钮时取消流式响应。
     * 在 [sendMessage] 开始流式时赋值，在流式完成/失败/取消时置 null。
     */
    private var streamingJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getAllSessions().collect { sessions ->
                // 首次拿到数据后关闭骨架屏加载状态
                _uiState.update { it.copy(sessions = sessions, isLoadingSessions = false) }
            }
        }
        viewModelScope.launch {
            repository.getAllConfigs().collect { configs ->
                _uiState.update { state ->
                    val firstConfig = configs.firstOrNull() ?: state.agentConfig
                    state.copy(agentConfig = firstConfig, agentConfigs = configs)
                }
            }
        }
        viewModelScope.launch {
            val savedConfig = repository.getAllConfigs().let { flow ->
                flow.first().firstOrNull { it.serverUrl.isNotBlank() && !it.id.startsWith("seed_") }
            }
            if (savedConfig != null) {
                _uiState.update { it.copy(showWizard = false) }
                connectWith(savedConfig)
            }
        }
        // Preload most recent session messages
        viewModelScope.launch {
            val sessions = repository.getAllSessionsList()
            val mostRecent = sessions.maxByOrNull { it.updatedAt }
            if (mostRecent != null) {
                setCurrentSession(mostRecent.id)
                _uiState.update { it.copy(showWizard = false) }
            }
        }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            connectionRepository.events.collect { event ->
                handleAgentEvent(event)
            }
        }
        viewModelScope.launch {
            connectionRepository.connectionState.collect { state ->
                handleConnectionState(state)
            }
        }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _currentSessionId.flatMapLatest { id ->
                if (id != null) repository.getMessagesBySession(id)
                else kotlinx.coroutines.flow.flowOf(emptyList())
            }.collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }

        // Sprint 8: 注册 Widget 快捷输入广播，接收 WidgetInputActivity 发来的消息
        registerWidgetMessageReceiver()

        // Critical 3 修复：消费冷启动时遗留的 Widget 待发送消息。
        // 当 App 进程被系统回收后，用户从 Widget 快捷输入发送的消息无法通过广播送达
        // （ChatViewModel 的接收器尚未注册）。WidgetInputActivity 已将消息持久化到
        // WidgetDataProvider，这里在 ViewModel 初始化时消费并发送，作为补偿通道。
        viewModelScope.launch {
            val pendingText = WidgetDataProvider.consumePendingInput(getApplication())
            if (!pendingText.isNullOrBlank()) {
                handleWidgetMessage(pendingText)
            }
        }
    }

    // Track when user message was sent for latency measurement
    private var lastUserMessageTime: Long = 0L

    /**
     * Sprint 8: 接收来自 [WidgetInputActivity] 的快捷输入消息。
     *
     * 生命周期：在 [init] 中通过 [registerWidgetMessageReceiver] 注册，
     * 在 [onCleared] 中注销。
     *
     * 架构约束：该接收器仅在 ChatViewModel 存活时生效（即 App 处于前台
     * 或进程未被回收）。若 App 已被系统冷启动回收，广播将丢失。后续可在
     * ChatViewModel 初始化时通过 WidgetDataProvider.consumePendingInput
     * 消费遗留消息作为补偿通道。
     */
    private val widgetMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(WidgetInputActivity.EXTRA_MESSAGE)
            if (!text.isNullOrBlank()) {
                handleWidgetMessage(text)
            }
        }
    }

    private fun registerWidgetMessageReceiver() {
        val filter = IntentFilter(WidgetInputActivity.ACTION_WIDGET_SEND_MESSAGE)
        // Android 13+ 要求显式声明 RECEIVER_NOT_EXPORTED 以接收同应用广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(widgetMessageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            getApplication<Application>().registerReceiver(widgetMessageReceiver, filter)
        }
    }

    private suspend fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageReceived -> {
                val sessionId = _uiState.value.currentSessionId
                if (sessionId == null) return

                // Record latency from user message to agent response
                if (lastUserMessageTime > 0) {
                    val latency = System.currentTimeMillis() - lastUserMessageTime
                    PerformanceMonitor.recordMessageLatency(latency)
                    lastUserMessageTime = 0L
                }

                if (event.isDelta) {
                    // Phase 1.3: 将 lastIdx/last 读取移入 _uiState.update lambda 内部，
                    // 保证原子性。此前在 lambda 外部读取，Room Flow 可能在读取与更新
                    // 之间发射新列表，导致索引越界或覆盖错误。
                    //
                    // 同时移除每个 delta 的 Room 写入（此前每次 delta 都调用
                    // repository.updateMessageStatus），改为仅在 StreamComplete 时
                    // 批量持久化，大幅减少流式期间的磁盘 I/O 与 Flow 重发。
                    val deltaContent = event.content
                    _uiState.update { state ->
                        val lastIdx = state.messages.lastIndex
                        if (lastIdx >= 0 && state.messages[lastIdx].role == MessageRole.Assistant) {
                            val last = state.messages[lastIdx]
                            val updated = state.messages.toMutableList()
                            updated[lastIdx] = last.copy(
                                content = last.content + deltaContent,
                                status = MessageStatus.Received
                            )
                            state.copy(messages = updated)
                        } else {
                            // 没有 assistant 消息可追加：创建一条新的（首个 delta）
                            state.copy(
                                messages = state.messages + Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = MessageRole.Assistant,
                                    content = deltaContent,
                                    status = MessageStatus.Received
                                )
                            )
                        }
                    }
                    // 不再在此处写 Room —— 由 StreamComplete 统一批量持久化。
                } else {
                    // Non-delta: persist via repository and use returned message (same ID) to avoid duplicates
                    val persistedMsg = repository.sendMessage(sessionId, event.content, MessageRole.Assistant)
                    repository.logActivity("message", "Agent response received", event.content.take(80))
                    _uiState.update { state ->
                        val filtered = state.messages.filter { it.id != persistedMsg.id }
                        state.copy(
                            messages = filtered + Message(
                                id = persistedMsg.id, sessionId = sessionId, role = MessageRole.Assistant,
                                content = event.content, status = MessageStatus.Received
                            ),
                            isStreaming = false
                        )
                    }
                    // I3: 非增量完整消息已到达，流式结束，取消 Live Updates 通知
                    cancelStreamingNotification()
                }
            }
            is AgentEvent.Error -> {
                repository.logActivity("error", "Agent error", event.message)
                _uiState.update { it.copy(isStreaming = false, isConnecting = false, errorMessage = event.message) }
                // I3: 流式出错，取消 Live Updates 通知
                cancelStreamingNotification()
            }
            is AgentEvent.Disconnected -> {
                repository.logActivity("connection", "Agent disconnected")
                _uiState.update { it.copy(isStreaming = false) }
                // I3: 连接断开，流式中止，取消 Live Updates 通知
                cancelStreamingNotification()
            }
            is AgentEvent.Connected -> {
                repository.logActivity("connection", "Agent connected")
                _uiState.update { it.copy(isConnecting = false, showWizard = false) }
            }
            is AgentEvent.Reconnecting -> {
                repository.logActivity("connection", "Agent reconnecting...")
            }
            is AgentEvent.StreamComplete -> {
                // HTTP SSE 纯增量流结束后由传输层发出此事件。
                // Phase 1.3: 在此统一持久化流式期间累加的助手回复，替代此前
                // 每个 delta 都写 Room 的高频 I/O 模式。同时重置 isStreaming。
                val messages = _uiState.value.messages
                val lastAssistant = messages.lastOrNull { it.role == MessageRole.Assistant }
                if (lastAssistant != null && lastAssistant.content.isNotEmpty()) {
                    repository.updateMessageStatus(
                        lastAssistant.id,
                        lastAssistant.content,
                        MessageStatus.Received
                    )
                    repository.logActivity("message", "Agent response received", lastAssistant.content.take(80))
                }
                _uiState.update { it.copy(isStreaming = false) }
                // I3: 流式完成，取消 Live Updates 通知
                cancelStreamingNotification()
            }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * 通过 [ConnectionRepository] 连接到指定 Agent。
     *
     * Repository 作为 @Singleton 持有唯一的 transport 实例，负责判断是否需要
     * 切换 transport 类型（Hermes ↔ OpenAI 等）以及旧 transport 的 [com.agentcontrolcenter.app.transport.protocol.AgentTransport.shutdown]。
     * 本方法仅负责读取 E2E 设置并委托给 repository。
     */
    private suspend fun connectWith(config: AgentConfig) {
        // E2E 仅对 WebSocket 对等传输（Hermes/OpenClaw/OpenCode）生效；
        // 通过 protocolType 判断比枚举列表更稳健（未来新增 WebSocket 类型无需改这里）。
        val e2eKey = if (config.protocolType == AgentProtocol.WebSocket) {
            val enabled = settingsDataStore.e2eEnabled.first()
            if (enabled) settingsDataStore.e2eKey.first().takeIf { it.isNotBlank() } else null
        } else null
        connectionRepository.connect(config, e2eKey = e2eKey)
    }

    private fun handleConnectionState(state: AgentConnectionState) {
        _uiState.update {
            it.copy(connectionState = ConnectionState(
                isConnected = state.isConnected,
                serverUrl = state.serverUrl,
                agentType = state.agentType,
                latency = state.latency
            ))
        }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val state = _uiState.value
        if ((text.isEmpty() && state.pendingAttachmentType == null) || state.isStreaming) return

        // If we're editing an existing message, update it instead of sending a new one
        val editingId = state.editingMessageId
        if (editingId != null) {
            editMessage(editingId, text)
            return
        }

        lastUserMessageTime = System.currentTimeMillis()

        streamingJob = viewModelScope.launch {
            val sessionId = state.currentSessionId
                ?: repository.createSession("New Chat").let { session ->
                    setCurrentSession(session.id)
                    session.id
                }

            val userMsg = repository.sendMessage(
                sessionId = sessionId,
                content = text,
                role = MessageRole.User,
                attachmentType = state.pendingAttachmentType,
                attachmentData = state.pendingAttachmentData,
                attachmentName = state.pendingAttachmentName,
                replyToId = state.replyingToMessageId
            )
            repository.logActivity("message", "Message sent", text.take(80))

            _uiState.update { s ->
                val stateMsg = Message(
                    id = userMsg.id,
                    sessionId = userMsg.sessionId,
                    role = MessageRole.User,
                    content = text,
                    // G4-2: 先置为 Sending，发送成功后更新为 Sent，失败更新为 Failed
                    status = MessageStatus.Sending,
                    attachmentType = state.pendingAttachmentType,
                    attachmentData = state.pendingAttachmentData,
                    attachmentName = state.pendingAttachmentName,
                    replyToId = state.replyingToMessageId
                )
                s.copy(
                    messages = s.messages + stateMsg,
                    inputText = "",
                    isStreaming = true,
                    pendingAttachmentType = null,
                    pendingAttachmentData = null,
                    pendingAttachmentName = null,
                    replyingToMessageId = null,
                    replyingToMessageContent = null
                )
            }

            if (_uiState.value.connectionState.isConnected) {
                // I3: 流式响应开始，显示 Live Updates 通知（Android 16+；低版本静默跳过）
                showStreamingNotification()
                // G4-2: 捕获网络发送异常，将消息状态置为 Failed 以支持重试。
                try {
                    connectionRepository.sendMessage(sessionId, text)
                    // 发送成功，更新状态为 Sent
                    _uiState.update { s ->
                        s.copy(messages = s.messages.map {
                            if (it.id == userMsg.id) it.copy(status = MessageStatus.Sent) else it
                        })
                    }
                } catch (e: Exception) {
                    // I3: 发送失败，流式不会进行，取消已显示的 Live Updates 通知
                    cancelStreamingNotification()
                    // 发送失败，更新状态为 Failed，供用户点击重试
                    val app = getApplication<Application>()
                    _uiState.update { s ->
                        s.copy(
                            messages = s.messages.map {
                                if (it.id == userMsg.id) it.copy(status = MessageStatus.Failed) else it
                            },
                            isStreaming = false,
                            errorMessage = app.getString(R.string.error_send_failed, e.message ?: app.getString(R.string.error_unknown))
                        )
                    }
                }
            } else {
                simulateResponse()
            }
            // 流式请求已发起（或离线模拟已完成）。正常结束时释放 Job 引用；
            // 若被 [stopStreaming] 取消则不会执行到此行，由其负责置空。
            streamingJob = null
        }
    }

    /**
     * 取消当前正在进行的流式响应。
     *
     * 由聊天界面的 Stop 按钮调用：当 [ChatUiState.isStreaming] 为 true 时，
     * 发送按钮会变身为 Stop 按钮，点击后调用本方法取消 [sendMessage] 启动的
     * Coroutine Job，并将 [ChatUiState.isStreaming] 置为 false，使输入栏恢复可用。
     */
    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _uiState.update { it.copy(isStreaming = false) }
        // I3: 用户主动停止流式响应，取消 Live Updates 通知
        cancelStreamingNotification()
    }

    /**
     * I3: 显示 Agent 流式响应的 Live Updates 通知（Android 16+）。
     *
     * 在流式开始时调用，使用不确定进度（indeterminate）样式提示用户「Agent 正在响应」。
     * 低于 Android 16 的设备由 [LocalNotificationManager.notifyLiveUpdate] 内部静默跳过，
     * 不会影响现有通知行为。
     */
    private fun showStreamingNotification() {
        // 低于 Android 16 的设备不支持 Live Updates；notifyLiveUpdate 内部亦会跳过。
        // 此处提前 return 以满足 lint 对 @RequiresApi(BAKLAVA) 调用的约束，
        // 避免在 minSdk 设备上触发 NewApi lint 错误。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        try {
            val app = getApplication<Application>()
            LocalNotificationManager.getInstance(app).notifyLiveUpdate(
                notificationId = LIVE_UPDATE_NOTIFICATION_ID,
                title = app.getString(R.string.app_name),
                content = "Agent is responding…",
                progress = 0,
                indeterminate = true
            )
        } catch (_: Exception) {
            // 通知不可用不影响流式响应本身
        }
    }

    /**
     * I3: 取消 Agent 流式响应的 Live Updates 通知。
     *
     * 在流式完成/失败/断开/用户取消时调用。即使未曾显示通知（如低版本设备或权限缺失），
     * cancel 也是幂等的，安全可调用。
     */
    private fun cancelStreamingNotification() {
        try {
            LocalNotificationManager.getInstance(getApplication())
                .cancel(LIVE_UPDATE_NOTIFICATION_ID)
        } catch (_: Exception) {
            // 取消失败可忽略
        }
    }

    /**
     * G4-2: 重试发送此前失败的消息。
     *
     * 由 [MessageBubble] 的重试按钮调用：当消息状态为 [MessageStatus.Failed] 时，
     * 将状态置回 [MessageStatus.Sending] 并重新通过 [connectionRepository] 发送。
     * 成功则更新为 [MessageStatus.Sent]，失败则再次置为 [MessageStatus.Failed]。
     */
    fun retrySendMessage(messageId: String) {
        val failedMessage = _uiState.value.messages.find { it.id == messageId } ?: return
        // 仅允许重试 User 角色且 Failed 状态的消息
        if (failedMessage.role != MessageRole.User || failedMessage.status != MessageStatus.Failed) return
        val sessionId = failedMessage.sessionId
        val content = failedMessage.content
        // 先更新状态为 Sending，提示用户正在重试
        _uiState.update { state ->
            state.copy(messages = state.messages.map {
                if (it.id == messageId) it.copy(status = MessageStatus.Sending) else it
            })
        }
        viewModelScope.launch {
            try {
                connectionRepository.sendMessage(sessionId, content)
                // 重试成功，更新为 Sent
                _uiState.update { state ->
                    state.copy(messages = state.messages.map {
                        if (it.id == messageId) it.copy(status = MessageStatus.Sent) else it
                    })
                }
            } catch (e: Exception) {
                // 重试仍失败，置回 Failed 并提示错误
                val app = getApplication<Application>()
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map {
                            if (it.id == messageId) it.copy(status = MessageStatus.Failed) else it
                        },
                        errorMessage = app.getString(R.string.error_retry_failed, e.message ?: app.getString(R.string.error_unknown))
                    )
                }
            }
        }
    }

    private suspend fun simulateResponse() {
        kotlinx.coroutines.delay(500)
        val sessionId = _uiState.value.currentSessionId ?: return
        val reply = "Hello! You said: \"${_uiState.value.messages.lastOrNull()?.content}\"\n\n" +
                "> Offline mode — connect to an agent server for real AI responses."

        repository.sendMessage(sessionId, reply, MessageRole.Assistant).let { msg ->
            _uiState.update { state ->
                // Deduplicate: use returned message ID to avoid Room flow creating a second copy
                val filtered = state.messages.filter { it.id != msg.id }
                state.copy(
                    messages = filtered + Message(
                        id = msg.id, sessionId = sessionId, role = MessageRole.Assistant,
                        content = reply, status = MessageStatus.Received
                    ),
                    isStreaming = false
                )
            }
        }
    }

    // ── Message Edit ──

    fun startEditMessage(messageId: String, content: String) {
        _uiState.update { it.copy(inputText = content, editingMessageId = messageId) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editingMessageId = null) }
    }

    // ── Message Reply ──

    fun startReply(messageId: String, content: String) {
        _uiState.update { it.copy(
            replyingToMessageId = messageId,
            replyingToMessageContent = content.trim().take(100)
        ) }
    }

    fun cancelReply() {
        _uiState.update { it.copy(
            replyingToMessageId = null,
            replyingToMessageContent = null
        ) }
    }

    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            repository.updateMessageStatus(messageId, newContent, MessageStatus.Sent)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map {
                        if (it.id == messageId) it.copy(content = newContent) else it
                    },
                    inputText = "",
                    editingMessageId = null
                )
            }
        }
    }

    fun clearMessages() {
        // 先取消正在进行的流式响应，否则清空后新内容仍会继续追加到最后一条消息。
        stopStreaming()
        viewModelScope.launch {
            _uiState.value.currentSessionId?.let { id ->
                // 清空传输层中当前 session 的对话历史，确保下次发送的消息
                // 不会携带清除前的上下文（/clear 命令的语义是「重新开始」）。
                connectionRepository.clearHistory(id)
                repository.deleteMessagesBySession(id)
            }
        }
        _uiState.update {
            it.copy(
                messages = emptyList(),
                errorMessage = null,
                lastAction = getApplication<Application>().getString(R.string.chat_cleared)
            )
        }
    }

    fun dismissWizard() {
        _uiState.update { it.copy(showWizard = false, isConnecting = false) }
    }

    /** 打开连接向导（供离线横幅的「去连接」按钮调用）。 */
    fun openConnectWizard() {
        _uiState.update { it.copy(showWizard = true) }
    }

    fun connectToServer(url: String, apiKey: String, agentType: AgentType) {
        viewModelScope.launch {
            val existing = _uiState.value.agentConfigs.firstOrNull { it.serverUrl == url }
            val config = if (existing != null) {
                existing.copy(apiKey = apiKey, type = agentType)
            } else {
                AgentConfig(
                    id = java.util.UUID.randomUUID().toString(),
                    name = agentType.displayName,
                    type = agentType,
                    serverUrl = url,
                    apiKey = apiKey
                )
            }
            repository.saveConfig(config)

            connectWith(config)
            _uiState.update { state ->
                state.copy(
                    isConnecting = true,
                    showWizard = true,
                    errorMessage = null,
                    agentConfig = config
                )
            }
            repository.logActivity("connection", "Connecting to ${config.serverUrl}", config.type.displayName)
        }
    }

    // ── Session management ──

    fun createNewSession() {
        viewModelScope.launch {
            val session = repository.createSession("New Chat")
            setCurrentSession(session.id)
        }
    }

    fun switchToSession(sessionId: String) {
        // 切换会话前清除上一个会话的残留状态，避免编辑/回复/附件/流式等
        // 状态泄漏到新会话。先取消流式 Job，再重置 UI 状态。
        stopStreaming()
        setCurrentSession(sessionId)
        _uiState.update {
            it.copy(
                isStreaming = false,
                editingMessageId = null,
                replyingToMessageId = null,
                replyingToMessageContent = null,
                pendingAttachmentType = null,
                pendingAttachmentData = null,
                pendingAttachmentName = null,
                inputText = ""
            )
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            // 清空传输层中该 session 的对话历史，避免历史残留导致下次
            // 同 sessionId（若被复用）的请求携带已删除会话的上下文。
            // transport 可能为 null（尚未连接），安全调用会跳过；
            // OpenAIHttpTransport 清空客户端历史，WebSocketTransport 清空本地缓存。
            connectionRepository.clearHistory(sessionId)
            repository.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) {
                val sessions = _uiState.value.sessions
                val next = sessions.firstOrNull { it.id != sessionId }
                if (next != null) {
                    switchToSession(next.id)
                } else {
                    setCurrentSession(null)
                }
            }
        }
    }

    fun toggleSessionPin(sessionId: String, isPinned: Boolean) {
        viewModelScope.launch {
            repository.togglePin(sessionId, isPinned)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Copy text to the system clipboard.
     */
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Agent Control Center Message", text)
        clipboard.setPrimaryClip(clip)
        _uiState.update { it.copy(lastAction = getApplication<Application>().getString(R.string.copied_to_clipboard)) }
    }

    /**
     * Delete a single message by id.
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.filter { it.id != messageId },
                    lastAction = getApplication<Application>().getString(R.string.message_deleted)
                )
            }
        }
    }

    /**
     * 清除 lastAction，避免配置变更（如旋转屏）后 Snackbar 重复弹出。
     */
    fun clearLastAction() {
        _uiState.update { it.copy(lastAction = null) }
    }

    /**
     * Refresh sessions list from repository.
     */
    fun refreshSessions(): Job {
        return viewModelScope.launch {
            // Force re-read from Room to trigger the Flow emission
            val sessions = repository.getAllSessionsList()
            _uiState.update { it.copy(sessions = sessions) }
            delay(300) // Brief delay for pull-to-refresh indicator visibility
        }
    }

    // ── Session swipe navigation ──

    fun switchToNextSession() {
        val sessions = _uiState.value.sessions
        val currentId = _uiState.value.currentSessionId ?: return
        val currentIndex = sessions.indexOfFirst { it.id == currentId }
        if (currentIndex >= 0 && currentIndex < sessions.size - 1) {
            switchToSession(sessions[currentIndex + 1].id)
        }
    }

    fun switchToPreviousSession() {
        val sessions = _uiState.value.sessions
        val currentId = _uiState.value.currentSessionId ?: return
        val currentIndex = sessions.indexOfFirst { it.id == currentId }
        if (currentIndex > 0) {
            switchToSession(sessions[currentIndex - 1].id)
        }
    }

    // ── Message reactions ──

    private val reactionEmojis = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDD25", "\uD83C\uDF89", "\uD83D\uDC40")

    fun toggleMessageReaction(messageId: String) {
        viewModelScope.launch {
            val currentMessage = _uiState.value.messages.find { it.id == messageId } ?: return@launch
            val currentReaction = currentMessage.reaction
            val nextReaction = if (currentReaction.isEmpty()) {
                reactionEmojis[0]
            } else {
                val idx = reactionEmojis.indexOf(currentReaction)
                if (idx >= 0 && idx < reactionEmojis.size - 1) {
                    reactionEmojis[idx + 1]
                } else {
                    "" // cycle back to no reaction
                }
            }
            repository.updateReaction(messageId, nextReaction)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map {
                        if (it.id == messageId) it.copy(reaction = nextReaction) else it
                    }
                )
            }
        }
    }

    // ── Command execution ──

    fun executeCommand(command: String): Boolean {
        when (command) {
            "/clear" -> {
                clearMessages()
                return true
            }
            "/new" -> {
                createNewSession()
                return true
            }
            "/search" -> {
                openSearch()
                return true
            }
            "/reconnect" -> {
                val config = _uiState.value.agentConfig
                if (config.serverUrl.isNotBlank()) {
                    viewModelScope.launch { connectWith(config) }
                    _uiState.update { it.copy(isConnecting = true) }
                }
                return true
            }
            "/model" -> {
                // Cycle to next agent config
                val configs = _uiState.value.agentConfigs
                if (configs.size > 1) {
                    val currentId = _uiState.value.agentConfig.id
                    val currentIdx = configs.indexOfFirst { it.id == currentId }
                    val nextIdx = (currentIdx + 1) % configs.size
                    val nextConfig = configs[nextIdx]
                    viewModelScope.launch {
                        connectWith(nextConfig)
                        _uiState.update { it.copy(agentConfig = nextConfig, isConnecting = true) }
                    }
                }
                return true
            }
            "/export" -> {
                // Export handled by SettingsViewModel; signal via activity log
                viewModelScope.launch {
                    repository.logActivity("command", "Export requested via /export command")
                }
                return true
            }
            "/help" -> {
                val helpText = "Available commands:\n" +
                    "/clear — Clear current chat\n" +
                    "/new — Create new session\n" +
                    "/search — Search messages\n" +
                    "/reconnect — Reconnect to agent\n" +
                    "/model — Switch to next agent\n" +
                    "/export — Export chat history\n" +
                    "/help — Show this help"
                viewModelScope.launch {
                    val sessionId = _uiState.value.currentSessionId
                        ?: repository.createSession("Help").let { session ->
                            setCurrentSession(session.id)
                            session.id
                        }
                    repository.sendMessage(sessionId, helpText, MessageRole.Assistant)
                }
                return true
            }
            "/compare" -> {
                _uiState.update { it.copy(comparePending = true) }
                return true
            }
            else -> return false
        }
    }

    // ── Search ──

    fun openSearch() {
        _uiState.update { it.copy(isSearchActive = true, searchQuery = "", searchResults = emptyList()) }
    }

    fun closeSearch() {
        _uiState.update { it.copy(isSearchActive = false, searchQuery = "", searchResults = emptyList()) }
    }

    fun searchMessages(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            val results = repository.searchMessages(query)
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    // ── Share Intent ──

    fun handleSharedText(text: String) {
        viewModelScope.launch {
            val sessionId = _uiState.value.currentSessionId
                ?: repository.createSession("Shared Content").let { session ->
                    setCurrentSession(session.id)
                    session.id
                }
            val userMsg = repository.sendMessage(sessionId, text, MessageRole.User)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + Message(
                        id = userMsg.id,
                        sessionId = userMsg.sessionId,
                        role = MessageRole.User,
                        content = text,
                        status = MessageStatus.Sent
                    ),
                    isStreaming = true
                )
            }
            if (_uiState.value.connectionState.isConnected) {
                connectionRepository.sendMessage(sessionId, text)
            } else {
                simulateResponse()
            }
        }
    }

    /**
     * Sprint 8: 处理来自 [WidgetInputActivity] 的快捷输入消息。
     *
     * 行为与 [handleSharedText] 一致：创建/复用 session、持久化 user 消息、
     * 通过 transport 发送或离线模拟回复。完成后刷新 Widget 以显示最新消息
     * （对应需求"Widget 更新为 sent 状态"）。
     */
    fun handleWidgetMessage(text: String) {
        viewModelScope.launch {
            val sessionId = _uiState.value.currentSessionId
                ?: repository.createSession("Widget Message").let { session ->
                    setCurrentSession(session.id)
                    session.id
                }
            val userMsg = repository.sendMessage(sessionId, text, MessageRole.User)
            repository.logActivity("widget", "Message sent from Widget", text.take(80))
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + Message(
                        id = userMsg.id,
                        sessionId = userMsg.sessionId,
                        role = MessageRole.User,
                        content = text,
                        status = MessageStatus.Sent
                    ),
                    isStreaming = true
                )
            }
            if (_uiState.value.connectionState.isConnected) {
                connectionRepository.sendMessage(sessionId, text)
            } else {
                simulateResponse()
            }
            // 刷新所有 Widget 实例，使其显示最新发送的消息
            AgentControlCenterWidget.updateAll(getApplication())
        }
    }

    /**
     * Critical 1 修复：处理来自通知内联回复的消息。
     *
     * 此前 MainActivity.handleReplyIntent 读取 EXTRA_REPLY_TEXT 后立即 removeExtra，
     * 但 ChatViewModel 从未读取该文本，导致回复被永久丢弃。现 MainActivity 将回复文本
     * 转发到本方法，行为与 [handleWidgetMessage] 一致：创建/复用 session、持久化 user 消息、
     * 通过 transport 发送或离线模拟回复。
     */
    fun handleNotificationReply(text: String) {
        viewModelScope.launch {
            val sessionId = _uiState.value.currentSessionId
                ?: repository.createSession("Notification Reply").let { session ->
                    setCurrentSession(session.id)
                    session.id
                }
            val userMsg = repository.sendMessage(sessionId, text, MessageRole.User)
            repository.logActivity("notification", "Message sent from notification reply", text.take(80))
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + Message(
                        id = userMsg.id,
                        sessionId = userMsg.sessionId,
                        role = MessageRole.User,
                        content = text,
                        status = MessageStatus.Sent
                    ),
                    isStreaming = true
                )
            }
            if (_uiState.value.connectionState.isConnected) {
                connectionRepository.sendMessage(sessionId, text)
            } else {
                simulateResponse()
            }
        }
    }

    // ── Voice Input ──

    fun initVoiceInput(context: Context) {
        if (voiceInputManager == null) {
            // F23 修复：原代码直接传入 Activity Context（来自 LocalContext.current），
            // VoiceInputManager 会将其强引用为字段。ViewModel 生命周期长于 Activity，
            // 导致 Activity 无法被 GC 回收（内存泄漏）。改用 applicationContext。
            // SpeechRecognizer / TextToSpeech 均兼容 Application Context。
            voiceInputManager = VoiceInputManager(context.applicationContext)
        }
    }

    fun toggleVoiceInput() {
        val manager = voiceInputManager ?: return
        if (_uiState.value.isVoiceListening) {
            manager.stopListening()
            _uiState.update { it.copy(isVoiceListening = false) }
        } else {
            manager.startListening()
            _uiState.update { it.copy(isVoiceListening = true) }
        }
    }

    fun stopVoiceInput() {
        voiceInputManager?.stopListening()
        _uiState.update { it.copy(isVoiceListening = false) }
    }

    fun getVoiceInputManager(): VoiceInputManager? = voiceInputManager

    // ── Attachment ──

    fun setPendingAttachment(context: Context, uri: android.net.Uri, isImage: Boolean) {
        viewModelScope.launch {
            try {
                // H-A2 修复：原代码在 viewModelScope.launch 默认 Main 调度器上做
                // ContentResolver.openInputStream / readBytes / BitmapFactory.decodeByteArray
                // 等磁盘 + CPU 密集型操作，会阻塞 UI 线程导致卡顿甚至 ANR。
                // 将整段 I/O + Bitmap 处理移到 Dispatchers.IO，
                // 仅在结束后再回到 Main 更新 _uiState。
                //
                // 返回值约定：Pair<状态码, Triple<type, base64, name>?>
                //   "ok"        → 第二项为 Triple
                //   "too_large" → 第二项为 null（UI 显示错误消息）
                //   "empty"     → 第二项为 null（原实现静默忽略，保持兼容）
                val result: Pair<String, Triple<String, String, String>?> = withContext(Dispatchers.IO) {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes == null) {
                        return@withContext "empty" to null
                    }
                    // Warn if attachment is very large
                    if (bytes.size > MAX_ATTACHMENT_BYTES) {
                        return@withContext "too_large" to null
                    }
                    val finalBytes = if (isImage && bytes.size > 1_000_000) {
                        try {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val maxDim = 720
                                val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
                                if (scale < 1f) {
                                    val scaled = Bitmap.createScaledBitmap(
                                        bitmap,
                                        (bitmap.width * scale).toInt(),
                                        (bitmap.height * scale).toInt(),
                                        true
                                    )
                                    val out = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    if (scaled !== bitmap) scaled.recycle()
                                    bitmap.recycle()
                                    out.toByteArray()
                                } else {
                                    val out = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    bitmap.recycle()
                                    out.toByteArray()
                                }
                            } else bytes
                        } catch (_: Exception) { bytes }
                    } else bytes
                    val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
                    val name = uri.lastPathSegment ?: if (isImage) "image" else "file"
                    "ok" to Triple(
                        if (isImage) "image" else "file",
                        base64,
                        name
                    )
                }
                when (result.first) {
                    "too_large" -> {
                        _uiState.update { it.copy(errorMessage = "Attachment too large (>10MB)") }
                    }
                    "ok" -> {
                        val payload = result.second
                        if (payload != null) {
                            _uiState.update {
                                it.copy(
                                    pendingAttachmentType = payload.first,
                                    pendingAttachmentData = payload.second,
                                    pendingAttachmentName = payload.third
                                )
                            }
                        }
                    }
                    "empty" -> { /* bytes == null：原实现静默忽略，保持兼容 */ }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load attachment") }
            }
        }
    }

    fun clearPendingAttachment() {
        _uiState.update {
            it.copy(
                pendingAttachmentType = null,
                pendingAttachmentData = null,
                pendingAttachmentName = null
            )
        }
    }

    // ── Marketplace ──

    fun installMarketplaceAgent(config: AgentConfig) {
        viewModelScope.launch {
            repository.saveConfig(config)
            repository.logActivity("marketplace", "Agent installed", config.name)
        }
    }

    // ── Voice Chat Mode (full conversation) ──

    fun initVoiceChatMode(context: Context) {
        if (voiceChatManager == null) {
            // F23 修复：同 initVoiceInput，改用 applicationContext 避免 Activity 泄漏。
            voiceChatManager = VoiceChatManager(context.applicationContext).apply {
                onSpeechResult = { text ->
                    // 仅记录最近一次识别结果用于界面展示；实际发送统一由
                    // VoiceChatOverlay 的 LaunchedEffect(state.recognizedText)
                    // → onSendMessage 触发，避免此处再调 sendMessage() 造成双发。
                    _uiState.update { it.copy(voiceChatLastUserText = text) }
                }
            }
        }
    }

    fun enterVoiceChatMode() {
        val manager = voiceChatManager ?: return
        manager.startVoiceMode()
        _uiState.update { it.copy(isVoiceChatMode = true) }
    }

    fun exitVoiceChatMode() {
        val manager = voiceChatManager ?: return
        manager.stopVoiceMode()
        _uiState.update { it.copy(isVoiceChatMode = false, voiceChatLastUserText = "", voiceChatLastAgentText = "") }
    }

    fun getVoiceChatManager(): VoiceChatManager? = voiceChatManager

    fun speakAgentResponse(text: String) {
        voiceChatManager?.speak(text)
        _uiState.update { it.copy(voiceChatLastAgentText = text) }
    }

    // ── Local Model ──

    fun discoverLocalModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringLocalModels = true) }
            val models = localModelManager.discoverModels()
            _uiState.update {
                it.copy(
                    localModels = models,
                    isDiscoveringLocalModels = false,
                    localModelError = if (models.isEmpty()) "No local models found" else null
                )
            }
        }
    }

    fun discoverLocalEndpoint(endpoint: String, provider: LocalModelManager.LocalProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringLocalModels = true) }
            val models = localModelManager.discoverFromEndpoint(endpoint, provider)
            _uiState.update {
                it.copy(
                    localModels = localModelManager.state.value.models,
                    isDiscoveringLocalModels = false
                )
            }
        }
    }

    fun connectLocalModel(model: LocalModelManager.LocalModel) {
        viewModelScope.launch {
            val config = AgentConfig(
                id = java.util.UUID.randomUUID().toString(),
                name = model.name,
                type = AgentType.LocalModel,
                serverUrl = model.endpoint,
                model = model.id
            )
            repository.saveConfig(config)
            repository.logActivity("local_model", "Connected to local model", model.name)
            _uiState.update { state ->
                state.copy(
                    showWizard = false,
                    agentConfig = config
                )
            }
        }
    }

    fun clearLocalModelError() {
        _uiState.update { it.copy(localModelError = null) }
        localModelManager.clearError()
    }

    fun resetComparePending() {
        _uiState.update { it.copy(comparePending = false) }
    }

    override fun onCleared() {
        super.onCleared()
        // Sprint 8: 注销 Widget 快捷输入广播接收器，避免内存泄漏
        try {
            getApplication<Application>().unregisterReceiver(widgetMessageReceiver)
        } catch (_: Exception) {
            // 接收器可能因异常路径未注册成功，忽略注销失败
        }
        // Transport 生命周期由 ConnectionRepository (@Singleton) 管理，ViewModel
        // 销毁时不 disconnect/shutdown —— 前台服务及其他消费者可能仍在使用连接。
        voiceInputManager?.destroy()
        voiceChatManager?.destroy()
    }
}

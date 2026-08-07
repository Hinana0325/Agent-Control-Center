package com.agentcontrolcenter.app.feature.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeXs6
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.data.model.Message
import com.agentcontrolcenter.app.data.model.MessageRole
import com.agentcontrolcenter.app.data.model.MessageStatus
import com.agentcontrolcenter.app.ui.adaptive.AdaptiveConfig
import android.net.Uri
import com.agentcontrolcenter.app.navigation.Screen
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.adaptive.shouldShowSidebar
import com.agentcontrolcenter.app.ui.components.ErrorSnackbar
import kotlinx.coroutines.launch
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import com.agentcontrolcenter.app.ui.theme.AppEnterTransition
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenu
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenuItem
import com.agentcontrolcenter.app.ui.theme.ShapePill
import androidx.navigation.NavHostController
import com.agentcontrolcenter.app.core.ui.HapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    navController: NavHostController? = null,
    onNavigateToSessions: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val adaptive = currentAdaptiveConfig()
    val snackbarHostState = remember { SnackbarHostState() }

    // 判断用户是否在列表底部附近：当用户主动向上滚动阅读历史消息时，
    // 不应强制把视图拉回底部。以「最后可见 item 接近末尾」作为底部判定。
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex >= totalItems - 2
        }
    }

    // 流式响应时 messages.size 不变，但最后一条消息的 content 会随 delta 增长，
    // 因此同时依赖 size 与最后一条消息 content 作为滚动触发器；且仅在用户
    // 处于底部（atBottom）时自动滚动，避免打断阅读历史消息。
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
        if (uiState.messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    val context = LocalContext.current
    LaunchedEffect(uiState.connectionState.isConnected) {
        if (uiState.connectionState.isConnected) {
            HapticFeedback.medium(context)
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            HapticFeedback.error(context)
        }
    }

    LaunchedEffect(uiState.comparePending) {
        if (uiState.comparePending && navController != null) {
            navController.navigate(Screen.Compare.route)
            viewModel.resetComparePending()
        }
    }

    // 监听操作结果（复制/删除/清空）并通过 Snackbar 给用户即时反馈。
    // 展示后立即清除 lastAction，避免配置变更后重复弹出。
    LaunchedEffect(uiState.lastAction) {
        val action = uiState.lastAction
        if (!action.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = action,
                withDismissAction = true
            )
            viewModel.clearLastAction()
        }
    }

    // Voice Chat Mode overlay
    val voiceChatManager = viewModel.getVoiceChatManager()
    // 最外层 Box 用于承载内容以及底部 Snackbar，确保操作反馈浮于所有布局之上
    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isVoiceChatMode && voiceChatManager != null) {
            VoiceChatOverlay(
                voiceManager = voiceChatManager,
                lastUserText = uiState.voiceChatLastUserText,
                lastAgentText = uiState.voiceChatLastAgentText,
                onExit = { viewModel.exitVoiceChatMode() },
                onSendMessage = { text ->
                    viewModel.updateInput(text)
                    viewModel.sendMessage()
                }
            )
        } else {
            // Box ensures SearchOverlay floats on top of the main layout instead of
            // stacking below it (which caused the "Agent Control Center" title to overlap with the search bar).
            Box(modifier = Modifier.fillMaxSize()) {
                if (adaptive.shouldShowSidebar) {
                    // 双栏：Sessions 已是左侧 sidebar，仅需 Tasks 入口融合到顶栏
                    TabletChatLayout(uiState, listState, viewModel, onNavigateToTasks, adaptive)
                } else {
                    // 单栏：Sessions + Tasks 入口均融合到顶栏
                    PhoneChatLayout(uiState, listState, viewModel, onNavigateToSessions, onNavigateToTasks, adaptive)
                }

                // Search overlay — rendered on top of the main layout
                if (uiState.isSearchActive) {
                    SearchOverlay(
                        query = uiState.searchQuery,
                        results = uiState.searchResults,
                        onQueryChange = { viewModel.searchMessages(it) },
                        onResultClick = { msg ->
                            // G4-1: 点击搜索结果滚动到对应消息在列表中的位置
                            val index = uiState.messages.indexOfFirst { it.id == msg.id }
                            if (index >= 0) {
                                scope.launch {
                                    listState.animateScrollToItem(index)
                                }
                            }
                            viewModel.closeSearch()
                        },
                        onClose = { viewModel.closeSearch() }
                    )
                }
            }
        }

        // Snackbar 宿主：展示复制/删除/清空等操作的结果反馈
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * P2: 抽取 Phone/Tablet 聊天布局共用的附件启动器初始化，消除重复代码。
 *
 * 内部 remember 两个 [ActivityResultContracts.GetContent] 启动器（图片 / 文件），
 * 返回封装好的 launch 动作 [ChatActions]，供 [ChatInputBar] 的
 * onAttachImage / onAttachFile 直接使用。
 */
@Composable
private fun rememberChatActions(viewModel: ChatViewModel): ChatActions {
    val context = LocalContext.current
    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setPendingAttachment(context, it, isImage = true) }
    }
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setPendingAttachment(context, it, isImage = false) }
    }
    return ChatActions(
        onAttachImage = { imagePickerLauncher.launch("image/*") },
        onAttachFile = { filePickerLauncher.launch("*/*") }
    )
}

/**
 * 附件选择动作持有者。由 [rememberChatActions] 构造。
 */
private class ChatActions(
    val onAttachImage: () -> Unit,
    val onAttachFile: () -> Unit
)

@Composable
private fun PhoneChatLayout(
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: ChatViewModel,
    onNavigateToSessions: () -> Unit,
    onNavigateToTasks: () -> Unit,
    adaptive: AdaptiveConfig
) {
    // 向导模式：全屏覆盖，不显示输入栏
    if (uiState.showWizard) {
        WizardOverlay(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissWizard() },
            onConnect = { url, key, type ->
                viewModel.connectToServer(url, key, type)
            }
        )
        return
    }

    // Voice + Attachment integration
    val context = LocalContext.current
    // P2: 附件启动器初始化抽取到 rememberChatActions，与 Tablet 共用。
    val chatActions = rememberChatActions(viewModel)

    LaunchedEffect(Unit) {
        viewModel.initVoiceInput(context)
        viewModel.initVoiceChatMode(context)
    }

    val voiceManager = viewModel.getVoiceInputManager()
    if (voiceManager != null) {
        LaunchedEffect(voiceManager) {
            voiceManager.recognizedText.collect { text ->
                if (text.isNotEmpty()) {
                    val current = uiState.inputText
                    viewModel.updateInput(if (current.isEmpty()) text else "$current $text")
                }
            }
        }
    }

    // 正常聊天模式
    Scaffold(
        topBar = { ChatTopBar(uiState, viewModel, onNavigateToSessions, onNavigateToTasks) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                // Command palette
                val inputText = uiState.inputText
                if (inputText.startsWith("/")) {
                    CommandPalette(
                        query = inputText,
                        onCommandSelected = { cmd ->
                            if (!viewModel.executeCommand(cmd)) {
                                viewModel.updateInput("")
                            } else {
                                viewModel.updateInput("")
                            }
                        },
                        onDismiss = { viewModel.updateInput("") }
                    )
                }
                ChatInputBar(
                    inputText = uiState.inputText,
                    isStreaming = uiState.isStreaming,
                    onInputChange = { viewModel.updateInput(it) },
                    onSend = { viewModel.sendMessage() },
                    onStop = { viewModel.stopStreaming() },
                    adaptive = adaptive,
                    isVoiceListening = uiState.isVoiceListening,
                    onVoiceToggle = { viewModel.toggleVoiceInput() },
                    onAttachImage = chatActions.onAttachImage,
                    onAttachFile = chatActions.onAttachFile,
                    pendingAttachmentType = uiState.pendingAttachmentType,
                    pendingAttachmentName = uiState.pendingAttachmentName,
                    onClearAttachment = { viewModel.clearPendingAttachment() },
                    isEditing = uiState.editingMessageId != null,
                    onCancelEdit = { viewModel.cancelEdit() },
                    replyContent = uiState.replyingToMessageContent,
                    onCancelReply = { viewModel.cancelReply() }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        if (dragAmount < -80) {
                            viewModel.switchToNextSession()
                        } else if (dragAmount > 80) {
                            viewModel.switchToPreviousSession()
                        }
                    }
                }
        ) {
            ChatContent(uiState, listState, viewModel, adaptive)
        }
    }
}

@Composable
private fun TabletChatLayout(
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: ChatViewModel,
    onNavigateToTasks: () -> Unit,
    adaptive: AdaptiveConfig
) {
    // 向导模式：全屏覆盖
    if (uiState.showWizard) {
        WizardOverlay(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissWizard() },
            onConnect = { url, key, type ->
                viewModel.connectToServer(url, key, type)
            }
        )
        return
    }

    // Voice + Attachment integration (same as Phone layout)
    val context = LocalContext.current
    // P2: 附件启动器初始化抽取到 rememberChatActions，与 Phone 共用。
    val chatActions = rememberChatActions(viewModel)

    LaunchedEffect(Unit) {
        viewModel.initVoiceInput(context)
        viewModel.initVoiceChatMode(context)
    }

    val voiceManager = viewModel.getVoiceInputManager()
    if (voiceManager != null) {
        LaunchedEffect(voiceManager) {
            voiceManager.recognizedText.collect { text ->
                if (text.isNotEmpty()) {
                    val current = uiState.inputText
                    viewModel.updateInput(if (current.isEmpty()) text else "$current $text")
                }
            }
        }
    }

    val sidebarWidth = adaptive.panelConfig.sidebarWidth

    Row(modifier = Modifier.fillMaxSize()) {
        // Sessions sidebar — dynamic width from adaptive config
        Surface(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nav_sessions),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = { viewModel.createNewSession() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(18.dp))
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(uiState.sessions, key = { it.id }) { session ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.switchToSession(session.id) },
                            color = if (session.id == uiState.currentSessionId)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (session.isPinned) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = session.title.ifEmpty { stringResource(R.string.untitled) },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Chat area
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                ChatTopBar(
                    uiState, viewModel,
                    onNavigateToSessions = {},
                    onNavigateToTasks = onNavigateToTasks
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    // Command palette
                    val inputText = uiState.inputText
                    if (inputText.startsWith("/")) {
                        CommandPalette(
                            query = inputText,
                            onCommandSelected = { cmd ->
                                if (!viewModel.executeCommand(cmd)) {
                                    viewModel.updateInput("")
                                } else {
                                    viewModel.updateInput("")
                                }
                            },
                            onDismiss = { viewModel.updateInput("") }
                        )
                    }
                    ChatInputBar(
                        inputText = uiState.inputText,
                        isStreaming = uiState.isStreaming,
                        onInputChange = { viewModel.updateInput(it) },
                        onSend = { viewModel.sendMessage() },
                        onStop = { viewModel.stopStreaming() },
                        adaptive = adaptive,
                        isVoiceListening = uiState.isVoiceListening,
                        onVoiceToggle = { viewModel.toggleVoiceInput() },
                        onAttachImage = chatActions.onAttachImage,
                        onAttachFile = chatActions.onAttachFile,
                        pendingAttachmentType = uiState.pendingAttachmentType,
                        pendingAttachmentName = uiState.pendingAttachmentName,
                        onClearAttachment = { viewModel.clearPendingAttachment() },
                        isEditing = uiState.editingMessageId != null,
                        onCancelEdit = { viewModel.cancelEdit() },
                        replyContent = uiState.replyingToMessageContent,
                        onCancelReply = { viewModel.cancelReply() }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
            ChatContent(uiState, listState, viewModel, adaptive)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onNavigateToSessions: () -> Unit,
    onNavigateToTasks: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }

    AppTopAppBar(
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    // 连接状态指示器
                    val statusColor = when {
                        uiState.connectionState.isConnected -> MaterialTheme.colorScheme.primary
                        uiState.isConnecting -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    }
                    val statusText = when {
                        uiState.connectionState.isConnected -> stringResource(R.string.status_connected)
                        uiState.isConnecting -> stringResource(R.string.btn_connecting)
                        else -> stringResource(R.string.status_disconnected)
                    }
                    val infiniteTransition = rememberInfiniteTransition(label = "status-pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = EaseInOut),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse-alpha"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .semantics { contentDescription = statusText }
                            .clickable { }
                    ) {
                        Surface(
                            modifier = Modifier.size(10.dp),
                            shape = CircleShape,
                            color = statusColor.copy(alpha = if (uiState.isConnecting) pulseAlpha else 1f)
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                // Streaming / collab sub-line — only shown when active, with proper spacing
                if (uiState.isStreaming) {
                    Text(
                        stringResource(R.string.streaming),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Collaboration indicator — disabled until real implementation (v2.2.0)
                // val isCollabEnabled = false
                // if (isCollabEnabled) {
                //     val collabManager = remember { com.agentcontrolcenter.app.data.collab.CollaborationManager() }
                //     val collabState by collabManager.collabState.collectAsState()
                //     if (collabState.isInSession && collabState.session != null) { ... }
                // }
            }
        },
        actions = {
            IconButton(onClick = { viewModel.openSearch() }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
            }
            IconButton(onClick = { viewModel.createNewSession() }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat))
            }
            // v5.1: Sessions/Tasks 从主 Tab 融合进首页顶栏入口
            IconButton(onClick = onNavigateToSessions) {
                Icon(Icons.Default.History, contentDescription = stringResource(R.string.nav_sessions))
            }
            IconButton(onClick = onNavigateToTasks) {
                Icon(Icons.Default.TaskAlt, contentDescription = stringResource(R.string.nav_tasks))
            }
            IconButton(onClick = { viewModel.enterVoiceChatMode() }) {
                Icon(Icons.Default.Headset, contentDescription = stringResource(R.string.voice_mode))
            }
            IconButton(onClick = { showClearDialog = true }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
            }
        },
    )

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.action_clear)) },
            text = { Text(stringResource(R.string.clear_messages_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearMessages()
                    showClearDialog = false
                }) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}



@Composable
private fun ChatContent(
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: ChatViewModel,
    adaptive: AdaptiveConfig
) {
    val context = LocalContext.current
    val contentMaxWidth = adaptive.panelConfig.contentMaxWidth
    val horizontalPadding = if (adaptive.isTablet) 48.dp else 12.dp
    // Messages already on screen at first composition are considered "seen" so they
    // don't replay the spring entrance while merely scrolling. Only genuinely new
    // messages (added later this session) animate in.
    //
    // Phase 2.2: 改为 mutableStateMapOf 驱动 Compose 重组，不再作为参数下传到
    // MessageBubble（MutableSet 非 @Stable，会导致所有可见 item 每次都重组）。
    val seenMessageIds = remember { androidx.compose.runtime.mutableStateMapOf<String, Unit>() }

    Column(modifier = Modifier.fillMaxSize()) {
        // 离线横幅：未连接且非连接中时，醒目提示用户去连接模型端点
        AnimatedVisibility(
            visible = !uiState.connectionState.isConnected && !uiState.isConnecting,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            OfflineBanner(onConnect = { viewModel.openConnectWizard() })
        }
        Box(modifier = Modifier.weight(1f)) {
        if (uiState.messages.isEmpty()) {
            EmptyChatPlaceholder(
                connectionState = uiState.connectionState,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding)
                    .widthIn(max = contentMaxWidth)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    MessageBubble(
                        message = message,
                        seenMessageIds = seenMessageIds,
                        onDelete = { viewModel.deleteMessage(message.id) },
                        onCopy = { viewModel.copyToClipboard(context, message.content) },
                        onReaction = { viewModel.toggleMessageReaction(message.id) },
                        onEdit = if (message.role == MessageRole.User) {
                            { viewModel.startEditMessage(message.id, message.content) }
                        } else null,
                        onReply = { viewModel.startReply(message.id, message.content) },
                        onRetry = if (message.role == MessageRole.User) {
                            { viewModel.retrySendMessage(message.id) }
                        } else null
                    )
                }
            }
        }

        uiState.errorMessage?.let { error ->
            ErrorSnackbar(
                message = error,
                onDismiss = { viewModel.dismissError() },
                onRetry = { viewModel.dismissError() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
        }
    }
}

/**
 * Message bubble with long-press context menu and status indicator.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    onDelete: () -> Unit = {},
    onCopy: () -> Unit = {},
    onReaction: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    // G4-2: 发送失败时的重试回调，仅对 User 消息且 status == Failed 时触发
    onRetry: (() -> Unit)? = null,
    // Phase 2.2: seenMessageIds 改为 SnapshotStateMap，由调用方在外层 remember
    // 并通过 Lambda 或直接传入。SnapshotStateMap 是 @Stable 的，读取其值会
    // 正确参与 Compose 重组，不会导致所有可见 item 每次都重组。
    seenMessageIds: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Unit> = androidx.compose.runtime.mutableStateMapOf()
) {
    val isUser = message.role == MessageRole.User
    val isStreaming = message.status == MessageStatus.Sending
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val hasEntered = seenMessageIds.containsKey(message.id)

    var showContextMenu by remember { mutableStateOf(false) }

    // Mark this message as seen after first composition so future scroll-ins are static.
    LaunchedEffect(message.id) {
        seenMessageIds[message.id] = Unit
    }

    AnimatedVisibility(
        visible = true,
        enter = if (hasEntered) EnterTransition.None else AppEnterTransition
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box {
            val bubbleShape = remember(isUser) {
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                )
            }
            Surface(
                shape = bubbleShape,
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (isUser) 0.dp else 1.dp,
                modifier = Modifier
                    .semantics {
                        contentDescription = if (isUser)
                            context.getString(R.string.a11y_message_user, message.content.take(50))
                        else
                            context.getString(R.string.a11y_message_agent, message.content.take(50))
                    }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showContextMenu = true
                        },
                        onDoubleClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReaction()
                        }
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Reply reference indicator
                    if (message.replyToId != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = ShapeXs6
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Reply,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (isStreaming) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        MarkdownText(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUser)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Timestamp + Status indicator row
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        // Message status indicator (only for user messages)
                        if (isUser) {
                            MessageStatusIndicator(
                                status = message.status,
                                onRetry = onRetry
                            )
                        }
                        // Reaction emoji
                        if (message.reaction.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = message.reaction,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Context menu
            AppDropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                AppDropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        showContextMenu = false
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Agent Control Center Message", message.content)
                        clipboard.setPrimaryClip(clip)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.a11y_copy_message), modifier = Modifier.size(18.dp))
                    }
                )
                if (isUser && onEdit != null) {
                    AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = {
                            showContextMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                if (onReply != null) {
                    AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reply)) },
                        onClick = {
                            showContextMenu = false
                            onReply()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Reply,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                AppDropdownMenuItem(
                    text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
    }
}

/**
 * Message status indicator icon with animations.
 */
@Composable
fun MessageStatusIndicator(
    status: MessageStatus,
    onRetry: (() -> Unit)? = null
) {
    when (status) {
        MessageStatus.Sending -> {
            // Rotating clock icon
            val infiniteTransition = rememberInfiniteTransition(label = "sending-rotation")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "clock-rotation"
            )
            Icon(
                Icons.Default.Schedule,
                contentDescription = stringResource(R.string.status_sending),
                modifier = Modifier
                    .size(12.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
        MessageStatus.Sent -> {
            // Single check
            Icon(
                Icons.Default.Done,
                contentDescription = stringResource(R.string.status_sent),
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
        MessageStatus.Received -> {
            // Double check
            Icon(
                Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.status_received),
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        MessageStatus.Failed -> {
            // Red exclamation with retry
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = stringResource(R.string.status_failed),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                // G4-2: 失败时显示重试按钮，点击触发 onRetry 回调
                if (onRetry != null) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_retry_send),
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onRetry() },
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Phase 2.2: 缓存 SimpleDateFormat 实例，避免每个 MessageBubble 重组时都新建。
 * SimpleDateFormat 构造昂贵（解析 pattern、查 Locale 数据）。
 * Compose 主线程单线程访问，无需同步。
 */
private val TIME_FORMAT = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

internal fun formatTimestamp(timestamp: Long): String {
    return TIME_FORMAT.format(java.util.Date(timestamp))
}

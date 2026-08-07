package com.agentcontrolcenter.app.feature.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import com.agentcontrolcenter.app.ui.theme.ShapeS8
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.app.data.model.Session
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.adaptive.shouldShowSidebar
import com.agentcontrolcenter.app.core.ui.HapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.feature.chat.ChatViewModel
import com.agentcontrolcenter.app.feature.chat.MessageBubble
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import com.agentcontrolcenter.app.ui.theme.ShapePill
import com.agentcontrolcenter.app.ui.components.EmptyStateView
import com.agentcontrolcenter.app.ui.components.SessionSkeletonItem
import com.agentcontrolcenter.app.ui.components.sharedBounds
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 会话列表排序方式。仅在 UI 层对已加载的会话排序，不修改数据库。
 */
enum class SessionSortMode { LAST_UPDATED, CREATED, NAME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    chatViewModel: ChatViewModel? = null,
    onBack: (() -> Unit)? = null
) {
    val adaptive = currentAdaptiveConfig()
    val context = LocalContext.current
    val uiState = chatViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val sessionList = uiState?.sessions ?: emptyList()
    val currentSessionId = uiState?.currentSessionId
    val isLoadingSessions = uiState?.isLoadingSessions ?: true
    val useDualPane = adaptive.shouldShowSidebar // Expanded or Medium+Landscape

    if (useDualPane && chatViewModel != null) {
        SessionsDualPaneLayout(
            sessionList = sessionList,
            currentSessionId = currentSessionId,
            chatViewModel = chatViewModel,
            adaptive = adaptive
        )
    } else {
        // v5.1: Sessions 从 Tab 下沉为首页 push 页面，单栏需要返回按钮
        SessionsSinglePaneLayout(
            sessionList = sessionList,
            currentSessionId = currentSessionId,
            chatViewModel = chatViewModel,
            adaptive = adaptive,
            isLoading = isLoadingSessions,
            onBack = onBack
        )
    }
}

/**
 * Dual-pane layout for tablets: session list on the left, selected session messages on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsDualPaneLayout(
    sessionList: List<Session>,
    currentSessionId: String?,
    chatViewModel: ChatViewModel,
    adaptive: com.agentcontrolcenter.app.ui.adaptive.AdaptiveConfig
) {
    val sidebarWidth = adaptive.panelConfig.sidebarWidth
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val refreshScope = rememberCoroutineScope()

    var isRefreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredSessions = remember(sessionList, searchQuery) {
        if (searchQuery.isBlank()) sessionList
        else sessionList.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.nav_sessions)) },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Left panel — session list
            Surface(
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        // refreshSessions() launches a suspend coroutine and returns its
                        // Job. Wait for it to complete before clearing the refreshing
                        // indicator — otherwise the spinner disappears while the refresh
                        // is still running.
                        refreshScope.launch {
                            isRefreshing = true
                            chatViewModel.refreshSessions().join()
                            isRefreshing = false
                        }
                    }
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
                                onClick = { chatViewModel.createNewSession() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(18.dp))
                            }
                        }
                        // Search field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_sessions), style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = ShapeS12,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        )
                        if (sessionList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.no_sessions),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        } else if (filteredSessions.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.search_no_results),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(filteredSessions, key = { it.id }) { session ->
                                    SwipeableSessionItem(
                                        session = session,
                                        isSelected = session.id == currentSessionId,
                                        onSelect = { HapticFeedback.light(context); chatViewModel.switchToSession(session.id) },
                                        onDelete = { HapticFeedback.medium(context); chatViewModel.deleteSession(session.id) },
                                        onTogglePin = { HapticFeedback.light(context); chatViewModel.toggleSessionPin(session.id, !session.isPinned) },
                                        compact = true
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right panel — session detail / message preview
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface
            ) {
                if (currentSessionId == null) {
                    // No session selected
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.select_session),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else {
                    // Show messages for the selected session
                    val messages = uiState.messages
                    val contentMaxWidth = adaptive.panelConfig.contentMaxWidth

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Session header
                        val selectedSession = sessionList.firstOrNull { it.id == currentSessionId }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedSession?.title?.ifEmpty { stringResource(R.string.untitled) } ?: stringResource(R.string.nav_sessions),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (selectedSession != null) {
                                        Text(
                                            text = "${selectedSession.messageCount} messages",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }

                        // Messages list
                        if (messages.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.no_sessions),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .widthIn(max = contentMaxWidth)
                                    .align(Alignment.CenterHorizontally),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(
                                    items = messages,
                                    key = { it.id }
                                ) { message ->
                                    MessageBubble(message)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single-pane layout for phones: just a session list with pull-to-refresh and swipe gestures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSinglePaneLayout(
    sessionList: List<Session>,
    currentSessionId: String?,
    chatViewModel: ChatViewModel?,
    adaptive: com.agentcontrolcenter.app.ui.adaptive.AdaptiveConfig,
    isLoading: Boolean = true,
    onBack: (() -> Unit)? = null
) {
    val maxContentWidth = if (adaptive.isTablet) 720.dp else 600.dp
    val context = LocalContext.current
    val refreshScope = rememberCoroutineScope()

    var isRefreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // 排序状态：仅前端排序，不修改数据库
    var sortMode by remember { mutableStateOf(SessionSortMode.LAST_UPDATED) }
    var showSortMenu by remember { mutableStateOf(false) }
    val filteredSessions = remember(sessionList, searchQuery, sortMode) {
        val filtered = if (searchQuery.isBlank()) sessionList
        else sessionList.filter { it.title.contains(searchQuery, ignoreCase = true) }
        when (sortMode) {
            SessionSortMode.LAST_UPDATED -> filtered.sortedByDescending { it.updatedAt }
            SessionSortMode.CREATED -> filtered.sortedByDescending { it.createdAt }
            SessionSortMode.NAME -> filtered.sortedBy { it.title }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.nav_sessions)) },
                navigationIcon = {
                    // v5.1: Sessions 作为 push 页面时显示返回按钮
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.btn_back)
                            )
                        }
                    }
                },
                actions = {
                    // 排序按钮 + 下拉菜单
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.sort))
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_last_updated)) },
                                onClick = { sortMode = SessionSortMode.LAST_UPDATED; showSortMenu = false },
                                trailingIcon = {
                                    if (sortMode == SessionSortMode.LAST_UPDATED) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_created)) },
                                onClick = { sortMode = SessionSortMode.CREATED; showSortMenu = false },
                                trailingIcon = {
                                    if (sortMode == SessionSortMode.CREATED) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_name)) },
                                onClick = { sortMode = SessionSortMode.NAME; showSortMenu = false },
                                trailingIcon = {
                                    if (sortMode == SessionSortMode.NAME) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        // M3 Expressive FAB：手机布局下新建会话入口（保留顶栏按钮向后兼容）
        floatingActionButton = {
            FloatingActionButton(
                onClick = { chatViewModel?.createNewSession() },
                shape = ShapePill,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat))
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier.widthIn(max = maxContentWidth).fillMaxSize()
            ) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        // Wait for the async refresh to finish before clearing the
                        // refreshing indicator (see dual-pane variant for details).
                        refreshScope.launch {
                            isRefreshing = true
                            chatViewModel?.refreshSessions()?.join()
                            isRefreshing = false
                        }
                    }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                    // Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_sessions), style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = ShapeS12,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    )
                    if (sessionList.isEmpty() && isLoading) {
                        // 首屏加载：显示骨架屏占位
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(5) {
                                SessionSkeletonItem()
                            }
                        }
                    } else if (sessionList.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.ChatBubbleOutline,
                            title = stringResource(R.string.no_sessions),
                            description = stringResource(R.string.no_sessions_subtitle),
                            actionText = stringResource(R.string.new_chat),
                            onAction = { chatViewModel?.createNewSession() }
                        )
                    } else if (filteredSessions.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.search_no_results),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(filteredSessions, key = { it.id }) { session ->
                                SwipeableSessionItem(
                                    session = session,
                                    isSelected = session.id == currentSessionId,
                                    onSelect = { HapticFeedback.light(context); chatViewModel?.switchToSession(session.id) },
                                    onDelete = { HapticFeedback.medium(context); chatViewModel?.deleteSession(session.id) },
                                    onTogglePin = { HapticFeedback.light(context); chatViewModel?.toggleSessionPin(session.id, !session.isPinned) },
                                    compact = false
                                )
                            }
                        }
                    }
                    } // PullToRefreshBox content
                }
            }
        }
    }
}

/**
 * P2: 合并后的可滑动会话项，统一服务双栏侧边栏（compact）与单栏卡片两种布局。
 *
 * - compact = true：双栏侧边栏列表项，使用 [SessionListItem] + [ShapeS8]。
 * - compact = false：单栏布局卡片，使用 [SessionCard] + [ShapeS12]。
 *
 * 左滑 = 删除（红色），右滑 = 切换置顶（蓝色）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionItem(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    compact: Boolean
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onTogglePin()
                    false // Don't dismiss, just trigger the action
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    val untitled = stringResource(R.string.untitled_session)
    val sessionContentDescription = stringResource(R.string.cd_session_item, session.title.ifEmpty { untitled }, session.messageCount)
    val clipShape = if (compact) ShapeS8 else ShapeS12

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = sessionContentDescription
        },
        backgroundContent = {
            val direction = dismissState.dismissDirection

            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                },
                label = "swipe-bg-color"
            )

            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.PushPin
                else -> Icons.Default.Delete
            }

            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(clipShape)
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true
    ) {
        if (compact) {
            SessionListItem(
                session = session,
                isSelected = isSelected,
                onSelect = onSelect,
                onDelete = onDelete,
                onTogglePin = onTogglePin
            )
        } else {
            SessionCard(
                session = session,
                isSelected = isSelected,
                onSelect = onSelect,
                onDelete = onDelete,
                onTogglePin = onTogglePin
            )
        }
    }
}

/**
 * Compact session list item for the dual-pane sidebar.
 */
@Composable
private fun SessionListItem(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .sharedBounds("session_${session.id}")
            .semantics {
                contentDescription = "Session: ${session.title.ifEmpty { "untitled" }}, ${session.messageCount} messages"
            }
            .clickable(onClick = onSelect),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifEmpty { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSessionTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            // Inline actions
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (session.isPinned) "Unpin" else "Pin",
                    modifier = Modifier.size(14.dp),
                    tint = if (session.isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .sharedBounds("session_${session.id}")
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "Session: ${session.title.ifEmpty { "untitled" }}, ${session.messageCount} messages"
            }
            .clickable(onClick = onSelect),
        shape = ShapeS12
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = session.title.ifEmpty { stringResource(R.string.untitled) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatSessionTime(session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (session.isPinned) "Unpin" else "Pin",
                    modifier = Modifier.size(20.dp),
                    tint = if (session.isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

private fun formatSessionTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

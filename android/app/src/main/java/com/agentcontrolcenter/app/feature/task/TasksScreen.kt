package com.agentcontrolcenter.app.feature.task

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.core.util.formatDateTime
import com.agentcontrolcenter.app.runtime.task.TaskManager
import com.agentcontrolcenter.app.ui.components.EmptyStateView
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenu
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenuItem
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import com.agentcontrolcenter.app.ui.theme.LocalSuccessColor

/**
 * 任务页面 — 对齐 iOS TasksView。
 *
 * 功能：
 * - 任务列表（LazyColumn），每项显示类型图标、输入预览、状态徽章、Agent、时间
 * - 状态过滤（全部/进行中/已完成/失败）— FilterChip
 * - 下拉刷新
 * - 长按或溢出菜单取消/删除任务（对齐 iOS 滑动操作）
 * - 空状态提示
 * - 顶部 TopAppBar 带返回按钮
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(
    onBack: () -> Unit = {},
    viewModel: TasksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tasks by viewModel.filteredTasks.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.tasks_refresh))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 顶部状态过滤（FilterChip 行，对齐 iOS segmented picker）
            TaskFilterRow(
                currentFilter = uiState.filter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            HorizontalDivider()

            // 下拉刷新 + 列表 / 空状态
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                state = pullState,
                modifier = Modifier.fillMaxSize()
            ) {
                if (tasks.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.Default.CheckCircleOutline,
                        title = stringResource(R.string.no_tasks),
                        description = stringResource(R.string.no_tasks_subtitle),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            TaskItem(
                                task = task,
                                onCancel = { viewModel.cancelTask(task.id) },
                                onDelete = { viewModel.deleteTask(task.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskFilterRow(
    currentFilter: TaskFilter,
    onFilterSelected: (TaskFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskFilter.entries.forEach { filter ->
            FilterChip(
                selected = currentFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = { Text(stringResource(filter.labelRes)) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TaskItem(
    task: TaskManager.AgentTask,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.task_delete_title)) },
            text = { Text(stringResource(R.string.task_delete_message)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    val isActive = task.status == TaskManager.TaskStatus.Pending ||
        task.status == TaskManager.TaskStatus.Running

    Box {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = { /* 暂无详情页 */ },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    }
                ),
            shape = ShapeS12,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 类型图标 + 状态色背景
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = taskStatusColor(task.status).copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = taskTypeIcon(task.type),
                            contentDescription = null,
                            tint = taskStatusColor(task.status),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 输入内容（最多两行）
                    Text(
                        text = task.input.ifEmpty { stringResource(R.string.task_empty_input) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // 状态徽章 + 类型 + 时间
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusBadge(status = task.status)
                        Text(
                            text = taskTypeDisplayName(task.type),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            text = formatRelativeTime(task.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    // Agent 标识（如果有 agentId）
                    if (task.agentId.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = task.agentId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 错误信息（失败时显示）
                    if (task.status == TaskManager.TaskStatus.Failed && !task.error.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = task.error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 溢出菜单按钮
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.task_more_actions),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        AppDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            if (isActive) {
                // 进行中任务：取消
                AppDropdownMenuItem(
                    text = { Text(stringResource(R.string.task_cancel)) },
                    onClick = { showMenu = false; onCancel() },
                    leadingIcon = {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }
            AppDropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.task_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                onClick = { showMenu = false; showDeleteConfirm = true },
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

@Composable
private fun StatusBadge(status: TaskManager.TaskStatus) {
    val color = taskStatusColor(status)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = taskStatusDisplayName(status),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Display helpers ──

@Composable
private fun taskStatusDisplayName(status: TaskManager.TaskStatus): String = when (status) {
    TaskManager.TaskStatus.Pending -> stringResource(R.string.task_status_pending)
    TaskManager.TaskStatus.Running -> stringResource(R.string.task_status_running)
    TaskManager.TaskStatus.Completed -> stringResource(R.string.task_status_completed)
    TaskManager.TaskStatus.Failed -> stringResource(R.string.task_status_failed)
    TaskManager.TaskStatus.Cancelled -> stringResource(R.string.task_status_cancelled)
}

@Composable
private fun taskTypeDisplayName(type: TaskManager.TaskType): String = when (type) {
    TaskManager.TaskType.CHAT -> stringResource(R.string.task_type_chat)
    TaskManager.TaskType.CODE -> stringResource(R.string.task_type_code)
    TaskManager.TaskType.WORKFLOW -> stringResource(R.string.task_type_workflow)
    TaskManager.TaskType.TOOL_CALL -> stringResource(R.string.task_type_tool_call)
    TaskManager.TaskType.FILE_OPERATION -> stringResource(R.string.task_type_file_operation)
}

@Composable
private fun taskTypeIcon(type: TaskManager.TaskType): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    TaskManager.TaskType.CHAT -> Icons.Default.Chat
    TaskManager.TaskType.CODE -> Icons.Default.Code
    TaskManager.TaskType.WORKFLOW -> Icons.Default.AccountTree
    TaskManager.TaskType.TOOL_CALL -> Icons.Default.Build
    TaskManager.TaskType.FILE_OPERATION -> Icons.Default.Folder
}

@Composable
private fun taskStatusColor(status: TaskManager.TaskStatus): Color = when (status) {
    TaskManager.TaskStatus.Pending -> MaterialTheme.colorScheme.secondary
    TaskManager.TaskStatus.Running -> MaterialTheme.colorScheme.primary
    TaskManager.TaskStatus.Completed -> LocalSuccessColor.current
    TaskManager.TaskStatus.Failed -> MaterialTheme.colorScheme.error
    TaskManager.TaskStatus.Cancelled -> MaterialTheme.colorScheme.outline
}

/**
 * 格式化时间戳为 "yyyy-MM-dd HH:mm" 格式（本地时区）。
 *
 * 委托给统一的 [com.agentcontrolcenter.app.core.util.formatDateTime]，
 * 复用 ThreadLocal 缓存的 SimpleDateFormat 实例，避免每次调用重复创建。
 */
private fun formatRelativeTime(timestamp: Long): String {
    return formatDateTime(timestamp)
}

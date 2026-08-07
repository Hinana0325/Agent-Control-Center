package com.agentcontrolcenter.app.feature.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.navigation.Screen
import com.agentcontrolcenter.app.data.model.ActivityItem
import com.agentcontrolcenter.app.ui.adaptive.WindowWidthClass
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.components.EmptyStateView
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    activityViewModel: ActivityViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit = {}
) {
    val uiState by activityViewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = currentAdaptiveConfig()
    val useDualPane = adaptive.widthClass == WindowWidthClass.Expanded

    var selectedActivityId by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Auto-select first item when entering dual-pane mode
    LaunchedEffect(useDualPane, uiState.activities) {
        if (useDualPane && selectedActivityId == null && uiState.activities.isNotEmpty()) {
            selectedActivityId = uiState.activities.first().id
        }
        if (!useDualPane) {
            selectedActivityId = null
        }
    }

    val selectedActivity = uiState.activities.find { it.id == selectedActivityId }

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.nav_activity)) },
                actions = {
                    // v5.1: Insights（运行观察）按功能归属放在 Activity Tab
                    IconButton(onClick = { onNavigate(Screen.Insights.route) }) {
                        Icon(Screen.Insights.icon, contentDescription = stringResource(Screen.Insights.stringResId))
                    }
                    if (uiState.activities.isNotEmpty()) {
                        IconButton(onClick = { activityViewModel.clearLog() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (useDualPane) {
            // Dual-pane: left = timeline list, right = selected activity details
            Row(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                // Left pane - activity timeline with pull-to-refresh
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    tonalElevation = 1.dp
                ) {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            activityViewModel.refreshActivities()
                            isRefreshing = false
                        }
                    ) {
                        if (uiState.activities.isEmpty()) {
                            EmptyActivityHint()
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(uiState.activities, key = { it.id }) { activity ->
                                    ActivityListItem(
                                        activity = activity,
                                        isSelected = activity.id == selectedActivityId,
                                        onClick = { selectedActivityId = activity.id }
                                    )
                                }
                            }
                        }
                    }
                }

                // Right pane - activity details
                Surface(
                    modifier = Modifier.weight(1.5f).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val activity = selectedActivity
                    if (activity != null) {
                        ActivityDetailPanel(activity)
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.select_activity),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        } else {
            // Single-pane layout with pull-to-refresh
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize()
                ) {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            activityViewModel.refreshActivities()
                            isRefreshing = false
                        }
                    ) {
                        if (uiState.activities.isEmpty()) {
                            EmptyActivityHint()
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(uiState.activities, key = { it.id }) { activity ->
                                    ActivityCard(activity)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyActivityHint() {
    EmptyStateView(
        icon = Icons.Default.Timeline,
        title = stringResource(R.string.no_activity),
        description = stringResource(R.string.no_activity_subtitle)
    )
}

@Composable
private fun ActivityListItem(
    activity: ActivityItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val (icon, color) = activityIconAndColor(activity)

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else
            Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                if (activity.description.isNotBlank()) {
                    Text(
                        text = activity.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2
                    )
                }
            }
            Text(
                text = formatTime(activity.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun ActivityDetailPanel(activity: ActivityItem) {
    val (icon, color) = activityIconAndColor(activity)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = color)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = formatTime(activity.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        // Details section
        DetailSection(title = stringResource(R.string.activity_type)) {
            Text(activity.type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
        }

        if (activity.description.isNotBlank()) {
            DetailSection(title = stringResource(R.string.activity_description)) {
                Text(activity.description, style = MaterialTheme.typography.bodyLarge)
            }
        }

        DetailSection(title = stringResource(R.string.activity_time)) {
            Text(formatTimeFull(activity.timestamp), style = MaterialTheme.typography.bodyLarge)
        }

        DetailSection(title = stringResource(R.string.activity_id)) {
            Text(
                activity.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}

@Composable
private fun ActivityCard(activity: ActivityItem) {
    val (icon, color) = activityIconAndColor(activity)

    AppCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = ShapeS12,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = activity.title, style = MaterialTheme.typography.titleSmall)
                if (activity.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = activity.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTime(activity.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun activityIconAndColor(activity: ActivityItem): Pair<ImageVector, Color> = when (activity.type) {
    "run" -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.primary
    "event" -> Icons.Default.Notifications to MaterialTheme.colorScheme.tertiary
    "error" -> Icons.Default.Error to MaterialTheme.colorScheme.error
    "system" -> Icons.Default.Info to MaterialTheme.colorScheme.secondary
    "message" -> Icons.Default.Send to MaterialTheme.colorScheme.primary
    "connection" -> Icons.Default.Wifi to MaterialTheme.colorScheme.tertiary
    else -> Icons.Default.Circle to Color.Gray
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatTimeFull(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

package com.agentcontrolcenter.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.navigation.Screen
import com.agentcontrolcenter.app.ui.adaptive.WindowWidthClass
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.data.update.UpdateManager
import com.agentcontrolcenter.app.ui.components.LocalSnackbarHost
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 设置页 — v5.1 信息架构重构。
 *
 * 单栏模式从「12 分类平铺 40+ 项」改为「分类列表 → 点击进入二级页」，
 * 与双栏模式 IA 对齐；同时砍掉与 More Tab 重复的纯跳转分类
 * （connection / marketplace / sync / plugins / insights），只保留真正设置项。
 *
 * 保留的 8 个分类：外观 / 安全 / 数据备份 / 性能 / 通知 / 同步 / 关于 / 实验性功能。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit = {}
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = currentAdaptiveConfig()
    val context = LocalContext.current
    val useDualPane = adaptive.widthClass == WindowWidthClass.Expanded

    // P2: 通过根 Scaffold 提供的全局 SnackbarHostState 展示消息，替代 Toast。
    val snackbarHostState = LocalSnackbarHost.current

    // 实验性功能 ViewModel：双栏 / 单栏布局的「实验性功能」分类共用同一实例，
    // 在 @Composable 顶层收集一次 flags 后传给 LazyListScope 扩展。
    val featureFlagViewModel: FeatureFlagSettingsViewModel = hiltViewModel()
    val experimentalFlags by featureFlagViewModel.flags.collectAsStateWithLifecycle()

    // Refresh performance metrics only while the Settings screen is visible.
    // This replaces the permanent `while (isActive)` loop that previously ran in
    // SettingsViewModel.init for the entire app lifetime.
    LaunchedEffect(Unit) {
        while (true) {
            settingsViewModel.refreshPerformanceMetrics()
            kotlinx.coroutines.delay(3000)
        }
    }

    // v5.1: 单栏模式改为「分类列表 ↔ 详情」切换。
    // null = 显示分类列表；非 null = 显示对应分类详情。
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    // 搜索：从「始终占位的搜索框」改为 TopAppBar 搜索图标点击展开。
    var searchActive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showE2EDialog by remember { mutableStateOf(false) }

    val appearanceTitle = stringResource(R.string.appearance)
    val securityTitle = stringResource(R.string.e2e_security)
    val dataTitle = stringResource(R.string.data_backup)
    val performanceTitle = stringResource(R.string.performance)
    val notificationsTitle = stringResource(R.string.smart_notif_title)
    val aboutTitle = stringResource(R.string.about)
    val experimentalTitle = "实验性功能"
    val deviceSyncTitle = stringResource(R.string.device_sync_title)

    // v5.1: More Tab 移除后，按功能归属安置 6 个次级入口：
    // - Workflow/Plugins/Mcp/Compare → Agents Tab 溢出菜单（Agent 能力相关）
    // - Insights → Activity Tab（运行观察）
    // - DeviceSync → Settings「同步」分类（系统级配置）
    // sync 分类为跳转入口（点击进入 DeviceSync 独立页），其余 7 个为详情渲染。
    val allCategories = remember(
        appearanceTitle, securityTitle, dataTitle, performanceTitle,
        notificationsTitle, aboutTitle, experimentalTitle, deviceSyncTitle
    ) {
        listOf(
            SettingsCategory("appearance", appearanceTitle, Icons.Default.Palette),
            SettingsCategory("security", securityTitle, Icons.Default.Lock),
            SettingsCategory("data", dataTitle, Icons.Default.Backup),
            SettingsCategory("performance", performanceTitle, Icons.Default.Speed),
            SettingsCategory("notifications", notificationsTitle, Icons.Default.NotificationsActive),
            SettingsCategory("sync", deviceSyncTitle, Icons.Default.Sync),
            SettingsCategory("about", aboutTitle, Icons.Default.Info),
            SettingsCategory("experimental", experimentalTitle, Icons.Default.Bolt)
        )
    }
    val filteredCategories = if (searchText.isBlank()) allCategories
        else allCategories.filter { it.title.contains(searchText, ignoreCase = true) }

    fun categoryByKey(key: String): SettingsCategory? = allCategories.firstOrNull { it.key == key }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { settingsViewModel.importChatHistory(context, it) }
    }

    // Backup message snackbar
    LaunchedEffect(uiState.backupMessage) {
        uiState.backupMessage?.let {
            snackbarHostState.showSnackbar(it)
            settingsViewModel.clearBackupMessage()
        }
    }

    val performanceMetrics by settingsViewModel.getPerformanceMetrics().collectAsStateWithLifecycle()

    // --- In-app update check ---
    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) { "0.0.0" }
    }
    val updateManager = remember { UpdateManager() }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var triggerCheck by remember { mutableIntStateOf(0) }

    LaunchedEffect(triggerCheck) {
        if (triggerCheck == 0) return@LaunchedEffect
        isChecking = true
        updateCheckResult = null
        showUpdateDialog = false
        val result = updateManager.checkForUpdates(currentVersion)
        updateCheckResult = when {
            result.isFailure ->
                UpdateCheckResult.Error(result.exceptionOrNull()?.message ?: "Network error")
            result.getOrNull() != null ->
                UpdateCheckResult.Available(result.getOrNull()!!)
            else -> UpdateCheckResult.UpToDate
        }
        isChecking = false
        showUpdateDialog = true
    }

    if (showUpdateDialog && updateCheckResult != null) {
        UpdateCheckDialog(
            result = updateCheckResult!!,
            currentVersion = currentVersion,
            onDownload = { info -> updateManager.downloadUpdate(context, info) },
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            current = uiState.themeMode,
            onSelect = { settingsViewModel.setThemeMode(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false }
        )
    }
    if (showFontDialog) {
        FontSizePickerDialog(
            current = uiState.fontSize,
            onSelect = { settingsViewModel.setFontSize(it); showFontDialog = false },
            onDismiss = { showFontDialog = false }
        )
    }
    if (showE2EDialog) {
        E2EPasswordDialog(
            uiState = uiState,
            viewModel = settingsViewModel,
            onDismiss = { showE2EDialog = false }
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // v5.1: 打包设置详情所需状态/回调，避免 settingsDetail 触发 LongParameterList。
    val detailState = SettingsDetailState(
        uiState = uiState,
        performanceMetrics = performanceMetrics,
        currentVersion = currentVersion,
        isChecking = isChecking,
        experimentalFlags = experimentalFlags,
        featureFlagViewModel = featureFlagViewModel,
        context = context,
        settingsViewModel = settingsViewModel,
        onShowThemeDialog = { showThemeDialog = true },
        onShowFontDialog = { showFontDialog = true },
        onShowE2EDialog = { showE2EDialog = true },
        onCheckUpdate = { triggerCheck++ },
        onExport = { settingsViewModel.exportChatHistory(context) },
        onImport = { importLauncher.launch(arrayOf("application/json")) }
    )

    if (useDualPane) {
        // ── 双栏：左分类列表 + 右详情（精简后的 7 分类）──
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                AppTopAppBar(title = { Text(stringResource(R.string.nav_settings)) })
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            Row(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                // Left pane - category navigation
                Surface(
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    tonalElevation = 1.dp
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        item {
                            SettingsSearchField(
                                query = searchText,
                                onQueryChange = { searchText = it },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        if (filteredCategories.isEmpty()) {
                            item {
                                Text(
                                    text = "无匹配项",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            filteredCategories.forEach { cat ->
                                item {
                                    CategoryItem(
                                        title = cat.title,
                                        icon = cat.icon,
                                        isSelected = selectedCategory == cat.key,
                                        onClick = {
                                            if (cat.key == "sync") {
                                                onNavigate(Screen.DeviceSync.route)
                                            } else {
                                                selectedCategory = cat.key
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Right pane - settings detail
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        settingsDetail(category = selectedCategory, state = detailState)
                    }
                }
            }
        }
    } else {
        // ── 单栏：分类列表 ↔ 详情 切换 ──
        val showDetail = selectedCategory != null && !searchActive

        if (showDetail) {
            val cat = categoryByKey(selectedCategory!!)
            Scaffold(
                topBar = {
                    AppTopAppBar(
                        title = { Text(cat?.title ?: "") },
                        navigationIcon = {
                            IconButton(onClick = { selectedCategory = null }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.btn_back)
                                )
                            }
                        }
                    )
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    settingsDetail(category = selectedCategory, state = detailState)
                }
            }
        } else {
            // 分类列表页（或搜索态）
            Scaffold(
                topBar = {
                    AppTopAppBar(
                        title = { Text(stringResource(R.string.nav_settings)) },
                        actions = {
                            IconButton(onClick = {
                                searchActive = !searchActive
                                if (!searchActive) searchText = ""
                            }) {
                                Icon(
                                    if (searchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (searchActive) "关闭搜索" else "搜索设置项"
                                )
                            }
                        }
                    )
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (searchActive) {
                        item {
                            SettingsSearchField(
                                query = searchText,
                                onQueryChange = { searchText = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (filteredCategories.isEmpty()) {
                        item {
                            Text(
                                text = "无匹配项",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(filteredCategories, key = { it.key }) { cat ->
                            CategoryEntryCard(
                                title = cat.title,
                                icon = cat.icon,
                                onClick = {
                                    if (cat.key == "sync") {
                                        onNavigate(Screen.DeviceSync.route)
                                    } else {
                                        selectedCategory = cat.key
                                        searchActive = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置详情渲染所需的全部状态与回调，打包传给 [settingsDetail] 避免 LongParameterList。
 */
@Suppress("LongParameterList")
private class SettingsDetailState(
    val uiState: SettingsUiState,
    val performanceMetrics: com.agentcontrolcenter.app.core.common.PerformanceMetrics,
    val currentVersion: String,
    val isChecking: Boolean,
    val experimentalFlags: List<FeatureFlagSettingsViewModel.FlagUiState>,
    val featureFlagViewModel: FeatureFlagSettingsViewModel,
    val context: android.content.Context,
    val settingsViewModel: SettingsViewModel,
    val onShowThemeDialog: () -> Unit,
    val onShowFontDialog: () -> Unit,
    val onShowE2EDialog: () -> Unit,
    val onCheckUpdate: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit
)

/**
 * 设置分类详情内容 — 由双栏右栏与单栏详情页共用，避免两份重复的 when 分支。
 *
 * 抽取为 LazyListScope 扩展，使双栏 / 单栏详情渲染逻辑保持单一来源。
 */
@Suppress("LongMethod")
private fun LazyListScope.settingsDetail(
    category: String?,
    state: SettingsDetailState
) {
    val s = state
    when (category) {
        "appearance" -> {
            item { SettingsHeader(androidx.compose.ui.res.stringResource(R.string.appearance)) }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.theme),
                    subtitle = themeLabel(s.uiState.themeMode, s.context),
                    icon = Icons.Default.Palette,
                    onClick = s.onShowThemeDialog
                )
            }
            item {
                SettingsToggleItem(
                    title = androidx.compose.ui.res.stringResource(R.string.dynamic_color),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.dynamic_color_desc),
                    icon = Icons.Default.AutoAwesome,
                    checked = s.uiState.dynamicColor,
                    onCheckedChange = { s.settingsViewModel.setDynamicColor(it) }
                )
            }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.font_size),
                    subtitle = fontSizeLabel(s.uiState.fontSize, s.context),
                    icon = Icons.Default.TextFields,
                    onClick = s.onShowFontDialog
                )
            }
        }
        "security" -> {
            item { SettingsHeader(androidx.compose.ui.res.stringResource(R.string.e2e_security)) }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.e2e_title),
                    subtitle = if (s.uiState.e2eEnabled) s.context.getString(R.string.e2e_enabled)
                               else s.context.getString(R.string.e2e_disabled),
                    icon = Icons.Default.Lock,
                    onClick = s.onShowE2EDialog
                )
            }
        }
        "data" -> {
            item { SettingsHeader(androidx.compose.ui.res.stringResource(R.string.data_backup)) }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.export_chat_history),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.export_chat_history_subtitle),
                    icon = Icons.Default.FileDownload,
                    onClick = s.onExport
                )
            }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.import_chat_history),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.import_chat_history_subtitle),
                    icon = Icons.Default.FileUpload,
                    onClick = s.onImport
                )
            }
        }
        "performance" -> {
            item { SettingsHeader(androidx.compose.ui.res.stringResource(R.string.performance)) }
            item { PerformanceMetricItem(
                title = androidx.compose.ui.res.stringResource(R.string.perf_avg_latency),
                value = "${s.performanceMetrics.avgMessageLatency} ms",
                icon = Icons.Default.Timer
            ) }
            item { PerformanceMetricItem(
                title = androidx.compose.ui.res.stringResource(R.string.perf_connection_quality),
                value = s.performanceMetrics.connectionQuality,
                icon = Icons.Default.Wifi
            ) }
            item { PerformanceMetricItem(
                title = androidx.compose.ui.res.stringResource(R.string.perf_memory_usage),
                value = "${s.performanceMetrics.memoryUsageMB} MB",
                icon = Icons.Default.Memory
            ) }
            item { PerformanceMetricItem(
                title = androidx.compose.ui.res.stringResource(R.string.perf_total_messages),
                value = "${s.performanceMetrics.totalMessages}",
                icon = Icons.Default.Message
            ) }
            item { PerformanceMetricItem(
                title = androidx.compose.ui.res.stringResource(R.string.perf_uptime),
                value = "${s.performanceMetrics.uptimeMinutes} min",
                icon = Icons.Default.AccessTime
            ) }
        }
        "notifications" -> {
            item { SettingsHeader(androidx.compose.ui.res.stringResource(R.string.smart_notif_title)) }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.smart_notif_high),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.smart_notif_high_desc),
                    icon = Icons.Default.PriorityHigh,
                    onClick = { }
                )
            }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.smart_notif_medium),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.smart_notif_medium_desc),
                    icon = Icons.Default.Notifications,
                    onClick = { }
                )
            }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.smart_notif_low),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.smart_notif_low_desc),
                    icon = Icons.Default.NotificationsNone,
                    onClick = { }
                )
            }
        }
        "about" -> {
            item { SettingsHeader(androidx.compose.ui.res.stringResource(R.string.about)) }
            item {
                SettingsItem(
                    title = androidx.compose.ui.res.stringResource(R.string.check_update),
                    subtitle = if (s.isChecking) androidx.compose.ui.res.stringResource(R.string.checking_update)
                        else "v${s.currentVersion}",
                    icon = Icons.Default.SystemUpdate,
                    onClick = s.onCheckUpdate
                )
            }
            item { VersionSettingsItem() }
        }
        "experimental" -> {
            // 「实验性功能」分类 — 复用 FeatureFlagSettingsViewModel
            // 与 iOS SettingsView.experimentalFeaturesSection 对齐
            experimentalFeaturesSection(
                flags = s.experimentalFlags,
                viewModel = s.featureFlagViewModel
            )
        }
    }
}

// ── 设置页搜索相关组件 ──

/**
 * 设置分类元数据，用于搜索过滤与左侧导航列表渲染。
 */
private data class SettingsCategory(
    val key: String,
    val title: String,
    val icon: ImageVector
)

/**
 * 单栏模式下的分类入口卡片 — 样式与 More Tab（已移除）原入口对齐：
 * AppCard + Row（图标 + 标题 + trailing 箭头）。
 */
@Composable
private fun CategoryEntryCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeS12
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * 设置页搜索框。与 iOS SettingsView.searchable 对齐：
 * 输入文本即按分类标题过滤（双栏过滤左侧列表，单栏过滤分类卡片）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("搜索设置项") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        // 清空按钮：非空时显示，便于一键重置搜索
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "清除")
                }
            }
        } else null
    )
}

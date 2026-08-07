package com.agentcontrolcenter.app.feature.agents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS10
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.navigation.Screen
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.agent.model.AgentTypeUi
import com.agentcontrolcenter.app.core.config.AgentConfigValidator
import com.agentcontrolcenter.app.ui.adaptive.WindowWidthClass
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.components.EmptyStateView
import com.agentcontrolcenter.app.ui.components.AgentCardSkeletonItem
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenu
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenuItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    agentsViewModel: AgentsViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit = {}
) {
    val uiState by agentsViewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = currentAdaptiveConfig()
    val useGrid = adaptive.widthClass == WindowWidthClass.Expanded
    val context = LocalContext.current
    var showFabMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Import file launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { agentsViewModel.importConfigs(context, it) }
    }

    // Observe export message
    LaunchedEffect(uiState.exportMessage) {
        uiState.exportMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            agentsViewModel.clearExportMessage()
        }
    }

    // Render the form dialog only when an agent is actually being edited.
    // Do NOT use an early `return` here: that would skip the entire Scaffold
    // and render a blank screen whenever showForm is true but editingAgent is
    // momentarily null (e.g. during a transient state update). Instead we
    // conditionally render the dialog on top of the normal list UI.
    uiState.editingAgent?.let { agent ->
        if (uiState.showForm) {
            AgentFormDialog(
                agent = agent,
                onSave = { agentsViewModel.saveAgent(it) },
                onDismiss = { agentsViewModel.dismissForm() }
            )
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.nav_agents)) },
                actions = {
                    // v5.1: Agent 能力相关次级入口（原 More Tab 悬空项按功能归属下沉）
                    // Workflow/Plugins/Mcp/Compare 均围绕「使用/扩展 Agent」，就近放在 Agents Tab
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        AppDropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            AppDropdownMenuItem(
                                text = { Text(stringResource(Screen.Workflow.stringResId)) },
                                onClick = { showOverflowMenu = false; onNavigate(Screen.Workflow.route) },
                                leadingIcon = {
                                    Icon(
                                        Screen.Workflow.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            AppDropdownMenuItem(
                                text = { Text(stringResource(Screen.Plugins.stringResId)) },
                                onClick = { showOverflowMenu = false; onNavigate(Screen.Plugins.route) },
                                leadingIcon = {
                                    Icon(
                                        Screen.Plugins.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            AppDropdownMenuItem(
                                text = { Text(stringResource(Screen.Mcp.stringResId)) },
                                onClick = { showOverflowMenu = false; onNavigate(Screen.Mcp.route) },
                                leadingIcon = {
                                    Icon(
                                        Screen.Mcp.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            AppDropdownMenuItem(
                                text = { Text(stringResource(Screen.Compare.stringResId)) },
                                onClick = { showOverflowMenu = false; onNavigate(Screen.Compare.route) },
                                leadingIcon = {
                                    Icon(
                                        Screen.Compare.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // FAB menu items
                AppDropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false }
                ) {
                    AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.new_agent)) },
                        onClick = {
                            showFabMenu = false
                            agentsViewModel.showNewForm()
                        },
                        leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                    AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.import_configs)) },
                        onClick = {
                            showFabMenu = false
                            importLauncher.launch(arrayOf("application/json"))
                        },
                        leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                    AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.export_configs)) },
                        onClick = {
                            showFabMenu = false
                            agentsViewModel.exportConfigs(context)
                        },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = stringResource(R.string.new_agent)
                    )
                }
            }
        }
    ) { padding ->
        if (uiState.agents.isEmpty() && uiState.isLoading) {
            // 首屏加载：显示骨架屏占位
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(4) {
                            AgentCardSkeletonItem()
                        }
                    }
                }
            }
        } else if (uiState.agents.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.SmartToy,
                title = stringResource(R.string.no_agents),
                description = stringResource(R.string.no_agents_subtitle),
                actionText = stringResource(R.string.new_agent),
                onAction = { agentsViewModel.showNewForm() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else if (useGrid) {
            // Grid layout for Expanded: 2-3 columns
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.agents, key = { it.id }) { agent ->
                    AgentGridCard(
                        agent = agent,
                        onEdit = { agentsViewModel.showEditForm(agent) },
                        onDelete = { agentsViewModel.deleteAgent(agent.id) }
                    )
                }
            }
        } else {
            // Single column list for Compact
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(uiState.agents, key = { it.id }) { agent ->
                            AgentCard(
                                agent = agent,
                                onEdit = { agentsViewModel.showEditForm(agent) },
                                onDelete = { agentsViewModel.deleteAgent(agent.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentGridCard(
    agent: AgentConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_agent_title)) },
            text = { Text(stringResource(R.string.delete_agent_message, agent.name)) },
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

    Box {
    AppCard(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Agent: ${agent.name}, type: ${agent.type.displayName}"
        }.combinedClickable(
            onClick = onEdit,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showContextMenu = true
            }
        ),
        shape = ShapeS12,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = ShapeS10,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            AgentTypeUi.icon(agent.type),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = agent.type.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
            if (agent.serverUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = agent.serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // Context menu
    AppDropdownMenu(
        expanded = showContextMenu,
        onDismissRequest = { showContextMenu = false }
    ) {
        AppDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_edit)) },
            onClick = { showContextMenu = false; onEdit() },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        AppDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
            onClick = { showContextMenu = false; showDeleteConfirm = true },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
        AppDropdownMenuItem(
            text = { Text(stringResource(R.string.copy_config)) },
            onClick = { showContextMenu = false },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
    }
    } // Box
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentCard(
    agent: AgentConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_agent_title)) },
            text = { Text(stringResource(R.string.delete_agent_message, agent.name)) },
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

    Box {
    AppCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics {
            contentDescription = "Agent: ${agent.name}, type: ${agent.type.displayName}"
        }.combinedClickable(
            onClick = onEdit,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showContextMenu = true
            }
        ),
        shape = ShapeS12,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = ShapeS10,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = agent.name.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = agent.type.displayName + if (agent.serverUrl.isNotEmpty()) " · ${agent.serverUrl}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }

    // Context menu
    AppDropdownMenu(
        expanded = showContextMenu,
        onDismissRequest = { showContextMenu = false }
    ) {
        AppDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_edit)) },
            onClick = { showContextMenu = false; onEdit() },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        AppDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
            onClick = { showContextMenu = false; showDeleteConfirm = true },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
        AppDropdownMenuItem(
            text = { Text(stringResource(R.string.copy_config)) },
            onClick = { showContextMenu = false },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
    }
    } // Box
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentFormDialog(
    agent: AgentConfig,
    onSave: (AgentConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(agent.name) }
    var serverUrl by remember { mutableStateOf(agent.serverUrl) }
    var apiKey by remember { mutableStateOf(agent.apiKey) }
    var type by remember { mutableStateOf(agent.type) }
    var model by remember { mutableStateOf(agent.model) }
    var systemPrompt by remember { mutableStateOf(agent.systemPrompt) }
    var temperature by remember { mutableStateOf(agent.temperature.toString()) }
    var maxTokens by remember { mutableStateOf(agent.maxTokens.toString()) }

    // ── 实时字段验证：统一走 AgentConfigValidator（与 iOS 表单 + ViewModel 兜底校验一致）──
    // 构造当前表单草稿并校验，错误按字段回填到对应输入框的 isError / supportingText。
    val draft = remember(name, serverUrl, apiKey, type, model, systemPrompt, temperature, maxTokens) {
        agent.copy(
            name = name, serverUrl = serverUrl, apiKey = apiKey, type = type,
            model = model, systemPrompt = systemPrompt,
            temperature = temperature.toFloatOrNull() ?: 0.7f,
            maxTokens = maxTokens.toIntOrNull() ?: 4096
        )
    }
    val validationResult = remember(draft) { AgentConfigValidator.validate(draft) }
    // LocalModel 走本地进程，serverUrl / apiKey 豁免校验且 UI 隐藏对应输入框
    val isLocal = type == AgentType.LocalModel
    // ComfyUI 本地部署通常无认证，apiKey 可选（与 AgentConfigValidator 一致）
    val apiKeyOptional = AgentTypeUi.apiKeyOptional(type)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (agent.serverUrl.isBlank()) stringResource(R.string.new_agent) else stringResource(R.string.btn_edit) + " " + agent.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = validationResult.errorFor("name") != null,
                    supportingText = validationResult.errorFor("name")?.let { msg ->
                        { Text(msg) }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                TypeSelector(
                    selected = type,
                    onSelect = { newType ->
                        // 切换类型时预填合理默认值（serverUrl/model/temperature/maxTokens/systemPrompt），
                        // 仅在用户尚未填写时填充，避免覆盖已输入内容
                        val prefilled = AgentTypeUi.withDefaults(
                            agent.copy(
                                type = newType,
                                serverUrl = serverUrl,
                                apiKey = apiKey,
                                model = model,
                                systemPrompt = systemPrompt,
                                temperature = temperature.toFloatOrNull() ?: 0.7f,
                                maxTokens = maxTokens.toIntOrNull() ?: 4096
                            )
                        )
                        type = newType
                        serverUrl = prefilled.serverUrl
                        model = prefilled.model
                        systemPrompt = prefilled.systemPrompt
                        temperature = prefilled.temperature.toString()
                        maxTokens = prefilled.maxTokens.toString()
                    }
                )
                if (isLocal) {
                    // LocalModel 走本地进程（Ollama / LM Studio），无需远程端点与 API Key
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "LocalModel 类型使用本地推理，无需填写服务器地址与 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.label_server_url)) },
                        placeholder = { Text(AgentTypeUi.serverUrlPlaceholder(type)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        isError = validationResult.errorFor("serverUrl") != null,
                        supportingText = validationResult.errorFor("serverUrl")?.let { msg ->
                            { Text(msg) }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val apiKeyLabelRes = if (apiKeyOptional) {
                        R.string.label_api_key_optional
                    } else {
                        R.string.label_api_key
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(apiKeyLabelRes)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        // H5: 视觉掩码，避免 apiKey 明文显示被肩窥/录屏泄漏
                        visualTransformation = PasswordVisualTransformation(),
                        isError = validationResult.errorFor("apiKey") != null,
                        supportingText = {
                            val err = validationResult.errorFor("apiKey")
                            if (err != null) {
                                Text(err)
                            } else if (apiKeyOptional && type == AgentType.ComfyUI) {
                                Text("ComfyUI 本地部署通常无需 API Key")
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(AgentTypeUi.modelLabel(type)) },
                    placeholder = { Text(AgentTypeUi.modelPlaceholder(type)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = validationResult.errorFor("model") != null,
                    supportingText = validationResult.errorFor("model")?.let { msg ->
                        { Text(msg) }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(AgentTypeUi.systemPromptLabel(type)) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    isError = validationResult.errorFor("systemPrompt") != null,
                    supportingText = validationResult.errorFor("systemPrompt")?.let { msg ->
                        { Text(msg) }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text(AgentTypeUi.temperatureLabel(type)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = validationResult.errorFor("temperature") != null,
                    supportingText = validationResult.errorFor("temperature")?.let { msg ->
                        { Text(msg) }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text(AgentTypeUi.maxTokensLabel(type)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = validationResult.errorFor("maxTokens") != null,
                    supportingText = validationResult.errorFor("maxTokens")?.let { msg ->
                        { Text(msg) }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(agent.copy(
                        name = name,
                        serverUrl = serverUrl,
                        apiKey = apiKey,
                        type = type,
                        model = model,
                        systemPrompt = systemPrompt,
                        temperature = temperature.toFloatOrNull() ?: 0.7f,
                        maxTokens = maxTokens.toIntOrNull() ?: 4096
                    ))
                },
                enabled = validationResult.isValid
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(
    selected: AgentType,
    onSelect: (AgentType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    // Listen for click events on the text field to toggle dropdown
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                expanded = !expanded
            }
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_protocol)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AgentType.entries.forEach { agentType ->
                AppDropdownMenuItem(
                    text = { Text(agentType.displayName) },
                    onClick = { onSelect(agentType); expanded = false }
                )
            }
        }
    }
}

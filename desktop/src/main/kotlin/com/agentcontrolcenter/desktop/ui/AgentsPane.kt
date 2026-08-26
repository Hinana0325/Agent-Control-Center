package com.agentcontrolcenter.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.app.AppStore
import com.agentcontrolcenter.desktop.app.Strings

/**
 * Agent 配置面板：配置卡片列表 + 新增/编辑对话框（对应移动端 AgentsScreen）。
 */
@Composable
fun AgentsPane(store: AppStore) {
    val agents by store.agents.collectAsState()
    val activeAgentId by store.activeAgentId.collectAsState()
    val connection by store.connectionState.collectAsState()

    var editing by remember { mutableStateOf<AgentConfig?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(Strings.t("agents.title"), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Strings.t("agents.add"))
                }
            }
            Spacer(Modifier.height(16.dp))

            if (agents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        Strings.t("agents.empty"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(agents, key = { it.id }) { agent ->
                        AgentCard(
                            agent = agent,
                            active = agent.id == activeAgentId,
                            connecting = agent.id == activeAgentId && !connection.isConnected,
                            onConnect = { store.connectAgent(agent) },
                            onDisconnect = { store.disconnect() },
                            onEdit = { editing = agent },
                            onDelete = { store.deleteAgent(agent.id) }
                        )
                    }
                }
            }
        }
    }

    // 新增 / 编辑对话框
    val editingConfig = editing
    if (creating || editingConfig != null) {
        AgentDialog(
            initial = editingConfig,
            onSave = { config ->
                store.saveAgent(config)
                creating = false
                editing = null
            },
            onDismiss = {
                creating = false
                editing = null
            }
        )
    }
}

@Composable
private fun AgentCard(
    agent: AgentConfig,
    active: Boolean,
    connecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 连接状态点
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        when {
                            active -> Color(0xFF4CAF50)
                            connecting -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        },
                        androidx.compose.foundation.shape.CircleShape
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${agent.type.displayName} · ${agent.serverUrl.ifBlank { "—" }}" +
                        (if (agent.model.isNotBlank()) " · ${agent.model}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            if (active) {
                TextButton(onClick = onDisconnect) { Text(Strings.t("agents.disconnect")) }
            } else {
                TextButton(onClick = onConnect) { Text(Strings.t("agents.connect")) }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = Strings.t("agents.edit"))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = Strings.t("agents.delete"))
            }
        }
    }
}

/** 新增/编辑 Agent 配置对话框。 */
@Composable
private fun AgentDialog(
    initial: AgentConfig?,
    onSave: (AgentConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: AgentType.LocalModel) }
    var serverUrl by remember { mutableStateOf(initial?.serverUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var systemPrompt by remember { mutableStateOf(initial?.systemPrompt ?: "") }
    var temperature by remember { mutableStateOf(initial?.temperature ?: 0.7f) }
    var maxTokensText by remember { mutableStateOf((initial?.maxTokens ?: 4096).toString()) }
    var typeMenuOpen by remember { mutableStateOf(false) }

    Dialog(onCloseRequest = onDismiss, title = Strings.t(if (initial == null) "agents.add" else "agents.edit")) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(
                Modifier
                    .padding(24.dp)
                    .width(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(Strings.t("agents.name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 类型选择
                Box {
                    OutlinedButton(onClick = { typeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${Strings.t("agents.type")}: ${type.displayName}")
                    }
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        AgentType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.displayName) },
                                onClick = {
                                    type = t
                                    typeMenuOpen = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(Strings.t("agents.server_url")) },
                    placeholder = { Text("http://127.0.0.1:11434") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(Strings.t("agents.api_key")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(Strings.t("agents.model")) },
                    placeholder = { Text("llama3") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(Strings.t("agents.system_prompt")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("${Strings.t("agents.temperature")}: %.1f".format(temperature), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { maxTokensText = it.filter { c -> c.isDigit() } },
                    label = { Text(Strings.t("agents.max_tokens")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(Strings.t("agents.cancel")) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val config = (initial ?: AgentConfig(id = java.util.UUID.randomUUID().toString())).copy(
                                name = name.trim().ifBlank { type.displayName },
                                type = type,
                                serverUrl = serverUrl.trim(),
                                apiKey = apiKey.trim(),
                                model = model.trim(),
                                systemPrompt = systemPrompt,
                                temperature = temperature,
                                maxTokens = maxTokensText.toIntOrNull() ?: 4096
                            )
                            onSave(config)
                        },
                        enabled = serverUrl.isNotBlank()
                    ) {
                        Text(Strings.t("agents.save"))
                    }
                }
            }
        }
    }
}

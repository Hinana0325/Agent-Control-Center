package com.agentcontrolcenter.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.desktop.app.AppStore
import com.agentcontrolcenter.desktop.app.Strings
import com.agentcontrolcenter.desktop.data.model.Message
import com.agentcontrolcenter.desktop.data.model.MessageRole
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 聊天面板：左侧会话列表 + 右侧消息流与输入框（对应移动端 ChatScreen 主链路）。
 */
@Composable
fun ChatPane(store: AppStore) {
    val sessions by store.sessions.collectAsState()
    val currentSessionId by store.currentSessionId.collectAsState()
    val messages by store.messages.collectAsState()
    val isStreaming by store.isStreaming.collectAsState()
    val activeAgentId by store.activeAgentId.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // ── 会话侧栏 ──
        Column(
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(Strings.t("nav.chat"), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { store.createSession() }) {
                    Icon(Icons.Filled.Add, contentDescription = Strings.t("chat.new_session"))
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        title = session.title,
                        updatedAt = session.updatedAt,
                        selected = session.id == currentSessionId,
                        onClick = { store.selectSession(session.id) },
                        onDelete = { store.deleteSession(session.id) }
                    )
                }
            }
        }

        // ── 消息区 ──
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f)) {
                if (currentSessionId == null) {
                    EmptyHint(Strings.t("chat.no_session"))
                } else if (messages.isEmpty()) {
                    EmptyHint(Strings.t("chat.empty"))
                } else {
                    MessageList(messages, isStreaming)
                }
            }

            // 输入栏（未连接 Agent 时禁用）
            var input by remember(currentSessionId) { mutableStateOf("") }
            val connected = activeAgentId != null
            Row(
                Modifier.fillMaxWidth().padding(12.dp).imePadding(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    enabled = connected && !isStreaming,
                    placeholder = { Text(Strings.t("chat.placeholder")) },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank()) {
                            store.sendMessage(input)
                            input = ""
                        }
                    })
                )
                Spacer(Modifier.width(8.dp))
                if (isStreaming) {
                    TextButton(onClick = { /* v1: HTTP 流由传输层自行收尾 */ }, enabled = false) {
                        Icon(Icons.Filled.Stop, contentDescription = Strings.t("chat.stop"))
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("chat.stop"))
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                store.sendMessage(input)
                                input = ""
                            }
                        },
                        enabled = connected && input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = Strings.t("chat.send"))
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("chat.send"))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    title: String,
    updatedAt: Long,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val time = remember(updatedAt) {
        SimpleDateFormat("MM-dd HH:mm").format(Date(updatedAt))
    }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.height(28.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = Strings.t("chat.delete_session"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<Message>, isStreaming: Boolean) {
    val listState = rememberLazyListState()
    // 新消息 / 流式追加时自动滚到底部
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message, isStreamingNow = isStreaming)
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageBubble(message: Message, isStreamingNow: Boolean) {
    val isUser = message.role == MessageRole.User
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        buildString {
                            append(SimpleDateFormat("HH:mm").format(Date(message.timestamp)))
                            if (!isUser && isStreamingNow) append(" ▍")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

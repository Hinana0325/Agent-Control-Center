package com.agentcontrolcenter.desktop

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.data.model.Message
import com.agentcontrolcenter.desktop.data.model.MessageRole
import com.agentcontrolcenter.desktop.data.model.Session
import com.agentcontrolcenter.desktop.data.persistence.AppSettings
import com.agentcontrolcenter.desktop.data.persistence.JsonStore
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JsonStore 持久化测试 — 临时目录隔离（不污染真实用户目录）。
 */
class JsonStoreTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"),
        "acc-desktop-test-${System.nanoTime()}").apply { mkdirs() }
    private val store = JsonStore(tmp)

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun `settings 往返`() = runBlocking {
        val s = AppSettings(darkTheme = false, locale = "zh", e2ePassphrase = "secret",
            closeToTray = false)
        store.saveSettings(s)
        val loaded = store.loadSettings()
        assertEquals(s, loaded)
    }

    @Test
    fun `agents 往返含全部字段`() = runBlocking {
        val agents = listOf(
            AgentConfig(
                id = "a1", name = "Local Ollama", type = AgentType.LocalModel,
                serverUrl = "http://127.0.0.1:11434", model = "llama3",
                temperature = 0.2f, maxTokens = 2048
            ),
            AgentConfig(
                id = "a2", name = "Remote Hermes", type = AgentType.Hermes,
                serverUrl = "wss://hermes.example.com", apiKey = "sk-test"
            )
        )
        store.saveAgents(agents)
        assertEquals(agents, store.loadAgents())
    }

    @Test
    fun `messages 按会话隔离追加且幂等`() = runBlocking {
        val sessionId = "session-1"
        val session = Session(id = sessionId, title = "Test")
        val m1 = Message(id = "m1", sessionId = sessionId, role = MessageRole.User, content = "hi")
        val m2 = Message(id = "m2", sessionId = sessionId, role = MessageRole.Assistant, content = "hello")

        var sessions = listOf(session)
        sessions = store.appendMessage(sessionId, m1, sessions)
        sessions = store.appendMessage(sessionId, m2, sessions)
        // 同 id 重放（流式 finalize 场景）不重复追加
        sessions = store.appendMessage(sessionId, m2, sessions)

        val loaded = store.loadMessages(sessionId)
        assertEquals(listOf(m1, m2), loaded)

        // 会话元数据同步：messageCount 与 updatedAt
        assertEquals(2, sessions.first { it.id == sessionId }.messageCount)

        // 其他会话文件隔离
        assertEquals(emptyList(), store.loadMessages("session-other"))
    }

    @Test
    fun `deleteMessages 删除会话文件`() = runBlocking {
        val sessionId = "session-del"
        val msg = Message(id = "x", sessionId = sessionId, role = MessageRole.User, content = "bye")
        store.appendMessage(sessionId, msg, listOf(Session(id = sessionId)))
        assertTrue(store.loadMessages(sessionId).isNotEmpty())
        store.deleteMessages(sessionId)
        assertEquals(emptyList(), store.loadMessages(sessionId))
    }

    @Test
    fun `损坏的 JSON 回退空态而非崩溃`() = runBlocking {
        File(tmp, "agents.json").writeText("{ not valid json !!")
        assertEquals(emptyList(), store.loadAgents())
    }
}

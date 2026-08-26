package com.agentcontrolcenter.desktop.data.persistence

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.data.model.Message
import com.agentcontrolcenter.desktop.data.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 桌面端本地持久化 — JSON 文件存储（跨三平台用户目录）。
 *
 * 布局（`~/.agent-control-center/`）：
 * ```
 * settings.json                     — AppSettings
 * agents.json                       — List<AgentConfig>
 * sessions.json                      — List<Session>
 * messages/<sessionId>.json          — List<Message>
 * ```
 *
 * 设计取舍：
 *  - kotlinx-serialization（默认值语义安全，文件缺字段不致 NPE——Gson 反射
 *    构造会绕过 Kotlin 非空校验）
 *  - 每会话独立文件：单会话写入不放大 IO；删除会话即删文件
 *  - [Mutex] 串行化写操作，避免并发保存撕裂文件；写入走 Dispatchers.IO
 *  - 移动端 Room v11 的表结构不直接照搬：桌面 v1 无 Insights/多表 join 需求，
 *    SQLDelight 迁移列入路线图（docs/desktop-development.md）
 */
class JsonStore(
    private val rootDir: File = File(System.getProperty("user.home"), ".agent-control-center")
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val messagesDir = File(rootDir, "messages")

    private val settingsFile = File(rootDir, "settings.json")
    private val agentsFile = File(rootDir, "agents.json")
    private val sessionsFile = File(rootDir, "sessions.json")

    init {
        rootDir.mkdirs()
        messagesDir.mkdirs()
    }

    // MARK: - Settings

    fun loadSettings(): AppSettings = readJson(settingsFile, AppSettings()) ?: AppSettings()

    suspend fun saveSettings(settings: AppSettings) = writeJson(settingsFile, settings)

    // MARK: - Agents

    fun loadAgents(): List<AgentConfig> = readJsonList(agentsFile) ?: emptyList()

    suspend fun saveAgents(agents: List<AgentConfig>) = writeJson(agentsFile, agents)

    // MARK: - Sessions

    fun loadSessions(): List<Session> = readJsonList(sessionsFile) ?: emptyList()

    suspend fun saveSessions(sessions: List<Session>) = writeJson(sessionsFile, sessions)

    // MARK: - Messages

    fun loadMessages(sessionId: String): List<Message> =
        readJsonList(messagesFileFor(sessionId)) ?: emptyList()

    /** 追加一条消息并同步会话元数据（updatedAt / messageCount）。 */
    suspend fun appendMessage(sessionId: String, message: Message, sessions: List<Session>): List<Session> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = messagesFileFor(sessionId)
                val list: MutableList<Message> = readJsonList<Message>(file)?.toMutableList() ?: mutableListOf()
                // 幂等：同 id 消息（流式 finalize 重放）不重复追加
                if (list.none { it.id == message.id }) {
                    list.add(message)
                    writeJsonLocked(file, list)
                }
                sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(updatedAt = System.currentTimeMillis(), messageCount = list.size)
                    } else s
                }
            }
        }

    suspend fun deleteMessages(sessionId: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                messagesFileFor(sessionId).delete()
            }
        }
    }

    private fun messagesFileFor(sessionId: String): File =
        File(messagesDir, "$sessionId.json")

    // MARK: - IO helpers

    /** 会话/消息标题可能含非法文件名字符，文件名统一用 sessionId（UUID）本身。 */
    private inline fun <reified T> readJson(file: File, default: T): T? =
        if (file.exists()) {
            try { json.decodeFromString<T>(file.readText()) } catch (_: Exception) { null } ?: default
        } else default

    private inline fun <reified T> readJsonList(file: File): List<T>? =
        if (file.exists()) {
            try { json.decodeFromString<List<T>>(file.readText()) } catch (_: Exception) { null }
        } else null

    private suspend inline fun <reified T> writeJson(file: File, value: T) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeJsonLocked(file, value)
            }
        }
    }

    /** 非原子化写入的调用点必须已持有 [mutex]。 */
    private inline fun <reified T> writeJsonLocked(file: File, value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        // 先写临时文件再原子重命名，避免断电/崩溃产生半截 JSON
        tmp.writeText(json.encodeToString(value))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(value))
            tmp.delete()
        }
    }
}

/**
 * 应用设置 — 桌面版（无 DataStore，JSON 持久化）。
 */
@kotlinx.serialization.Serializable
data class AppSettings(
    val darkTheme: Boolean = true,
    /** UI 语言：en / zh */
    val locale: String = "en",
    /** E2E 端到端加密口令（对等模式，WebSocket 传输生效）；空 = 关闭 */
    val e2ePassphrase: String = "",
    /** 关闭窗口时最小化到托盘而非退出 */
    val closeToTray: Boolean = true
)

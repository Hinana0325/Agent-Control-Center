package com.agentcontrolcenter.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentcontrolcenter.app.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 消息列表轻量投影（公平内存机制适配）：
 *
 * 排除 `attachmentData`（MB 级 Base64 字符串，消息 UI 零消费，纯死重量）。
 * 聊天页 Room Flow 每次全量重发列表时，长会话 + 带附件场景下该字段会把
 * 内存占用放大一个数量级。附件完整数据仍可通过 [MessageDao.getMessageById]
 * / [MessageDao.getMessagesBySessionOnce]（备份导出路径）按需获取。
 */
data class MessageListItem(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val metadataJson: String,
    val attachmentType: String? = null,
    val attachmentName: String? = null,
    val reaction: String = "",
    val replyToId: String? = null
)

/**
 * Insights 统计投影（公平内存机制适配）：
 *
 * 仅携带聚合所需三列（role/content/timestamp），排除 attachmentData /
 * metadataJson 等大字段 —— 原实现 `SELECT *` 全表载入做内存聚合，
 * 附件列会把瞬时内存峰值放大一个数量级。
 */
data class MessageStat(
    val role: String,
    val content: String,
    val timestamp: Long
)

@Dao
interface MessageDao {
    @Query(
        "SELECT id, sessionId, role, content, timestamp, status, metadataJson, " +
            "attachmentType, attachmentName, reaction, replyToId " +
            "FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC"
    )
    fun getMessagesBySession(sessionId: String): Flow<List<MessageListItem>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateMessage(id: String, content: String, status: String)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(): MessageEntity?

    @Query("SELECT * FROM messages WHERE content LIKE :query ESCAPE '\\' ORDER BY timestamp DESC LIMIT 50")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<MessageEntity>

    @Query("UPDATE messages SET reaction = :reaction WHERE id = :id")
    suspend fun updateReaction(id: String, reaction: String)

    // ── Data Insights queries ──

    @Query("SELECT role, content, timestamp FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesForStats(): List<MessageStat>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Long
}

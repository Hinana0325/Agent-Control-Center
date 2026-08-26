package com.agentcontrolcenter.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2.4: 添加 timestamp 和 role 单列索引。
 * - timestamp: getLastMessage 使用 ORDER BY timestamp DESC LIMIT 1（无 sessionId 过滤），
 *   复合索引 (sessionId, timestamp) 无法高效服务此查询。
 * - role: getAllMessagesForStats 内存过滤 it.role == "User"/"Assistant"，
 *   无索引时全表扫描。
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["sessionId", "timestamp"]),
        Index("timestamp"),
        Index("role")
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val metadataJson: String = "{}",
    val attachmentType: String? = null,
    val attachmentData: String? = null,
    val attachmentName: String? = null,
    val reaction: String = "",
    val replyToId: String? = null
)

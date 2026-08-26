package com.agentcontrolcenter.desktop.data.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 聊天消息 — 与 Android 端 `Message` 逐字段对齐（H14：协议字段名为
 * metadataJson: String）。
 */
@kotlinx.serialization.Serializable
data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.Sent,
    // H14: 协议要求字段名为 metadataJson: String
    val metadataJson: String = "{}",
    val attachmentType: String? = null, // "image", "file"
    val attachmentData: String? = null, // base64 or path
    val attachmentName: String? = null, // display name
    val reaction: String = "", // emoji reaction
    val replyToId: String? = null
) {
    // H14: 保留 metadata 作为计算属性，从 metadataJson 解析出 Map（Gson，
    // 与 Android 端一致——wire/元数据 JSON 均走 Gson）
    val metadata: Map<String, String>
        get() = try {
            gson.fromJson(metadataJson, METADATA_MAP_TYPE) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

    companion object {
        private val gson = Gson()
        private val METADATA_MAP_TYPE = object : TypeToken<Map<String, String>>() {}.type
    }
}

/**
 * 消息角色 — H13：apiValue 返回协议要求的 lowercase 角色名，
 * 供 OpenAIHttpTransport 构建请求体时引用。
 */
@kotlinx.serialization.Serializable
enum class MessageRole {
    User, Assistant, System, Tool;

    val apiValue: String get() = name.lowercase()
}

@kotlinx.serialization.Serializable
enum class MessageStatus { Sending, Sent, Received, Failed }

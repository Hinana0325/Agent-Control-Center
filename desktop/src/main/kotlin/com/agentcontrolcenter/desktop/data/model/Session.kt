package com.agentcontrolcenter.desktop.data.model

/**
 * 会话 — 与 Android 端 `Session` 逐字段对齐。
 */
@kotlinx.serialization.Serializable
data class Session(
    val id: String,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val messageCount: Int = 0,
    val summary: String = ""
)

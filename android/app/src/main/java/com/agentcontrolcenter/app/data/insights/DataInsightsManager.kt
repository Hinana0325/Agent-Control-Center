package com.agentcontrolcenter.app.data.insights

import com.agentcontrolcenter.app.core.database.dao.MessageDao
import com.agentcontrolcenter.app.core.database.dao.MessageStat
import com.agentcontrolcenter.app.core.database.dao.SessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 数据洞察管理器
 * 从数据库中提取统计信息，生成使用洞察报告
 *
 * 公平内存机制适配：消息统计改用 [MessageStat] 轻量投影（仅 role/content/timestamp
 * 三列），排除 attachmentData（MB 级 Base64）与 metadataJson 等大字段——
 * 原实现 `SELECT *` 全表载入做内存聚合，附件列会把瞬时内存峰值放大一个数量级。
 */
class DataInsightsManager(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao
) {
    data class Insights(
        val totalMessages: Long = 0,
        val totalSessions: Long = 0,
        val avgResponseTime: Long = 0,          // ms
        val mostActiveHour: Int = 0,            // 0-23
        val topAgentType: String = "",
        val messagesByDay: Map<String, Long> = emptyMap(),   // "yyyy-MM-dd" -> count
        val messagesByAgent: Map<String, Long> = emptyMap(), // agent name -> count
        val avgMessageLength: Int = 0,
        val longestStreak: Int = 0,             // consecutive days
        val messagesByHour: Map<Int, Long> = emptyMap(),     // hour -> count
        val userMessageCount: Long = 0,
        val assistantMessageCount: Long = 0
    )

    // Critical 4 修复：dateFormat/dayFormat 为死代码（各方法均使用局部 SimpleDateFormat），
    // 且 SimpleDateFormat 非线程安全，作为共享成员存在并发隐患，已删除。

    suspend fun generateInsights(): Insights {
        // Room suspend DAO 自身已切换到 Dispatchers.IO，无需在此包裹。
        val messages = messageDao.getAllMessagesForStats()
        val sessions = sessionDao.getAllSessionsForInsights()
        val messageCount = messageDao.getMessageCount()
        val sessionCount = sessionDao.getSessionCount()

        // F31 修复：后续聚合（filter / groupBy / sort / SimpleDateFormat.parse）属 CPU 密集计算，
        // 原实现直接在调用方协程（通常为 viewModelScope.launch → Dispatchers.Main.immediate）执行，
        // 消息量大时会卡主线程。统一包裹 withContext(Dispatchers.Default) 切到默认调度器。
        return withContext(Dispatchers.Default) {
            val userMessages = messages.filter { it.role == "User" }
            val assistantMessages = messages.filter { it.role == "Assistant" }

            Insights(
                totalMessages = messageCount,
                totalSessions = sessionCount,
                avgResponseTime = calculateAvgResponseTime(messages),
                mostActiveHour = findMostActiveHour(messages),
                topAgentType = findTopAgentType(sessions),
                messagesByDay = groupByDay(messages),
                messagesByAgent = groupByAgent(sessions),
                avgMessageLength = calculateAvgLength(messages),
                longestStreak = calculateLongestStreak(messages),
                messagesByHour = groupByHour(messages),
                userMessageCount = userMessages.size.toLong(),
                assistantMessageCount = assistantMessages.size.toLong()
            )
        }
    }

    private fun calculateAvgResponseTime(messages: List<MessageStat>): Long {
        if (messages.size < 2) return 0L
        var totalTime = 0L
        var count = 0
        for (i in 1 until messages.size) {
            val prev = messages[i - 1]
            val curr = messages[i]
            if (prev.role == "User" && curr.role == "Assistant") {
                totalTime += curr.timestamp - prev.timestamp
                count++
            }
        }
        return if (count > 0) totalTime / count else 0L
    }

    private fun findMostActiveHour(messages: List<MessageStat>): Int {
        if (messages.isEmpty()) return 0
        val hourMap = messages.groupBy { msg ->
            Calendar.getInstance().apply { timeInMillis = msg.timestamp }.get(Calendar.HOUR_OF_DAY)
        }
        return hourMap.maxByOrNull { it.value.size }?.key ?: 0
    }

    private fun findTopAgentType(sessions: List<com.agentcontrolcenter.app.core.database.entity.SessionEntity>): String {
        if (sessions.isEmpty()) return ""
        // 使用 session 标题中最常见的关键词作为 agent 类型
        val words = sessions.flatMap { it.title.split(" ") }
            .filter { it.length > 2 }
            .groupingBy { it.lowercase() }
            .eachCount()
        return words.maxByOrNull { it.value }?.key?.replaceFirstChar { it.uppercase() } ?: "General"
    }

    private fun groupByDay(messages: List<MessageStat>): Map<String, Long> {
        // Critical 4 修复：格式含年份 "yyyy-MM-dd"，避免跨年（如 2023-12-31 与 2024-12-31）
        // 被错误合并到同一组；使用局部 SimpleDateFormat 避免共享成员线程安全问题。
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return messages.groupBy { sdf.format(Date(it.timestamp)) }
            .mapValues { it.value.size.toLong() }
            .toSortedMap()
    }

    private fun groupByAgent(sessions: List<com.agentcontrolcenter.app.core.database.entity.SessionEntity>): Map<String, Long> {
        if (sessions.isEmpty()) return emptyMap()
        // 按 session 标题分组，取前 15 字符避免过长，并合并同名
        return sessions
            .groupBy { it.title.take(15).ifEmpty { "Untitled" } }
            .mapValues { it.value.size.toLong() }
            .toList()
            .sortedByDescending { it.second }
            .take(8)
            .toMap()
    }

    private fun calculateAvgLength(messages: List<MessageStat>): Int {
        if (messages.isEmpty()) return 0
        val totalLength = messages.sumOf { it.content.length }
        return totalLength / messages.size
    }

    private fun calculateLongestStreak(messages: List<MessageStat>): Int {
        if (messages.isEmpty()) return 0
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = messages.map { sdf.format(Date(it.timestamp)) }.distinct().sorted()
        if (days.isEmpty()) return 0

        var maxStreak = 1
        var currentStreak = 1
        for (i in 1 until days.size) {
            // SimpleDateFormat.parse 理论上可能因格式异常返回 null（或抛 ParseException）。
            // 这里用 try-catch 包裹日期解析，解析失败时重置当前连续计数并跳过本次循环，
            // 避免 !! 在异常数据下抛 NPE 导致整个 insights 计算崩溃。
            val prevDate = try { sdf.parse(days[i - 1]) } catch (_: Exception) { null }
            val currDate = try { sdf.parse(days[i]) } catch (_: Exception) { null }
            if (prevDate == null || currDate == null) {
                currentStreak = 1
                continue
            }
            val diff = (currDate.time - prevDate.time) / (1000 * 60 * 60 * 24)
            if (diff == 1L) {
                currentStreak++
                maxStreak = maxOf(maxStreak, currentStreak)
            } else {
                currentStreak = 1
            }
        }
        return maxStreak
    }

    private fun groupByHour(messages: List<MessageStat>): Map<Int, Long> {
        val hourMap = mutableMapOf<Int, Long>()
        for (i in 0..23) hourMap[i] = 0L
        messages.forEach { msg ->
            val hour = Calendar.getInstance().apply { timeInMillis = msg.timestamp }.get(Calendar.HOUR_OF_DAY)
            hourMap[hour] = (hourMap[hour] ?: 0L) + 1
        }
        return hourMap
    }

    /**
     * 生成纯文本报告用于导出
     */
    suspend fun exportReport(): String {
        val insights = generateInsights()
        return buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("        Agent Control Center Data Insights Report")
            appendLine("═══════════════════════════════════════")
            appendLine()
            appendLine("📊 Overview")
            appendLine("  Total Messages:  ${insights.totalMessages}")
            appendLine("  Total Sessions:  ${insights.totalSessions}")
            appendLine("  User Messages:   ${insights.userMessageCount}")
            appendLine("  Agent Messages:  ${insights.assistantMessageCount}")
            appendLine()
            appendLine("⏱️ Performance")
            appendLine("  Avg Response Time: ${insights.avgResponseTime} ms")
            appendLine("  Avg Message Length: ${insights.avgMessageLength} chars")
            appendLine("  Longest Streak:    ${insights.longestStreak} days")
            appendLine()
            appendLine("🕐 Activity")
            appendLine("  Most Active Hour:  ${insights.mostActiveHour}:00")
            appendLine()
            appendLine("📅 Messages by Day")
            insights.messagesByDay.forEach { (day, count) ->
                appendLine("  $day: ${"█".repeat((count / 2).toInt().coerceAtMost(20))} ($count)")
            }
            appendLine()
            appendLine("🤖 Agent Usage")
            insights.messagesByAgent.forEach { (name, count) ->
                appendLine("  $name: $count sessions")
            }
            appendLine()
            appendLine("⏰ Hourly Distribution")
            insights.messagesByHour.toSortedMap().forEach { (hour, count) ->
                val bar = "█".repeat((count / 2).toInt().coerceAtMost(15))
                appendLine("  %02d:00 %s (%d)".format(hour, bar, count))
            }
            appendLine()
            appendLine("═══════════════════════════════════════")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
        }
    }
}

package com.agentcontrolcenter.desktop.data.model

import com.agentcontrolcenter.desktop.agent.model.AgentType
import java.util.UUID

/**
 * 工作流数据模型 — Sprint 16.5 按 `protocol/schemas/workflow-schema.json`
 * 逐字段移植，与 Android `runtime/workflow/WorkflowEngine.kt` 内模型对齐。
 *
 * 字段名即 JSON key（kotlinx-serialization），保证六端 schema 兼容：
 * 与 Android 的差异仅在 WorkflowRunRecord 的日志字段——Android 受 Room
 * 限制存 JSON 字符串（logsJson / failedNodeIdsJson），此处直接存原生
 * List，与 schema 的 WorkflowRunRecord 契约一致。
 */

/** 工作流节点类型（schema NodeType）。 */
@kotlinx.serialization.Serializable
enum class NodeType(val displayName: String) {
    INPUT("Input"),
    AGENT("Agent"),
    TRANSFORM("Transform"),
    OUTPUT("Output")
}

/** TRANSFORM 节点的 8 种变换类型（schema TransformType）。 */
@kotlinx.serialization.Serializable
enum class TransformType(val displayName: String) {
    PASSTHROUGH("Pass Through"),
    EXTRACT("Extract"),
    TO_UPPERCASE("To Uppercase"),
    TO_LOWERCASE("To Lowercase"),
    TRIM("Trim"),
    PREFIX("Add Prefix"),
    SUFFIX("Add Suffix"),
    JSON_EXTRACT("Extract JSON Field")
}

/** 工作流节点（schema WorkflowNode）。AGENT 节点的 prompt 支持 {input} 占位符。 */
@kotlinx.serialization.Serializable
data class WorkflowNode(
    val id: String = UUID.randomUUID().toString(),
    val type: NodeType,
    val label: String = "",
    val agentType: AgentType? = null,
    val prompt: String = "",
    val transformType: TransformType = TransformType.PASSTHROUGH,
    /** 画布坐标（仅 UI 用途，不影响执行语义）。 */
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    /** 节点最近一次执行输出缓存，便于调试与回放。 */
    val outputCache: String = ""
)

/** 工作流边（schema WorkflowEdge）。condition 为 null 表示无条件执行。 */
@kotlinx.serialization.Serializable
data class WorkflowEdge(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val toNodeId: String,
    val condition: String? = null
)

/** 工作流定义（schema Workflow）：节点 + 边组成的有向无环图。 */
@kotlinx.serialization.Serializable
data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList()
)

/** 工作流执行状态快照（schema WorkflowExecutionState，运行时内存态，不落盘）。 */
@kotlinx.serialization.Serializable
data class WorkflowExecutionState(
    val isRunning: Boolean = false,
    val currentNodeId: String? = null,
    val completedNodeIds: Set<String> = emptySet(),
    val output: String = "",
    val error: String? = null,
    val logs: List<String> = emptyList()
)

/** 工作流执行记录状态（schema WorkflowRunStatus）。 */
@kotlinx.serialization.Serializable
enum class WorkflowRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 工作流执行历史记录（schema WorkflowRunRecord，落盘实体）。
 * 每次执行生成一条：RUNNING 起始，终态 COMPLETED / FAILED / CANCELLED。
 */
@kotlinx.serialization.Serializable
data class WorkflowRunRecord(
    val id: String,
    val workflowId: String,
    /** 工作流名称快照，防止工作流被删后历史无意义。 */
    val workflowName: String,
    val input: String = "",
    val output: String = "",
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: WorkflowRunStatus,
    val failedNodeIds: List<String> = emptyList(),
    val error: String? = null,
    val logs: List<String> = emptyList()
)

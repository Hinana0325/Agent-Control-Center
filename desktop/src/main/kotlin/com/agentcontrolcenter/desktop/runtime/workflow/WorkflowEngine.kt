package com.agentcontrolcenter.desktop.runtime.workflow

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.data.model.NodeType
import com.agentcontrolcenter.desktop.data.model.TransformType
import com.agentcontrolcenter.desktop.data.model.Workflow
import com.agentcontrolcenter.desktop.data.model.WorkflowExecutionState
import com.agentcontrolcenter.desktop.data.model.WorkflowNode
import com.agentcontrolcenter.desktop.data.model.WorkflowRunRecord
import com.agentcontrolcenter.desktop.data.model.WorkflowRunStatus
import com.agentcontrolcenter.desktop.data.persistence.JsonStore
import com.agentcontrolcenter.desktop.transport.TransportFactory
import com.agentcontrolcenter.desktop.transport.protocol.AgentEvent
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Agent 编排工作流引擎 — Sprint 16.5 自 Android `WorkflowEngine.kt` 移植。
 *
 * 执行语义与 Android 逐行为对齐（契约见 `protocol/schemas/workflow-schema.json`）：
 *  - 从 INPUT 节点 BFS 拓扑执行；前驱未全部完成的节点重新入队，
 *    单节点重新入队超过 [MAX_REQUEUES] 次判定为环（6003 场景）
 *  - 多前驱输出以空行（\n\n）拼接
 *  - AGENT 节点 prompt 的 {input} 占位符替换为上游输出；无上游则为原始输入
 *  - 单次 AGENT 调用 60 秒超时（6002 场景）
 *  - 节点级失败返回 "Error: ..." 字符串而非抛异常，保证后续节点可继续
 *
 * 与 Android 实现的差异（桌面形态取舍）：
 *  - 无 Hilt：依赖经构造函数注入；[TransportFactory] 为 open 供测试替身
 *  - 无 FeatureFlagManager：桌面端无特性开关层
 *  - 历史记录经 [JsonStore] 落 JSON 文件（Android 为 Room 表 workflow_runs）
 *  - CANCELLED 终态在 NonCancellable 中写入：外层协程已取消时普通
 *    suspend 调用会立刻再抛 CancellationException，不如此历史里会残留
 *    RUNNING 死记录
 */
class WorkflowEngine(
    private val store: JsonStore,
    private val transportFactory: TransportFactory = TransportFactory(),
    /** AGENT 节点建连用的配置源（AppStore 内存态，apiKey 已解密）。 */
    private val agentConfigs: () -> List<AgentConfig> = { emptyList() },
    /** E2E 口令源；null = 不加密（与 Android connect(config) 行为一致）。 */
    private val e2eKeyProvider: () -> String? = { null }
) {

    private val _executionState = MutableStateFlow(WorkflowExecutionState())
    val executionState: StateFlow<WorkflowExecutionState> = _executionState.asStateFlow()

    companion object {
        private const val MAX_REQUEUES = 100

        /** 单个 AGENT 节点调用超时（schema notes.timeout）。 */
        private const val AGENT_TIMEOUT_MILLIS = 60_000L
    }

    /** 执行历史（按 startedAt 倒序，最多 [limit] 条）。 */
    fun getHistory(workflowId: String? = null, limit: Int = 50): List<WorkflowRunRecord> =
        store.loadWorkflowRuns(workflowId, limit)

    suspend fun execute(workflow: Workflow, input: String): String = withContext(Dispatchers.IO) {
        _executionState.value = WorkflowExecutionState(isRunning = true)

        val record = WorkflowRunRecord(
            id = UUID.randomUUID().toString(),
            workflowId = workflow.id,
            workflowName = workflow.name,
            input = input,
            startedAt = System.currentTimeMillis(),
            status = WorkflowRunStatus.RUNNING
        )
        store.insertWorkflowRun(record)

        try {
            // Build adjacency map
            val adjacency = mutableMapOf<String, MutableList<String>>()
            workflow.edges.forEach { edge ->
                adjacency.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId)
            }

            // Find input node
            val inputNode = workflow.nodes.find { it.type == NodeType.INPUT }
                ?: throw IllegalStateException("No INPUT node found")

            // Topological execution via BFS
            val nodeMap = workflow.nodes.associateBy { it.id }
            val results = mutableMapOf<String, String>()
            val visited = mutableSetOf<String>()
            val queue = mutableListOf(inputNode.id)
            results[inputNode.id] = input

            val stepLog = mutableListOf<String>()
            stepLog.add("Starting workflow: ${workflow.name}")

            // Cycle detection: track how many times each node has been re-queued.
            // If a node is re-queued more than MAX_REQUEUES times, its predecessors
            // can never all complete (likely a cycle), so we abort.
            val requeueCount = mutableMapOf<String, Int>()

            while (queue.isNotEmpty()) {
                val nodeId = queue.removeFirst()
                if (nodeId in visited) continue
                val node = nodeMap[nodeId] ?: continue

                // Check all predecessors are done
                val predecessors = workflow.edges.filter { it.toNodeId == nodeId }.map { it.fromNodeId }
                if (predecessors.any { it !in visited && it != nodeId }) {
                    val count = (requeueCount[nodeId] ?: 0) + 1
                    if (count > MAX_REQUEUES) {
                        throw IllegalStateException("Possible cycle detected in workflow at node '${node.label.ifEmpty { nodeId }}'")
                    }
                    requeueCount[nodeId] = count
                    queue.add(nodeId) // Re-queue
                    continue
                }

                _executionState.value = _executionState.value.copy(
                    currentNodeId = nodeId,
                    logs = stepLog
                )

                // Execute node
                val nodeInput = if (predecessors.isEmpty()) {
                    results[nodeId] ?: input
                } else {
                    // Combine outputs from all predecessors
                    predecessors.map { results[it] ?: "" }.joinToString("\n\n")
                }

                val nodeOutput = executeNode(node, nodeInput)
                results[nodeId] = nodeOutput
                visited.add(nodeId)

                stepLog.add("[${node.type.displayName}] ${node.label.ifEmpty { node.id }}: ${nodeOutput.take(100)}")

                // Add successors to queue
                adjacency[nodeId]?.let { successors ->
                    queue.addAll(successors.filter { it !in visited })
                }
            }

            // Find output
            val outputNode = workflow.nodes.find { it.type == NodeType.OUTPUT }
            val finalOutput = outputNode?.let { results[it.id] }
                ?: results.values.lastOrNull()
                ?: ""

            stepLog.add("Workflow completed.")
            _executionState.value = WorkflowExecutionState(
                isRunning = false,
                completedNodeIds = visited,
                output = finalOutput,
                logs = stepLog
            )

            store.updateWorkflowRun(
                record.copy(
                    status = WorkflowRunStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    output = finalOutput,
                    logs = stepLog
                )
            )

            finalOutput
        } catch (e: CancellationException) {
            // 协程取消必须传播，绝不能被下面 catch (e: Exception) 吞掉，
            // 否则 execute 会把取消当成业务错误返回 "Error: ..." 字符串，
            // 破坏结构化并发（调用方永远等不到真正的取消信号）。
            _executionState.value = _executionState.value.copy(isRunning = false)
            withContext(NonCancellable) {
                runCatching {
                    store.updateWorkflowRun(
                        record.copy(
                            status = WorkflowRunStatus.CANCELLED,
                            completedAt = System.currentTimeMillis(),
                            output = "",
                            error = "Cancelled",
                            logs = _executionState.value.logs
                        )
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            _executionState.value = _executionState.value.copy(
                isRunning = false,
                error = e.message,
                logs = _executionState.value.logs + "Error: ${e.message}"
            )
            runCatching {
                store.updateWorkflowRun(
                    record.copy(
                        status = WorkflowRunStatus.FAILED,
                        completedAt = System.currentTimeMillis(),
                        output = "",
                        error = e.message,
                        logs = _executionState.value.logs
                    )
                )
            }
            "Error: ${e.message}"
        }
    }

    private suspend fun executeNode(node: WorkflowNode, input: String): String {
        return when (node.type) {
            NodeType.INPUT -> input
            NodeType.AGENT -> {
                val agentType = node.agentType ?: AgentType.Hermes
                val prompt = if (node.prompt.isNotBlank()) {
                    node.prompt.replace("{input}", input)
                } else {
                    input
                }
                executeAgent(agentType, prompt)
            }
            NodeType.TRANSFORM -> applyTransform(node.transformType, input, node.prompt)
            NodeType.OUTPUT -> input
        }
    }

    /**
     * 通过真实 Transport 执行 Agent 调用：
     * 1. 取第一个匹配 [agentType] 的配置 2. 建连 → sendMessage →
     * 3. 收集 events 直到 StreamComplete / Error（60s 超时）→ 4. shutdown。
     *
     * 无匹配配置、超时或空响应时返回错误字符串而非抛异常，
     * 保证工作流后续节点能继续执行（与 Android 一致）。
     */
    private suspend fun executeAgent(agentType: AgentType, prompt: String): String {
        val config = agentConfigs().firstOrNull { it.type == agentType }
            ?: return "Error: No agent config found for type ${agentType.displayName}"

        val transport = transportFactory.create(agentType)

        return try {
            transport.connect(config, e2eKeyProvider())
            // 使用唯一 sessionId 避免与主会话冲突
            val sessionId = "workflow_${UUID.randomUUID()}"
            transport.sendMessage(sessionId, prompt)

            val responseBuilder = StringBuilder()
            withTimeoutOrNull(AGENT_TIMEOUT_MILLIS) {
                try {
                    transport.events.collect { event ->
                        when (event) {
                            // delta 增量 append；non-delta 整帧覆盖
                            // （完整消息再 append 会与 delta 累加重复，Android M-16 同源修复）
                            is AgentEvent.MessageReceived -> {
                                if (event.isDelta) {
                                    responseBuilder.append(event.content)
                                } else {
                                    responseBuilder.setLength(0)
                                    responseBuilder.append(event.content)
                                }
                            }
                            is AgentEvent.Error -> throw FlowAbortException
                            is AgentEvent.StreamComplete -> throw FlowAbortException
                            else -> { /* Connected/Disconnected/Reconnecting 忽略 */ }
                        }
                    }
                } catch (_: FlowAbortException) {
                    // 正常结束：收到 StreamComplete 或 Error，停止收集
                }
            }

            responseBuilder.toString().ifBlank {
                "Error: Agent ${agentType.displayName} returned empty response"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}"
        } finally {
            transport.shutdown()
        }
    }

    private fun applyTransform(type: TransformType, input: String, extra: String): String {
        return when (type) {
            TransformType.PASSTHROUGH -> input
            TransformType.EXTRACT -> {
                // Simple regex extraction
                val regex = try { Regex(extra) } catch (_: Exception) { Regex("(.+)") }
                regex.find(input)?.groupValues?.getOrNull(1) ?: input
            }
            TransformType.TO_UPPERCASE -> input.uppercase()
            TransformType.TO_LOWERCASE -> input.lowercase()
            TransformType.TRIM -> input.trim()
            TransformType.PREFIX -> "$extra$input"
            TransformType.SUFFIX -> "$input$extra"
            TransformType.JSON_EXTRACT -> {
                // 与 Android 同源：Gson 解析（桌面 wire 协议同用 Gson）
                try {
                    val json = JsonParser.parseString(input).asJsonObject
                    if (json.has(extra)) {
                        // asString 会对字符串自动去除外层引号；非字符串字段则返回其文本表示
                        json.get(extra).let { elem ->
                            if (elem.isJsonPrimitive) elem.asString else elem.toString()
                        }
                    } else {
                        input
                    }
                } catch (_: Exception) { input }
            }
        }
    }

    fun reset() {
        _executionState.value = WorkflowExecutionState()
    }
}

/** 用于在收到 [AgentEvent.StreamComplete] 或 [AgentEvent.Error] 时中断 Flow 收集。 */
private object FlowAbortException : Exception()

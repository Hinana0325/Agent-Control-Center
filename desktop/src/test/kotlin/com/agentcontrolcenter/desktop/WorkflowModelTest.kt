package com.agentcontrolcenter.desktop

import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.data.model.NodeType
import com.agentcontrolcenter.desktop.data.model.TransformType
import com.agentcontrolcenter.desktop.data.model.Workflow
import com.agentcontrolcenter.desktop.data.model.WorkflowEdge
import com.agentcontrolcenter.desktop.data.model.WorkflowExecutionState
import com.agentcontrolcenter.desktop.data.model.WorkflowNode
import com.agentcontrolcenter.desktop.data.model.WorkflowRunRecord
import com.agentcontrolcenter.desktop.data.model.WorkflowRunStatus
import com.agentcontrolcenter.desktop.runtime.workflow.WorkflowTemplates
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Workflow 数据模型与序列化测试 — 验证按 `protocol/schemas/workflow-schema.json`
 * 逐字段移植（字段名 = JSON key，schema 形状的 JSON 必须能无损解码）。
 */
class WorkflowModelTest {

    // ── 模型默认值（与 Android WorkflowEngineTest 同源用例）──

    @Test
    fun `节点默认值`() {
        val node = WorkflowNode(type = NodeType.INPUT)
        assertTrue(node.id.isNotBlank())
        assertEquals(NodeType.INPUT, node.type)
        assertEquals("", node.label)
        assertNull(node.agentType)
        assertEquals("", node.prompt)
        assertEquals(TransformType.PASSTHROUGH, node.transformType)
        assertEquals(0f, node.positionX)
        assertEquals(0f, node.positionY)
        assertEquals("", node.outputCache)
    }

    @Test
    fun `节点自定义值`() {
        val node = WorkflowNode(
            id = "test-id",
            type = NodeType.AGENT,
            label = "My Agent",
            agentType = AgentType.OpenAI,
            prompt = "Hello {input}",
            positionX = 100f,
            positionY = 200f
        )
        assertEquals("test-id", node.id)
        assertEquals(NodeType.AGENT, node.type)
        assertEquals("My Agent", node.label)
        assertEquals(AgentType.OpenAI, node.agentType)
        assertEquals("Hello {input}", node.prompt)
        assertEquals(100f, node.positionX)
        assertEquals(200f, node.positionY)
    }

    @Test
    fun `边默认值与条件`() {
        val plain = WorkflowEdge(fromNodeId = "a", toNodeId = "b")
        assertTrue(plain.id.isNotBlank())
        assertNull(plain.condition)

        val conditional = WorkflowEdge(fromNodeId = "a", toNodeId = "b", condition = "contains:ok")
        assertEquals("contains:ok", conditional.condition)
    }

    @Test
    fun `工作流与执行状态默认值`() {
        val workflow = Workflow(name = "Test", description = "desc")
        assertTrue(workflow.id.isNotBlank())
        assertEquals("Test", workflow.name)
        assertTrue(workflow.nodes.isEmpty())
        assertTrue(workflow.edges.isEmpty())

        val state = WorkflowExecutionState()
        assertEquals(false, state.isRunning)
        assertNull(state.currentNodeId)
        assertTrue(state.completedNodeIds.isEmpty())
        assertEquals("", state.output)
        assertNull(state.error)
        assertTrue(state.logs.isEmpty())
    }

    // ── 序列化 schema 兼容 ──

    @Test
    fun `schema 形状的 Workflow JSON 可无损解码`() {
        val payload = """
            {
              "id": "wf-1",
              "name": "Demo",
              "description": "d",
              "nodes": [
                {"id": "n1", "type": "INPUT", "label": "In", "positionX": 10.0, "positionY": 20.0, "outputCache": "c"},
                {"id": "n2", "type": "AGENT", "agentType": "OpenAI", "prompt": "Sum: {input}"},
                {"id": "n3", "type": "TRANSFORM", "transformType": "JSON_EXTRACT", "prompt": "data"},
                {"id": "n4", "type": "OUTPUT"}
              ],
              "edges": [
                {"id": "e1", "fromNodeId": "n1", "toNodeId": "n2"},
                {"id": "e2", "fromNodeId": "n2", "toNodeId": "n3", "condition": "contains:ok"},
                {"id": "e3", "fromNodeId": "n3", "toNodeId": "n4"}
              ]
            }
        """.trimIndent()
        val wf = Json.decodeFromString<Workflow>(payload)
        assertEquals("wf-1", wf.id)
        assertEquals(4, wf.nodes.size)

        val agent = wf.nodes[1]
        assertEquals(NodeType.AGENT, agent.type)
        assertEquals(AgentType.OpenAI, agent.agentType)
        assertEquals("Sum: {input}", agent.prompt)

        val transform = wf.nodes[2]
        assertEquals(TransformType.JSON_EXTRACT, transform.transformType)
        // 缺省字段回落默认值（positionX 未给 → 0）
        assertEquals(0f, transform.positionX)

        assertEquals(10f, wf.nodes[0].positionX)
        assertEquals("contains:ok", wf.edges[1].condition)
        assertNull(wf.edges[0].condition)

        // 往返：编码 → 解码保持等值
        assertEquals(wf, Json.decodeFromString<Workflow>(Json.encodeToString(wf)))
    }

    @Test
    fun `WorkflowRunRecord 序列化往返含可空终态字段`() {
        val running = WorkflowRunRecord(
            id = "r1", workflowId = "wf-1", workflowName = "Demo",
            input = "hello", startedAt = 1700000000000, status = WorkflowRunStatus.RUNNING
        )
        val decoded = Json.decodeFromString<WorkflowRunRecord>(Json.encodeToString(running))
        assertEquals(running, decoded)
        assertNull(decoded.completedAt)
        assertNull(decoded.error)
        assertEquals(emptyList(), decoded.failedNodeIds)

        val failed = running.copy(
            status = WorkflowRunStatus.FAILED,
            completedAt = 1700000001000,
            error = "boom",
            logs = listOf("l1"),
            failedNodeIds = listOf("n2")
        )
        assertEquals(failed, Json.decodeFromString<WorkflowRunRecord>(Json.encodeToString(failed)))
    }

    @Test
    fun `四态 WorkflowRunStatus 枚举值与 schema 一致`() {
        assertEquals(listOf("RUNNING", "COMPLETED", "FAILED", "CANCELLED"),
            WorkflowRunStatus.entries.map { it.name })
    }

    // ── 预置模板 ──

    @Test
    fun `全部模板有名称且含 INPUT 与 OUTPUT 节点`() {
        val templates = WorkflowTemplates.allTemplates()
        assertEquals(4, templates.size)
        templates.forEach { t ->
            assertTrue(t.name.isNotBlank(), "模板名称不能为空")
            assertNotNull(t.nodes.firstOrNull { it.type == NodeType.INPUT }, "${t.name} 缺 INPUT")
            assertNotNull(t.nodes.firstOrNull { it.type == NodeType.OUTPUT }, "${t.name} 缺 OUTPUT")
        }
    }

    @Test
    fun `全部模板为 DAG（Kahn 拓扑可清空）`() {
        WorkflowTemplates.allTemplates().forEach { wf ->
            val indegree = wf.nodes.associate { it.id to 0 }.toMutableMap()
            wf.edges.forEach { indegree[it.toNodeId] = (indegree[it.toNodeId] ?: 0) + 1 }
            val queue = ArrayDeque(wf.nodes.filter { (indegree[it.id] ?: 0) == 0 }.map { it.id })
            var removed = 0
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                removed++
                wf.edges.filter { it.fromNodeId == id }.forEach {
                    indegree[it.toNodeId] = indegree[it.toNodeId]!! - 1
                    if (indegree[it.toNodeId] == 0) queue.add(it.toNodeId)
                }
            }
            assertEquals(wf.nodes.size, removed, "${wf.name} 含环")
        }
    }

    @Test
    fun `模板节点 id 与边端点闭合`() {
        WorkflowTemplates.allTemplates().forEach { wf ->
            val ids = wf.nodes.map { it.id }.toSet()
            wf.edges.forEach { e ->
                assertTrue(e.fromNodeId in ids, "${wf.name} 边 ${e.id} fromNodeId 不存在")
                assertTrue(e.toNodeId in ids, "${wf.name} 边 ${e.id} toNodeId 不存在")
            }
        }
    }
}

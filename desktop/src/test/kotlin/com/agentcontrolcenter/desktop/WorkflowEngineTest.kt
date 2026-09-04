package com.agentcontrolcenter.desktop

import com.agentcontrolcenter.desktop.agent.model.AgentConfig
import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.data.model.NodeType
import com.agentcontrolcenter.desktop.data.model.TransformType
import com.agentcontrolcenter.desktop.data.model.Workflow
import com.agentcontrolcenter.desktop.data.model.WorkflowEdge
import com.agentcontrolcenter.desktop.data.model.WorkflowNode
import com.agentcontrolcenter.desktop.data.model.WorkflowRunRecord
import com.agentcontrolcenter.desktop.data.model.WorkflowRunStatus
import com.agentcontrolcenter.desktop.data.persistence.JsonStore
import com.agentcontrolcenter.desktop.runtime.workflow.WorkflowEngine
import com.agentcontrolcenter.desktop.transport.TransportFactory
import com.agentcontrolcenter.desktop.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.desktop.transport.protocol.AgentEvent
import com.agentcontrolcenter.desktop.transport.protocol.AgentTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WorkflowEngine 执行语义测试 — 拓扑执行 / 环检测 / 8 种变换 /
 * AGENT 节点事件流收集 / 执行历史落盘，行为须与 Android 引擎一致
 * （`protocol/schemas/workflow-schema.json` 契约）。
 */
class WorkflowEngineTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"),
        "acc-desktop-wf-test-${System.nanoTime()}").apply { mkdirs() }
    private val store = JsonStore(tmp)

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    // MARK: - 测试脚手架

    /** INPUT → TRANSFORM([transform]/[prompt]) → OUTPUT 的线性工作流。 */
    private fun linearWorkflow(
        transform: TransformType = TransformType.PASSTHROUGH,
        prompt: String = ""
    ): Workflow {
        val input = WorkflowNode(id = "in", type = NodeType.INPUT, label = "Input")
        val t = WorkflowNode(
            id = "t", type = NodeType.TRANSFORM, label = "Transform",
            transformType = transform, prompt = prompt
        )
        val output = WorkflowNode(id = "out", type = NodeType.OUTPUT, label = "Output")
        return Workflow(
            id = "wf-linear", name = "Linear",
            nodes = listOf(input, t, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "in", toNodeId = "t"),
                WorkflowEdge(fromNodeId = "t", toNodeId = "out")
            )
        )
    }

    /** INPUT → AGENT([prompt]) → OUTPUT 的工作流。 */
    private fun agentWorkflow(prompt: String): Workflow {
        val input = WorkflowNode(id = "in", type = NodeType.INPUT)
        val agent = WorkflowNode(
            id = "agent", type = NodeType.AGENT, agentType = AgentType.OpenAI, prompt = prompt
        )
        val output = WorkflowNode(id = "out", type = NodeType.OUTPUT)
        return Workflow(
            id = "wf-agent", name = "Agent Flow",
            nodes = listOf(input, agent, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "in", toNodeId = "agent"),
                WorkflowEdge(fromNodeId = "agent", toNodeId = "out")
            )
        )
    }

    /** 脚本化传输：按序回放事件；[hang] 为 true 时事件流永不结束（超时/取消用）。 */
    private class ScriptedTransport(
        script: List<AgentEvent> = emptyList(),
        private val hang: Boolean = false
    ) : AgentTransport {
        val sent = mutableListOf<Pair<String, String>>()
        var connectedConfig: AgentConfig? = null
        override val events: Flow<AgentEvent> =
            if (hang) flow { awaitCancellation() } else script.asFlow()
        override val connectionState: StateFlow<AgentConnectionState> =
            MutableStateFlow(AgentConnectionState())
        override fun connect(config: AgentConfig, e2eKey: String?) {
            connectedConfig = config
        }
        override suspend fun sendMessage(sessionId: String, content: String) {
            sent.add(sessionId to content)
        }
        override fun disconnect() {}
        override fun shutdown() {}
    }

    private class FakeTransportFactory(private val transport: AgentTransport) : TransportFactory() {
        override fun create(type: AgentType): AgentTransport = transport
    }

    private val openAiConfig = AgentConfig(
        id = "a1", name = "OpenAI", type = AgentType.OpenAI,
        serverUrl = "https://api.example.com", apiKey = "sk-test"
    )

    // MARK: - 拓扑执行与变换

    @Test
    fun `线性工作流 PASSTHROUGH 透传并产出执行状态`() = runBlocking {
        val engine = WorkflowEngine(store)
        val out = engine.execute(linearWorkflow(), "hello")

        assertEquals("hello", out)
        val state = engine.executionState.value
        assertEquals(false, state.isRunning)
        assertNull(state.error)
        assertEquals(setOf("in", "t", "out"), state.completedNodeIds)
        assertEquals("hello", state.output)
        assertEquals("Workflow completed.", state.logs.last())
    }

    @Test
    fun `TO_UPPERCASE 与 TO_LOWERCASE 变换`() = runBlocking {
        val engine = WorkflowEngine(store)
        assertEquals("HELLO", engine.execute(linearWorkflow(TransformType.TO_UPPERCASE), "hello"))
        assertEquals("hello", engine.execute(linearWorkflow(TransformType.TO_LOWERCASE), "HELLO"))
    }

    @Test
    fun `TRIM 变换去首尾空白`() = runBlocking {
        assertEquals("hi", WorkflowEngine(store)
            .execute(linearWorkflow(TransformType.TRIM), "  hi  "))
    }

    @Test
    fun `PREFIX 与 SUFFIX 变换`() = runBlocking {
        val engine = WorkflowEngine(store)
        assertEquals("[Q] hello", engine.execute(linearWorkflow(TransformType.PREFIX, "[Q] "), "hello"))
        assertEquals("hello [END]", engine.execute(linearWorkflow(TransformType.SUFFIX, " [END]"), "hello"))
    }

    @Test
    fun `EXTRACT 正则命中提取第一捕获组`() = runBlocking {
        assertEquals("tag", WorkflowEngine(store)
            .execute(linearWorkflow(TransformType.EXTRACT, "\\[(.+)]"), "[tag] body"))
    }

    @Test
    fun `EXTRACT 非法正则回退全量捕获`() = runBlocking {
        // 与 Android 一致：非法正则退化为 (.+)，捕获组 1 即全文
        assertEquals("xyz", WorkflowEngine(store)
            .execute(linearWorkflow(TransformType.EXTRACT, "([invalid"), "xyz"))
    }

    @Test
    fun `JSON_EXTRACT 提取字符串字段与嵌套对象`() = runBlocking {
        val engine = WorkflowEngine(store)
        assertEquals("value", engine.execute(
            linearWorkflow(TransformType.JSON_EXTRACT, "name"), """{"name":"value","n":5}"""))
        // 非原始类型字段返回其 JSON 文本表示
        assertEquals("""{"a":1}""", engine.execute(
            linearWorkflow(TransformType.JSON_EXTRACT, "nested"), """{"nested":{"a":1}}"""))
    }

    @Test
    fun `JSON_EXTRACT 字段缺失或非法 JSON 回退原输入`() = runBlocking {
        val engine = WorkflowEngine(store)
        val missing = """{"other":1}"""
        assertEquals(missing, engine.execute(linearWorkflow(TransformType.JSON_EXTRACT, "name"), missing))
        assertEquals("not json", engine.execute(
            linearWorkflow(TransformType.JSON_EXTRACT, "name"), "not json"))
    }

    @Test
    fun `多前驱输出以空行拼接`() = runBlocking {
        val input = WorkflowNode(id = "in", type = NodeType.INPUT)
        val upper = WorkflowNode(id = "t1", type = NodeType.TRANSFORM, transformType = TransformType.TO_UPPERCASE)
        val prefix = WorkflowNode(id = "t2", type = NodeType.TRANSFORM, transformType = TransformType.PREFIX, prompt = "p:")
        val output = WorkflowNode(id = "out", type = NodeType.OUTPUT)
        val wf = Workflow(
            id = "wf-fanin", name = "FanIn",
            nodes = listOf(input, upper, prefix, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "in", toNodeId = "t1"),
                WorkflowEdge(fromNodeId = "in", toNodeId = "t2"),
                WorkflowEdge(fromNodeId = "t1", toNodeId = "out"),
                WorkflowEdge(fromNodeId = "t2", toNodeId = "out")
            )
        )
        // 前驱顺序跟随边列表顺序：t1 在前 t2 在后
        assertEquals("HELLO\n\np:hello", WorkflowEngine(store).execute(wf, "hello"))
    }

    @Test
    fun `孤立节点不执行且不进已完成集合`() = runBlocking {
        val wf = linearWorkflow().let { linear ->
            linear.copy(nodes = linear.nodes + WorkflowNode(id = "orphan", type = NodeType.TRANSFORM))
        }
        val engine = WorkflowEngine(store)
        assertEquals("hello", engine.execute(wf, "hello"))
        assertEquals(setOf("in", "t", "out"), engine.executionState.value.completedNodeIds)
    }

    // MARK: - 环检测与结构校验

    @Test
    fun `环检测返回错误串且历史记 FAILED`() = runBlocking {
        val wf = Workflow(
            id = "wf-cycle", name = "Cycle",
            nodes = listOf(
                WorkflowNode(id = "in", type = NodeType.INPUT),
                WorkflowNode(id = "b", type = NodeType.TRANSFORM)
            ),
            edges = listOf(
                WorkflowEdge(fromNodeId = "in", toNodeId = "b"),
                WorkflowEdge(fromNodeId = "b", toNodeId = "in")
            )
        )
        val engine = WorkflowEngine(store)
        val out = engine.execute(wf, "hello")

        assertEquals("Error: Possible cycle detected in workflow at node 'in'", out)
        assertEquals("Possible cycle detected in workflow at node 'in'", engine.executionState.value.error)

        val run = store.loadWorkflowRuns().single()
        assertEquals(WorkflowRunStatus.FAILED, run.status)
        assertNotNull(run.completedAt)
        assertTrue(run.error!!.contains("cycle"))
    }

    @Test
    fun `无 INPUT 节点返回错误串`() = runBlocking {
        val wf = Workflow(
            id = "wf-noinput", name = "NoInput",
            nodes = listOf(
                WorkflowNode(id = "t", type = NodeType.TRANSFORM),
                WorkflowNode(id = "out", type = NodeType.OUTPUT)
            ),
            edges = listOf(WorkflowEdge(fromNodeId = "t", toNodeId = "out"))
        )
        assertEquals("Error: No INPUT node found", WorkflowEngine(store).execute(wf, "hello"))
        assertEquals(WorkflowRunStatus.FAILED, store.loadWorkflowRuns().single().status)
    }

    // MARK: - AGENT 节点

    @Test
    fun `AGENT 无匹配配置返回错误串且工作流仍完成`() = runBlocking {
        val engine = WorkflowEngine(store, agentConfigs = { emptyList() })
        val out = engine.execute(agentWorkflow(prompt = "Sum: {input}"), "hello")

        // 节点级失败不抛异常，错误串作为节点输出流向下游
        assertEquals("Error: No agent config found for type OpenAI Compatible", out)
        assertEquals(WorkflowRunStatus.COMPLETED, store.loadWorkflowRuns().single().status)
    }

    @Test
    fun `AGENT 占位符替换 sessionId 前缀与建连配置`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(AgentEvent.MessageReceived("ok", isDelta = true), AgentEvent.StreamComplete)
        )
        val engine = WorkflowEngine(
            store, FakeTransportFactory(transport), agentConfigs = { listOf(openAiConfig) }
        )

        assertEquals("ok", engine.execute(agentWorkflow(prompt = "Sum: {input}"), "hello"))

        val (sessionId, content) = transport.sent.single()
        assertEquals("Sum: hello", content)
        assertTrue(sessionId.startsWith("workflow_"), "工作流会话须用唯一前缀避免与主会话冲突")
        assertEquals(openAiConfig, transport.connectedConfig)
    }

    @Test
    fun `AGENT delta 增量累加`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                AgentEvent.MessageReceived("a", isDelta = true),
                AgentEvent.MessageReceived("b", isDelta = true),
                AgentEvent.StreamComplete
            )
        )
        val engine = WorkflowEngine(
            store, FakeTransportFactory(transport), agentConfigs = { listOf(openAiConfig) }
        )
        assertEquals("ab", engine.execute(agentWorkflow(prompt = "{input}"), "x"))
    }

    @Test
    fun `AGENT 整帧覆盖 delta 累积避免重复`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                AgentEvent.MessageReceived("partial", isDelta = true),
                AgentEvent.MessageReceived("FULL RESPONSE", isDelta = false),
                AgentEvent.StreamComplete
            )
        )
        val engine = WorkflowEngine(
            store, FakeTransportFactory(transport), agentConfigs = { listOf(openAiConfig) }
        )
        assertEquals("FULL RESPONSE", engine.execute(agentWorkflow(prompt = "{input}"), "x"))
    }

    @Test
    fun `AGENT 事件 Error 中断收集并保留已累积内容`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                AgentEvent.MessageReceived("partial", isDelta = true),
                AgentEvent.Error("boom")
            )
        )
        val engine = WorkflowEngine(
            store, FakeTransportFactory(transport), agentConfigs = { listOf(openAiConfig) }
        )
        assertEquals("partial", engine.execute(agentWorkflow(prompt = "{input}"), "x"))
    }

    @Test
    fun `AGENT 空响应返回错误串`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(AgentEvent.Connected("https://api.example.com", AgentType.OpenAI), AgentEvent.StreamComplete)
        )
        val engine = WorkflowEngine(
            store, FakeTransportFactory(transport), agentConfigs = { listOf(openAiConfig) }
        )
        assertEquals(
            "Error: Agent OpenAI Compatible returned empty response",
            engine.execute(agentWorkflow(prompt = "{input}"), "x")
        )
    }

    // MARK: - 执行历史

    @Test
    fun `执行历史 COMPLETED 落盘`() = runBlocking {
        val engine = WorkflowEngine(store)
        engine.execute(linearWorkflow().copy(id = "wf-hist", name = "Hist"), "hello")

        val run = store.loadWorkflowRuns().single()
        assertEquals(WorkflowRunStatus.COMPLETED, run.status)
        assertEquals("wf-hist", run.workflowId)
        assertEquals("Hist", run.workflowName)
        assertEquals("hello", run.input)
        assertEquals("hello", run.output)
        assertNotNull(run.completedAt)
        assertTrue(run.completedAt >= run.startedAt)
        assertNull(run.error)
        assertTrue("Workflow completed." in run.logs)
        assertEquals(emptyList(), run.failedNodeIds)
    }

    @Test
    fun `loadWorkflowRuns 倒序过滤与 limit`() = runBlocking {
        fun record(id: String, workflowId: String, startedAt: Long) = WorkflowRunRecord(
            id = id, workflowId = workflowId, workflowName = "W$workflowId",
            startedAt = startedAt, status = WorkflowRunStatus.COMPLETED
        )
        store.insertWorkflowRun(record("r1", "wf-a", 1000))
        store.insertWorkflowRun(record("r2", "wf-b", 3000))
        store.insertWorkflowRun(record("r3", "wf-a", 2000))

        assertEquals(listOf("r2", "r3", "r1"), store.loadWorkflowRuns().map { it.id })
        assertEquals(listOf("r3", "r1"), store.loadWorkflowRuns(workflowId = "wf-a").map { it.id })
        assertEquals(listOf("r2", "r3"), store.loadWorkflowRuns(limit = 2).map { it.id })

        // 引擎侧历史入口与 store 一致
        val engine = WorkflowEngine(store)
        assertEquals(listOf("r2", "r3", "r1"), engine.getHistory().map { it.id })
    }

    @Test
    fun `取消执行传播取消信号且历史记 CANCELLED`() = runBlocking {
        val transport = ScriptedTransport(hang = true)
        val engine = WorkflowEngine(
            store, FakeTransportFactory(transport), agentConfigs = { listOf(openAiConfig) }
        )

        var rethrown = false
        val job = launch {
            try {
                engine.execute(agentWorkflow(prompt = "{input}"), "x")
            } catch (e: CancellationException) {
                rethrown = true
                throw e
            }
        }
        delay(300)
        job.cancelAndJoin()

        assertTrue(rethrown, "取消必须向调用方传播，不能被吞成错误字符串")
        val run = store.loadWorkflowRuns().single()
        assertEquals(WorkflowRunStatus.CANCELLED, run.status)
        assertEquals("Cancelled", run.error)
        assertTrue(run.completedAt != null, "取消也须落终态时间戳")
    }

    @Test
    fun `reset 清空执行状态`() = runBlocking {
        val engine = WorkflowEngine(store)
        engine.execute(linearWorkflow(), "hello")
        engine.reset()
        assertEquals(
            com.agentcontrolcenter.desktop.data.model.WorkflowExecutionState(),
            engine.executionState.value
        )
    }
}

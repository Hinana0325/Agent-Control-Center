package com.agentcontrolcenter.desktop.runtime.workflow

import com.agentcontrolcenter.desktop.agent.model.AgentType
import com.agentcontrolcenter.desktop.data.model.NodeType
import com.agentcontrolcenter.desktop.data.model.TransformType
import com.agentcontrolcenter.desktop.data.model.Workflow
import com.agentcontrolcenter.desktop.data.model.WorkflowEdge
import com.agentcontrolcenter.desktop.data.model.WorkflowNode

/**
 * 预置工作流模板 — 与 Android `WorkflowTemplates` 逐字段一致
 * （节点 id / label / prompt / 布局坐标 / 边结构均相同，保证六端模板可互换）。
 */
object WorkflowTemplates {

    fun translationChain(): Workflow {
        val input = WorkflowNode(
            id = "input",
            type = NodeType.INPUT,
            label = "Input Text",
            positionX = 50f, positionY = 200f
        )
        val translate = WorkflowNode(
            id = "translate",
            type = NodeType.AGENT,
            label = "Translate",
            agentType = AgentType.OpenAI,
            prompt = "Translate the following text to the target language. Preserve meaning and tone:\n\n{input}",
            positionX = 250f, positionY = 200f
        )
        val review = WorkflowNode(
            id = "review",
            type = NodeType.AGENT,
            label = "Review & Polish",
            agentType = AgentType.OpenAI,
            prompt = "Review this translation for accuracy and natural flow. Fix any issues:\n\n{input}",
            positionX = 450f, positionY = 200f
        )
        val output = WorkflowNode(
            id = "output",
            type = NodeType.OUTPUT,
            label = "Final Translation",
            positionX = 650f, positionY = 200f
        )

        return Workflow(
            name = "Translation Chain",
            description = "Translate → Review & Polish",
            nodes = listOf(input, translate, review, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "input", toNodeId = "translate"),
                WorkflowEdge(fromNodeId = "translate", toNodeId = "review"),
                WorkflowEdge(fromNodeId = "review", toNodeId = "output")
            )
        )
    }

    fun codeReview(): Workflow {
        val input = WorkflowNode(
            id = "input",
            type = NodeType.INPUT,
            label = "Code Input",
            positionX = 50f, positionY = 200f
        )
        val analyze = WorkflowNode(
            id = "analyze",
            type = NodeType.AGENT,
            label = "Analyze Code",
            agentType = AgentType.OpenCode,
            prompt = "Analyze the following code for potential issues, bugs, and improvements. Provide a structured analysis:\n\n{input}",
            positionX = 250f, positionY = 100f
        )
        val suggest = WorkflowNode(
            id = "suggest",
            type = NodeType.AGENT,
            label = "Generate Suggestions",
            agentType = AgentType.OpenCode,
            prompt = "Based on this code analysis, provide specific improvement suggestions with code examples:\n\n{input}",
            positionX = 250f, positionY = 300f
        )
        val output = WorkflowNode(
            id = "output",
            type = NodeType.OUTPUT,
            label = "Review Report",
            positionX = 500f, positionY = 200f
        )

        return Workflow(
            name = "Code Review",
            description = "Analyze → Suggest Improvements",
            nodes = listOf(input, analyze, suggest, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "input", toNodeId = "analyze"),
                WorkflowEdge(fromNodeId = "input", toNodeId = "suggest"),
                WorkflowEdge(fromNodeId = "analyze", toNodeId = "output"),
                WorkflowEdge(fromNodeId = "suggest", toNodeId = "output")
            )
        )
    }

    fun researchAssistant(): Workflow {
        val input = WorkflowNode(
            id = "input",
            type = NodeType.INPUT,
            label = "Research Topic",
            positionX = 50f, positionY = 200f
        )
        val search = WorkflowNode(
            id = "search",
            type = NodeType.AGENT,
            label = "Search & Gather",
            agentType = AgentType.OpenAI,
            prompt = "Research the following topic. Provide key facts, data points, and relevant information:\n\n{input}",
            positionX = 250f, positionY = 200f
        )
        val extract = WorkflowNode(
            id = "extract",
            type = NodeType.TRANSFORM,
            label = "Extract Key Points",
            transformType = TransformType.PASSTHROUGH,
            positionX = 450f, positionY = 100f
        )
        val summarize = WorkflowNode(
            id = "summarize",
            type = NodeType.AGENT,
            label = "Summarize",
            agentType = AgentType.OpenAI,
            prompt = "Create a concise, well-structured summary of this research:\n\n{input}",
            positionX = 450f, positionY = 300f
        )
        val output = WorkflowNode(
            id = "output",
            type = NodeType.OUTPUT,
            label = "Research Report",
            positionX = 650f, positionY = 200f
        )

        return Workflow(
            name = "Research Assistant",
            description = "Search → Summarize",
            nodes = listOf(input, search, extract, summarize, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "input", toNodeId = "search"),
                WorkflowEdge(fromNodeId = "search", toNodeId = "extract"),
                WorkflowEdge(fromNodeId = "search", toNodeId = "summarize"),
                WorkflowEdge(fromNodeId = "extract", toNodeId = "output"),
                WorkflowEdge(fromNodeId = "summarize", toNodeId = "output")
            )
        )
    }

    /**
     * ComfyUI 文生图工作流 — INPUT → AGENT(ComfyUI) → OUTPUT。
     *
     * 注：桌面端 ComfyUI 传输尚未移植（TransportFactory 降级为
     * UnsupportedTransport），此模板仅在配置了受支持 AgentType 的
     * 等价节点时可运行，保留以维持六端模板集合一致。
     */
    fun imageGeneration(): Workflow {
        val input = WorkflowNode(
            id = "input",
            type = NodeType.INPUT,
            label = "Prompt",
            positionX = 50f, positionY = 200f
        )
        val generate = WorkflowNode(
            id = "generate",
            type = NodeType.AGENT,
            label = "Generate Image",
            agentType = AgentType.ComfyUI,
            prompt = "{input}",
            positionX = 250f, positionY = 200f
        )
        val output = WorkflowNode(
            id = "output",
            type = NodeType.OUTPUT,
            label = "Image Result",
            positionX = 450f, positionY = 200f
        )

        return Workflow(
            name = "Image Generation",
            description = "ComfyUI 文生图",
            nodes = listOf(input, generate, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "input", toNodeId = "generate"),
                WorkflowEdge(fromNodeId = "generate", toNodeId = "output")
            )
        )
    }

    fun allTemplates(): List<Workflow> = listOf(
        translationChain(),
        codeReview(),
        researchAssistant(),
        imageGeneration()
    )
}

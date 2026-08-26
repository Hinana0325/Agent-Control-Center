// WorkflowEngine（对标 android runtime/workflow/WorkflowEngine.kt）
// Agent 编排工作流引擎 — 支持多节点 DAG 执行。
//
// 节点类型：
// - INPUT: 接收外部输入
// - AGENT: 调用 Agent（通过 TransportFactory 创建临时传输）
// - TRANSFORM: 数据转换（提取、格式化、过滤）
// - OUTPUT: 输出最终结果
//
// 执行模型（与 Android 对齐）：BFS 拓扑执行 + requeue 计数环检测。
// AGENT 节点不再使用 mock executor，而是查询数据库中匹配 AgentType 的
// 第一个 AgentConfigEntity，创建临时 AgentTransport，connect → sendMessage →
// 收集 events 直到 StreamComplete → shutdown → 返回拼接结果。
//
// ArkTS 无 Kotlin StateFlow，执行状态通过 onStateChanged 回调通知 UI。

import { AgentConfig, AgentType } from '../protocol/Agent';
import { AgentEvent, AgentEventType } from '../protocol/AgentEvent';
import { NodeType, TransformType, Workflow, WorkflowNode, WorkflowRunStatus,
  WorkflowExecutionState } from '../protocol/Workflow';
import { AgentTransport, EventListener } from '../transport/AgentTransport';
import { TransportFactory } from '../transport/TransportFactory';
import { AgentConfigDao } from '../persistence/AgentConfigDao';
import { WorkflowRunDao } from '../persistence/WorkflowRunDao';
import { AgentConfigEntity, WorkflowRunEntity } from '../persistence/Entities';
import { KeystoreManager } from '../security/KeystoreManager';

// AGENT 节点超时保护，避免工作流无限挂起（与 Android 60s 一致）
const AGENT_TIMEOUT_MS: number = 60000;

// 环检测：节点 requeue 超过该次数视为图中有环
const MAX_REQUEUES: number = 100;

// 简易 UUID 生成（ArkTS 无 crypto.randomUUID）
function uuid(): string {
  const hex: string = '0123456789abcdef';
  let s: string = '';
  for (let i: number = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      s += '-';
    } else {
      s += hex.charAt(Math.floor(Math.random() * 16));
    }
  }
  return s;
}

export class WorkflowEngine {
  private agentConfigDao: AgentConfigDao = new AgentConfigDao();
  private workflowRunDao: WorkflowRunDao = new WorkflowRunDao();

  private executionState: WorkflowExecutionState = {
    isRunning: false,
    currentNodeId: null,
    completedNodeIds: [],
    output: '',
    error: null,
    logs: []
  };

  // UI 状态回调（对标 Android executionState StateFlow 订阅）
  onStateChanged?: (state: WorkflowExecutionState) => void;

  /**
   * 获取工作流执行历史（按时间倒序）。
   */
  async getHistory(workflowId: string | null, limit: number = 50): Promise<WorkflowRunEntity[]> {
    if (workflowId !== null) {
      return this.workflowRunDao.getRunsForWorkflow(workflowId);
    }
    return this.workflowRunDao.getRecentRuns(limit);
  }

  /**
   * 执行工作流。
   *
   * 返回最终输出字符串；出错时更新历史记录为 FAILED 并返回 "Error: ..."。
   * v4.9.0 对齐：执行开始落一条 RUNNING 记录，结束时更新 COMPLETED / FAILED。
   */
  async execute(workflow: Workflow, input: string): Promise<string> {
    this.updateState({ ...this.executionState, isRunning: true, currentNodeId: null,
      completedNodeIds: [], output: '', error: null, logs: [] });

    // 创建执行历史记录（RUNNING 状态）
    const runId: string = uuid();
    const now: number = Date.now();
    const runEntity: WorkflowRunEntity = {
      id: runId,
      workflowId: workflow.id,
      workflowName: workflow.name,
      input: input,
      output: '',
      startedAt: now,
      completedAt: null,
      status: WorkflowRunStatus.RUNNING,
      failedNodeIdsJson: '[]',
      error: null,
      logsJson: '[]'
    };
    await this.workflowRunDao.insertRun(runEntity);

    try {
      // 构建邻接表
      const adjacency: Map<string, string[]> = new Map();
      for (const edge of workflow.edges) {
        const list: string[] = adjacency.get(edge.fromNodeId) ?? [];
        list.push(edge.toNodeId);
        adjacency.set(edge.fromNodeId, list);
      }

      // 查找 INPUT 节点
      let inputNode: WorkflowNode | null = null;
      for (const node of workflow.nodes) {
        if (node.type === NodeType.INPUT) {
          inputNode = node;
          break;
        }
      }
      if (inputNode === null) {
        throw new Error('No INPUT node found');
      }

      // BFS 拓扑执行
      const nodeMap: Map<string, WorkflowNode> = new Map();
      for (const node of workflow.nodes) {
        nodeMap.set(node.id, node);
      }
      const results: Map<string, string> = new Map();
      const visited: Set<string> = new Set();
      const queue: string[] = [inputNode.id];
      results.set(inputNode.id, input);

      const stepLog: string[] = [`Starting workflow: ${workflow.name}`];

      // 环检测：统计每个节点被 requeue 的次数，超过 MAX_REQUEUES 视为环
      const requeueCount: Map<string, number> = new Map();

      while (queue.length > 0) {
        const nodeId: string = queue.shift() as string;
        if (visited.has(nodeId)) {
          continue;
        }
        const node: WorkflowNode | undefined = nodeMap.get(nodeId);
        if (node === undefined) {
          continue;
        }

        // 检查所有前驱是否已完成
        const predecessors: string[] = [];
        for (const edge of workflow.edges) {
          if (edge.toNodeId === nodeId) {
            predecessors.push(edge.fromNodeId);
          }
        }
        let hasPendingPredecessor: boolean = false;
        for (const pred of predecessors) {
          if (!visited.has(pred) && pred !== nodeId) {
            hasPendingPredecessor = true;
            break;
          }
        }
        if (hasPendingPredecessor) {
          const count: number = (requeueCount.get(nodeId) ?? 0) + 1;
          if (count > MAX_REQUEUES) {
            throw new Error(
              `Possible cycle detected in workflow at node '${node.label.length > 0 ? node.label : nodeId}'`);
          }
          requeueCount.set(nodeId, count);
          queue.push(nodeId);
          continue;
        }

        this.updateState({ ...this.executionState, currentNodeId: nodeId, logs: [...stepLog] });

        // 计算节点输入：无前驱用自身结果（INPUT），否则合并所有前驱输出
        let nodeInput: string = input;
        if (predecessors.length > 0) {
          const parts: string[] = [];
          for (const pred of predecessors) {
            parts.push(results.get(pred) ?? '');
          }
          nodeInput = parts.join('\n\n');
        } else {
          const cached: string | undefined = results.get(nodeId);
          if (cached !== undefined) {
            nodeInput = cached;
          }
        }

        const nodeOutput: string = await this.executeNode(node, nodeInput);
        results.set(nodeId, nodeOutput);
        visited.add(nodeId);

        const displayName: string = node.label.length > 0 ? node.label : node.id;
        stepLog.push(`[${node.type}] ${displayName}: ${nodeOutput.substring(0, 100)}`);

        // 后继节点入队
        const successors: string[] | undefined = adjacency.get(nodeId);
        if (successors !== undefined) {
          for (const succ of successors) {
            if (!visited.has(succ)) {
              queue.push(succ);
            }
          }
        }
      }

      // 查找 OUTPUT 节点结果
      let finalOutput: string = '';
      let outputNode: WorkflowNode | null = null;
      for (const node of workflow.nodes) {
        if (node.type === NodeType.OUTPUT) {
          outputNode = node;
          break;
        }
      }
      if (outputNode !== null) {
        finalOutput = results.get(outputNode.id) ?? '';
      } else {
        // 无 OUTPUT 节点：取最后一个结果
        const values: string[] = Array.from(results.values());
        finalOutput = values.length > 0 ? values[values.length - 1] : '';
      }

      stepLog.push('Workflow completed.');
      this.updateState({
        isRunning: false,
        currentNodeId: null,
        completedNodeIds: Array.from(visited),
        output: finalOutput,
        error: null,
        logs: stepLog
      });

      // 更新历史记录为 COMPLETED
      await this.workflowRunDao.updateStatus(runId, WorkflowRunStatus.COMPLETED,
        Date.now(), finalOutput, null, JSON.stringify(stepLog), '[]');

      return finalOutput;
    } catch (e) {
      const message: string = `${e}`;
      const logs: string[] = [...this.executionState.logs, `Error: ${message}`];
      this.updateState({ ...this.executionState, isRunning: false, error: message, logs: logs });

      // 更新历史记录为 FAILED
      await this.workflowRunDao.updateStatus(runId, WorkflowRunStatus.FAILED,
        Date.now(), '', message, JSON.stringify(logs), '[]');

      return `Error: ${message}`;
    }
  }

  /**
   * 执行单个节点。
   */
  private async executeNode(node: WorkflowNode, input: string): Promise<string> {
    switch (node.type) {
      case NodeType.INPUT:
        return input;
      case NodeType.AGENT: {
        const agentType: AgentType = node.agentType ?? AgentType.Hermes;
        const prompt: string = node.prompt.trim().length > 0
          ? node.prompt.replace('{input}', input)
          : input;
        return this.executeAgent(agentType, prompt);
      }
      case NodeType.TRANSFORM:
        return WorkflowEngine.applyTransform(node.transformType, input, node.prompt);
      case NodeType.OUTPUT:
        return input;
      default:
        return input;
    }
  }

  /**
   * 通过真实 Transport 执行 Agent 调用。
   *
   * 1. 查询数据库中匹配 agentType 的第一个 AgentConfigEntity
   * 2. 创建临时 AgentTransport
   * 3. connect → sendMessage → 收集 events 直到 StreamComplete
   * 4. 拼接所有 delta 为完整响应（non-delta 覆盖，避免与 delta 累加重复）
   * 5. shutdown 释放资源
   *
   * 若无匹配配置或超时（60s），返回错误字符串而非抛异常，
   * 保证工作流后续节点能继续执行。
   */
  private async executeAgent(agentType: AgentType, prompt: string): Promise<string> {
    const configs: AgentConfigEntity[] = await this.agentConfigDao.getAllConfigs();
    let configEntity: AgentConfigEntity | null = null;
    for (const entity of configs) {
      if (entity.type === agentType) {
        configEntity = entity;
        break;
      }
    }
    if (configEntity === null) {
      return `Error: No agent config found for type ${agentType}`;
    }

    // 解密 apiKey（AKS: 前缀 → 明文）
    const apiKey: string = await KeystoreManager.getInstance()
      .decryptOrRaw(configEntity.apiKey);
    const config: AgentConfig = {
      id: configEntity.id,
      name: configEntity.name,
      type: agentType,
      serverUrl: configEntity.serverUrl,
      apiKey: apiKey,
      model: configEntity.model,
      systemPrompt: configEntity.systemPrompt,
      temperature: configEntity.temperature,
      maxTokens: configEntity.maxTokens
    };

    const transport: AgentTransport = TransportFactory.create(agentType);
    try {
      transport.connect(config, null);
      // 使用唯一 sessionId 避免与主会话冲突
      const sessionId: string = `workflow_${uuid()}`;
      await transport.sendMessage(sessionId, prompt);

      const response: string = await this.collectAgentResponse(transport);
      return response.length > 0
        ? response
        : `Error: Agent ${agentType} returned empty response`;
    } catch (e) {
      return `Error: ${e}`;
    } finally {
      transport.shutdown();
    }
  }

  /**
   * 收集 transport 事件直到 StreamComplete / Error，或 60s 超时。
   * - delta（isDelta=true）：增量片段，append 到累加器
   * - non-delta（isDelta=false）：完整消息，覆盖累加器
   */
  private collectAgentResponse(transport: AgentTransport): Promise<string> {
    return new Promise<string>((resolve: (v: string) => void) => {
      let response: string = '';
      let settled: boolean = false;

      const finish = (): void => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        resolve(response);
      };

      const timer: number = setTimeout((): void => {
        if (!settled) {
          settled = true;
          resolve(response);
        }
      }, AGENT_TIMEOUT_MS);

      const listener: EventListener = (event: AgentEvent): void => {
        switch (event.type) {
          case AgentEventType.MessageReceived: {
            if (event.isDelta) {
              response += event.content;
            } else {
              response = event.content;
            }
            break;
          }
          case AgentEventType.Error:
          case AgentEventType.StreamComplete: {
            finish();
            break;
          }
          default:
            // Connected / Disconnected / Reconnecting 忽略
            break;
        }
      };
      transport.setEventListener(listener);
    });
  }

  /**
   * 应用 TRANSFORM 节点的数据转换。
   */
  private static applyTransform(type: TransformType, input: string, extra: string): string {
    switch (type) {
      case TransformType.PASSTHROUGH:
        return input;
      case TransformType.EXTRACT: {
        // 正则提取第一个捕获组
        try {
          const regex: RegExp = new RegExp(extra);
          const match: RegExpMatchArray | null = input.match(regex);
          if (match !== null && match.length > 1) {
            return match[1];
          }
          return input;
        } catch (e) {
          const fallback: RegExpMatchArray | null = input.match(/(.+)/);
          return fallback !== null && fallback.length > 1 ? fallback[1] : input;
        }
      }
      case TransformType.TO_UPPERCASE:
        return input.toUpperCase();
      case TransformType.TO_LOWERCASE:
        return input.toLowerCase();
      case TransformType.TRIM:
        return input.trim();
      case TransformType.PREFIX:
        return `${extra}${input}`;
      case TransformType.SUFFIX:
        return `${input}${extra}`;
      case TransformType.JSON_EXTRACT: {
        // 提取 JSON 字段：字符串字段去引号，非字符串返回文本表示
        try {
          const json: Record<string, Object> = JSON.parse(input) as Record<string, Object>;
          const value: Object | undefined = json[extra];
          if (value !== undefined) {
            return typeof value === 'string' ? value as string : JSON.stringify(value);
          }
          return input;
        } catch (e) {
          return input;
        }
      }
      default:
        return input;
    }
  }

  /** 重置执行状态。 */
  reset(): void {
    this.updateState({
      isRunning: false,
      currentNodeId: null,
      completedNodeIds: [],
      output: '',
      error: null,
      logs: []
    });
  }

  private updateState(state: WorkflowExecutionState): void {
    this.executionState = state;
    if (this.onStateChanged !== undefined) {
      this.onStateChanged(state);
    }
  }
}

/**
 * 预置工作流模板（对标 Android WorkflowTemplates）。
 */
export class WorkflowTemplates {
  /** 翻译链：INPUT → Translate → Review & Polish → OUTPUT。 */
  static translationChain(): Workflow {
    const input: WorkflowNode = {
      id: 'input', type: NodeType.INPUT, label: 'Input Text', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 50, positionY: 200, outputCache: ''
    };
    const translate: WorkflowNode = {
      id: 'translate', type: NodeType.AGENT, label: 'Translate', agentType: AgentType.OpenAI,
      prompt: 'Translate the following text to the target language. Preserve meaning and tone:\n\n{input}',
      transformType: TransformType.PASSTHROUGH, positionX: 250, positionY: 200, outputCache: ''
    };
    const review: WorkflowNode = {
      id: 'review', type: NodeType.AGENT, label: 'Review & Polish', agentType: AgentType.OpenAI,
      prompt: 'Review this translation for accuracy and natural flow. Fix any issues:\n\n{input}',
      transformType: TransformType.PASSTHROUGH, positionX: 450, positionY: 200, outputCache: ''
    };
    const output: WorkflowNode = {
      id: 'output', type: NodeType.OUTPUT, label: 'Final Translation', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 650, positionY: 200, outputCache: ''
    };
    return {
      id: uuid(), name: 'Translation Chain', description: 'Translate → Review & Polish',
      nodes: [input, translate, review, output],
      edges: [
        { id: uuid(), fromNodeId: 'input', toNodeId: 'translate', condition: null },
        { id: uuid(), fromNodeId: 'translate', toNodeId: 'review', condition: null },
        { id: uuid(), fromNodeId: 'review', toNodeId: 'output', condition: null }
      ]
    };
  }

  /** 代码评审：INPUT →（Analyze ‖ Suggest）→ OUTPUT。 */
  static codeReview(): Workflow {
    const input: WorkflowNode = {
      id: 'input', type: NodeType.INPUT, label: 'Code Input', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 50, positionY: 200, outputCache: ''
    };
    const analyze: WorkflowNode = {
      id: 'analyze', type: NodeType.AGENT, label: 'Analyze Code', agentType: AgentType.OpenCode,
      prompt: 'Analyze the following code for potential issues, bugs, and improvements. Provide a structured analysis:\n\n{input}',
      transformType: TransformType.PASSTHROUGH, positionX: 250, positionY: 100, outputCache: ''
    };
    const suggest: WorkflowNode = {
      id: 'suggest', type: NodeType.AGENT, label: 'Generate Suggestions', agentType: AgentType.OpenCode,
      prompt: 'Based on this code analysis, provide specific improvement suggestions with code examples:\n\n{input}',
      transformType: TransformType.PASSTHROUGH, positionX: 250, positionY: 300, outputCache: ''
    };
    const output: WorkflowNode = {
      id: 'output', type: NodeType.OUTPUT, label: 'Review Report', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 500, positionY: 200, outputCache: ''
    };
    return {
      id: uuid(), name: 'Code Review', description: 'Analyze → Suggest Improvements',
      nodes: [input, analyze, suggest, output],
      edges: [
        { id: uuid(), fromNodeId: 'input', toNodeId: 'analyze', condition: null },
        { id: uuid(), fromNodeId: 'input', toNodeId: 'suggest', condition: null },
        { id: uuid(), fromNodeId: 'analyze', toNodeId: 'output', condition: null },
        { id: uuid(), fromNodeId: 'suggest', toNodeId: 'output', condition: null }
      ]
    };
  }

  /** 研究助手：INPUT → Search →（Extract ‖ Summarize）→ OUTPUT。 */
  static researchAssistant(): Workflow {
    const input: WorkflowNode = {
      id: 'input', type: NodeType.INPUT, label: 'Research Topic', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 50, positionY: 200, outputCache: ''
    };
    const search: WorkflowNode = {
      id: 'search', type: NodeType.AGENT, label: 'Search & Gather', agentType: AgentType.OpenAI,
      prompt: 'Research the following topic. Provide key facts, data points, and relevant information:\n\n{input}',
      transformType: TransformType.PASSTHROUGH, positionX: 250, positionY: 200, outputCache: ''
    };
    const extract: WorkflowNode = {
      id: 'extract', type: NodeType.TRANSFORM, label: 'Extract Key Points', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 450, positionY: 100, outputCache: ''
    };
    const summarize: WorkflowNode = {
      id: 'summarize', type: NodeType.AGENT, label: 'Summarize', agentType: AgentType.OpenAI,
      prompt: 'Create a concise, well-structured summary of this research:\n\n{input}',
      transformType: TransformType.PASSTHROUGH, positionX: 450, positionY: 300, outputCache: ''
    };
    const output: WorkflowNode = {
      id: 'output', type: NodeType.OUTPUT, label: 'Research Report', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 650, positionY: 200, outputCache: ''
    };
    return {
      id: uuid(), name: 'Research Assistant', description: 'Search → Summarize',
      nodes: [input, search, extract, summarize, output],
      edges: [
        { id: uuid(), fromNodeId: 'input', toNodeId: 'search', condition: null },
        { id: uuid(), fromNodeId: 'search', toNodeId: 'extract', condition: null },
        { id: uuid(), fromNodeId: 'search', toNodeId: 'summarize', condition: null },
        { id: uuid(), fromNodeId: 'extract', toNodeId: 'output', condition: null },
        { id: uuid(), fromNodeId: 'summarize', toNodeId: 'output', condition: null }
      ]
    };
  }

  /**
   * ComfyUI 文生图：INPUT → AGENT(ComfyUI) → OUTPUT。
   * 纯文本 → 默认文生图工作流；`{` 开头 → 直接提交 JSON 工作流。
   */
  static imageGeneration(): Workflow {
    const input: WorkflowNode = {
      id: 'input', type: NodeType.INPUT, label: 'Prompt', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 50, positionY: 200, outputCache: ''
    };
    const generate: WorkflowNode = {
      id: 'generate', type: NodeType.AGENT, label: 'Generate Image', agentType: AgentType.ComfyUI,
      prompt: '{input}', transformType: TransformType.PASSTHROUGH,
      positionX: 250, positionY: 200, outputCache: ''
    };
    const output: WorkflowNode = {
      id: 'output', type: NodeType.OUTPUT, label: 'Image Result', agentType: null,
      prompt: '', transformType: TransformType.PASSTHROUGH,
      positionX: 450, positionY: 200, outputCache: ''
    };
    return {
      id: uuid(), name: 'Image Generation', description: 'ComfyUI 文生图',
      nodes: [input, generate, output],
      edges: [
        { id: uuid(), fromNodeId: 'input', toNodeId: 'generate', condition: null },
        { id: uuid(), fromNodeId: 'generate', toNodeId: 'output', condition: null }
      ]
    };
  }

  static allTemplates(): Workflow[] {
    return [
      WorkflowTemplates.translationChain(),
      WorkflowTemplates.codeReview(),
      WorkflowTemplates.researchAssistant(),
      WorkflowTemplates.imageGeneration()
    ];
  }
}

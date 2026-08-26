// Workflow 协议契约（对标 protocol/schemas/workflow-schema.json）
// 对应 Android WorkflowEngine.kt 相关 model + iOS Models/Workflow.swift。
// DAG 工作流：节点 + 边组成有向无环图，Kahn 拓扑排序执行，AGENT 节点 60s 超时。

import { AgentType } from './Agent';

export enum NodeType {
  INPUT = 'INPUT',
  AGENT = 'AGENT',
  TRANSFORM = 'TRANSFORM',
  OUTPUT = 'OUTPUT'
}

export enum TransformType {
  PASSTHROUGH = 'PASSTHROUGH',
  EXTRACT = 'EXTRACT',
  TO_UPPERCASE = 'TO_UPPERCASE',
  TO_LOWERCASE = 'TO_LOWERCASE',
  TRIM = 'TRIM',
  PREFIX = 'PREFIX',
  SUFFIX = 'SUFFIX',
  JSON_EXTRACT = 'JSON_EXTRACT'
}

export enum WorkflowRunStatus {
  RUNNING = 'RUNNING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED'
}

export interface WorkflowNode {
  id: string;                          // 默认随机 UUID
  type: NodeType;                      // required
  label: string;                       // 默认 ''
  agentType: AgentType | null;         // 默认 null，仅 AGENT 节点有效
  prompt: string;                      // 默认 ''，支持 {input} 占位符
  transformType: TransformType;        // 默认 PASSTHROUGH
  positionX: number;                   // 默认 0，UI 用途
  positionY: number;                   // 默认 0，UI 用途
  outputCache: string;                 // 默认 ''，最近一次执行输出
}

export interface WorkflowEdge {
  id: string;                          // 默认随机 UUID
  fromNodeId: string;                  // required
  toNodeId: string;                    // required
  condition: string | null;            // 默认 null，null=无条件边
}

export interface Workflow {
  id: string;                          // 默认随机 UUID
  name: string;                        // required
  description: string;                  // 默认 ''
  nodes: WorkflowNode[];               // 默认 []
  edges: WorkflowEdge[];               // 默认 []
}

// 运行时内存快照（区别于落库的 WorkflowRunRecord）
export interface WorkflowExecutionState {
  isRunning: boolean;                  // 默认 false
  currentNodeId: string | null;       // 默认 null
  completedNodeIds: string[];          // 默认 []
  output: string;                      // 默认 ''
  error: string | null;               // 默认 null
  logs: string[];                      // 默认 []
}

// 历史执行记录（落库实体，对标 v4.9.0 双端 WorkflowRunEntity）
export interface WorkflowRunRecord {
  id: string;                          // required，UUID
  workflowId: string;                  // required
  workflowName: string;                // required，名称快照
  input: string;                       // 默认 ''
  output: string;                      // 默认 ''
  startedAt: number;                   // required，Unix 毫秒
  completedAt: number | null;         // 默认 null
  status: WorkflowRunStatus;           // required
  failedNodeIds: string[];             // 默认 []
  error: string | null;               // 默认 null
  logs: string[];                      // 默认 []
}

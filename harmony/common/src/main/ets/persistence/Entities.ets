// 数据库实体接口（对标 android core/database/entity/*.kt）
// 对应 Android Room @Entity data class，字段名/类型严格对齐。

// ── SessionEntity（对标 SessionEntity.kt，表 sessions） ──
export interface SessionEntity {
  id: string;                 // PRIMARY KEY
  title: string;
  createdAt: number;          // Unix 毫秒
  updatedAt: number;
  isPinned: boolean;          // SQLite INTEGER(0/1)，relationalStore 自动转换
  messageCount: number;
  summary: string;
}

// ── MessageEntity（对标 MessageEntity.kt，表 messages） ──
export interface MessageEntity {
  id: string;                 // PRIMARY KEY
  sessionId: string;
  role: string;               // MessageRole 枚举值
  content: string;
  timestamp: number;          // Unix 毫秒
  status: string;             // MessageStatus 枚举值
  metadataJson: string;
  attachmentType: string | null;
  attachmentData: string | null;
  attachmentName: string | null;
  reaction: string;
  replyToId: string | null;
}

// ── AgentConfigEntity（对标 AgentConfigEntity.kt，表 agent_configs） ──
export interface AgentConfigEntity {
  id: string;                 // PRIMARY KEY，默认 'default'
  name: string;
  type: string;               // AgentType 枚举值
  serverUrl: string;
  apiKey: string;             // AKS: + Base64(IV‖ciphertext)，HUKS 加密
  model: string;
  systemPrompt: string;
  temperature: number;
  maxTokens: number;
  protocolType: string;       // AgentProtocol 枚举值
}

// ── WorkflowRunEntity（对标 WorkflowRunEntity.kt，表 workflow_runs） ──
// v4.9.0: 工作流执行历史记录，每次 WorkflowEngine.execute() 生成一条。
// 索引：workflowId（按工作流过滤）/ startedAt（按时间倒序展示）。
export interface WorkflowRunEntity {
  id: string;                 // PRIMARY KEY，UUID
  workflowId: string;
  workflowName: string;       // 名称快照
  input: string;              // 默认 ''
  output: string;             // 默认 ''
  startedAt: number;          // Unix 毫秒
  completedAt: number | null; // 默认 null
  status: string;             // RUNNING / COMPLETED / FAILED / CANCELLED
  failedNodeIdsJson: string;  // string[] 序列化，默认 '[]'
  error: string | null;
  logsJson: string;           // string[] 序列化，默认 '[]'
}

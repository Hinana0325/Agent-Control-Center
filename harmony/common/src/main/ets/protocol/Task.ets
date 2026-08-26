// Task 协议契约（对标 protocol/schemas/task-schema.json）
// 对应 Android Task.kt + iOS Models/Task.swift。
// Task 是异步、可追踪、可调度的执行单元，区别于同步 Chat。

export enum TaskType {
  CHAT = 'CHAT',
  CODE = 'CODE',
  WORKFLOW = 'WORKFLOW',
  TOOL_CALL = 'TOOL_CALL',
  FILE_OPERATION = 'FILE_OPERATION'
}

export enum TaskStatus {
  Pending = 'Pending',
  Running = 'Running',
  Completed = 'Completed',
  Failed = 'Failed',
  Cancelled = 'Cancelled'
}

export interface AgentTask {
  id: string;                          // required，格式 task_<uuid>
  agentId: string;                     // required
  sessionId: string | null;           // 默认 null，关联会话 ID
  type: TaskType;                      // required
  input: string;                       // required
  status: TaskStatus;                  // required，默认 Pending
  result: string | null;             // 默认 null
  createdAt: number;                  // required，Unix 毫秒
  completedAt: number | null;         // 默认 null
  error: string | null;              // 默认 null，status=Failed 时设置
}

export const newTask = (
  id: string,
  agentId: string,
  type: TaskType,
  input: string,
  now: number
): AgentTask => ({
  id,
  agentId,
  sessionId: null,
  type,
  input,
  status: TaskStatus.Pending,
  result: null,
  createdAt: now,
  completedAt: null,
  error: null
});

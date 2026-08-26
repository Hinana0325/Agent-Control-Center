// Agent 协议契约（对标 protocol/schemas/agent-schema.json）
// 对应 Android Agent.kt + iOS Models/Agent.swift。
// 时间戳用 number（Unix 毫秒），可空用 T | null，与双端字节级对齐。

export enum AgentType {
  Hermes = 'Hermes',
  OpenCode = 'OpenCode',
  OpenClaw = 'OpenClaw',
  OpenAI = 'OpenAI',
  XiaomiMiMo = 'XiaomiMiMo',
  LocalModel = 'LocalModel',
  ComfyUI = 'ComfyUI',
  OpenWebUI = 'OpenWebUI'
}

export enum AgentProtocol {
  WebSocket = 'WebSocket',
  HttpSSE = 'HttpSSE',
  MCP = 'MCP',
  Local = 'Local'
}

export enum AgentStatus {
  Online = 'Online',
  Offline = 'Offline',
  Connecting = 'Connecting',
  Error = 'Error'
}

export enum AgentCapability {
  CHAT = 'CHAT',
  TASK = 'TASK',
  WORKFLOW = 'WORKFLOW',
  MCP = 'MCP',
  FILESYSTEM = 'FILESYSTEM',
  TERMINAL = 'TERMINAL',
  VOICE = 'VOICE',
  IMAGE_GEN = 'IMAGE_GEN',
  CODE_EXECUTION = 'CODE_EXECUTION'
}

// AgentConfig：apiKey 持久化用 AKS: 前缀（HUKS 加密），传输时明文
export interface AgentConfig {
  id: string;                  // 默认 'default'
  name: string;                // 默认 'Default Agent'
  type: AgentType;             // required
  serverUrl: string;           // 默认 ''
  apiKey: string;              // AKS: + Base64(IV‖ciphertext)，AES-256-GCM
  model: string;
  systemPrompt: string;
  temperature: number;         // 默认 0.7 / ComfyUI CFG scale 默认 7.0
  maxTokens: number;           // 默认 4096 / ComfyUI steps 默认 20
}

// Agent：运行时实例，含状态与能力声明
export interface Agent {
  id: string;                  // required，格式 agent_<uuid>
  name: string;                // required
  endpoint: string;           // 默认 ''
  status: AgentStatus;         // 默认 Offline
  capabilities: AgentCapability[]; // 默认 [CHAT]
  protocol: AgentProtocol;    // 默认 WebSocket
  config: AgentConfig | null;  // 默认 null
}

export const DEFAULT_AGENT_CONFIG: AgentConfig = {
  id: 'default',
  name: 'Default Agent',
  type: AgentType.OpenAI,
  serverUrl: '',
  apiKey: '',
  model: '',
  systemPrompt: '',
  temperature: 0.7,
  maxTokens: 4096
};

export const DEFAULT_AGENT: Agent = {
  id: '',
  name: '',
  endpoint: '',
  status: AgentStatus.Offline,
  capabilities: [AgentCapability.CHAT],
  protocol: AgentProtocol.WebSocket,
  config: null
};

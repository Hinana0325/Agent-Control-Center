// 错误码注册表（对标 protocol/schemas/error-codes.json）
// 对应 Android AppErrorCode.kt + iOS 错误码定义。
// 错误码按类别分段：Transport(1xxx) / Protocol(2xxx) / Agent(3xxx) /
// Session(4xxx) / Task(5xxx) / Workflow(6xxx) / Plugin(7xxx) / MCP(8xxx) /
// File(9xxx) / Crypto(10xxx)。

export enum ErrorCategory {
  Transport = 'Transport',
  Protocol = 'Protocol',
  Agent = 'Agent',
  Session = 'Session',
  Task = 'Task',
  Workflow = 'Workflow',
  Plugin = 'Plugin',
  MCP = 'MCP',
  File = 'File',
  Crypto = 'Crypto'
}

export interface ErrorCode {
  code: number;              // required，按类别分段
  name: string;             // required，UPPER_SNAKE_CASE
  message: string;          // required，中文描述
  category: ErrorCategory;  // required
}

export class AppErrorCode {
  // ── Transport (1xxx) ──
  static readonly TRANSPORT_CONNECT_FAILED: number = 1001;
  static readonly TRANSPORT_AUTH_FAILED: number = 1002;
  static readonly TRANSPORT_DISCONNECTED: number = 1003;
  static readonly TRANSPORT_TIMEOUT: number = 1004;
  static readonly TRANSPORT_RECONNECT_FAILED: number = 1005;

  // ── Protocol (2xxx) ──
  static readonly PROTOCOL_INVALID_FORMAT: number = 2001;
  static readonly PROTOCOL_UNKNOWN_TYPE: number = 2002;
  static readonly PROTOCOL_PARSE_FAILED: number = 2003;
  static readonly PROTOCOL_VERSION_MISMATCH: number = 2004;

  // ── Agent (3xxx) ──
  static readonly AGENT_NOT_FOUND: number = 3001;
  static readonly AGENT_OFFLINE: number = 3002;
  static readonly AGENT_CAPABILITY_MISSING: number = 3003;
  static readonly AGENT_CONFIG_MISSING: number = 3004;
  static readonly AGENT_EMPTY_RESPONSE: number = 3005;

  // ── Session (4xxx) ──
  static readonly SESSION_NOT_FOUND: number = 4001;
  static readonly SESSION_CREATE_FAILED: number = 4002;

  // ── Task (5xxx) ──
  static readonly TASK_NOT_FOUND: number = 5001;
  static readonly TASK_ALREADY_RUNNING: number = 5002;
  static readonly TASK_CANCEL_FAILED: number = 5003;
  static readonly TASK_TIMEOUT: number = 5004;

  // ── Workflow (6xxx) ──
  static readonly WORKFLOW_NOT_FOUND: number = 6001;
  static readonly WORKFLOW_NODE_FAILED: number = 6002;
  static readonly WORKFLOW_CYCLE_DETECTED: number = 6003;
  static readonly WORKFLOW_TIMEOUT: number = 6004;

  // ── Plugin (7xxx) ──
  static readonly PLUGIN_NOT_FOUND: number = 7001;
  static readonly PLUGIN_DISABLED: number = 7002;
  static readonly PLUGIN_EXECUTION_FAILED: number = 7003;

  // ── MCP (8xxx) ──
  static readonly MCP_SERVER_UNREACHABLE: number = 8001;
  static readonly MCP_TOOL_NOT_FOUND: number = 8002;
  static readonly MCP_TOOL_EXECUTION_FAILED: number = 8003;
  static readonly MCP_INIT_FAILED: number = 8004;

  // ── File (9xxx) ──
  static readonly FILE_TOO_LARGE: number = 9001;
  static readonly FILE_NOT_FOUND: number = 9002;
  static readonly FILE_TRANSFER_FAILED: number = 9003;

  // ── Crypto (10xxx) ──
  static readonly CRYPTO_DECRYPT_FAILED: number = 10001;
  static readonly CRYPTO_KEYSTORE_UNAVAILABLE: number = 10002;
  static readonly CRYPTO_KEY_MISMATCH: number = 10003;
}

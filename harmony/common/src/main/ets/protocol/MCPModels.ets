// MCP 协议契约（对标 protocol/schemas/mcp-schema.json）
// 对应 Android mcp/model/McpModels.kt + iOS Models/MCPModels.swift。
// 基于 MCP 2025-03-26 规范，JSON-RPC 2.0 信封。

export enum McpTransportType {
  STDIO = 'STDIO',
  SSE = 'SSE',
  HTTP = 'HTTP'
}

export interface McpServerCapabilities {
  tools: boolean;       // 默认 false
  resources: boolean;   // 默认 false
  prompts: boolean;     // 默认 false
  logging: boolean;     // 默认 false
}

export interface McpServer {
  id: string;                          // required
  name: string;                        // required
  transportUrl: string;                // required，HTTP/SSE 为 URL，STDIO 为可执行文件路径
  transportType: McpTransportType;     // 默认 SSE
  apiKey: string | null;               // 默认 null，存在时附 Authorization: Bearer
  isEnabled: boolean;                  // 默认 true
  capabilities: McpServerCapabilities; // 默认 {}，握手后回填
}

export interface McpToolProperty {
  type: string;                // required，'string'/'number'/'boolean'/'integer'/'array'/'object'
  description: string;         // 默认 ''
  enum: string[] | null;       // 默认 null
}

export interface McpToolSchema {
  type: string;                            // 默认 'object'
  properties: Record<string, McpToolProperty>; // 默认 {}
  required: string[];                      // 默认 []
}

export interface McpTool {
  name: string;          // required
  description: string;    // 默认 ''
  inputSchema: McpToolSchema; // 默认 {}
  serverId: string;      // required
}

export interface TextMcpContent {
  type: 'text';
  text: string;          // required
}

export interface ImageMcpContent {
  type: 'image';
  data: string;          // required，Base64
  mimeType: string;      // required，'image/png' 等
}

export interface AudioMcpContent {
  type: 'audio';
  data: string;          // required，Base64
  mimeType: string;      // required
}

export type McpContent = TextMcpContent | ImageMcpContent | AudioMcpContent;

export interface McpToolResult {
  content: McpContent[];     // required
  isError: boolean;          // 默认 false，true 触发 8003
}

export interface JsonRpcError {
  code: number;          // required
  message: string;       // required
}

export interface JsonRpcRequest {
  jsonrpc: string;       // 默认 '2.0'
  id: number;            // required
  method: string;        // required，'initialize'/'tools/list'/'tools/call'
  params: object;        // 默认 {}
}

export interface JsonRpcResponse {
  jsonrpc: string;       // required，'2.0'
  id: number;            // required
  result: object | null; // 默认 null，成功时非空
  error: JsonRpcError | null; // 默认 null，失败时非空
}

export interface InitializeParams {
  protocolVersion: string;  // 默认 '2025-03-26'
  capabilities: object;      // 默认 {}
  clientInfo: { name: string; version: string }; // 默认 {name:'Agent Control Center', version}
}

export interface ToolsCallParams {
  name: string;          // required
  arguments: object;     // 默认 {}
}

// MCP 方法名常量
export class McpMethod {
  static readonly INITIALIZE: string = 'initialize';
  static readonly TOOLS_LIST: string = 'tools/list';
  static readonly TOOLS_CALL: string = 'tools/call';
}

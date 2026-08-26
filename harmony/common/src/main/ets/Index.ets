// common HSP 公共导出入口
// 对标 android 的 package 聚合 + iOS 的 umbrella header。
// 所有外部模块（entry / features）通过 import { ... } from 'common' 访问。

// ── Protocol 层（10 schema 映射） ──
export * from './protocol/Agent';
export * from './protocol/Session';
export * from './protocol/Message';
export * from './protocol/AgentEvent';
export * from './protocol/Task';
export * from './protocol/Workflow';
export * from './protocol/Plugin';
export * from './protocol/MCPModels';
export * from './protocol/FileTransfer';
export * from './protocol/ErrorCodes';

// ── Transport 传输层 ──
export * from './transport/AgentTransport';
export * from './transport/TransportFactory';
export * from './transport/ConnectionRepository';
export * from './transport/http/OpenAIHttpTransport';
export * from './transport/comfyui/ComfyUITransport';
export * from './transport/comfyui/ComfyApiClient';
export * from './transport/comfyui/ComfyWorkflowBuilder';
export * from './transport/comfyui/ComfyImageOutput';
export * from './transport/websocket/WebSocketTransport';

// ── Security 安全层 ──
export * from './security/KeystoreManager';
export * from './security/CryptoManager';
export * from './security/UrlValidator';

// ── Persistence 持久化层 ──
export * from './persistence/Database';
export * from './persistence/Entities';
export * from './persistence/SessionDao';
export * from './persistence/MessageDao';
export * from './persistence/AgentConfigDao';
export * from './persistence/WorkflowRunDao';

// ── Runtime 运行时 ──
export * from './runtime/WorkflowEngine';
export * from './runtime/KeepAliveManager';

// ── MCP 协议实现 ──
export * from './mcp/McpRegistry';
export * from './mcp/McpClient';
export * from './mcp/McpBridge';

// ── Plugin 插件系统 ──
export * from './plugin/PluginExecutor';

// ── Widget 服务卡片数据桥 ──
export * from './widget/CardDataProvider';

// ── Theme 设计 token ──
export * from './theme/Color';
export * from './theme/Shape';
export * from './theme/Spacing';
export * from './theme/Type';

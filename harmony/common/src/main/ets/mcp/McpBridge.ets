// McpBridge（对标 android mcp/bridge/McpBridge.kt）
// MCP 桥接层 — Agent 工具调用与 MCP Server 之间的协调器。
//
// 1. connectServer: 连接 MCP Server → initialize 握手 → tools/list → 注册到 Registry
// 2. callTool: 从 Registry 查找工具归属 server → 委托 McpClient 执行 tools/call
// 3. disconnectServer: 从 Registry 注销 → 关闭 McpClient 连接
//
// Agent 层只需调用 callTool，无需关心工具来自哪个 server。
// 设计参考 MCP Host 角色：https://modelcontextprotocol.io/docs/learn/architecture
//
// ArkTS 无 StateFlow：连接状态通过 Map 拉取（onConnectionStateChanged 回调通知 UI）。

import { McpServer, McpTool, McpToolResult } from '../protocol/MCPModels';
import { McpRegistry } from './McpRegistry';
import { McpClient, mcpResultAsText } from './McpClient';

export enum McpConnectionState {
  Disconnected = 'Disconnected',
  Connecting = 'Connecting',
  Connected = 'Connected',
  Failed = 'Failed'
}

export interface ServerConnectionState {
  serverId: string;
  state: McpConnectionState;
  errorMessage: string | null;
}

export class McpBridge {
  private static instance: McpBridge | null = null;

  private registry: McpRegistry = new McpRegistry();
  private client: McpClient = new McpClient();

  private connectionStates: Map<string, ServerConnectionState> = new Map();

  // UI 连接状态回调
  onConnectionStateChanged?: (states: Map<string, ServerConnectionState>) => void;

  static getInstance(): McpBridge {
    if (McpBridge.instance === null) {
      McpBridge.instance = new McpBridge();
    }
    return McpBridge.instance;
  }

  /**
   * 连接 MCP Server：initialize 握手 + tools/list + 注册到 Registry。
   * @return true 表示连接成功并已注册工具
   */
  async connectServer(server: McpServer): Promise<boolean> {
    this.updateConnectionState(server.id, McpConnectionState.Connecting, null);

    try {
      // 1. initialize 握手
      const capabilities = await this.client.connect(server);
      if (capabilities === null) {
        this.updateConnectionState(server.id, McpConnectionState.Failed, 'Initialize failed');
        return false;
      }

      // 2. 注册 server 配置（更新 capabilities）
      this.registry.registerServer({
        ...server,
        capabilities: capabilities
      });

      // 3. 若支持 tools，获取工具列表并注册
      if (capabilities.tools) {
        const tools = await this.client.listTools(server);
        if (tools !== null) {
          this.registry.registerTools(server.id, tools);
        }
      }

      this.updateConnectionState(server.id, McpConnectionState.Connected, null);
      return true;
    } catch (e) {
      this.updateConnectionState(server.id, McpConnectionState.Failed, `${e}`);
      return false;
    }
  }

  /**
   * 调用工具 — Agent 层入口。
   *
   * 从 Registry 查找工具名归属的 server，再委托 McpClient 执行。
   * @return 工具结果文本（若 isError=true 则为错误描述），null 表示工具不存在
   */
  async callTool(toolName: string, args: Record<string, Object>): Promise<string | null> {
    const entry: [string, McpTool] | null = this.registry.findTool(toolName);
    if (entry === null) {
      return null;
    }
    const server: McpServer | null = this.registry.getServer(entry[0]);
    if (server === null) {
      return null;
    }
    const result: McpToolResult | null =
      await this.client.callTool(server, toolName, args);
    if (result === null) {
      return null;
    }
    return mcpResultAsText(result);
  }

  /**
   * 调用工具，返回完整的 McpToolResult（含 isError 标志和多种内容类型）。
   */
  async callToolDetailed(toolName: string,
    args: Record<string, Object>): Promise<McpToolResult | null> {
    const entry: [string, McpTool] | null = this.registry.findTool(toolName);
    if (entry === null) {
      return null;
    }
    const server: McpServer | null = this.registry.getServer(entry[0]);
    if (server === null) {
      return null;
    }
    return this.client.callTool(server, toolName, args);
  }

  /** 断开 MCP Server 连接。 */
  async disconnectServer(serverId: string): Promise<void> {
    this.registry.unregisterServer(serverId);
    await this.client.disconnect(serverId);
    this.updateConnectionState(serverId, McpConnectionState.Disconnected, null);
  }

  /** 获取所有已注册的工具名。 */
  getAvailableTools(): string[] {
    return this.registry.getAllToolNames();
  }

  /** 刷新某个 server 的工具列表（重新 tools/list）。 */
  async refreshTools(serverId: string): Promise<boolean> {
    const server: McpServer | null = this.registry.getServer(serverId);
    if (server === null) {
      return false;
    }
    const tools = await this.client.listTools(server);
    if (tools === null) {
      return false;
    }
    this.registry.registerTools(serverId, tools);
    return true;
  }

  /** 获取注册表（供 UI 查询 server / 工具列表）。 */
  getRegistry(): McpRegistry {
    return this.registry;
  }

  /** 获取连接状态快照。 */
  getConnectionStates(): Map<string, ServerConnectionState> {
    return this.connectionStates;
  }

  // ── 内部方法 ──

  private updateConnectionState(serverId: string, state: McpConnectionState,
    error: string | null): void {
    this.connectionStates.set(serverId, { serverId: serverId, state: state, errorMessage: error });
    if (this.onConnectionStateChanged !== undefined) {
      this.onConnectionStateChanged(new Map(this.connectionStates));
    }
  }
}

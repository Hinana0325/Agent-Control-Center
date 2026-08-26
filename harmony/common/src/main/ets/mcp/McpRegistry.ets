// McpRegistry（对标 android mcp/registry/McpRegistry.kt）
// MCP Server 注册表 — 管理已连接的 MCP Server 及其提供的工具。
//
// 维护三个索引：
// - servers: serverId → McpServer 配置
// - toolsByServer / tools: 工具列表
// - toolIndex: toolName → (serverId, McpTool)，按工具名 O(1) 查找
//
// 当 McpClient 连接成功并完成 tools/list 后，调用 registerTools 注册工具。
// McpBridge 调用 findTool 查询工具归属的 server，再委托 McpClient 执行 tools/call。
//
// ArkTS 无 ConcurrentHashMap/StateFlow：单线程事件循环下普通 Map 即可，
// UI 通过 getter 拉取最新状态。

import { McpServer, McpTool } from '../protocol/MCPModels';

export class McpRegistry {
  private servers: Map<string, McpServer> = new Map();
  private tools: McpTool[] = [];

  // 工具名 -> (serverId, McpTool)，用于 O(1) 查找
  private toolIndex: Map<string, [string, McpTool]> = new Map();

  /** 注册或更新一个 MCP Server 配置。 */
  registerServer(server: McpServer): void {
    this.servers.set(server.id, server);
  }

  /** 注销 MCP Server，同时移除其所有工具。 */
  unregisterServer(serverId: string): void {
    this.servers.delete(serverId);
    const toRemove: string[] = [];
    this.toolIndex.forEach((value: [string, McpTool], key: string): void => {
      if (value[0] === serverId) {
        toRemove.push(key);
      }
    });
    for (const key of toRemove) {
      this.toolIndex.delete(key);
    }
    this.tools = this.tools.filter((t: McpTool): boolean => t.serverId !== serverId);
  }

  /**
   * 注册某个 server 提供的工具列表（来自 tools/list 响应）。
   * 先清除该 server 的旧工具，再注册新工具。
   */
  registerTools(serverId: string, tools: McpTool[]): void {
    // 清除旧工具
    const toRemove: string[] = [];
    this.toolIndex.forEach((value: [string, McpTool], key: string): void => {
      if (value[0] === serverId) {
        toRemove.push(key);
      }
    });
    for (const key of toRemove) {
      this.toolIndex.delete(key);
    }

    // 注册新工具
    for (const tool of tools) {
      this.toolIndex.set(tool.name, [serverId, tool]);
    }

    // 更新工具列表：移除旧 + 追加新
    const kept: McpTool[] = this.tools.filter((t: McpTool): boolean => t.serverId !== serverId);
    this.tools = kept.concat(tools);
  }

  /** 按工具名查找工具及其所属 serverId。 */
  findTool(toolName: string): [string, McpTool] | null {
    return this.toolIndex.get(toolName) ?? null;
  }

  /** 按 serverId 获取配置。 */
  getServer(serverId: string): McpServer | null {
    return this.servers.get(serverId) ?? null;
  }

  /** 获取所有已注册的工具名列表（排序）。 */
  getAllToolNames(): string[] {
    return Array.from(this.toolIndex.keys()).sort();
  }

  /** 获取某个 server 的所有工具。 */
  getToolsForServer(serverId: string): McpTool[] {
    return this.tools.filter((t: McpTool): boolean => t.serverId === serverId);
  }

  /** 获取所有工具。 */
  getAllTools(): McpTool[] {
    return [...this.tools];
  }

  /** 获取所有已启用的 server。 */
  getEnabledServers(): McpServer[] {
    const result: McpServer[] = [];
    this.servers.forEach((server: McpServer): void => {
      if (server.isEnabled) {
        result.push(server);
      }
    });
    return result;
  }

  /** 获取所有 server。 */
  getAllServers(): McpServer[] {
    return Array.from(this.servers.values());
  }

  /** 清空所有注册信息。 */
  clear(): void {
    this.toolIndex.clear();
    this.servers.clear();
    this.tools = [];
  }
}

// McpViewModel（对标 android feature/mcp/McpViewModel.kt）
// MCP 服务器管理：列表 CRUD（preferences 持久化 JSON）+ 连接测试 + 工具发现。
//
// 对齐双端持久化机制：Android 用 SharedPreferences + Gson，
// iOS 用 UserDefaults + JSONEncoder，鸿蒙用 @ohos.data.preferences。
// 后续若引入 McpServerEntity 持久化到 RDB，可平滑迁移。

import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import { McpServer, McpTransportType, McpTool,
  McpBridge, McpConnectionState, ServerConnectionState } from 'common';

const PREFS_NAME: string = 'mcp_prefs';
const SERVERS_KEY: string = 'mcp_servers';

// 渲染模型：server + 当前连接状态
export interface McpServerRowState {
  server: McpServer;
  connectionState: McpConnectionState;
}

export class McpViewModel {
  private context: common.Context | null = null;
  private prefs: preferences.Preferences | null = null;
  private bridge: McpBridge = McpBridge.getInstance();

  // UI 回调
  onServersChanged?: (rows: McpServerRowState[]) => void;
  onToolsChanged?: (tools: string[]) => void;
  onMessage?: (message: string) => void;

  private servers: McpServer[] = [];

  /** 页面 aboutToAppear 时初始化（注入 Context 并加载持久化列表）。 */
  async init(context: common.Context): Promise<void> {
    this.context = context;
    this.prefs = await preferences.getPreferences(context, PREFS_NAME);
    await this.loadServers();
    this.bridge.onConnectionStateChanged = (states: Map<string, ServerConnectionState>): void => {
      this.notifyServersChanged();
    };
    this.notifyServersChanged();
    this.notifyToolsChanged();
  }

  /** 获取持久化的 server 列表。 */
  private async loadServers(): Promise<void> {
    if (this.prefs === null) {
      return;
    }
    const json: string = this.prefs.getSync(SERVERS_KEY, '[]') as string;
    try {
      const parsed: Object[] = JSON.parse(json) as Object[];
      this.servers = parsed.map((item: Object): McpServer => {
        const obj: Record<string, Object> = item as Record<string, Object>;
        return {
          id: obj['id'] as string,
          name: obj['name'] as string,
          transportUrl: obj['transportUrl'] as string,
          transportType: McpTransportType.HTTP,
          apiKey: obj['apiKey'] !== undefined && obj['apiKey'] !== null
            ? obj['apiKey'] as string : null,
          isEnabled: obj['isEnabled'] !== undefined ? obj['isEnabled'] === true : true,
          capabilities: { tools: false, resources: false, prompts: false, logging: false }
        };
      });
    } catch (e) {
      this.servers = [];
    }
  }

  /** 持久化 server 列表。 */
  private async saveServers(): Promise<void> {
    if (this.prefs === null) {
      return;
    }
    await this.prefs.put(SERVERS_KEY, JSON.stringify(this.servers));
    await this.prefs.flush();
  }

  /** 添加 server。 */
  async addServer(name: string, transportUrl: string, apiKey: string): Promise<void> {
    const server: McpServer = {
      id: `mcp_${Date.now()}`,
      name: name,
      transportUrl: transportUrl,
      transportType: McpTransportType.HTTP,
      apiKey: apiKey.length > 0 ? apiKey : null,
      isEnabled: true,
      capabilities: { tools: false, resources: false, prompts: false, logging: false }
    };
    this.servers.push(server);
    await this.saveServers();
    this.notifyServersChanged();
    this.onMessage?.(`Added ${name}`);
  }

  /** 删除 server 并断开连接。 */
  async removeServer(serverId: string): Promise<void> {
    this.servers = this.servers.filter((s: McpServer): boolean => s.id !== serverId);
    await this.saveServers();
    await this.bridge.disconnectServer(serverId);
    this.notifyServersChanged();
    this.notifyToolsChanged();
  }

  /** 连接 server（initialize 握手 + tools/list）。 */
  async connectServer(serverId: string): Promise<void> {
    const server: McpServer | undefined =
      this.servers.find((s: McpServer): boolean => s.id === serverId);
    if (server === undefined) {
      return;
    }
    const ok: boolean = await this.bridge.connectServer(server);
    this.onMessage?.(ok
      ? `Connected: ${server.name}`
      : `Connection failed: ${server.name}`);
    this.notifyServersChanged();
    this.notifyToolsChanged();
  }

  /** 断开 server。 */
  async disconnectServer(serverId: string): Promise<void> {
    await this.bridge.disconnectServer(serverId);
    this.notifyServersChanged();
    this.notifyToolsChanged();
  }

  /** 刷新 server 的工具列表。 */
  async refreshTools(serverId: string): Promise<void> {
    const ok: boolean = await this.bridge.refreshTools(serverId);
    this.onMessage?.(ok ? 'Tools refreshed' : 'Refresh failed');
    this.notifyToolsChanged();
  }

  /** 获取已连接 server 的工具列表。 */
  getTools(): McpTool[] {
    return this.bridge.getRegistry().getAllTools();
  }

  // ── 状态通知 ──

  private notifyServersChanged(): void {
    if (this.onServersChanged === undefined) {
      return;
    }
    const states: Map<string, ServerConnectionState> =
      this.bridge.getConnectionStates();
    const rows: McpServerRowState[] = this.servers.map((server: McpServer): McpServerRowState => {
      const entry: ServerConnectionState | undefined = states.get(server.id);
      return {
        server: server,
        connectionState: entry !== undefined
          ? entry.state : McpConnectionState.Disconnected
      };
    });
    this.onServersChanged(rows);
  }

  private notifyToolsChanged(): void {
    if (this.onToolsChanged !== undefined) {
      this.onToolsChanged(this.bridge.getAvailableTools());
    }
  }
}

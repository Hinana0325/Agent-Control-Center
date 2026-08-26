// WebSocketTransport（对标 android transport/websocket/WebSocketTransport.kt）
// WebSocket 传输层，对应 Hermes / OpenClaw / OpenCode 等基于
// ws://host/ws 的 Agent 服务。保留鉴权帧与自动重连逻辑。
//
// 多轮对话说明：
//  WebSocket 服务端通过 sessionId 维护会话状态与历史，客户端在每条消息帧中
//  携带正确的 sessionId 即可，无需转发完整历史。本类额外维护一份
//  localMessageCache（按 sessionId 分组），仅用于客户端侧展示与调试。
//
// E2E 加密：CryptoManager（AES-256-GCM + PBKDF2），AH1: 格式对齐 Android/iOS。

import webSocket from '@ohos.net.webSocket';
import { AgentConfig, AgentType } from '../../protocol/Agent';
import { AgentEvent, AgentEventType, AgentConnectionState, DEFAULT_CONNECTION_STATE } from '../../protocol/AgentEvent';
import { MessageRole } from '../../protocol/Message';
import { AppErrorCode } from '../../protocol/ErrorCodes';
import { AgentTransport, EventListener, ConnectionStateListener, AgentEvents } from '../AgentTransport';
import { CryptoManager } from '../../security/CryptoManager';
import { UrlValidator } from '../../security/UrlValidator';

// 自动重连最大次数
const MAX_RETRIES: number = 3;
// 心跳间隔（毫秒）
const HEARTBEAT_INTERVAL_MS: number = 30000;

// 本地缓存条目：仅记录角色与明文内容，用于展示
interface CachedMessage {
  role: MessageRole;
  content: string;
}

export class WebSocketTransport implements AgentTransport {
  private eventListener: EventListener | null = null;
  private stateListener: ConnectionStateListener | null = null;
  private connectionState: AgentConnectionState = { ...DEFAULT_CONNECTION_STATE };

  private wsClient: webSocket.WebSocketClient | null = null;

  // 当前连接配置（用于网络恢复后重连）
  private currentConfig: AgentConfig | null = null;

  // E2E 密钥，null 表示不加密
  private e2eKey: string | null = null;

  // 本地消息缓存：按 sessionId 分组
  private localMessageCache: Map<string, CachedMessage[]> = new Map();

  // 重连计数器
  private retryCount: number = 0;
  private isConnecting: boolean = false;
  private isShutdown: boolean = false;

  // 心跳定时器 ID
  private heartbeatTimerId: number = -1;

  setEventListener(listener: EventListener): void {
    this.eventListener = listener;
  }

  setConnectionStateListener(listener: ConnectionStateListener): void {
    this.stateListener = listener;
  }

  getConnectionState(): AgentConnectionState {
    return this.connectionState;
  }

  connect(config: AgentConfig, e2eKey?: string | null): void {
    this.currentConfig = config;
    this.e2eKey = (e2eKey !== undefined && e2eKey !== null && e2eKey.length > 0) ? e2eKey : null;
    this.retryCount = 0;
    this.isShutdown = false;

    this.updateState({
      isConnected: false,
      serverUrl: config.serverUrl,
      agentType: config.type,
      latency: 0
    });

    this.connectLoop();
  }

  /**
   * 运行时热更新 E2E 密钥。
   */
  updateE2eKey(key: string | null): void {
    this.e2eKey = (key !== null && key.length > 0) ? key : null;
  }

  /**
   * 连接循环：带指数退避重连。
   */
  private connectLoop(): void {
    if (this.isShutdown || this.retryCount >= MAX_RETRIES) {
      return;
    }
    if (this.isConnecting) return;
    this.isConnecting = true;

    const config: AgentConfig | null = this.currentConfig;
    if (config === null) {
      this.isConnecting = false;
      return;
    }

    const wsUrl: string = this.buildWsUrl(config.serverUrl);

    // SSRF 防护：校验 WebSocket URL（防 169.254.169.254 元数据泄漏 apiKey）
    if (!UrlValidator.validate(wsUrl, true)) {
      this.emit(AgentEvents.error('Invalid or blocked WebSocket URL'));
      return;
    }

    this.emit(AgentEvents.reconnecting());
    const startTime: number = Date.now();

    // 销毁旧 client
    this.cleanupClient();
    const client: webSocket.WebSocketClient = webSocket.createWebSocket();
    this.wsClient = client;

    this.registerWsCallbacks(client, config, startTime);

    const options: webSocket.WebSocketRequestOptions = {};
    client.connect(wsUrl, options).catch((err: Error): void => {
      this.isConnecting = false;
      this.handleConnectError(err, config);
    });
  }

  /**
   * 注册 WebSocket 事件回调。
   */
  private registerWsCallbacks(client: webSocket.WebSocketClient, config: AgentConfig, startTime: number): void {
    // 连接打开
    client.on('open', (err: Error, value: Object): void => {
      if (err) {
        this.isConnecting = false;
        this.handleConnectError(err, config);
        return;
      }
      this.isConnecting = false;
      this.retryCount = 0;

      const latency: number = Date.now() - startTime;
      this.updateState({
        isConnected: true,
        serverUrl: config.serverUrl,
        agentType: config.type,
        latency: latency
      });

      // 发送鉴权帧
      if (config.apiKey.length > 0) {
        const authFrame: string = JSON.stringify({ type: 'auth', key: config.apiKey });
        client.send(authFrame).catch((): void => {
          // 鉴权帧发送失败，不中断——后续消息仍可能成功
        });
      }

      this.emit(AgentEvents.connected(config.serverUrl, config.type));

      // 启动心跳
      this.startHeartbeat(client);
    });

    // 收到消息
    client.on('message', (err: Error, value: string | ArrayBuffer): void => {
      if (err) return;
      const text: string = typeof value === 'string' ? value : '';
      if (text.length > 0) {
        // handleMessage 是 async（E2E 解密），以 fire-and-forget 方式调用，
        // 错误在 handleMessage 内部 try/catch 处理
        this.handleMessage(text);
      }
    });

    // 连接关闭
    client.on('close', (err: Error, value: webSocket.WebSocketCloseResult): void => {
      this.stopHeartbeat();
      this.updateState({ ...this.connectionState, isConnected: false });
      this.emit(AgentEvents.disconnected());

      // 非主动 shutdown 时尝试重连
      if (!this.isShutdown) {
        this.retryCount++;
        if (this.retryCount < MAX_RETRIES) {
          // 指数退避 + 抖动
          const baseDelay: number = Math.min(1000 * Math.pow(2, this.retryCount - 1), 30000);
          const jitter: number = Math.floor(Math.random() * Math.max(baseDelay / 2, 1));
          const delay: number = baseDelay + jitter;
          setTimeout((): void => { this.connectLoop(); }, delay);
        } else {
          this.emit(AgentEvents.error(`Connection failed after ${MAX_RETRIES} attempts`));
        }
      }
    });

    // 错误
    client.on('error', (err: Error): void => {
      this.stopHeartbeat();
      this.updateState({ ...this.connectionState, isConnected: false });
      if (!this.isShutdown) {
        this.handleConnectError(err, config);
      }
    });
  }

  /**
   * 处理连接错误：递增重试计数并安排重连。
   */
  private handleConnectError(err: Error, config: AgentConfig): void {
    this.retryCount++;
    this.updateState({ ...this.connectionState, isConnected: false });

    const msg: string = err.message ?? 'Connection error';
    if (this.retryCount >= MAX_RETRIES) {
      this.emit(AgentEvents.error(`Connection failed after ${MAX_RETRIES} attempts: ${msg}`));
    } else {
      this.emit(AgentEvents.error(`Connection failed (retry ${this.retryCount}/${MAX_RETRIES}): ${msg}`));
      const baseDelay: number = Math.min(1000 * Math.pow(2, this.retryCount - 1), 30000);
      const jitter: number = Math.floor(Math.random() * Math.max(baseDelay / 2, 1));
      const delay: number = baseDelay + jitter;
      setTimeout((): void => { this.connectLoop(); }, delay);
    }
  }

  /**
   * 启动心跳：每 30s 发送 ping 帧。
   */
  private startHeartbeat(client: webSocket.WebSocketClient): void {
    this.stopHeartbeat();
    this.heartbeatTimerId = setInterval((): void => {
      if (this.wsClient === null || this.isShutdown) {
        this.stopHeartbeat();
        return;
      }
      const pingFrame: string = JSON.stringify({ type: 'ping' });
      this.wsClient.send(pingFrame).catch((): void => {
        // 心跳发送失败意味着连接已断，停止心跳
        this.stopHeartbeat();
      });
    }, HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimerId !== -1) {
      clearInterval(this.heartbeatTimerId);
      this.heartbeatTimerId = -1;
    }
  }

  /**
   * 构造 WebSocket URL：http(s):// → ws(s):// + /ws
   */
  private buildWsUrl(serverUrl: string): string {
    let url: string = serverUrl;
    url = url.replace(/^https:\/\//, 'wss://');
    url = url.replace(/^http:\/\//, 'ws://');
    url = url.replace(/\/+$/, '');
    return `${url}/ws`;
  }

  /**
   * 解析服务端消息帧并派发事件。
   * async 因为 E2E 解密（CryptoManager.decrypt）是异步的。
   */
  private async handleMessage(text: string): Promise<void> {
    try {
      const json: Record<string, Object> = JSON.parse(text) as Record<string, Object>;
      const type: string = (json['type'] as string) ?? '';

      switch (type) {
        case 'message':
        case 'response': {
          const raw: string = (json['content'] as string) ?? '';
          const delta: boolean = (json['delta'] as boolean) ?? false;
          // E2E：尝试解密；失败则原样展示（兼容非 E2E 对端）
          const content: string = await this.decrypt(raw);
          this.emit(AgentEvents.messageReceived(content, delta));
          // 仅缓存完整的助手回复（非增量帧）
          if (!delta && content.length > 0) {
            const msgSessionId: string = (json['sessionId'] as string) ?? '';
            if (msgSessionId.length > 0) {
              this.cacheMessage(msgSessionId, MessageRole.Assistant, content);
            }
            this.emit(AgentEvents.streamComplete());
          }
          break;
        }
        case 'error': {
          const msg: string = (json['message'] as string) ?? 'Unknown error';
          this.emit(AgentEvents.error(msg));
          break;
        }
        case 'ping':
          // 心跳响应，忽略
          break;
        default:
          this.emit(AgentEvents.error(`Unknown frame type: ${type}`));
          break;
      }
    } catch (e) {
      this.emit(AgentEvents.error('Malformed frame from server'));
    }
  }

  async sendMessage(sessionId: string, content: string): Promise<void> {
    // E2E：发送前加密正文
    const outContent: string = await this.encrypt(content);

    const message: string = JSON.stringify({
      type: 'message',
      sessionId: sessionId,
      content: outContent,
      role: MessageRole.User
    });

    // 先把用户消息写入本地缓存（保存明文）
    if (sessionId.length > 0 && content.length > 0) {
      this.cacheMessage(sessionId, MessageRole.User, content);
    }

    if (this.wsClient === null) {
      this.emit(AgentEvents.error('Failed to send message: not connected'));
      return;
    }

    try {
      await this.wsClient.send(message);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : 'Send failed';
      this.emit(AgentEvents.error(`Failed to send message: ${msg}`));
    }
  }

  private cacheMessage(sessionId: string, role: MessageRole, content: string): void {
    const list: CachedMessage[] = this.localMessageCache.get(sessionId) ?? [];
    list.push({ role, content });
    this.localMessageCache.set(sessionId, list);
  }

  async clearHistory(sessionId: string): Promise<void> {
    this.localMessageCache.delete(sessionId);
  }

  async clearAllHistory(): Promise<void> {
    this.localMessageCache.clear();
  }

  disconnect(): void {
    this.cleanupClient();
    this.stopHeartbeat();
    this.updateState({ ...DEFAULT_CONNECTION_STATE });
    this.emit(AgentEvents.disconnected());
  }

  shutdown(): void {
    this.isShutdown = true;
    this.stopHeartbeat();
    this.cleanupClient();
    this.eventListener = null;
    this.stateListener = null;
    this.updateState({ ...DEFAULT_CONNECTION_STATE });
  }

  private cleanupClient(): void {
    if (this.wsClient !== null) {
      try {
        this.wsClient.close();
      } catch (e) {
        // 忽略关闭错误
      }
      this.wsClient = null;
    }
  }

  // ── E2E 加解密（CryptoManager AES-256-GCM + PBKDF2）──

  private async encrypt(plain: string): Promise<string> {
    if (this.e2eKey === null) return plain;
    return await CryptoManager.getInstance().encrypt(plain, this.e2eKey);
  }

  private async decrypt(cipher: string): Promise<string> {
    if (this.e2eKey === null) return cipher;
    const decrypted: string | null = await CryptoManager.getInstance().decrypt(cipher, this.e2eKey);
    return decrypted ?? cipher;
  }

  // ── 内部工具 ──

  private emit(event: AgentEvent): void {
    if (this.eventListener !== null) {
      this.eventListener(event);
    }
  }

  private updateState(state: AgentConnectionState): void {
    this.connectionState = state;
    if (this.stateListener !== null) {
      this.stateListener(state);
    }
  }
}

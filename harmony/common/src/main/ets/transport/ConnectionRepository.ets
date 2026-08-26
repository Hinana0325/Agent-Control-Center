// ConnectionRepository（对标 android transport/ConnectionRepository.kt）
// Transport 单例所有者，进程内持有唯一 transport 实例。
//
// 背景：Android 端此前 ChatViewModel 和 AgentConnectionService 各自创建
// transport 实例导致双连接。本类作为单例，在进程内持有唯一 transport，
// 统一管理事件流与连接状态。
//
// 网络切换感知：通过 @ohos.net.connection 注册网络回调，
// 网络恢复后主动重连。

import connection from '@ohos.net.connection';
import { AgentConfig, AgentType } from '../protocol/Agent';
import { AgentEvent, AgentConnectionState, DEFAULT_CONNECTION_STATE } from '../protocol/AgentEvent';
import { AgentTransport, EventListener, ConnectionStateListener } from './AgentTransport';
import { TransportFactory } from './TransportFactory';

export class ConnectionRepository {
  private transport: AgentTransport | null = null;

  private eventListener: EventListener | null = null;
  private stateListener: ConnectionStateListener | null = null;

  // 最近一次 connect 的配置，用于网络恢复后自动重连
  private lastConfig: AgentConfig | null = null;
  private lastE2eKey: string | null = null;

  private isNetworkAvailable: boolean = true;
  private netHandle: connection.NetHandle | null = null;

  private static instance: ConnectionRepository | null = null;

  private constructor() {
    this.registerNetworkCallback();
  }

  static getInstance(): ConnectionRepository {
    if (ConnectionRepository.instance === null) {
      ConnectionRepository.instance = new ConnectionRepository();
    }
    return ConnectionRepository.instance;
  }

  /**
   * 注册事件监听器（透传当前 transport 的事件）。
   */
  setEventListener(listener: EventListener): void {
    this.eventListener = listener;
    this.transport?.setEventListener(listener);
  }

  /**
   * 注册连接状态监听器（透传当前 transport 的状态）。
   */
  setConnectionStateListener(listener: ConnectionStateListener): void {
    this.stateListener = listener;
    this.transport?.setConnectionStateListener(listener);
  }

  /** 当前是否已连接。 */
  get isConnected(): boolean {
    return this.transport?.getConnectionState().isConnected ?? false;
  }

  /** 同步读取当前连接状态。 */
  getConnectionState(): AgentConnectionState {
    return this.transport?.getConnectionState() ?? DEFAULT_CONNECTION_STATE;
  }

  /**
   * 连接到指定 Agent。
   * 若当前 transport 不存在或类型不同，则先 shutdown 旧 transport，
   * 再通过 TransportFactory 创建新 transport。若类型相同则复用并重新连接。
   */
  connect(config: AgentConfig, e2eKey: string | null): void {
    // 记录配置用于网络恢复后重连
    this.lastConfig = config;
    this.lastE2eKey = e2eKey;

    const current: AgentTransport | null = this.transport;
    const currentType: AgentType | undefined = current?.getConnectionState().agentType;
    const needsNew: boolean = current === null || currentType !== config.type;

    if (needsNew) {
      current?.shutdown();
      const newTransport: AgentTransport = TransportFactory.create(config.type);
      this.transport = newTransport;
      // 将监听器绑定到新 transport
      if (this.eventListener !== null) {
        newTransport.setEventListener(this.eventListener);
      }
      if (this.stateListener !== null) {
        newTransport.setConnectionStateListener(this.stateListener);
      }
      newTransport.connect(config, e2eKey);
    } else {
      // 类型相同，复用并重新连接
      current.connect(config, e2eKey);
    }
  }

  /** 通过当前 transport 发送消息。transport 为 null 时安全跳过。 */
  async sendMessage(sessionId: string, content: string): Promise<void> {
    if (this.transport === null) return;
    await this.transport.sendMessage(sessionId, content);
  }

  /** 清空指定 session 的传输层历史。 */
  async clearHistory(sessionId: string): Promise<void> {
    if (this.transport === null) return;
    await this.transport.clearHistory(sessionId);
  }

  /** 断开当前连接但保留 transport 实例以便后续重连。 */
  disconnect(): void {
    this.transport?.disconnect();
  }

  /**
   * 彻底关闭 transport 并释放所有底层资源。
   */
  shutdown(): void {
    this.transport?.shutdown();
    this.transport = null;
    this.lastConfig = null;
    this.lastE2eKey = null;
  }

  /**
   * 运行时热更新 E2E 密钥。
   */
  updateE2eKey(key: string | null): void {
    this.lastE2eKey = key;
    this.transport?.updateE2eKey(key);
  }

  /**
   * 注册网络回调，在网络恢复后主动重连。
   */
  private registerNetworkCallback(): void {
    try {
      connection.on('netAvailable', (): void => {
        if (!this.isNetworkAvailable) {
          this.isNetworkAvailable = true;
          const config: AgentConfig | null = this.lastConfig;
          if (config !== null) {
            // 网络恢复，主动重连
            this.connect(config, this.lastE2eKey);
          }
        }
      });
      connection.on('netLost', (): void => {
        this.isNetworkAvailable = false;
      });
    } catch (e) {
      // 网络回调注册失败，降级为不监听
    }
  }
}

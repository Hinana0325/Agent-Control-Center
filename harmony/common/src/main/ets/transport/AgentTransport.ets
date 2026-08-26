// AgentTransport 统一传输层契约（对标 android transport/protocol/AgentTransport.kt）
// 不同 AgentType 由 TransportFactory 路由到具体实现：
//  - WebSocketTransport  : Hermes / OpenClaw / OpenCode（WebSocket 协议）
//  - OpenAIHttpTransport : OpenAI / OpenRouter / Ollama / LM Studio / Xiaomi MiMo / OpenWebUI（HTTP + SSE）
//
// ArkTS 无 Kotlin Flow，事件流改为回调监听器模式：
//  - setEventListener：注册 AgentEvent 回调
//  - setConnectionStateListener：注册 AgentConnectionState 回调
//  - getConnectionState：同步读取当前状态快照

import { AgentEvent, AgentEventType, AgentConnectionState, DEFAULT_CONNECTION_STATE,
  ConnectedEvent, DisconnectedEvent, MessageReceivedEvent, ErrorEvent,
  ReconnectingEvent, StreamCompleteEvent } from '../protocol/AgentEvent';
import { AgentConfig, AgentType } from '../protocol/Agent';

// 事件监听器：传输层发出 AgentEvent 时回调
export type EventListener = (event: AgentEvent) => void;

// 连接状态监听器：connectionState 变更时回调
export type ConnectionStateListener = (state: AgentConnectionState) => void;

/**
 * 统一 Agent 传输层接口。
 *
 * 多轮对话历史管理：
 *  - WebSocket 传输：服务端通过 sessionId 维护会话状态，客户端仅需在每条消息中
 *    携带正确的 sessionId。clearHistory / clearAllHistory 仅清空本地展示缓存。
 *  - HTTP 传输：服务端无状态，多轮上下文由客户端维护并随请求发送。
 *    clearHistory / clearAllHistory 清空客户端侧会话历史。
 */
export interface AgentTransport {
  /** 注册事件监听器。覆盖前一个监听器。 */
  setEventListener(listener: EventListener): void;

  /** 注册连接状态监听器。覆盖前一个监听器。 */
  setConnectionStateListener(listener: ConnectionStateListener): void;

  /** 同步读取当前连接状态快照。 */
  getConnectionState(): AgentConnectionState;

  /**
   * 建立连接。
   * - WebSocket 传输：建立 WebSocket 会话，自动重连。
   * - HTTP 传输：探测端点可达性，标记就绪状态。
   *
   * @param config Agent 配置（serverUrl / apiKey / model 等）
   * @param e2eKey E2E 加密密钥，null 表示不加密（仅 WebSocket 传输生效）
   */
  connect(config: AgentConfig, e2eKey?: string | null): void;

  /**
   * 通过当前连接发送消息。
   * - WebSocket 传输：发送 JSON 消息帧，服务端维护多轮上下文。
   * - HTTP 传输：发起 SSE 流式请求，增量通过 MessageReceived(isDelta=true) 回调。
   *
   * @param sessionId 会话 ID
   * @param content 用户消息正文
   */
  sendMessage(sessionId: string, content: string): Promise<void>;

  /** 断开当前连接但保留 transport 实例以便后续 connect 重连。 */
  disconnect(): void;

  /**
   * 彻底释放底层资源：关闭 HttpClient / WebSocket、移除监听器。
   * 调用后 transport 不再可用，应由 TransportFactory 创建新实例。
   */
  shutdown(): void;

  /**
   * 运行时热更新 E2E 密钥，无需断开重连。
   * - key == null：关闭加密。
   * - key != null：开启加密，后续外发消息加密、收到的消息尝试解密。
   * 默认空操作：不适用 E2E 的传输（如 OpenAIHttpTransport）无需实现。
   */
  updateE2eKey(key: string | null): void;

  /**
   * 清空指定 sessionId 的本地会话历史。
   * Promise 语义便于在锁内安全修改共享历史结构。
   */
  clearHistory(sessionId: string): Promise<void>;

  /** 清空所有会话的本地历史。 */
  clearAllHistory(): Promise<void>;
}

/**
 * 构造 AgentEvent 的便捷工厂方法，避免在各 transport 实现中重复拼装 type 字段。
 * 通过显式标注子类型接口，避免 ArkTS 严格模式下 type 字段无法自动收窄的问题。
 */
export class AgentEvents {
  static connected(serverUrl: string, agentType: AgentType): AgentEvent {
    const event: ConnectedEvent = { type: AgentEventType.Connected, serverUrl, agentType };
    return event;
  }

  static disconnected(reason: string = ''): AgentEvent {
    const event: DisconnectedEvent = { type: AgentEventType.Disconnected, reason };
    return event;
  }

  static messageReceived(content: string, isDelta: boolean = false): AgentEvent {
    const event: MessageReceivedEvent = { type: AgentEventType.MessageReceived, content, isDelta };
    return event;
  }

  static error(message: string): AgentEvent {
    const event: ErrorEvent = { type: AgentEventType.Error, message };
    return event;
  }

  static reconnecting(): AgentEvent {
    const event: ReconnectingEvent = { type: AgentEventType.Reconnecting };
    return event;
  }

  static streamComplete(): AgentEvent {
    const event: StreamCompleteEvent = { type: AgentEventType.StreamComplete };
    return event;
  }
}

// AgentEvent 协议契约（对标 protocol/schemas/event-schema.json）
// 对应 Android AgentEvent sealed class + iOS Models/AgentEvent.swift enum。
// 通过 type 字段判别的标签联合 (discriminated union)。

import { AgentType } from './Agent';

export enum AgentEventType {
  Connected = 'Connected',
  Disconnected = 'Disconnected',
  MessageReceived = 'MessageReceived',
  Error = 'Error',
  Reconnecting = 'Reconnecting',
  StreamComplete = 'StreamComplete'
}

export interface ConnectedEvent {
  type: typeof AgentEventType.Connected;
  serverUrl: string;       // required
  agentType: AgentType;   // required
}

export interface DisconnectedEvent {
  type: typeof AgentEventType.Disconnected;
  reason: string;          // 默认 ''
}

export interface MessageReceivedEvent {
  type: typeof AgentEventType.MessageReceived;
  content: string;         // required，isDelta=true 时为增量片段
  isDelta: boolean;        // 默认 false
}

export interface ErrorEvent {
  type: typeof AgentEventType.Error;
  message: string;         // required
}

export interface ReconnectingEvent {
  type: typeof AgentEventType.Reconnecting;
}

export interface StreamCompleteEvent {
  type: typeof AgentEventType.StreamComplete;
}

export type AgentEvent =
  | ConnectedEvent
  | DisconnectedEvent
  | MessageReceivedEvent
  | ErrorEvent
  | ReconnectingEvent
  | StreamCompleteEvent;

// AgentConnectionState：状态快照（可随时读取），区别于事件流
export interface AgentConnectionState {
  isConnected: boolean;    // 默认 false
  serverUrl: string;       // 默认 ''
  agentType: AgentType;    // 默认 Hermes
  latency: number;        // 默认 0，单位 ms
}

export const DEFAULT_CONNECTION_STATE: AgentConnectionState = {
  isConnected: false,
  serverUrl: '',
  agentType: AgentType.Hermes,
  latency: 0
};

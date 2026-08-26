// TransportFactory（对标 android transport/TransportFactory.kt）
// 按 AgentType 路由到具体的传输实现。
//
// - Hermes / OpenClaw / OpenCode → WebSocketTransport（WebSocket 协议）
// - OpenAI / XiaomiMiMo / LocalModel / OpenWebUI → OpenAIHttpTransport（HTTP + SSE）
// - ComfyUI → ComfyUITransport（提交工作流 + 轮询 + 图片下载）

import { AgentType } from '../protocol/Agent';
import { AgentTransport } from './AgentTransport';
import { OpenAIHttpTransport } from './http/OpenAIHttpTransport';
import { ComfyUITransport } from './comfyui/ComfyUITransport';
import { WebSocketTransport } from './websocket/WebSocketTransport';

export class TransportFactory {
  /**
   * 按 AgentType 创建对应的传输实现。
   * 调用方持有返回的 transport 实例，负责其生命周期（connect / disconnect / shutdown）。
   */
  static create(type: AgentType): AgentTransport {
    switch (type) {
      case AgentType.Hermes:
      case AgentType.OpenClaw:
      case AgentType.OpenCode:
        return new WebSocketTransport();

      case AgentType.OpenAI:
      case AgentType.XiaomiMiMo:
      case AgentType.LocalModel:
      case AgentType.OpenWebUI:
        return new OpenAIHttpTransport();

      case AgentType.ComfyUI:
        // 文生图：提交工作流 → 轮询结果 → 下载图片
        return new ComfyUITransport();

      default:
        return new OpenAIHttpTransport();
    }
  }
}

// ComfyUITransport（对标 android transport/comfyui/ComfyUITransport.kt）
// ComfyUI 传输层 — 适配基于节点的图像生成工作流引擎。
//
// ComfyUI 无聊天接口，API 范式为「提交工作流 → 轮询结果 → 下载图片」。
// 本传输层提供两种模式（由 sendMessage 的 content 自动判断）：
//
// 1. 文本→图像模式（content 不以 { 开头）：
//    用户输入作为正向提示词，由 ComfyWorkflowBuilder 构造默认文生图工作流。
//
// 2. 工作流 JSON 模式（content 以 { 开头）：
//    用户直接提供 ComfyUI API 格式的工作流 JSON，原样提交到 /prompt。
//    适用于需要自定义节点/参数的高级用户。
//
// 结果图片通过 /view 端点下载，转 base64 后以 markdown 图片语法返回：
// ![ComfyUI](data:image/png;base64,...)
//
// HTTP 操作委托给 ComfyApiClient，工作流构造委托给 ComfyWorkflowBuilder，
// 图片提取委托给 ComfyImageOutputExtractor。

import { AgentConfig, AgentType } from '../../protocol/Agent';
import { AgentEvent, AgentConnectionState, DEFAULT_CONNECTION_STATE } from '../../protocol/AgentEvent';
import { AgentTransport, EventListener, ConnectionStateListener, AgentEvents } from '../AgentTransport';
import { ComfyApiClient, SubmitResult, PollResult } from './ComfyApiClient';
import { ComfyWorkflowBuilder } from './ComfyWorkflowBuilder';
import { ComfyImageOutput } from './ComfyImageOutput';

export class ComfyUITransport implements AgentTransport {
  private eventListener: EventListener | null = null;
  private stateListener: ConnectionStateListener | null = null;
  private connectionState: AgentConnectionState = { ...DEFAULT_CONNECTION_STATE };

  private apiClient: ComfyApiClient = new ComfyApiClient();

  private currentConfig: AgentConfig | null = null;

  // 串行化标记：同一时刻只允许一个 sendMessage 执行（对标 Android sendMutex）
  private isSending: boolean = false;

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
    // ComfyUI 为明文 HTTP API，E2E 不适用（忽略 e2eKey）。
    this.currentConfig = config;
    // 探活：GET /system_stats（ComfyUI 内置健康检查端点）
    this.apiClient.checkHealth(config).then((reachable: boolean) => {
      if (reachable) {
        this.updateState({
          isConnected: true,
          serverUrl: config.serverUrl,
          agentType: AgentType.ComfyUI
        });
        this.emit(AgentEvents.connected(config.serverUrl, AgentType.ComfyUI));
      } else {
        this.emit(AgentEvents.error('无法连接到 ComfyUI 服务端'));
      }
    });
  }

  async sendMessage(sessionId: string, content: string): Promise<void> {
    if (this.isSending) {
      return;
    }
    this.isSending = true;
    try {
      const config: AgentConfig | null = this.currentConfig;
      if (config === null) {
        this.emit(AgentEvents.error('Not connected'));
        return;
      }
      const base: string = config.serverUrl.replace(/\/+$/, '');

      const workflowJson: Record<string, Object> | null = this.buildWorkflow(content, config);
      if (workflowJson === null) {
        return;
      }
      this.emit(AgentEvents.messageReceived('正在提交工作流到 ComfyUI...\n', true));

      const promptId: string | null = await this.submitWorkflow(base, workflowJson, config);
      if (promptId === null) {
        return;
      }
      this.emit(AgentEvents.messageReceived(
        `工作流已提交 (prompt_id: ${promptId})，等待执行完成...\n`, true));

      const images: ComfyImageOutput[] | null = await this.pollForImages(base, promptId, config);
      if (images === null) {
        return;
      }
      await this.downloadAndEmitImages(images, base, config);
    } finally {
      this.isSending = false;
    }
  }

  /**
   * 根据输入内容构造工作流 JSON。
   * - content 以 { 开头 → 解析为用户提供的 ComfyUI API 工作流 JSON
   * - 否则 → 构造默认文生图工作流
   * 解析失败时发送错误事件并返回 null。
   */
  private buildWorkflow(content: string, config: AgentConfig): Record<string, Object> | null {
    if (!content.trimStart().startsWith('{')) {
      return ComfyWorkflowBuilder.buildTextToImageWorkflow(content, config);
    }
    try {
      return JSON.parse(content) as Record<string, Object>;
    } catch (e) {
      this.emit(AgentEvents.error(`工作流 JSON 解析失败：${e}`));
      return null;
    }
  }

  /**
   * 提交工作流，出错时发送错误事件并返回 null。
   */
  private async submitWorkflow(base: string, workflowJson: Record<string, Object>,
    config: AgentConfig): Promise<string | null> {
    const result: SubmitResult = await this.apiClient.submitWorkflow(base, workflowJson, config);
    if (result.kind === 'success') {
      return result.promptId;
    }
    if (result.kind === 'httpError') {
      this.emit(AgentEvents.error(
        `ComfyUI 提交失败 (HTTP ${result.code}): ${result.body}`));
    } else if (result.kind === 'missingPromptId') {
      this.emit(AgentEvents.error('ComfyUI 响应缺少 prompt_id'));
    } else if (result.kind === 'invalidUrl') {
      this.emit(AgentEvents.error('Invalid or blocked server URL'));
    } else {
      this.emit(AgentEvents.error(`提交工作流失败：${result.message}`));
    }
    return null;
  }

  /**
   * 轮询工作流结果，返回图片输出列表；出错时发送错误事件并返回 null。
   */
  private async pollForImages(base: string, promptId: string,
    config: AgentConfig): Promise<ComfyImageOutput[] | null> {
    const result: PollResult = await this.apiClient.pollHistory(base, promptId, config);
    if (result.kind === 'success') {
      return result.images;
    }
    if (result.kind === 'workflowError') {
      this.emit(AgentEvents.error('ComfyUI 工作流执行出错'));
    } else if (result.kind === 'timeout') {
      this.emit(AgentEvents.error('ComfyUI 工作流执行超时或未生成图片'));
    } else {
      this.emit(AgentEvents.error(`轮询结果失败：${result.message}`));
    }
    return null;
  }

  /**
   * 下载所有图片并以 markdown 形式输出；若全部失败则发送错误事件。
   */
  private async downloadAndEmitImages(images: ComfyImageOutput[], base: string,
    config: AgentConfig): Promise<void> {
    let markdown: string = '';
    for (let i: number = 0; i < images.length; i++) {
      const md: string | null = await this.apiClient.downloadImageAsMarkdown(
        images[i], base, config.apiKey);
      if (md === null) {
        continue;
      }
      if (i > 0 && markdown.length > 0) {
        markdown += '\n\n';
      }
      markdown += md;
    }
    if (markdown.length === 0) {
      this.emit(AgentEvents.error('图片下载失败'));
      return;
    }
    this.emit(AgentEvents.messageReceived(markdown, false));
    this.emit(AgentEvents.streamComplete());
  }

  disconnect(): void {
    this.currentConfig = null;
    this.updateState({ ...DEFAULT_CONNECTION_STATE });
    this.emit(AgentEvents.disconnected('User disconnected'));
  }

  shutdown(): void {
    this.currentConfig = null;
    this.eventListener = null;
    this.stateListener = null;
    this.updateState({ ...DEFAULT_CONNECTION_STATE });
  }

  // E2E 不适用于 ComfyUI HTTP 传输
  updateE2eKey(key: string | null): void { }

  // ComfyUI 每次提交独立工作流，无客户端会话历史
  async clearHistory(sessionId: string): Promise<void> { }

  async clearAllHistory(): Promise<void> { }

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

// OpenAIHttpTransport（对标 android transport/http/OpenAIHttpTransport.kt）
// OpenAI 兼容 HTTP + SSE 传输层。
//
// 覆盖 OpenAI / OpenRouter / Ollama / LM Studio / vLLM / Xiaomi MiMo / OpenWebUI ——
// 它们都暴露 OpenAI 格式的 /v1/chat/completions 端点。
//
// - 优先以 stream:true 发送，解析 SSE data: 行得到增量（delta）。
// - 对不支持 SSE 的端点（返回单个 JSON 完成包），自动回退为整段解析。
// - HTTP 传输无持久连接：connect() 仅探测端点并标记就绪；disconnect() 才置为离线。
// - 多轮对话历史由客户端维护，按 sessionId 隔离，滑动窗口 20 条。

import http from '@ohos.net.http';
import util from '@ohos.util';
import { AgentConfig, AgentType } from '../../protocol/Agent';
import { AgentEvent, AgentEventType, AgentConnectionState, DEFAULT_CONNECTION_STATE } from '../../protocol/AgentEvent';
import { MessageRole } from '../../protocol/Message';
import { AppErrorCode } from '../../protocol/ErrorCodes';
import { AgentTransport, EventListener, ConnectionStateListener, AgentEvents } from '../AgentTransport';
import { UrlValidator } from '../../security/UrlValidator';

// 每条 session 最多保留的历史消息条数（含 user 与 assistant）
const MAX_HISTORY_MESSAGES: number = 20;

// HTTP 请求超时
const CONNECT_TIMEOUT_MS: number = 10000;
const READ_TIMEOUT_MS: number = 120000;

// 对话消息结构（仅含 role + content，用于拼装请求体）
interface ConversationMessage {
  role: string;
  content: string;
}

// SSE 流解析状态
interface SseParserState {
  dataBuffer: string;       // 累积的 data: 行内容
  singleJson: string;       // 非 SSE 行的 fallback 累积
  sawData: boolean;          // 是否遇到过 data: 行
  done: boolean;            // 是否收到 [DONE]
}

export class OpenAIHttpTransport implements AgentTransport {
  private eventListener: EventListener | null = null;
  private stateListener: ConnectionStateListener | null = null;
  private connectionState: AgentConnectionState = { ...DEFAULT_CONNECTION_STATE };

  // @ohos.net.http HttpRequest 实例（流式请求期间持有，结束后 destroy）
  private httpRequest: http.HttpRequest | null = null;

  private currentConfig: AgentConfig | null = null;

  // 按 sessionId 隔离的多轮对话历史
  private conversationHistory: Map<string, ConversationMessage[]> = new Map();

  // 当前请求的助手回复累加器
  private responseAccumulator: string = '';

  // SSE 解析的行缓冲（跨 dataReceive chunk 拼接不完整行）
  private lineBuffer: string = '';

  // SSE 解析状态
  private sseState: SseParserState = { dataBuffer: '', singleJson: '', sawData: false, done: false };

  // TextDecoder 用于将 ArrayBuffer 转 UTF-8 字符串
  private decoder: util.TextDecoder = util.TextDecoder.create('utf-8');

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
    // HTTP 传输：对端是 LLM 服务，需要明文请求体，E2E 不适用（忽略 e2eKey）。
    this.currentConfig = config;

    // 探测端点可达性：GET {base}/v1/models
    this.probeEndpoint(config).then((reachable: boolean) => {
      if (reachable) {
        this.updateState({
          isConnected: true,
          serverUrl: config.serverUrl,
          agentType: config.type
        });
        this.emit(AgentEvents.connected(config.serverUrl, config.type));
      } else {
        this.updateState({ ...DEFAULT_CONNECTION_STATE });
        this.emit(AgentEvents.error(`Cannot connect to ${config.serverUrl}`));
      }
    }).catch(() => {
      this.updateState({ ...DEFAULT_CONNECTION_STATE });
      this.emit(AgentEvents.error(`Cannot connect to ${config.serverUrl}`));
    });
  }

  /**
   * 探测端点是否可达：GET {base}/v1/models。
   * 2xx / 401 / 403 / 404 视为可达（端点存在），5xx 视为不可用。
   */
  private probeEndpoint(config: AgentConfig): Promise<boolean> {
    return new Promise<boolean>((resolve: (v: boolean) => void): void => {
      const probeUrl: string = this.buildModelsUrl(config.serverUrl);
      // SSRF 防护：校验探活 URL
      if (!UrlValidator.validate(probeUrl, true)) {
        resolve(false);
        return;
      }
      const probe = http.createHttp();
      const opts: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        connectTimeout: 5000,
        readTimeout: 5000,
        header: config.apiKey.length > 0 ? { 'Authorization': `Bearer ${config.apiKey}` } : {}
      };
      probe.request(probeUrl, opts).then((resp: http.HttpResponse) => {
        const status: number = resp.responseCode;
        probe.destroy();
        resolve(status >= 200 && status <= 299 || status === 401 || status === 403 || status === 404);
      }).catch(() => {
        probe.destroy();
        resolve(false);
      });
    });
  }

  /**
   * 构造 /v1/models 探活 URL，兼容 /v1、/api/v1、/api 前缀。
   */
  private buildModelsUrl(serverUrl: string): string {
    const base: string = serverUrl.replace(/\/+$/, '');
    if (base.endsWith('/v1')) return `${base}/models`;
    if (base.endsWith('/api/v1')) return `${base}/models`;
    if (base.endsWith('/api')) return `${base}/v1/models`;
    return `${base}/v1/models`;
  }

  /**
   * 构造 /v1/chat/completions 端点 URL。
   */
  private buildChatCompletionsUrl(serverUrl: string): string {
    const base: string = serverUrl.replace(/\/+$/, '');
    if (base.endsWith('/v1')) return `${base}/chat/completions`;
    if (base.endsWith('/api/v1')) return `${base}/chat/completions`;
    if (base.endsWith('/api')) return `${base}/v1/chat/completions`;
    return `${base}/v1/chat/completions`;
  }

  async sendMessage(sessionId: string, content: string): Promise<void> {
    const config: AgentConfig | null = this.currentConfig;
    if (config === null) {
      this.emit(AgentEvents.error('Not connected'));
      return;
    }

    // 1. 追加用户消息到历史，取一份快照用于构建请求体
    const history: ConversationMessage[] = this.conversationHistory.get(sessionId) ?? [];
    history.push({ role: this.roleToApi(MessageRole.User), content });
    this.trimHistory(history);
    this.conversationHistory.set(sessionId, history);
    const messagesPayload: ConversationMessage[] = history;

    // 2. 重置助手回复累加器与 SSE 解析状态
    this.responseAccumulator = '';
    this.lineBuffer = '';
    this.sseState = { dataBuffer: '', singleJson: '', sawData: false, done: false };

    // 3. 构造请求体
    const url: string = this.buildChatCompletionsUrl(config.serverUrl);
    // SSRF 防护：校验 chat completions URL（防 apiKey 泄漏到元数据服务）
    if (!UrlValidator.validate(url, true)) {
      this.emit(AgentEvents.error('Invalid or blocked server URL'));
      return;
    }
    const body: string = JSON.stringify({
      model: config.model,
      messages: messagesPayload,
      stream: true,
      temperature: config.temperature,
      max_tokens: config.maxTokens
    });

    // 4. 发起流式 HTTP 请求
    const streamSucceeded: boolean = await this.executeStreamRequest(url, body, config.apiKey);

    // 5. 流结束后，把累加的助手回复写入历史
    this.saveAssistantResponse(sessionId);

    // 6. 通知上层流式响应已结束
    if (streamSucceeded) {
      this.emit(AgentEvents.streamComplete());
    }
  }

  /**
   * 执行流式 HTTP 请求并解析 SSE。
   * 返回 true 表示流成功完成，false 表示请求失败（已通过 Error 事件通知）。
   */
  private executeStreamRequest(url: string, body: string, apiKey: string): Promise<boolean> {
    return new Promise<boolean>((resolve: (v: boolean) => void): void => {
      // 销毁上一次的 httpRequest（如有）
      this.httpRequest?.destroy();
      const req: http.HttpRequest = http.createHttp();
      this.httpRequest = req;

      let succeeded: boolean = false;

      // 注册 dataReceive 回调：每收到一个 chunk 调用
      req.on('dataReceive', (data: ArrayBuffer): void => {
        const text: string = this.decoder.decodeWithStream(data);
        this.parseSseChunk(text);
      });

      req.on('dataEnd', (): void => {
        // 刷新缓冲中残留的行
        this.flushRemainingLines();
        succeeded = this.sseState.sawData && !this.sseState.done ? true : succeeded;
        resolve(succeeded);
      });

      req.on('dataProgress', (received: number, total: number): void => {
        // 进度回调，暂不处理
      });

      const opts: http.HttpRequestOptions = {
        method: http.RequestMethod.POST,
        connectTimeout: CONNECT_TIMEOUT_MS,
        readTimeout: READ_TIMEOUT_MS,
        header: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${apiKey}`
        },
        extraData: body
      };

      // requestInStream 启用流式响应接收
      req.requestInStream(url, opts).then((resp: http.HttpResponse) => {
        const status: number = resp.responseCode;
        if (status < 200 || status >= 300) {
          const errBody: string = typeof resp.result === 'string'
            ? (resp.result as string).substring(0, 2000)
            : 'Unknown error body';
          this.emit(AgentEvents.error(`HTTP ${status}: ${errBody}`));
          this.updateState({ ...this.connectionState, isConnected: false });
          succeeded = false;
        }
        // 2xx 时数据通过 dataReceive / dataEnd 回调处理
      }).catch((err: Error): void => {
        this.emit(AgentEvents.error(`Request failed: ${err.message ?? 'Network error'}`));
        this.updateState({ ...this.connectionState, isConnected: false });
        succeeded = false;
        resolve(succeeded);
      });
    });
  }

  /**
   * 解析一个 SSE chunk（可能含多行或不完整行）。
   */
  private parseSseChunk(chunk: string): void {
    this.lineBuffer += chunk;
    let idx: number = this.lineBuffer.indexOf('\n');
    while (idx >= 0) {
      const rawLine: string = this.lineBuffer.substring(0, idx);
      this.lineBuffer = this.lineBuffer.substring(idx + 1);
      // SSE 使用 \r\n 或 \n 行尾；仅剥离尾部 \r，不要 trim（data 字段可能以空格开头）
      const line: string = rawLine.endsWith('\r') ? rawLine.substring(0, rawLine.length - 1) : rawLine;
      this.processSseLine(line);
      idx = this.lineBuffer.indexOf('\n');
    }
  }

  /**
   * 处理单行 SSE 数据。
   */
  private processSseLine(line: string): void {
    if (line.length === 0) {
      // 空行：派发累积的 data 事件并重置
      this.flushData();
      return;
    }
    if (line.startsWith(':')) {
      // SSE 注释 / 心跳；忽略
      return;
    }
    if (line.startsWith('data:')) {
      this.sseState.sawData = true;
      // 按 SSE 规范：剥离 "data:" 后恰好一个前导空格
      let data: string = line.substring(5); // 去掉 "data:"
      if (data.startsWith(' ')) data = data.substring(1);
      if (this.sseState.dataBuffer.length > 0) this.sseState.dataBuffer += '\n';
      this.sseState.dataBuffer += data;
    } else {
      // 非 SSE 行（如非流式服务器返回的整段 JSON），累积到 singleJson
      this.sseState.singleJson += line + '\n';
    }
  }

  /**
   * 派发累积的 data 缓冲。若缓冲包含 [DONE] 标记，标记流结束。
   */
  private flushData(): void {
    if (this.sseState.dataBuffer.length === 0) return;
    const data: string = this.sseState.dataBuffer;
    this.sseState.dataBuffer = '';
    if (data === '[DONE]') {
      this.sseState.done = true;
      return;
    }
    this.emitDelta(data);
  }

  /**
   * 刷新缓冲中残留的最后一行（dataEnd 时调用）。
   */
  private flushRemainingLines(): void {
    if (this.lineBuffer.length > 0) {
      this.processSseLine(this.lineBuffer);
      this.lineBuffer = '';
    }
    this.flushData();
    // 非 SSE 整包 fallback
    if (!this.sseState.sawData && this.sseState.singleJson.trim().length > 0) {
      this.emitFull(this.sseState.singleJson);
    }
  }

  /**
   * 解析 SSE delta JSON 并发出 MessageReceived(isDelta=true) 事件。
   */
  private emitDelta(data: string): void {
    try {
      const json: Record<string, Object> = JSON.parse(data) as Record<string, Object>;
      const choices: Object[] = json['choices'] as Object[];
      if (choices === undefined || choices === null || choices.length === 0) return;
      const choice: Record<string, Object> = choices[0] as Record<string, Object>;
      const delta: Record<string, Object> = choice['delta'] as Record<string, Object>;
      const text: string = delta !== undefined && delta !== null
        ? (delta['content'] as string ?? '')
        : '';
      if (text.length > 0) {
        this.responseAccumulator += text;
        this.emit(AgentEvents.messageReceived(text, true));
      }
    } catch (e) {
      // 解析失败不中断流，后续 delta 仍可发出
    }
  }

  /**
   * 解析非 SSE 整段 JSON 并发出 MessageReceived(isDelta=false) 事件。
   */
  private emitFull(jsonText: string): void {
    try {
      const json: Record<string, Object> = JSON.parse(jsonText) as Record<string, Object>;
      const choices: Object[] = json['choices'] as Object[];
      if (choices === undefined || choices === null || choices.length === 0) return;
      const choice: Record<string, Object> = choices[0] as Record<string, Object>;
      const message: Record<string, Object> = choice['message'] as Record<string, Object>;
      const text: string = message !== undefined && message !== null
        ? (message['content'] as string ?? '')
        : '';
      if (text.length > 0) {
        this.responseAccumulator += text;
        this.emit(AgentEvents.messageReceived(text, false));
      }
    } catch (e) {
      // 解析失败不中断流
    }
  }

  /**
   * 把累加的助手回复写入指定 session 的历史。
   * 空回复不会被写入，避免污染历史。
   */
  private saveAssistantResponse(sessionId: string): void {
    const text: string = this.responseAccumulator;
    this.responseAccumulator = '';
    if (text.length === 0) return;
    const history: ConversationMessage[] = this.conversationHistory.get(sessionId) ?? [];
    history.push({ role: this.roleToApi(MessageRole.Assistant), content: text });
    this.trimHistory(history);
    this.conversationHistory.set(sessionId, history);
  }

  /**
   * 滑动窗口裁剪：保留最近 MAX_HISTORY_MESSAGES 条，从头部移除较旧条目。
   */
  private trimHistory(messages: ConversationMessage[]): void {
    const overflow: number = messages.length - MAX_HISTORY_MESSAGES;
    if (overflow > 0) {
      messages.splice(0, overflow);
    }
  }

  /**
   * 将 MessageRole 枚举转为 OpenAI API 所需的小写 role 字符串。
   */
  private roleToApi(role: MessageRole): string {
    switch (role) {
      case MessageRole.User: return 'user';
      case MessageRole.Assistant: return 'assistant';
      case MessageRole.System: return 'system';
      case MessageRole.Tool: return 'tool';
      default: return 'user';
    }
  }

  async clearHistory(sessionId: string): Promise<void> {
    this.conversationHistory.delete(sessionId);
  }

  async clearAllHistory(): Promise<void> {
    this.conversationHistory.clear();
    this.responseAccumulator = '';
  }

  disconnect(): void {
    this.httpRequest?.destroy();
    this.httpRequest = null;
    this.updateState({ ...DEFAULT_CONNECTION_STATE });
    // 传输断开时清空全部对话历史，避免重连后用过期上下文
    this.clearAllHistory();
    this.emit(AgentEvents.disconnected());
  }

  shutdown(): void {
    this.httpRequest?.destroy();
    this.httpRequest = null;
    this.eventListener = null;
    this.stateListener = null;
    this.updateState({ ...DEFAULT_CONNECTION_STATE });
  }

  // E2E 不适用于 HTTP 传输
  updateE2eKey(key: string | null): void { }

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

// ChatViewModel（对标 android features/chat/ChatViewModel.kt）
// 连接 ConnectionRepository（传输层）+ MessageDao/SessionDao（持久化），
// 管理消息列表状态与 SSE 流式增量累积。
//
// ArkUI 无 Kotlin StateFlow，状态更新通过回调通知 UI 层（@State）。

import { ConnectionRepository, AgentEvent, AgentEventType, AgentConnectionState,
  AgentConfig, AgentType, MessageRole, MessageStatus, AppErrorCode,
  MessageDao, SessionDao, AgentConfigDao, KeystoreManager,
  MessageEntity, SessionEntity, AgentConfigEntity } from 'common';

// UI 面向的消息模型
export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  timestamp: number;
  isStreaming: boolean;
}

export class ChatViewModel {
  private sessionId: string = '';
  private messageDao: MessageDao = new MessageDao();
  private sessionDao: SessionDao = new SessionDao();
  private agentConfigDao: AgentConfigDao = new AgentConfigDao();
  private connectionRepo: ConnectionRepository = ConnectionRepository.getInstance();

  // UI 回调
  onMessagesChanged?: (messages: ChatMessage[]) => void;
  onStreamingChanged?: (isStreaming: boolean) => void;
  onConnectionChanged?: (isConnected: boolean) => void;
  onLatencyMeasured?: (latencyMs: number) => void;
  onError?: (message: string) => void;

  // 当前消息列表
  private messages: ChatMessage[] = [];

  // SSE 增量累积器（当前助手回复的累积文本）
  private assistantAccumulator: string = '';

  // 当前流式中的助手消息 ID
  private streamingMessageId: string = '';

  // 是否正在流式接收
  private isStreaming: boolean = false;

  // 已连接 Agent 的显示名（供服务卡片同步）
  private connectedAgentName: string = '';

  /**
   * 初始化：创建/加载会话，加载历史消息，注册事件监听器。
   */
  async init(): Promise<void> {
    // 注册传输层事件监听器
    this.connectionRepo.setEventListener((event: AgentEvent): void => {
      this.handleEvent(event);
    });
    this.connectionRepo.setConnectionStateListener((state: AgentConnectionState): void => {
      if (this.onConnectionChanged !== undefined) {
        this.onConnectionChanged(state.isConnected);
      }
      // 连接成功时透传状态快照中的握手延迟（供服务卡片显示）
      if (state.isConnected && state.latency > 0 && this.onLatencyMeasured !== undefined) {
        this.onLatencyMeasured(state.latency);
      }
    });

    // 加载或创建默认会话
    await this.loadOrCreateSession();
    // 加载历史消息
    await this.loadMessages();
  }

  /**
   * 加载或创建默认会话。
   */
  private async loadOrCreateSession(): Promise<void> {
    const sessions: SessionEntity[] = await this.sessionDao.getAllSessions();
    if (sessions.length > 0) {
      this.sessionId = sessions[0].id;
    } else {
      // 创建默认会话
      this.sessionId = `session_${Date.now()}`;
      const now: number = Date.now();
      const session: SessionEntity = {
        id: this.sessionId,
        title: 'New Chat',
        createdAt: now,
        updatedAt: now,
        isPinned: false,
        messageCount: 0,
        summary: ''
      };
      await this.sessionDao.insertSession(session);
    }
  }

  /**
   * 从数据库加载消息并通知 UI。
   */
  private async loadMessages(): Promise<void> {
    const entities: MessageEntity[] = await this.messageDao.getMessagesBySession(this.sessionId);
    this.messages = entities.map((e: MessageEntity): ChatMessage => ({
      id: e.id,
      role: e.role as MessageRole,
      content: e.content,
      timestamp: e.timestamp,
      isStreaming: false
    }));
    this.notifyMessagesChanged();
  }

  /**
   * 连接到 Agent（使用默认 AgentConfig）。
   */
  async connect(): Promise<void> {
    const configEntity: AgentConfigEntity | null = await this.agentConfigDao.getDefaultConfig();
    if (configEntity === null) {
      if (this.onError !== undefined) {
        this.onError('No agent configured. Please add an agent in Settings.');
      }
      return;
    }

    // 解密 apiKey（AKS: 前缀 → 明文）
    const keystore: KeystoreManager = KeystoreManager.getInstance();
    const apiKey: string = await keystore.decryptOrRaw(configEntity.apiKey);

    const config: AgentConfig = {
      id: configEntity.id,
      name: configEntity.name,
      type: configEntity.type as AgentType,
      serverUrl: configEntity.serverUrl,
      apiKey: apiKey,
      model: configEntity.model,
      systemPrompt: configEntity.systemPrompt,
      temperature: configEntity.temperature,
      maxTokens: configEntity.maxTokens
    };

    this.connectionRepo.connect(config, null);
    this.connectedAgentName = configEntity.name;
  }

  /** 已连接 Agent 的显示名（未连接时为空串）。 */
  getConnectedAgentName(): string {
    return this.connectedAgentName;
  }

  /**
   * 发送消息：保存用户消息 → 通过传输层发送 → 接收流式回复。
   */
  async sendMessage(content: string): Promise<void> {
    if (content.trim().length === 0) return;
    if (this.isStreaming) return;

    // 1. 创建并保存用户消息
    const userMsgId: string = `msg_${Date.now()}_u`;
    const now: number = Date.now();
    const userMessage: ChatMessage = {
      id: userMsgId,
      role: MessageRole.User,
      content: content,
      timestamp: now,
      isStreaming: false
    };
    this.messages.push(userMessage);
    this.notifyMessagesChanged();

    // 持久化用户消息
    const userEntity: MessageEntity = {
      id: userMsgId,
      sessionId: this.sessionId,
      role: MessageRole.User,
      content: content,
      timestamp: now,
      status: MessageStatus.Sent,
      metadataJson: '{}',
      attachmentType: null,
      attachmentData: null,
      attachmentName: null,
      reaction: '',
      replyToId: null
    };
    await this.messageDao.insertMessage(userEntity);
    await this.sessionDao.incrementMessageCount(this.sessionId, now);

    // 2. 创建占位助手消息（流式累积）
    this.assistantAccumulator = '';
    this.streamingMessageId = `msg_${Date.now()}_a`;
    this.isStreaming = true;
    this.notifyStreamingChanged();
    const assistantMessage: ChatMessage = {
      id: this.streamingMessageId,
      role: MessageRole.Assistant,
      content: '',
      timestamp: Date.now(),
      isStreaming: true
    };
    this.messages.push(assistantMessage);
    this.notifyMessagesChanged();

    // 3. 通过传输层发送（SSE 回复通过 handleEvent 处理）
    await this.connectionRepo.sendMessage(this.sessionId, content);
  }

  /**
   * 处理传输层事件。
   */
  private handleEvent(event: AgentEvent): void {
    switch (event.type) {
      case AgentEventType.MessageReceived: {
        const content: string = event.content;
        const isDelta: boolean = event.isDelta;
        if (isDelta) {
          // SSE 增量：累积到当前助手消息
          this.assistantAccumulator += content;
          this.updateStreamingAssistant(this.assistantAccumulator);
        } else {
          // 完整消息（非 SSE 回退）
          this.assistantAccumulator = content;
          this.updateStreamingAssistant(content);
        }
        break;
      }
      case AgentEventType.StreamComplete: {
        // 流式结束：保存助手消息到数据库
        this.finishStreaming();
        break;
      }
      case AgentEventType.Error: {
        if (this.onError !== undefined) {
          this.onError(event.message);
        }
        // 出错时也结束流式状态
        if (this.isStreaming) {
          this.finishStreaming();
        }
        break;
      }
      case AgentEventType.Connected:
      case AgentEventType.Disconnected:
      case AgentEventType.Reconnecting:
        // 连接状态由 setConnectionStateListener 回调处理
        break;
    }
  }

  /**
   * 更新流式中的助手消息内容。
   */
  private updateStreamingAssistant(accumulatedContent: string): void {
    const idx: number = this.messages.findIndex((m: ChatMessage): boolean => m.id === this.streamingMessageId);
    if (idx >= 0) {
      this.messages[idx].content = accumulatedContent;
      this.notifyMessagesChanged();
    }
  }

  /**
   * 结束流式：保存助手消息到数据库，重置流式状态。
   */
  private async finishStreaming(): Promise<void> {
    this.isStreaming = false;
    this.notifyStreamingChanged();

    const content: string = this.assistantAccumulator;
    this.assistantAccumulator = '';

    // 更新 UI 中的助手消息为非流式
    const idx: number = this.messages.findIndex((m: ChatMessage): boolean => m.id === this.streamingMessageId);
    if (idx >= 0) {
      this.messages[idx].content = content;
      this.messages[idx].isStreaming = false;
      this.notifyMessagesChanged();
    }

    // 持久化助手消息
    if (content.length > 0) {
      const now: number = Date.now();
      const assistantEntity: MessageEntity = {
        id: this.streamingMessageId,
        sessionId: this.sessionId,
        role: MessageRole.Assistant,
        content: content,
        timestamp: now,
        status: MessageStatus.Received,
        metadataJson: '{}',
        attachmentType: null,
        attachmentData: null,
        attachmentName: null,
        reaction: '',
        replyToId: null
      };
      await this.messageDao.insertMessage(assistantEntity);
      await this.sessionDao.incrementMessageCount(this.sessionId, now);
    }
    this.streamingMessageId = '';
  }

  /**
   * 停止流式（用户点击 Stop 按钮）。
   */
  stopStreaming(): void {
    if (this.isStreaming) {
      this.connectionRepo.disconnect();
    }
  }

  // ── 状态通知 ──

  private notifyMessagesChanged(): void {
    if (this.onMessagesChanged !== undefined) {
      // 创建新数组以触发 UI 更新
      this.onMessagesChanged([...this.messages]);
    }
  }

  private notifyStreamingChanged(): void {
    if (this.onStreamingChanged !== undefined) {
      this.onStreamingChanged(this.isStreaming);
    }
  }

  getMessages(): ChatMessage[] {
    return this.messages;
  }

  getIsStreaming(): boolean {
    return this.isStreaming;
  }
}

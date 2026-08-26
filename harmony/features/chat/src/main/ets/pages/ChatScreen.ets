// Chat feature 主屏（对标 android features/chat/ChatScreen.kt + ios ChatScreen.swift）
// H7 MVP：消息列表 + 输入栏 + 流式 SSE 渲染 + 连接状态指示。
// 状态由 ChatViewModel 管理，UI 通过 @State + 回调同步。
import common from '@ohos.app.ability.common';
import { AppColors, AppSpacing, AppShape, AppType, CardDataProvider } from 'common';
import { MessageRole } from 'common';
import { ChatViewModel, ChatMessage } from '../viewmodel/ChatViewModel';

@Component
export struct ChatScreen {
  // ── UI 状态（由 ViewModel 回调驱动） ──
  @State messages: ChatMessage[] = [];
  @State inputText: string = '';
  @State isStreaming: boolean = false;
  @State isConnected: boolean = false;
  @State errorMessage: string = '';

  // ── ViewModel 与滚动控制器 ──
  private viewModel: ChatViewModel = new ChatViewModel();
  private scroller: ListScroller = new ListScroller();

  async aboutToAppear(): Promise<void> {
    // 绑定 ViewModel 回调到 @State
    this.viewModel.onMessagesChanged = (msgs: ChatMessage[]): void => {
      this.messages = msgs;
      // 消息变化后滚动到底部
      if (msgs.length > 0) {
        this.scroller.scrollToIndex(msgs.length - 1);
      }
    };
    this.viewModel.onStreamingChanged = (streaming: boolean): void => {
      this.isStreaming = streaming;
    };
    this.viewModel.onConnectionChanged = (connected: boolean): void => {
      this.isConnected = connected;
      // H5 服务卡片：同步连接状态与 Agent 名到 FormKit 卡片数据桥
      CardDataProvider.updateConnectionState(getContext(this) as common.Context,
        connected, connected ? this.viewModel.getConnectedAgentName() : null);
    };
    this.viewModel.onLatencyMeasured = (latencyMs: number): void => {
      // H5 服务卡片：同步连接握手延迟到 FormKit 卡片数据桥
      CardDataProvider.updateLatency(getContext(this) as common.Context, latencyMs);
    };
    this.viewModel.onError = (message: string): void => {
      this.errorMessage = message;
    };

    // 初始化 ViewModel：加载会话 + 历史消息 + 注册事件监听
    await this.viewModel.init();
    // 连接到 Agent（若无配置则通过 onError 提示）
    await this.viewModel.connect();
  }

  build() {
    Column() {
      // ── 顶部标题栏 + 连接状态 ──
      this.buildHeader();

      // ── 错误提示条（条件渲染） ──
      if (this.errorMessage.length > 0) {
        this.buildErrorBanner();
      }

      // ── 消息列表 ──
      this.buildMessageList();

      // ── 输入栏 ──
      this.buildInputBar();
    }
    .width('100%')
    .height('100%')
    .backgroundColor(AppColors.background);
  }

  @Builder
  buildHeader() {
    Row() {
      Text($r('app.string.chat_title'))
        .fontSize(AppType.headlineMedium)
        .fontWeight(AppType.semibold)
        .fontColor(AppColors.onSurface);

      // 连接状态指示点
      Row() {
        Circle({ width: 8, height: 8 })
          .fill(this.isConnected ? AppColors.success : AppColors.outline);
        Text(this.isConnected
          ? $r('app.string.chat_connected')
          : $r('app.string.chat_disconnected'))
          .fontSize(AppType.labelSmall)
          .fontColor(AppColors.outline)
          .margin({ left: AppSpacing.xs });
      }
      .margin({ left: AppSpacing.m });
    }
    .width('100%')
    .height(56)
    .padding({ left: AppSpacing.l, right: AppSpacing.l })
    .alignItems(VerticalAlign.Center);
  }

  @Builder
  buildErrorBanner() {
    Row() {
      Text(this.errorMessage)
        .fontSize(AppType.bodySmall)
        .fontColor(AppColors.onPrimary)
        .layoutWeight(1);
    }
    .width('100%')
    .padding({ left: AppSpacing.l, right: AppSpacing.l, top: AppSpacing.s, bottom: AppSpacing.s })
    .backgroundColor(AppColors.danger);
  }

  @Builder
  buildMessageList() {
    if (this.messages.length === 0) {
      // 空状态
      Column() {
        Text(this.isConnected
          ? $r('app.string.chat_empty_ready')
          : $r('app.string.chat_empty_no_agent'))
          .fontSize(AppType.bodyMedium)
          .fontColor(AppColors.outline);
      }
      .width('100%')
      .layoutWeight(1)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center);
    } else {
      List({ scroller: this.scroller }) {
        ForEach(this.messages, (msg: ChatMessage, idx: number): void => {
          ListItem() {
            this.buildMessageBubble(msg);
          }
          .key(msg.id);
        }, (msg: ChatMessage): string => msg.id);
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ left: AppSpacing.l, right: AppSpacing.l })
      .scrollBar(BarState.Off);
    }
  }

  @Builder
  buildMessageBubble(msg: ChatMessage) {
    // 用户消息右对齐，助手消息左对齐
    if (msg.role === MessageRole.User) {
      Row() {
        Column() {
          Text(msg.content)
            .fontSize(AppType.bodyMedium)
            .fontColor(AppColors.onPrimaryContainer);
        }
        .padding({ left: AppSpacing.m, right: AppSpacing.m, top: AppSpacing.s, bottom: AppSpacing.s })
        .borderRadius(AppShape.l)
        .backgroundColor(AppColors.primaryContainer)
        .maxWidth('80%');
      }
      .width('100%')
      .margin({ top: AppSpacing.s, bottom: AppSpacing.s })
      .justifyContent(FlexAlign.End);
    } else {
      Row() {
        Column() {
          if (msg.content.length > 0) {
            Text(msg.content)
              .fontSize(AppType.bodyMedium)
              .fontColor(AppColors.onSurface);
          } else if (msg.isStreaming) {
            // 流式生成中占位
            Text($r('app.string.chat_generating'))
              .fontSize(AppType.bodyMedium)
              .fontColor(AppColors.outline);
          }
        }
        .padding({ left: AppSpacing.m, right: AppSpacing.m, top: AppSpacing.s, bottom: AppSpacing.s })
        .borderRadius(AppShape.l)
        .backgroundColor(AppColors.surfaceVariant)
        .maxWidth('80%');
      }
      .width('100%')
      .margin({ top: AppSpacing.s, bottom: AppSpacing.s })
      .justifyContent(FlexAlign.Start);
    }
  }

  @Builder
  buildInputBar() {
    Row() {
      TextInput({ text: this.inputText, placeholder: $r('app.string.chat_input_hint') })
        .layoutWeight(1)
        .fontSize(AppType.bodyMedium)
        .borderRadius(AppShape.s)
        .backgroundColor(AppColors.surfaceVariant)
        .padding({ left: AppSpacing.m, right: AppSpacing.m })
        .enabled(!this.isStreaming)
        .onChange((value: string): void => {
          this.inputText = value;
        })
        .onSubmit((): void => {
          this.handleSend();
        });

      // 流式时显示 Stop，否则显示 Send
      Button(this.isStreaming
        ? $r('app.string.chat_stop')
        : $r('app.string.chat_send'))
        .margin({ left: AppSpacing.s })
        .fontSize(AppType.labelLarge)
        .borderRadius(AppShape.s)
        .backgroundColor(this.isStreaming ? AppColors.danger : AppColors.primary)
        .fontColor(AppColors.onPrimary)
        .enabled(this.isStreaming || this.inputText.trim().length > 0)
        .onClick((): void => {
          if (this.isStreaming) {
            this.viewModel.stopStreaming();
          } else {
            this.handleSend();
          }
        });
    }
    .width('100%')
    .padding({ left: AppSpacing.l, right: AppSpacing.l, top: AppSpacing.s, bottom: AppSpacing.l })
    .backgroundColor(AppColors.surface);
  }

  // ── 交互处理 ──

  private handleSend(): void {
    const text: string = this.inputText.trim();
    if (text.length === 0 || this.isStreaming) {
      return;
    }
    this.inputText = '';
    // 清空错误提示
    this.errorMessage = '';
    this.viewModel.sendMessage(text);
  }
}

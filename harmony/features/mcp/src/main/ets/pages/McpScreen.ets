// MCP feature 主屏（对标 android feature/mcp/McpScreen.kt）
// H4：MCP Server 管理 — 列表 + 添加表单 + 连接/断开 + 工具发现。

import common from '@ohos.app.ability.common';
import { AppColors, AppSpacing, AppShape, AppType } from 'common';
import { McpConnectionState } from 'common';
import { McpViewModel, McpServerRowState } from '../viewmodel/McpViewModel';

@Component
export struct McpScreen {
  @State rows: McpServerRowState[] = [];
  @State tools: string[] = [];
  @State message: string = '';
  @State showForm: boolean = false;
  @State formName: string = '';
  @State formUrl: string = '';
  @State formApiKey: string = '';

  private viewModel: McpViewModel = new McpViewModel();

  async aboutToAppear(): Promise<void> {
    this.viewModel.onServersChanged = (rows: McpServerRowState[]): void => {
      this.rows = rows;
    };
    this.viewModel.onToolsChanged = (tools: string[]): void => {
      this.tools = tools;
    };
    this.viewModel.onMessage = (message: string): void => {
      this.message = message;
    };
    await this.viewModel.init(getContext(this) as common.Context);
  }

  build(): void {
    Column(): void {
      this.buildHeader();
      if (this.message.length > 0) {
        Text(this.message)
          .fontSize(AppType.bodySmall)
          .fontColor(AppColors.info)
          .padding({ left: AppSpacing.l, right: AppSpacing.l })
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      this.buildServerList();
      this.buildToolsSection();
      this.buildAddButton()
    }
    .width('100%')
    .height('100%')
    .backgroundColor(AppColors.background)
  }

  @Builder
  buildHeader(): void {
    Row(): void {
      Text($r('app.string.mcp_title'))
        .fontSize(AppType.headlineMedium)
        .fontWeight(AppType.semibold)
        .fontColor(AppColors.onSurface)
    }
    .width('100%')
    .height(56)
    .padding({ left: AppSpacing.l, right: AppSpacing.l })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  buildServerList(): void {
    if (this.rows.length === 0 && !this.showForm) {
      Column(): void {
        Text($r('app.string.mcp_empty'))
          .fontSize(AppType.bodyMedium)
          .fontColor(AppColors.outline)
      }
      .width('100%')
      .padding({ top: AppSpacing.xl })
      .alignItems(HorizontalAlign.Center)
    } else {
      List(): void {
        ForEach(this.rows, (row: McpServerRowState): void => {
          ListItem(): void {
            Column(): void {
              Row(): void {
                Column(): void {
                  Text(row.server.name)
                    .fontSize(AppType.bodyLarge)
                    .fontWeight(AppType.medium)
                    .fontColor(AppColors.onSurface)
                  Text(row.server.transportUrl)
                    .fontSize(AppType.labelSmall)
                    .fontColor(AppColors.outline)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .margin({ top: AppSpacing.xs })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)

                Text(this.stateLabel(row.connectionState))
                  .fontSize(AppType.labelSmall)
                  .fontColor(this.stateColor(row.connectionState))
              }
              .width('100%')

              Row(): void {
                if (row.connectionState === McpConnectionState.Connected) {
                  Button($r('app.string.mcp_disconnect'))
                    .fontSize(AppType.labelMedium)
                    .borderRadius(AppShape.s)
                    .backgroundColor(AppColors.danger)
                    .fontColor(AppColors.onPrimary)
                    .height(32)
                    .onClick((): void => {
                      this.viewModel.disconnectServer(row.server.id);
                    })
                  Button($r('app.string.mcp_refresh'))
                    .fontSize(AppType.labelMedium)
                    .borderRadius(AppShape.s)
                    .backgroundColor(AppColors.surfaceVariant)
                    .fontColor(AppColors.onSurface)
                    .height(32)
                    .margin({ left: AppSpacing.s })
                    .onClick((): void => {
                      this.viewModel.refreshTools(row.server.id);
                    })
                } else {
                  Button($r('app.string.mcp_connect'))
                    .fontSize(AppType.labelMedium)
                    .borderRadius(AppShape.s)
                    .backgroundColor(AppColors.primary)
                    .fontColor(AppColors.onPrimary)
                    .height(32)
                    .onClick((): void => {
                      this.viewModel.connectServer(row.server.id);
                    })
                }
                Blank()
                Button($r('app.string.mcp_delete'))
                  .fontSize(AppType.labelMedium)
                  .borderRadius(AppShape.s)
                  .backgroundColor(AppColors.surfaceVariant)
                  .fontColor(AppColors.danger)
                  .height(32)
                  .onClick((): void => {
                    this.viewModel.removeServer(row.server.id);
                  })
              }
              .width('100%')
              .margin({ top: AppSpacing.s })
            }
            .padding(AppSpacing.m)
            .borderRadius(AppShape.m)
            .backgroundColor(AppColors.surfaceVariant)
            .width('100%')
            .margin({ bottom: AppSpacing.s })
          }
          .key(row.server.id)
        }, (row: McpServerRowState): string => row.server.id)
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ left: AppSpacing.l, right: AppSpacing.l })
      .scrollBar(BarState.Auto)
    }
  }

  @Builder
  buildToolsSection(): void {
    if (this.tools.length > 0) {
      Column(): void {
        Text(): void {
          Span($r('app.string.mcp_tools_title'))
          Span(` (${this.tools.length})`)
        }
          .fontSize(AppType.labelLarge)
          .fontWeight(AppType.medium)
          .fontColor(AppColors.onSurface)
          .margin({ bottom: AppSpacing.s })
        Text(this.tools.join(' · '))
          .fontSize(AppType.bodySmall)
          .fontColor(AppColors.outline)
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .width('100%')
      .padding({ left: AppSpacing.l, right: AppSpacing.l, bottom: AppSpacing.s })
      .alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  buildAddButton(): void {
    if (this.showForm) {
      Column(): void {
        TextInput({ text: this.formName, placeholder: $r('app.string.mcp_name_hint') })
          .fontSize(AppType.bodyMedium)
          .borderRadius(AppShape.s)
          .backgroundColor(AppColors.surfaceVariant)
          .margin({ bottom: AppSpacing.s })
          .onChange((value: string): void => {
            this.formName = value;
          })
        TextInput({ text: this.formUrl, placeholder: 'https://example.com/mcp' })
          .fontSize(AppType.bodyMedium)
          .borderRadius(AppShape.s)
          .backgroundColor(AppColors.surfaceVariant)
          .margin({ bottom: AppSpacing.s })
          .onChange((value: string): void => {
            this.formUrl = value;
          })
        TextInput({ text: this.formApiKey, placeholder: $r('app.string.mcp_apikey_hint') })
          .fontSize(AppType.bodyMedium)
          .borderRadius(AppShape.s)
          .backgroundColor(AppColors.surfaceVariant)
          .margin({ bottom: AppSpacing.s })
          .onChange((value: string): void => {
            this.formApiKey = value;
          })
        Row(): void {
          Button($r('app.string.mcp_cancel'))
            .fontSize(AppType.labelLarge)
            .borderRadius(AppShape.s)
            .backgroundColor(AppColors.surfaceVariant)
            .fontColor(AppColors.onSurface)
            .layoutWeight(1)
            .onClick((): void => {
              this.showForm = false;
            })
          Button($r('app.string.mcp_confirm'))
            .fontSize(AppType.labelLarge)
            .borderRadius(AppShape.s)
            .backgroundColor(AppColors.primary)
            .fontColor(AppColors.onPrimary)
            .layoutWeight(1)
            .margin({ left: AppSpacing.s })
            .enabled(this.formName.trim().length > 0
              && this.formUrl.trim().length > 0)
            .onClick((): void => {
              this.handleAdd();
            })
        }
        .width('100%')
      }
      .width('100%')
      .padding(AppSpacing.l)
    } else {
      Button($r('app.string.mcp_add'))
        .fontSize(AppType.labelLarge)
        .borderRadius(AppShape.s)
        .backgroundColor(AppColors.primary)
        .fontColor(AppColors.onPrimary)
        .width('90%')
        .margin({ bottom: AppSpacing.l })
        .onClick((): void => {
          this.showForm = true;
        })
    }
  }

  // ── 交互处理 ──

  private handleAdd(): void {
    const name: string = this.formName.trim();
    const url: string = this.formUrl.trim();
    const apiKey: string = this.formApiKey.trim();
    if (name.length === 0 || url.length === 0) {
      return;
    }
    this.formName = '';
    this.formUrl = '';
    this.formApiKey = '';
    this.showForm = false;
    this.viewModel.addServer(name, url, apiKey);
  }

  private stateLabel(state: McpConnectionState): Resource {
    switch (state) {
      case McpConnectionState.Connected: return $r('app.string.mcp_state_connected');
      case McpConnectionState.Connecting: return $r('app.string.mcp_state_connecting');
      case McpConnectionState.Failed: return $r('app.string.mcp_state_failed');
      default: return $r('app.string.mcp_state_disconnected');
    }
  }

  private stateColor(state: McpConnectionState): string {
    switch (state) {
      case McpConnectionState.Connected: return AppColors.success;
      case McpConnectionState.Connecting: return AppColors.info;
      case McpConnectionState.Failed: return AppColors.danger;
      default: return AppColors.outline;
    }
  }
}

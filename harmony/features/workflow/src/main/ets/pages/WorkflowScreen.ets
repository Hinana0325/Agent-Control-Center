// Workflow feature 主屏（对标 android features/workflow/WorkflowScreen.kt）
// H4：模板列表 + 输入执行 + 执行状态 + 历史记录。
// ViewModel 驱动，@State 通过回调同步。

import { AppColors, AppSpacing, AppShape, AppType } from 'common';
import { Workflow, WorkflowRunStatus } from 'common';
import { WorkflowRunEntity } from 'common';
import { WorkflowViewModel } from '../viewmodel/WorkflowViewModel';

@Component
export struct WorkflowScreen {
  @State templates: Workflow[] = [];
  @State selectedIndex: number = 0;
  @State inputText: string = '';
  @State isRunning: boolean = false;
  @State currentLog: string = '';
  @State output: string = '';
  @State runs: WorkflowRunEntity[] = [];

  private viewModel: WorkflowViewModel = new WorkflowViewModel();
  private scroller: Scroller = new Scroller();

  async aboutToAppear(): Promise<void> {
    this.viewModel.onRunningChanged = (running: boolean): void => {
      this.isRunning = running;
    };
    this.viewModel.onLogChanged = (log: string): void => {
      this.currentLog = log;
    };
    this.viewModel.onOutputChanged = (output: string): void => {
      this.output = output;
    };
    this.viewModel.onRunsChanged = (runs: WorkflowRunEntity[]): void => {
      this.runs = runs;
    };
    this.templates = await this.viewModel.loadTemplates();
    await this.viewModel.loadHistory();
  }

  build(): void {
    Column(): void {
      this.buildHeader();
      this.buildTemplateList();
      this.buildInputBar();
      this.buildExecutionSection();
      this.buildHistorySection();
    }
    .width('100%')
    .height('100%')
    .backgroundColor(AppColors.background)
  }

  @Builder
  buildHeader(): void {
    Row(): void {
      Text($r('app.string.workflow_title'))
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
  buildTemplateList(): void {
    List({ scroller: this.scroller }): void {
      ForEach(this.templates, (tpl: Workflow, idx: number): void => {
        ListItem(): void {
          Column(): void {
            Text(tpl.name)
              .fontSize(AppType.bodyLarge)
              .fontWeight(AppType.medium)
              .fontColor(this.selectedIndex === idx
                ? AppColors.onPrimaryContainer : AppColors.onSurface)
            Text(tpl.description)
              .fontSize(AppType.bodySmall)
              .fontColor(this.selectedIndex === idx
                ? AppColors.onPrimaryContainer : AppColors.outline)
              .margin({ top: AppSpacing.xs })
            Text(): void {
              Span(`${tpl.nodes.length}`)
              Span($r('app.string.workflow_nodes_suffix'))
            }
              .fontSize(AppType.labelSmall)
              .fontColor(this.selectedIndex === idx
                ? AppColors.onPrimaryContainer : AppColors.outline)
              .margin({ top: AppSpacing.xs })
          }
          .alignItems(HorizontalAlign.Start)
          .padding(AppSpacing.m)
          .borderRadius(AppShape.m)
          .backgroundColor(this.selectedIndex === idx
            ? AppColors.primaryContainer : AppColors.surfaceVariant)
          .width('100%')
          .onClick((): void => {
            this.selectedIndex = idx;
          })
        }
        .margin({ bottom: AppSpacing.s })
      }, (tpl: Workflow): string => tpl.id)
    }
    .width('100%')
    .height(140)
    .padding({ left: AppSpacing.l, right: AppSpacing.l })
    .scrollBar(BarState.Auto)
    .edgeEffect(EdgeEffect.Spring)
  }

  @Builder
  buildInputBar(): void {
    Row(): void {
      TextInput({ text: this.inputText, placeholder: $r('app.string.workflow_input_hint') })
        .layoutWeight(1)
        .fontSize(AppType.bodyMedium)
        .borderRadius(AppShape.s)
        .backgroundColor(AppColors.surfaceVariant)
        .enabled(!this.isRunning)
        .onChange((value: string): void => {
          this.inputText = value;
        })

      Button(this.isRunning
        ? $r('app.string.workflow_running')
        : $r('app.string.workflow_run'))
        .margin({ left: AppSpacing.s })
        .fontSize(AppType.labelLarge)
        .borderRadius(AppShape.s)
        .backgroundColor(AppColors.primary)
        .fontColor(AppColors.onPrimary)
        .enabled(!this.isRunning && this.inputText.trim().length > 0)
        .onClick((): void => {
          this.handleRun();
        })
    }
    .width('100%')
    .padding({ left: AppSpacing.l, right: AppSpacing.l, top: AppSpacing.s })
  }

  @Builder
  buildExecutionSection(): void {
    if (this.currentLog.length > 0 || this.output.length > 0) {
      Column(): void {
        if (this.currentLog.length > 0) {
          Text(this.currentLog)
            .fontSize(AppType.bodySmall)
            .fontColor(AppColors.outline)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        if (this.output.length > 0) {
          Scroll(): void {
            Text(this.output)
              .fontSize(AppType.bodySmall)
              .fontColor(AppColors.onSurface)
          }
          .layoutWeight(1)
          .align(Alignment.TopStart)
          .margin({ top: AppSpacing.s })
        }
      }
      .width('100%')
      .layoutWeight(1)
      .padding(AppSpacing.m)
      .margin({ left: AppSpacing.l, right: AppSpacing.l, top: AppSpacing.s })
      .borderRadius(AppShape.m)
      .backgroundColor(AppColors.surfaceVariant)
      .alignItems(HorizontalAlign.Start)
    } else {
      Column(): void {
        Text($r('app.string.workflow_select_hint'))
          .fontSize(AppType.bodyMedium)
          .fontColor(AppColors.outline)
      }
      .width('100%')
      .layoutWeight(1)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)
    }
  }

  @Builder
  buildHistorySection(): void {
    Column(): void {
      Text($r('app.string.workflow_history'))
        .fontSize(AppType.labelLarge)
        .fontWeight(AppType.medium)
        .fontColor(AppColors.onSurface)
        .margin({ bottom: AppSpacing.s })

      if (this.runs.length === 0) {
        Text($r('app.string.workflow_no_runs'))
          .fontSize(AppType.bodySmall)
          .fontColor(AppColors.outline)
      } else {
        List(): void {
          ForEach(this.runs, (run: WorkflowRunEntity): void => {
            ListItem(): void {
              Row(): void {
                Column(): void {
                  Text(run.workflowName)
                    .fontSize(AppType.bodyMedium)
                    .fontColor(AppColors.onSurface)
                  Text(this.formatTime(run.startedAt))
                    .fontSize(AppType.labelSmall)
                    .fontColor(AppColors.outline)
                    .margin({ top: AppSpacing.xs })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)

                Text(run.status)
                  .fontSize(AppType.labelSmall)
                  .fontColor(this.statusColor(run.status))
              }
              .width('100%')
              .padding({ top: AppSpacing.s, bottom: AppSpacing.s })
            }
            .key(run.id)
          }, (run: WorkflowRunEntity): string => run.id)
        }
        .width('100%')
        .height(160)
        .scrollBar(BarState.Auto)
      }
    }
    .width('100%')
    .padding({ left: AppSpacing.l, right: AppSpacing.l, bottom: AppSpacing.l })
    .alignItems(HorizontalAlign.Start)
  }

  // ── 交互处理 ──

  private handleRun(): void {
    const input: string = this.inputText.trim();
    if (input.length === 0 || this.isRunning || this.templates.length === 0) {
      return;
    }
    this.output = '';
    this.viewModel.execute(this.templates[this.selectedIndex], input);
  }

  private formatTime(timestamp: number): string {
    const date: Date = new Date(timestamp);
    const month: number = date.getMonth() + 1;
    const day: number = date.getDate();
    const hour: number = date.getHours();
    const minute: number = date.getMinutes();
    return `${month}/${day} ${hour}:${minute < 10 ? `0${minute}` : `${minute}`}`;
  }

  private statusColor(status: string): string {
    if (status === WorkflowRunStatus.COMPLETED) {
      return AppColors.success;
    }
    if (status === WorkflowRunStatus.RUNNING) {
      return AppColors.info;
    }
    return AppColors.danger;
  }
}

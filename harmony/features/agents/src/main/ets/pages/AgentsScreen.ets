// Agents feature 主屏（对标 android features/agents/AgentsScreen.kt）
// 占位阶段：标题栏 + Coming Soon 占位 + 次级功能入口。
//
// v5.1 IA 对齐：More Tab 移除后，Workflow / MCP 次级入口归属 Agents Tab
// （Android 溢出菜单）。点击后切换到对应 feature 页面，带返回。

import { AppColors, AppSpacing, AppShape, AppType } from 'common';
import { WorkflowScreen } from '@features/workflow';
import { McpScreen } from '@features/mcp';

// 子页面路由：null = 主列表
type SubPage = 'workflow' | 'mcp' | null;

@Component
export struct AgentsScreen {
  @State subPage: SubPage = null;

  build(): void {
    Column(): void {
      if (this.subPage === 'workflow') {
        this.buildSubPageHeader('Workflow', (): void => {
          this.subPage = null;
        });
        WorkflowScreen()
      } else if (this.subPage === 'mcp') {
        this.buildSubPageHeader('MCP', (): void => {
          this.subPage = null;
        });
        McpScreen()
      } else {
        this.buildMainList()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(AppColors.background)
  }

  @Builder
  buildMainList(): void {
    Column(): void {
      Row(): void {
        Text('Agents')
          .fontSize(AppType.headlineMedium)
          .fontWeight(AppType.semibold)
          .fontColor(AppColors.onSurface)
      }
      .width('100%')
      .height(56)
      .padding({ left: AppSpacing.l, right: AppSpacing.l })
      .alignItems(VerticalAlign.Center)

      Column(): void {
        Text($r('app.string.agents_coming_soon'))
          .fontSize(AppType.bodyLarge)
          .fontColor(AppColors.outline)
          .margin({ top: AppSpacing.xl })
      }
      .width('100%')
      .layoutWeight(1)
      .justifyContent(FlexAlign.Start)
      .alignItems(HorizontalAlign.Center)

      // v5.1 IA：次级入口（对标 Android Agents Tab 溢出菜单）
      Column(): void {
        Text($r('app.string.agents_more'))
          .fontSize(AppType.labelLarge)
          .fontWeight(AppType.medium)
          .fontColor(AppColors.onSurface)
          .margin({ bottom: AppSpacing.s })

        Row(): void {
          this.buildEntryPoint('Workflow', $r('app.string.agents_workflow_sub'), (): void => {
            this.subPage = 'workflow';
          })
          this.buildEntryPoint('MCP', $r('app.string.agents_mcp_sub'), (): void => {
            this.subPage = 'mcp';
          })
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)
      }
      .width('100%')
      .padding({ left: AppSpacing.l, right: AppSpacing.l, bottom: AppSpacing.l })
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildEntryPoint(title: string, subtitle: Resource, onTap: () => void): void {
    Column(): void {
      Text(title)
        .fontSize(AppType.bodyLarge)
        .fontWeight(AppType.medium)
        .fontColor(AppColors.onSurface)
      Text(subtitle)
        .fontSize(AppType.labelSmall)
        .fontColor(AppColors.outline)
        .margin({ top: AppSpacing.xs })
    }
    .alignItems(HorizontalAlign.Start)
    .padding(AppSpacing.m)
    .borderRadius(AppShape.m)
    .backgroundColor(AppColors.surfaceVariant)
    .layoutWeight(1)
    .margin({ left: AppSpacing.xs, right: AppSpacing.xs })
    .onClick((): void => {
      onTap();
    })
  }

  @Builder
  buildSubPageHeader(title: string, onBack: () => void): void {
    Row(): void {
      Button('<')
        .fontSize(AppType.labelLarge)
        .borderRadius(AppShape.full)
        .backgroundColor(AppColors.surfaceVariant)
        .fontColor(AppColors.onSurface)
        .width(36)
        .height(36)
        .padding(0)
        .margin({ left: AppSpacing.s })
        .onClick((): void => {
          onBack();
        })

      Text(title)
        .fontSize(AppType.headlineMedium)
        .fontWeight(AppType.semibold)
        .fontColor(AppColors.onSurface)
        .margin({ left: AppSpacing.m })
    }
    .width('100%')
    .height(56)
    .alignItems(VerticalAlign.Center)
    .backgroundColor(AppColors.surface)
  }
}

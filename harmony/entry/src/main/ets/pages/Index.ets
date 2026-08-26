// 主壳页面（对标 Android MainActivity setContent { AppNavigation() } + iOS ContentView）
// v5.1 IA 重组阶段 2：底部 Tab = Chat / Agents / Activity / Marketplace / Settings
// Sessions/Tasks 入口融合在 Chat 顶部。
import { ChatScreen } from '@features/chat';
import { AgentsScreen } from '@features/agents';
import { ActivityScreen } from '@features/activity';
import { MarketplaceScreen } from '@features/marketplace';
import { SettingsScreen } from '@features/settings';
import { AppColors } from 'common';

@Entry
@Component
struct Index {
  @State currentTab: number = 0;

  private tabs: Array<TabInfo> = [
    { title: $r('app.string.nav_chat'), icon: $r('app.media.ic_chat') },
    { title: $r('app.string.nav_agents'), icon: $r('app.media.ic_agents') },
    { title: $r('app.string.nav_activity'), icon: $r('app.media.ic_activity') },
    { title: $r('app.string.nav_marketplace'), icon: $r('app.media.ic_marketplace') },
    { title: $r('app.string.nav_settings'), icon: $r('app.media.ic_settings') }
  ];

  build() {
    Column() {
      // 内容区：按当前 Tab 渲染对应 feature
      // 占位阶段：未实现的 feature 显示 Coming Soon
      Tabs({ index: this.currentTab }) {
        ForEach(this.tabs, (tab: TabInfo, idx: number): void => {
          TabContent() {
            this.buildTabContent(idx)
          }
          .tabBar(this.buildTabBar(tab, idx))
        })
      }
      .barMode(BarMode.Fixed)
      .barPosition(BarPosition.End)
      .onChange((idx: number): void => {
        this.currentTab = idx;
      })
      .width('100%')
      .height('100%')
      .backgroundColor($r('app.color.window_background'))
    }
  }

  @Builder
  buildTabContent(idx: number) {
    if (idx === 0) {
      ChatScreen()
    } else if (idx === 1) {
      AgentsScreen()
    } else if (idx === 2) {
      ActivityScreen()
    } else if (idx === 3) {
      MarketplaceScreen()
    } else {
      SettingsScreen()
    }
  }

  @Builder
  buildTabBar(tab: TabInfo, idx: number) {
    Column() {
      Image(tab.icon)
        .width(24)
        .height(24)
        .fillColor(this.currentTab === idx ? AppColors.primary : AppColors.outline)
      Text(tab.title)
        .fontSize(10)
        .fontColor(this.currentTab === idx ? AppColors.primary : AppColors.outline)
        .margin({ top: 4 })
    }
    .width('100%')
    .height(56)
    .justifyContent(FlexAlign.Center)
  }
}

interface TabInfo {
  title: Resource;
  icon: Resource;
}

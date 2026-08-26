// UIAbility 入口（对标 Android MainActivity + iOS AgentControlCenterApp）
// HarmonyOS Stage 模型入口，负责窗口创建与主页面加载。
//
// H5 后台保活：切后台且 Agent 连接存在时启动长时任务（DATA_TRANSFER），
// 回到前台释放，对齐 Android AgentConnectionService 前台服务语义。
import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { AppDatabase, ConnectionRepository, KeepAliveManager, CardDataProvider } from 'common';

const DOMAIN = 0xA001;
const TAG = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  async onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): Promise<void> {
    // 应用启动入口：初始化 AppDatabase（relationalStore）
    hilog.info(DOMAIN, TAG, 'onCreate: Agent Control Center 启动');
    try {
      await AppDatabase.init(this.context);
      hilog.info(DOMAIN, TAG, 'AppDatabase 初始化成功');
    } catch (e) {
      hilog.error(DOMAIN, TAG, `AppDatabase 初始化失败: ${e}`);
    }
    // H5 服务卡片数据桥初始化（FormKit 卡片读取连接状态/Agent 名）
    try {
      await CardDataProvider.init(this.context);
      hilog.info(DOMAIN, TAG, 'CardDataProvider 初始化成功');
    } catch (e) {
      hilog.error(DOMAIN, TAG, `CardDataProvider 初始化失败: ${e}`);
    }
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 主窗口创建：加载 Index 主壳（Navigation 容器 + 5 Tab）
    hilog.info(DOMAIN, TAG, 'onWindowStageCreate');
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, `loadContent 失败: ${JSON.stringify(err)}`);
        return;
      }
      hilog.info(DOMAIN, TAG, 'loadContent 成功');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, TAG, 'onWindowStageDestroy');
  }

  onForeground(): void {
    // 回到前台：长时任务使命结束（UI 接管展示），释放后台保活窗口。
    // 连接本身由 ConnectionRepository 单例维持，不受影响。
    hilog.info(DOMAIN, TAG, 'onForeground');
    KeepAliveManager.getInstance().stop(this.context);
  }

  onBackground(): void {
    // 切到后台：若 Agent 连接存在，申请长时任务保持连接存活
    // （系统显示常驻通知，对齐 Android 前台服务）。
    hilog.info(DOMAIN, TAG, 'onBackground');
    if (ConnectionRepository.getInstance().isConnected) {
      KeepAliveManager.getInstance().start(this.context);
    }
  }
}

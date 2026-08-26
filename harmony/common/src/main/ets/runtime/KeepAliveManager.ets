// KeepAliveManager（对标 android AgentConnectionService.kt）
// 后台保活 — 基于 backgroundTaskManager 长时任务（DATA_TRANSFER 模式）。
//
// 语义对齐 Android 前台服务：App 切后台且 Agent 连接存在时启动连续任务，
// 系统自动显示常驻通知（鸿蒙规范：长时任务通知由系统生成，应用不可自绘），
// 保证 WebSocket/SSE 连接在后台不被系统冻结。
//
// module.json5 配套声明（entry）：
//  - abilities[].backgroundModes: ["dataTransfer"]
//  - requestPermissions: ohos.permission.KEEP_BACKGROUND_RUNNING
//
// 连接维持（重连退避）由 ConnectionRepository 的网络回调负责，
// 本类只负责向系统申请后台存活窗口。

import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import common from '@ohos.app.ability.common';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0xA001;
const TAG: string = 'KeepAliveManager';

export class KeepAliveManager {
  private static instance: KeepAliveManager | null = null;

  // 长时任务是否运行中（本地标记，避免重复申请）
  private running: boolean = false;

  private constructor() { }

  static getInstance(): KeepAliveManager {
    if (KeepAliveManager.instance === null) {
      KeepAliveManager.instance = new KeepAliveManager();
    }
    return KeepAliveManager.instance;
  }

  /** 当前保活是否已启动。 */
  isRunning(): boolean {
    return this.running;
  }

  /**
   * 启动后台保活长时任务。
   *
   * 幂等：已在运行时直接返回。
   * DATA_TRANSFER 模式对应 Agent 连接的数据传输场景，
   * 系统将显示常驻通知并在后台保持进程存活。
   */
  async start(context: common.Context): Promise<void> {
    if (this.running) {
      return;
    }
    try {
      await backgroundTaskManager.startBackgroundRunning(
        context,
        backgroundTaskManager.BackgroundMode.DATA_TRANSFER
      );
      this.running = true;
      hilog.info(DOMAIN, TAG, 'startBackgroundRunning: DATA_TRANSFER 已启动');
    } catch (e) {
      // 申请失败（权限缺失/模式不支持）时降级：不做后台保活，前台功能不受影响
      hilog.warn(DOMAIN, TAG, `startBackgroundRunning 失败: ${e}`);
    }
  }

  /**
   * 停止后台保活长时任务。
   *
   * 幂等：未运行时直接返回。
   */
  async stop(context: common.Context): Promise<void> {
    if (!this.running) {
      return;
    }
    try {
      await backgroundTaskManager.stopBackgroundRunning(context);
      this.running = false;
      hilog.info(DOMAIN, TAG, 'stopBackgroundRunning: 长时任务已停止');
    } catch (e) {
      hilog.warn(DOMAIN, TAG, `stopBackgroundRunning 失败: ${e}`);
    }
  }
}

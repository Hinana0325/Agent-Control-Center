// QuickInputFormAbility（对标 android Widget 快捷输入卡片）
// FormKit 服务卡片生命周期管理：
//  - onAddForm：注册活跃卡片 ID（供应用侧数据变化时主动推送），
//    同步返回默认数据（同步契约），异步加载真实数据后主动刷新
//  - onUpdateForm：定时/主动刷新回调，重新读取数据推送
//  - onRemoveForm：注销卡片 ID，停止推送
//
// 卡片为静态 UI + router 事件：点击卡片拉起主应用（对标 Android
// WidgetInputActivity 快捷输入入口；鸿蒙卡片不支持 TextInput，
// 与 Android RemoteViews 降级语义一致——点击跳转主界面输入）。

import { FormExtensionAbility, formBindingData, formProvider } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import { CardDataProvider } from 'common';

export default class QuickInputFormAbility extends FormExtensionAbility {
  /**
   * 卡片添加到桌面时回调：读取数据并填充卡片。
   */
  onAddForm(want: Want): formBindingData.FormBindingData {
    const formId: string =
      want.parameters?.[formBindingData.FormParam.IDENTITY_KEY] as string;
    // 注册活跃卡片：应用侧（CardDataProvider）数据变化时主动推送更新
    CardDataProvider.addFormId(this.context, formId);
    // 首次添加先以默认数据返回（同步契约），异步加载真实数据后主动刷新
    this.loadAndPushData(formId);
    return formBindingData.createFormBindingData({
      'agentName': 'Agent Control Center',
      'isConnected': false,
      'latencyLabel': ''
    });
  }

  /**
   * 卡片定期/主动更新时回调。
   */
  onUpdateForm(formId: string): void {
    this.loadAndPushData(formId);
  }

  /**
   * 读取连接状态与 Agent 名，推送到指定卡片（复用 CardDataProvider 组装逻辑）。
   */
  private async loadAndPushData(formId: string): Promise<void> {
    try {
      const data: formBindingData.FormBindingData =
        await CardDataProvider.buildCardData(this.context);
      await formProvider.updateForm(formId, data);
    } catch (e) {
      // 数据读取失败时保持卡片现状（下次更新重试）
    }
  }

  /**
   * 卡片销毁时回调：注销卡片，停止应用侧推送。
   */
  onRemoveForm(formId: string): void {
    CardDataProvider.removeFormId(this.context, formId);
  }

  /**
   * 卡片点击 router 事件将触发主应用拉起（由卡片 UI 的
   * postCardAction 处理），此处无需转发。
   */
  onCastToNormalForm(formId: string): void { }
}

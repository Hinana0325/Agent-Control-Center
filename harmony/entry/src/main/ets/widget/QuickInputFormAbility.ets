// QuickInputFormAbility（对标 android Widget 快捷输入卡片）
// FormKit 服务卡片生命周期管理：卡片添加/更新时从 CardDataProvider
// 读取连接状态与 Agent 名，填充 FormBindingData 推送到卡片 UI。
//
// 卡片为静态 UI + router 事件：点击卡片拉起主应用（对标 Android
// WidgetInputActivity 快捷输入入口；鸿蒙卡片不支持 TextInput，
// 与 Android RemoteViews 降级语义一致——点击跳转主界面输入）。

import { FormExtensionAbility, formBindingData } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import formProvider from '@ohos.app.form.formProvider';
import { CardDataProvider } from 'common';

export default class QuickInputFormAbility extends FormExtensionAbility {
  /**
   * 卡片添加到桌面时回调：读取数据并填充卡片。
   */
  onAddForm(want: Want): formBindingData.FormBindingData {
    const formId: string =
      want.parameters?.[formBindingData.FormParam.IDENTITY_KEY] as string;
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
   * 读取连接状态与 Agent 名，推送到指定卡片。
   */
  private async loadAndPushData(formId: string): Promise<void> {
    try {
      const agentName: string = await CardDataProvider.getAgentName(this.context);
      const isConnected: boolean = await CardDataProvider.isConnected(this.context);
      const latency: number = await CardDataProvider.getAvgLatency(this.context);

      const data: formBindingData.FormBindingData = formBindingData.createFormBindingData({
        'agentName': agentName.length > 0 ? agentName : 'Agent Control Center',
        'isConnected': isConnected,
        'latencyLabel': isConnected && latency > 0 ? `${latency}ms` : ''
      });
      await formProviderUpdate(formId, data);
    } catch (e) {
      // 数据读取失败时保持卡片现状（下次更新重试）
    }
  }

  /**
   * 卡片销毁时回调（预留清理）。
   */
  onRemoveForm(formId: string): void { }

  /**
   * 卡片点击 router 事件将触发主应用拉起（由卡片 UI 的
   * postCardAction 处理），此处无需转发。
   */
  onCastToNormalForm(formId: string): void { }
}

/** formProvider 更新封装：卡片可能已被删除，更新失败静默忽略。 */
async function formProviderUpdate(formId: string,
  data: formBindingData.FormBindingData): Promise<void> {
  try {
    await formProvider.updateForm(formId, data);
  } catch (e) {
    // 卡片可能已被删除，忽略更新失败
  }
}

// CardDataProvider（对标 android widget/WidgetDataProvider.kt）
// 应用与 FormKit 服务卡片之间的轻量数据桥。
//
// 对齐 Android SharedPreferences 桥接模式：
//  - 应用侧（ChatScreen 连接状态回调）写入连接状态 / Agent 名 / 延迟，
//    并通过 formProvider.updateForm 主动推送所有已添加的卡片（应用侧推送更新）
//  - 卡片侧（QuickInputFormAbility）读取并填充 FormBindingData
//  - 活跃卡片 ID 注册表由 FormAbility onAddForm/onRemoveForm 维护
//
// 安全说明：Agent 名称等字符串字段通过 KeystoreManager 加密存储（AKS: 前缀），
// 连接状态（boolean）与延迟（number）为非敏感数据，明文存储。

import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import { formBindingData, formProvider } from '@kit.FormKit';
import { KeystoreManager } from '../security/KeystoreManager';

const PREFS_NAME: string = 'widget_card_prefs';

const KEY_CONNECTED: string = 'card_connected';
const KEY_AGENT_NAME: string = 'card_agent_name';
const KEY_AVG_LATENCY: string = 'card_avg_latency_ms';
const KEY_FORM_IDS: string = 'card_form_ids';

const DEFAULT_AGENT_NAME: string = 'Agent Control Center';

export class CardDataProvider {
  private static prefs: preferences.Preferences | null = null;

  /** 初始化（EntryAbility onCreate 时调用一次）。 */
  static async init(context: common.Context): Promise<void> {
    if (CardDataProvider.prefs !== null) {
      return;
    }
    CardDataProvider.prefs = await preferences.getPreferences(context, PREFS_NAME);
  }

  private static async getPrefs(context: common.Context): Promise<preferences.Preferences> {
    if (CardDataProvider.prefs === null) {
      await CardDataProvider.init(context);
    }
    return CardDataProvider.prefs as preferences.Preferences;
  }

  /**
   * 更新连接状态与 Agent 名称（应用侧调用），并主动推送所有活跃卡片。
   * Agent 名加密存储（AKS: 前缀），与 Android WidgetDataProvider 对齐；
   * agentName 为 null（断开）时保留最近 Agent 名，卡片仅切换状态点。
   */
  static async updateConnectionState(context: common.Context, isConnected: boolean,
    agentName: string | null): Promise<void> {
    const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
    await prefs.put(KEY_CONNECTED, isConnected);
    if (agentName !== null && agentName.length > 0) {
      const encrypted: string = await KeystoreManager.getInstance().encrypt(agentName);
      await prefs.put(KEY_AGENT_NAME, encrypted);
    }
    await prefs.flush();
    // 应用侧推送更新：连接状态变化立即刷新所有已添加卡片
    await CardDataProvider.pushToAllCards(context);
  }

  /** 更新平均延迟（应用侧调用），并主动推送所有活跃卡片。 */
  static async updateLatency(context: common.Context, latencyMs: number): Promise<void> {
    const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
    await prefs.put(KEY_AVG_LATENCY, latencyMs);
    await prefs.flush();
    await CardDataProvider.pushToAllCards(context);
  }

  /** 注册活跃卡片 ID（FormAbility onAddForm 调用）。 */
  static async addFormId(context: common.Context, formId: string): Promise<void> {
    const ids: string[] = await CardDataProvider.getFormIds(context);
    if (!ids.includes(formId)) {
      ids.push(formId);
      const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
      await prefs.put(KEY_FORM_IDS, JSON.stringify(ids));
      await prefs.flush();
    }
  }

  /** 注销卡片 ID（FormAbility onRemoveForm 调用）。 */
  static async removeFormId(context: common.Context, formId: string): Promise<void> {
    const ids: string[] = await CardDataProvider.getFormIds(context);
    const idx: number = ids.indexOf(formId);
    if (idx >= 0) {
      ids.splice(idx, 1);
      const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
      await prefs.put(KEY_FORM_IDS, JSON.stringify(ids));
      await prefs.flush();
    }
  }

  /** 读取活跃卡片 ID 列表。 */
  static async getFormIds(context: common.Context): Promise<string[]> {
    const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
    const raw: string = prefs.getSync(KEY_FORM_IDS, '[]') as string;
    try {
      return JSON.parse(raw) as string[];
    } catch (e) {
      return [];
    }
  }

  /** 读取连接状态（卡片侧调用）。 */
  static async isConnected(context: common.Context): Promise<boolean> {
    const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
    return prefs.getSync(KEY_CONNECTED, false) as boolean;
  }

  /** 读取 Agent 名称（解密，兼容旧明文）。 */
  static async getAgentName(context: common.Context): Promise<string> {
    const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
    const raw: string = prefs.getSync(KEY_AGENT_NAME, '') as string;
    if (raw.length === 0) {
      return '';
    }
    return KeystoreManager.getInstance().decryptOrRaw(raw);
  }

  /** 读取平均延迟（ms）。 */
  static async getAvgLatency(context: common.Context): Promise<number> {
    const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
    return prefs.getSync(KEY_AVG_LATENCY, 0) as number;
  }

  /** 组装卡片展示数据（FormAbility 与应用侧推送共用）。 */
  static async buildCardData(context: common.Context): Promise<formBindingData.FormBindingData> {
    const agentName: string = await CardDataProvider.getAgentName(context);
    const isConnected: boolean = await CardDataProvider.isConnected(context);
    const latency: number = await CardDataProvider.getAvgLatency(context);
    return formBindingData.createFormBindingData({
      'agentName': agentName.length > 0 ? agentName : DEFAULT_AGENT_NAME,
      'isConnected': isConnected,
      'latencyLabel': isConnected && latency > 0 ? `${latency}ms` : ''
    });
  }

  /** 数据变化后主动推送所有活跃卡片（formProvider.updateForm）。 */
  private static async pushToAllCards(context: common.Context): Promise<void> {
    const formIds: string[] = await CardDataProvider.getFormIds(context);
    if (formIds.length === 0) {
      return;
    }
    const data: formBindingData.FormBindingData = await CardDataProvider.buildCardData(context);
    for (const formId of formIds) {
      try {
        await formProvider.updateForm(formId, data);
      } catch (e) {
        // 卡片可能已被删除，静默忽略（onRemoveForm 负责清理注册表）
      }
    }
  }
}

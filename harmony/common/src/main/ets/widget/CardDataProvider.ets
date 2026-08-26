// CardDataProvider（对标 android widget/WidgetDataProvider.kt）
// 应用与 FormKit 服务卡片之间的轻量数据桥。
//
// 对齐 Android SharedPreferences 桥接模式：
//  - 应用侧（ConnectionRepository 状态回调）写入连接状态 / Agent 名 / 延迟
//  - 卡片侧（QuickInputFormAbility）读取并填充 FormBindingData
//
// 安全说明：Agent 名称等字符串字段通过 KeystoreManager 加密存储（AKS: 前缀），
// 连接状态（boolean）与延迟（number）为非敏感数据，明文存储。

import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import { KeystoreManager } from '../security/KeystoreManager';

const PREFS_NAME: string = 'widget_card_prefs';

const KEY_CONNECTED: string = 'card_connected';
const KEY_AGENT_NAME: string = 'card_agent_name';
const KEY_AVG_LATENCY: string = 'card_avg_latency_ms';

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
   * 更新连接状态与 Agent 名称（应用侧调用）。
   * Agent 名加密存储（AKS: 前缀），与 Android WidgetDataProvider 对齐。
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
  }

  /** 更新平均延迟（应用侧调用）。 */
  static async updateLatency(context: common.Context, latencyMs: number): Promise<void> {
    const prefs: preferences.Preferences = await CardDataProvider.getPrefs(context);
    await prefs.put(KEY_AVG_LATENCY, latencyMs);
    await prefs.flush();
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
}

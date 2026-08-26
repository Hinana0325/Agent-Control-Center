// Plugin 协议契约（对标 protocol/schemas/plugin-schema.json）
// 对应 Android Plugin.kt + iOS Models/Plugin.swift。
// Plugin 是用户可触发的快捷动作，action 决定执行方式。
// 模板字段支持 {query} 占位符，运行时被用户输入替换。

export enum PluginActionType {
  http = 'http',
  broadcast = 'broadcast',
  workflow = 'workflow',
  none = 'none'
}

export type StringMap = Record<string, string>;

export interface HttpPluginAction {
  type: typeof PluginActionType.http;
  url: string;                    // required，支持 {query}（URLEncoder.encode 后替换）
  method: string;                 // 默认 'GET'
  headers: StringMap;             // 默认 {}
  bodyTemplate: string | null;    // 默认 null，{query} 用原始值替换
}

export interface BroadcastPluginAction {
  type: typeof PluginActionType.broadcast;
  action: string;                 // required，Intent action 字符串
  extras: StringMap;              // 默认 {}，value 支持 {query}
}

export interface WorkflowPluginAction {
  type: typeof PluginActionType.workflow;
  promptTemplate: string;         // required，支持 {query}
}

export interface NonePluginAction {
  type: typeof PluginActionType.none;
}

export type PluginAction =
  | HttpPluginAction
  | BroadcastPluginAction
  | WorkflowPluginAction
  | NonePluginAction;

export interface Plugin {
  id: string;                      // required
  name: string;                    // required
  description: string;             // 默认 ''
  icon: string;                    // 默认 ''，emoji / 资源名 / URL
  isEnabled: boolean;              // 默认 true，false 触发 PLUGIN_DISABLED
  permissions: string[];           // 默认 []
  version: string;                 // 默认 '1.0.0'
  action: PluginAction | null;     // 默认 null，等价 type=none
}

export interface PluginResult {
  content: string;                 // required，HTTP 响应（截断 3000）/ 提示词
  sendToAgent: boolean;            // 默认 false，workflow 默认 true
}

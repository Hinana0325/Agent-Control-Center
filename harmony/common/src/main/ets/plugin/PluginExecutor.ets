// PluginExecutor（对标 android plugin/runtime/PluginExecutor.kt）
// 插件执行引擎：根据 plugin.action 真实执行动作。
//  - http      → @ohos.net.http 发起 HTTP 请求并返回响应文本（截断 3000）
//  - broadcast → @ohos.commonEvent 发布公共事件（对应 Android sendBroadcast）
//  - workflow  → 产出提示词，交由已连接 Agent 执行（sendToAgent=true）
//
// 安全对齐（F19/F24）：
//  - 插件 url 来自用户/marketplace 配置，经 UrlValidator.validate 校验（allowLocalhost=false）
//  - 敏感 header（Authorization/Cookie/Host 等）过滤，禁止插件设置

import http from '@ohos.net.http';
import commonEvent from '@ohos.commonEvent';
import { Plugin, PluginAction, PluginResult,
  HttpPluginAction, BroadcastPluginAction, WorkflowPluginAction } from '../protocol/Plugin';
import { UrlValidator } from '../security/UrlValidator';

// HTTP 超时（与 Android PluginExecutor 一致）
const CONNECT_TIMEOUT_MS: number = 10000;
const REQUEST_TIMEOUT_MS: number = 30000;

// 响应文本截断长度
const MAX_RESPONSE_CHARS: number = 3000;

/** 插件 HTTP 请求禁止设置的敏感 header（小写匹配，F19/F24 对齐）。 */
const BLOCKED_HEADERS: Set<string> = new Set<string>([
  'authorization', 'proxy-authorization',
  'cookie', 'set-cookie', 'cookie2', 'set-cookie2',
  'host', 'content-length', 'connection',
  'upgrade', 'transfer-encoding', 'te',
  'trailer', 'expect',
  'www-authenticate', 'proxy-authenticate',
  'x-api-key', 'x-auth-token', 'x-session-token'
]);

export class PluginExecutor {
  /**
   * 执行插件动作。
   * sendToAgent=true 时 content 为应发送给 Agent 的提示词。
   */
  async execute(plugin: Plugin, input: string = ''): Promise<PluginResult> {
    const action: PluginAction | null = plugin.action;
    if (action === null) {
      return { content: `Plugin "${plugin.name}" has no executable action.`, sendToAgent: false };
    }
    switch (action.type) {
      case 'http':
        return this.httpCall(action as HttpPluginAction, input);
      case 'broadcast':
        return this.broadcast(action as BroadcastPluginAction, input);
      case 'workflow': {
        const typed = action as WorkflowPluginAction;
        return {
          content: typed.promptTemplate.replace('{query}', input),
          sendToAgent: true
        };
      }
      default:
        return { content: `Plugin "${plugin.name}" has no executable action.`, sendToAgent: false };
    }
  }

  /**
   * HTTP 请求动作：{query} 用 encodeURIComponent 编码后替换（URL 中），
   * bodyTemplate 的 {query} 用原始值替换。
   */
  private async httpCall(action: HttpPluginAction, input: string): Promise<PluginResult> {
    const query: string = encodeURIComponent(input);
    const url: string = action.url.replace('{query}', query);
    const body: string | null = action.bodyTemplate !== null
      ? action.bodyTemplate.replace('{query}', input)
      : null;

    // SSRF 防护：插件 url 来自用户/marketplace 配置，
    // allowLocalhost=false：插件不应访问本地服务
    if (!UrlValidator.validate(url, false)) {
      return { content: 'Error: URL not allowed (blocked by UrlValidator)', sendToAgent: false };
    }

    const req: http.HttpRequest = http.createHttp();
    try {
      // 过滤敏感 header
      const headers: Record<string, string> = {};
      const keys: string[] = Object.keys(action.headers);
      for (const key of keys) {
        if (!BLOCKED_HEADERS.has(key.toLowerCase())) {
          headers[key] = action.headers[key].replace('{query}', input);
        }
      }

      const opts: http.HttpRequestOptions = {
        method: this.parseMethod(action.method),
        connectTimeout: CONNECT_TIMEOUT_MS,
        readTimeout: REQUEST_TIMEOUT_MS,
        header: headers
      };
      if (body !== null) {
        opts.extraData = body;
      }

      const response: http.HttpResponse = await req.request(url, opts);
      const text: string = typeof response.result === 'string'
        ? (response.result as string).substring(0, MAX_RESPONSE_CHARS)
        : '';
      return {
        content: `HTTP ${response.responseCode}\n${text}`,
        sendToAgent: false
      };
    } catch (e) {
      return { content: `Request failed: ${e}`, sendToAgent: false };
    } finally {
      req.destroy();
    }
  }

  /**
   * 广播动作：通过 commonEvent 发布公共事件（对应 Android sendBroadcast）。
   * extras 的 value 支持 {query} 占位符。
   */
  private async broadcast(action: BroadcastPluginAction, input: string): Promise<PluginResult> {
    try {
      const parameters: Record<string, Object> = {};
      const keys: string[] = Object.keys(action.extras);
      for (const key of keys) {
        parameters[key] = action.extras[key].replace('{query}', input);
      }
      const options: commonEvent.CommonEventPublishData = {
        parameters: parameters
      };
      await new Promise<void>((resolve: () => void, reject: (e: Object) => void): void => {
        commonEvent.publish(action.action, options, (err: Error): void => {
          if (err) {
            reject(err);
          } else {
            resolve();
          }
        });
      });
      return { content: `Broadcast sent: ${action.action}`, sendToAgent: false };
    } catch (e) {
      return { content: `Broadcast failed: ${e}`, sendToAgent: false };
    }
  }

  /** 解析 HTTP method 字符串到 @ohos.net.http 枚举。 */
  private parseMethod(method: string): http.RequestMethod {
    switch (method.toUpperCase()) {
      case 'GET': return http.RequestMethod.GET;
      case 'POST': return http.RequestMethod.POST;
      case 'PUT': return http.RequestMethod.PUT;
      case 'DELETE': return http.RequestMethod.DELETE;
      case 'HEAD': return http.RequestMethod.HEAD;
      case 'OPTIONS': return http.RequestMethod.OPTIONS;
      default: return http.RequestMethod.GET;
    }
  }
}

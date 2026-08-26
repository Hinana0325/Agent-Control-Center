// ComfyApiClient（对标 android transport/comfyui/ComfyApiClient.kt）
// ComfyUI HTTP API 客户端 — 封装提交工作流、轮询结果、健康检查、图片下载。
//
// 错误以 kind 判别的联合类型返回（对标 Kotlin sealed result），
// 由调用方（ComfyUITransport）决定如何转成 AgentEvent，
// 避免传输层与 HTTP 细节耦合。

import http from '@ohos.net.http';
import util from '@ohos.util';
import { AgentConfig } from '../../protocol/Agent';
import { UrlValidator } from '../../security/UrlValidator';
import { ComfyImageOutput, ComfyImageOutputExtractor } from './ComfyImageOutput';

// ── 提交结果联合类型 ──
export interface SubmitSuccess { kind: 'success'; promptId: string; }
export interface SubmitHttpError { kind: 'httpError'; code: number; body: string; }
export interface SubmitMissingPromptId { kind: 'missingPromptId'; }
export interface SubmitInvalidUrl { kind: 'invalidUrl'; }
export interface SubmitNetworkError { kind: 'networkError'; message: string; }
export type SubmitResult =
  SubmitSuccess | SubmitHttpError | SubmitMissingPromptId | SubmitInvalidUrl | SubmitNetworkError;

// ── 轮询结果联合类型 ──
export interface PollSuccess { kind: 'success'; images: ComfyImageOutput[]; }
export interface PollWorkflowError { kind: 'workflowError'; }
export interface PollTimeout { kind: 'timeout'; }
export interface PollNetworkError { kind: 'networkError'; message: string; }
export type PollResult = PollSuccess | PollWorkflowError | PollTimeout | PollNetworkError;

// ── 单次轮询状态 ──
type PollStateKind = 'notReady' | 'done' | 'error';
interface PollState {
  kind: PollStateKind;
  images: ComfyImageOutput[];
}

// 轮询参数：每 3s 一次，最多 120 次（约 6 分钟），与双端一致
const POLL_MAX_ATTEMPTS: number = 120;
const POLL_INTERVAL_MS: number = 3000;

// HTTP 超时
const SUBMIT_TIMEOUT_MS: number = 30000;
const HEALTH_TIMEOUT_MS: number = 5000;
const DOWNLOAD_TIMEOUT_MS: number = 30000;

const sleep = (ms: number): Promise<void> => {
  return new Promise<void>((resolve: () => void) => {
    setTimeout(resolve, ms);
  });
};

export class ComfyApiClient {
  /**
   * 提交工作流到 /prompt。
   *
   * 成功返回 prompt_id；HTTP 非 2xx 返回 httpError；
   * 响应缺 prompt_id 返回 missingPromptId；URL 校验失败返回 invalidUrl；
   * 网络/解析异常返回 networkError。
   */
  async submitWorkflow(base: string, workflowJson: Record<string, Object>,
    config: AgentConfig): Promise<SubmitResult> {
    const promptUrl: string = `${base}/prompt`;
    if (!UrlValidator.validate(promptUrl, true)) {
      return { kind: 'invalidUrl' } as SubmitInvalidUrl;
    }

    const clientId: string = this.uuid();
    const requestBody: Record<string, Object> = {};
    requestBody['prompt'] = workflowJson;
    requestBody['client_id'] = clientId;

    const req: http.HttpRequest = http.createHttp();
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (config.apiKey.length > 0) {
        headers['Authorization'] = `Bearer ${config.apiKey}`;
      }
      const resp: http.HttpResponse = await req.request(promptUrl, {
        method: http.RequestMethod.POST,
        connectTimeout: SUBMIT_TIMEOUT_MS,
        readTimeout: SUBMIT_TIMEOUT_MS,
        header: headers,
        extraData: JSON.stringify(requestBody)
      });
      const status: number = resp.responseCode;
      if (status < 200 || status >= 300) {
        const bodyText: string = typeof resp.result === 'string'
          ? (resp.result as string).substring(0, 200) : 'Unknown error body';
        return { kind: 'httpError', code: status, body: bodyText } as SubmitHttpError;
      }
      const bodyStr: string = typeof resp.result === 'string'
        ? resp.result as string : '';
      const parsed: Record<string, Object> = JSON.parse(bodyStr) as Record<string, Object>;
      const promptIdValue: Object | undefined = parsed['prompt_id'];
      if (promptIdValue !== undefined && promptIdValue !== null) {
        return { kind: 'success', promptId: promptIdValue as string } as SubmitSuccess;
      }
      return { kind: 'missingPromptId' } as SubmitMissingPromptId;
    } catch (e) {
      return { kind: 'networkError', message: `${e}` } as SubmitNetworkError;
    } finally {
      req.destroy();
    }
  }

  /**
   * 轮询 /history/{promptId} 直到工作流执行完成或超时。
   *
   * - success：执行完成并提取到图片输出
   * - workflowError：服务端返回 status_str == "error"
   * - timeout：达到最大轮询次数仍未生成图片
   * - networkError：网络异常
   */
  async pollHistory(base: string, promptId: string, config: AgentConfig): Promise<PollResult> {
    const historyUrl: string = `${base}/history/${promptId}`;
    if (!UrlValidator.validate(historyUrl, true)) {
      return { kind: 'networkError', message: 'Invalid history URL' } as PollNetworkError;
    }
    try {
      for (let attempt: number = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
        await sleep(POLL_INTERVAL_MS);
        const state: PollState = await this.checkHistoryOnce(historyUrl, promptId, config);
        if (state.kind === 'done') {
          return { kind: 'success', images: state.images } as PollSuccess;
        }
        if (state.kind === 'error') {
          return { kind: 'workflowError' } as PollWorkflowError;
        }
        // notReady：尚未完成，继续轮询
      }
      return { kind: 'timeout' } as PollTimeout;
    } catch (e) {
      return { kind: 'networkError', message: `${e}` } as PollNetworkError;
    }
  }

  /**
   * 查询一次 history，返回当前状态。
   */
  private async checkHistoryOnce(historyUrl: string, promptId: string,
    config: AgentConfig): Promise<PollState> {
    const notReady: PollState = { kind: 'notReady', images: [] };
    const req: http.HttpRequest = http.createHttp();
    try {
      const headers: Record<string, string> = {};
      if (config.apiKey.length > 0) {
        headers['Authorization'] = `Bearer ${config.apiKey}`;
      }
      const resp: http.HttpResponse = await req.request(historyUrl, {
        method: http.RequestMethod.GET,
        connectTimeout: HEALTH_TIMEOUT_MS,
        readTimeout: HEALTH_TIMEOUT_MS,
        header: headers
      });
      const status: number = resp.responseCode;
      if (status < 200 || status >= 300) {
        return notReady;
      }

      const bodyStr: string = typeof resp.result === 'string' ? resp.result as string : '';
      if (bodyStr.length === 0) {
        return notReady;
      }
      const historyJson: Record<string, Object> = JSON.parse(bodyStr) as Record<string, Object>;
      const promptEntryValue: Object | undefined = historyJson[promptId];
      if (promptEntryValue === undefined || promptEntryValue === null) {
        return notReady;
      }
      const promptEntry: Record<string, Object> = promptEntryValue as Record<string, Object>;

      // 检查 status.status_str == "error"
      const statusValue: Object | undefined = promptEntry['status'];
      if (statusValue !== undefined && statusValue !== null) {
        const statusObj: Record<string, Object> = statusValue as Record<string, Object>;
        const statusStr: Object | undefined = statusObj['status_str'];
        if (statusStr !== undefined && statusStr === 'error') {
          return { kind: 'error', images: [] };
        }
        // 检查 completed
        const completed: Object | undefined = statusObj['completed'];
        const hasOutputs: boolean = promptEntry['outputs'] !== undefined;
        if (completed === true || hasOutputs) {
          const images: ComfyImageOutput[] =
            this.extractImages(promptEntry);
          if (images.length > 0) {
            return { kind: 'done', images: images };
          }
          return notReady;
        }
      } else if (promptEntry['outputs'] !== undefined) {
        const images: ComfyImageOutput[] = this.extractImages(promptEntry);
        if (images.length > 0) {
          return { kind: 'done', images: images };
        }
      }
      return notReady;
    } finally {
      req.destroy();
    }
  }

  /**
   * 健康检查：GET {base}/system_stats。
   * 返回 2xx 视为可达；任何异常/非 2xx 视为不可达。
   */
  async checkHealth(config: AgentConfig): Promise<boolean> {
    const base: string = config.serverUrl.replace(/\/+$/, '');
    const probeUrl: string = `${base}/system_stats`;
    if (!UrlValidator.validate(probeUrl, true)) {
      return false;
    }
    const req: http.HttpRequest = http.createHttp();
    try {
      const headers: Record<string, string> = {};
      if (config.apiKey.length > 0) {
        headers['Authorization'] = `Bearer ${config.apiKey}`;
      }
      const resp: http.HttpResponse = await req.request(probeUrl, {
        method: http.RequestMethod.GET,
        connectTimeout: HEALTH_TIMEOUT_MS,
        readTimeout: HEALTH_TIMEOUT_MS,
        header: headers
      });
      return resp.responseCode >= 200 && resp.responseCode <= 299;
    } catch (e) {
      return false;
    } finally {
      req.destroy();
    }
  }

  /**
   * 通过 /view 端点下载图片并转为 markdown data URI。
   *
   * 返回 `![ComfyUI](data:image/png;base64,...)` 或 null（下载失败）。
   */
  async downloadImageAsMarkdown(image: ComfyImageOutput, base: string,
    apiKey: string): Promise<string | null> {
    const viewUrl: string = `${base}/view?filename=${encodeURIComponent(image.filename)}` +
      `&subfolder=${encodeURIComponent(image.subfolder)}&type=${image.type}`;
    if (!UrlValidator.validate(viewUrl, true)) {
      return null;
    }
    const req: http.HttpRequest = http.createHttp();
    try {
      const headers: Record<string, string> = {};
      if (apiKey.length > 0) {
        headers['Authorization'] = `Bearer ${apiKey}`;
      }
      const resp: http.HttpResponse = await req.request(viewUrl, {
        method: http.RequestMethod.GET,
        connectTimeout: DOWNLOAD_TIMEOUT_MS,
        readTimeout: DOWNLOAD_TIMEOUT_MS,
        header: headers
      });
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        return null;
      }
      // result 为 ArrayBuffer 时转 Base64 data URI
      const result: Object = resp.result;
      if (result instanceof ArrayBuffer) {
        const base64: string = new util.Base64Helper()
          .encodeToStringSync(new Uint8Array(result as ArrayBuffer));
        return `![ComfyUI](data:image/png;base64,${base64})`;
      }
      return null;
    } catch (e) {
      return null;
    } finally {
      req.destroy();
    }
  }

  // ── 内部工具 ──

  private extractImages(promptEntry: Record<string, Object>): ComfyImageOutput[] {
    return ComfyImageOutputExtractor.extract(promptEntry);
  }

  /** 简易 UUID v4（ArkTS 无 crypto.randomUUID，用时间戳+随机数近似）。 */
  private uuid(): string {
    const hex: string = '0123456789abcdef';
    let s: string = '';
    for (let i: number = 0; i < 36; i++) {
      if (i === 8 || i === 13 || i === 18 || i === 23) {
        s += '-';
      } else {
        s += hex.charAt(Math.floor(Math.random() * 16));
      }
    }
    return s;
  }
}

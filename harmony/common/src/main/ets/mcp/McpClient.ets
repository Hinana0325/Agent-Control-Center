// McpClient（对标 android mcp/client/McpClient.kt）
// MCP 客户端 — 通过 JSON-RPC 2.0 over HTTP 与 MCP Server 通信。
//
// 生命周期：
// 1. connect: 发送 initialize 请求，完成能力协商
// 2. listTools: 发送 tools/list 请求，获取工具列表
// 3. callTool: 发送 tools/call 请求，执行工具
// 4. disconnect: 关闭连接
//
// 传输层使用 HTTP POST（SSE 模式留待后续扩展）。
// 每个 McpServer 对应一套请求配置，由 McpBridge 管理生命周期。
//
// 协议参考：https://modelcontextprotocol.io/specification/2025-03-26

import http from '@ohos.net.http';
import { McpServer, McpServerCapabilities, McpTool, McpToolProperty, McpToolSchema,
  McpToolResult, McpContent, TextMcpContent, ImageMcpContent, AudioMcpContent,
  JsonRpcResponse } from '../protocol/MCPModels';
import { UrlValidator } from '../security/UrlValidator';

const PROTOCOL_VERSION: string = '2025-03-26';
const CLIENT_NAME: string = 'Agent Control Center';
const CLIENT_VERSION: string = '2.1.3';

const CONNECT_TIMEOUT_MS: number = 10000;
const REQUEST_TIMEOUT_MS: number = 30000;

/** 提取工具结果中的所有文本内容拼接为单个字符串。 */
export function mcpResultAsText(result: McpToolResult): string {
  const parts: string[] = [];
  for (const item of result.content) {
    if (item.type === 'text') {
      parts.push((item as TextMcpContent).text);
    }
  }
  return parts.join('\n');
}

export class McpClient {
  private requestCounter: number = 0;

  /**
   * 连接到 MCP Server，完成 initialize 握手。
   * @return 协商后的服务器能力，失败返回 null
   */
  async connect(server: McpServer): Promise<McpServerCapabilities | null> {
    const params: Record<string, Object> = {
      'protocolVersion': PROTOCOL_VERSION,
      'capabilities': {},
      'clientInfo': { 'name': CLIENT_NAME, 'version': CLIENT_VERSION }
    };
    const response: JsonRpcResponse | null =
      await this.rpc(server, 'initialize', params);
    if (response === null || response.error !== null) {
      return null;
    }

    // 解析 capabilities
    const result: Object | null = response.result;
    if (result === null) {
      return null;
    }
    const resultObj: Record<string, Object> = result as Record<string, Object>;
    const capsValue: Object | undefined = resultObj['capabilities'];
    const caps: McpServerCapabilities = {
      tools: false, resources: false, prompts: false, logging: false
    };
    if (capsValue !== undefined && capsValue !== null) {
      const capsObj: Record<string, Object> = capsValue as Record<string, Object>;
      caps.tools = capsObj['tools'] !== undefined;
      caps.resources = capsObj['resources'] !== undefined;
      caps.prompts = capsObj['prompts'] !== undefined;
      caps.logging = capsObj['logging'] !== undefined;
    }
    return caps;
  }

  /**
   * 获取服务器提供的工具列表。
   */
  async listTools(server: McpServer): Promise<McpTool[] | null> {
    const response: JsonRpcResponse | null = await this.rpc(server, 'tools/list', {});
    if (response === null || response.error !== null) {
      return null;
    }
    const result: Object | null = response.result;
    if (result === null) {
      return null;
    }
    const resultObj: Record<string, Object> = result as Record<string, Object>;
    const toolsRaw: Object | undefined = resultObj['tools'];
    if (toolsRaw === undefined || toolsRaw === null) {
      return [];
    }
    const toolsArray: Object[] = toolsRaw as Object[];

    const tools: McpTool[] = [];
    for (const toolRaw of toolsArray) {
      const toolMap: Record<string, Object> = toolRaw as Record<string, Object>;
      const nameValue: Object | undefined = toolMap['name'];
      if (nameValue === undefined || nameValue === null) {
        continue;
      }
      const descriptionValue: Object | undefined = toolMap['description'];
      const schemaValue: Object | undefined = toolMap['inputSchema'];
      tools.push({
        name: nameValue as string,
        description: descriptionValue !== undefined && descriptionValue !== null
          ? descriptionValue as string : '',
        inputSchema: this.parseSchema(schemaValue),
        serverId: server.id
      });
    }
    return tools;
  }

  /**
   * 调用工具。
   * @return 工具执行结果；工具不存在/网络失败返回 null
   */
  async callTool(server: McpServer, toolName: string,
    args: Record<string, Object>): Promise<McpToolResult | null> {
    const params: Record<string, Object> = {
      'name': toolName,
      'arguments': args
    };
    const response: JsonRpcResponse | null =
      await this.rpc(server, 'tools/call', params);

    // 网络/解析失败
    if (response === null) {
      return null;
    }

    // JSON-RPC 协议错误 → isError=true 的结果
    if (response.error !== null) {
      const errorText: TextMcpContent = {
        type: 'text',
        text: `JSON-RPC Error ${response.error.code}: ${response.error.message}`
      };
      return { content: [errorText], isError: true };
    }

    const result: Object | null = response.result;
    if (result === null) {
      return null;
    }
    const resultObj: Record<string, Object> = result as Record<string, Object>;
    const contentRaw: Object | undefined = resultObj['content'];
    if (contentRaw === undefined || contentRaw === null) {
      return { content: [], isError: false };
    }
    const isErrorValue: Object | undefined = resultObj['isError'];

    const contentArray: Object[] = contentRaw as Object[];
    const content: McpContent[] = [];
    for (const item of contentArray) {
      const itemMap: Record<string, Object> = item as Record<string, Object>;
      const typeValue: Object | undefined = itemMap['type'];
      if (typeValue === 'text') {
        const textValue: Object | undefined = itemMap['text'];
        const text: TextMcpContent = {
          type: 'text',
          text: textValue !== undefined && textValue !== null ? textValue as string : ''
        };
        content.push(text);
      } else if (typeValue === 'image') {
        const dataValue: Object | undefined = itemMap['data'];
        const mimeValue: Object | undefined = itemMap['mimeType'];
        const image: ImageMcpContent = {
          type: 'image',
          data: dataValue !== undefined && dataValue !== null ? dataValue as string : '',
          mimeType: mimeValue !== undefined && mimeValue !== null ? mimeValue as string : 'image/png'
        };
        content.push(image);
      } else if (typeValue === 'audio') {
        const dataValue: Object | undefined = itemMap['data'];
        const mimeValue: Object | undefined = itemMap['mimeType'];
        const audio: AudioMcpContent = {
          type: 'audio',
          data: dataValue !== undefined && dataValue !== null ? dataValue as string : '',
          mimeType: mimeValue !== undefined && mimeValue !== null ? mimeValue as string : 'audio/wav'
        };
        content.push(audio);
      }
    }
    return { content: content, isError: isErrorValue === true };
  }

  /**
   * 关闭并释放某个 server 的连接资源。
   * HTTP 模式下无持久连接，仅需重置计数。
   */
  async disconnect(serverId: string): Promise<void> {
    // HTTP 无状态传输，无需释放
  }

  // ── 内部方法 ──

  private nextId(): number {
    this.requestCounter += 1;
    return this.requestCounter;
  }

  /**
   * 发送 JSON-RPC 请求并解析响应。
   * SSRF 校验：MCP server transportUrl 来自用户/外部配置，
   * allowLocalhost=false（与 PluginExecutor.httpCall 对齐），
   * 校验失败返回 null，由上层 McpBridge.connectServer 报错。
   */
  private async rpc(server: McpServer, method: string,
    params: Record<string, Object>): Promise<JsonRpcResponse | null> {
    if (!UrlValidator.validate(server.transportUrl, false)) {
      return null;
    }

    const requestBody: Record<string, Object> = {
      'jsonrpc': '2.0',
      'id': this.nextId(),
      'method': method,
      'params': params
    };

    const req: http.HttpRequest = http.createHttp();
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (server.apiKey !== null && server.apiKey.length > 0) {
        headers['Authorization'] = `Bearer ${server.apiKey}`;
      }
      const resp: http.HttpResponse = await req.request(server.transportUrl, {
        method: http.RequestMethod.POST,
        connectTimeout: CONNECT_TIMEOUT_MS,
        readTimeout: REQUEST_TIMEOUT_MS,
        header: headers,
        extraData: JSON.stringify(requestBody)
      });
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        return null;
      }
      const text: string = typeof resp.result === 'string' ? resp.result as string : '';
      return this.parseResponse(text);
    } catch (e) {
      return null;
    } finally {
      req.destroy();
    }
  }

  private parseResponse(text: string): JsonRpcResponse | null {
    if (text.trim().length === 0) {
      return null;
    }
    try {
      const json: Record<string, Object> = JSON.parse(text) as Record<string, Object>;
      const idValue: Object | undefined = json['id'];
      if (idValue === undefined || idValue === null) {
        return null;
      }
      const errorValue: Object | undefined = json['error'];
      let error: { code: number, message: string } | null = null;
      if (errorValue !== undefined && errorValue !== null) {
        const errObj: Record<string, Object> = errorValue as Record<string, Object>;
        const codeValue: Object | undefined = errObj['code'];
        const messageValue: Object | undefined = errObj['message'];
        error = {
          code: codeValue !== undefined && codeValue !== null ? codeValue as number : -1,
          message: messageValue !== undefined && messageValue !== null
            ? messageValue as string : 'Unknown error'
        };
      }
      return {
        jsonrpc: '2.0',
        id: idValue as number,
        result: json['result'] !== undefined ? json['result'] : null,
        error: error
      };
    } catch (e) {
      return null;
    }
  }

  /** 解析工具 inputSchema（简化 JSON Schema）。 */
  private parseSchema(schemaValue: Object | undefined): McpToolSchema {
    const schema: McpToolSchema = { type: 'object', properties: {}, required: [] };
    if (schemaValue === undefined || schemaValue === null) {
      return schema;
    }
    const schemaMap: Record<string, Object> = schemaValue as Record<string, Object>;

    const typeValue: Object | undefined = schemaMap['type'];
    if (typeValue !== undefined && typeValue !== null) {
      schema.type = typeValue as string;
    }

    const propsValue: Object | undefined = schemaMap['properties'];
    if (propsValue !== undefined && propsValue !== null) {
      const propsObj: Record<string, Object> = propsValue as Record<string, Object>;
      const properties: Record<string, McpToolProperty> = {};
      const keys: string[] = Object.keys(propsObj);
      for (const key of keys) {
        const propMap: Record<string, Object> = propsObj[key] as Record<string, Object>;
        const propType: Object | undefined = propMap['type'];
        const propDesc: Object | undefined = propMap['description'];
        const propEnum: Object | undefined = propMap['enum'];
        let enumValues: string[] | null = null;
        if (propEnum !== undefined && propEnum !== null) {
          const enumArray: Object[] = propEnum as Object[];
          enumValues = enumArray.map((v: Object): string => `${v}`);
        }
        properties[key] = {
          type: propType !== undefined && propType !== null ? propType as string : 'string',
          description: propDesc !== undefined && propDesc !== null ? propDesc as string : '',
          enum: enumValues
        };
      }
      schema.properties = properties;
    }

    const requiredValue: Object | undefined = schemaMap['required'];
    if (requiredValue !== undefined && requiredValue !== null) {
      const requiredArray: Object[] = requiredValue as Object[];
      schema.required = requiredArray.map((v: Object): string => `${v}`);
    }
    return schema;
  }
}

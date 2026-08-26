// UrlValidator（对标 android core/security/UrlValidator.kt + ios Security/URLValidator.swift）
// 防止用户输入危险 URL 触发 SSRF / apiKey 泄漏
//
// H-S1 修复：防止攻击者控制 Agent 配置触发 SSRF
// （如 http://169.254.169.254/... 探活 AWS metadata 泄漏 apiKey）

import url from '@ohos.url';

// 允许的 scheme 白名单
const ALLOWED_SCHEMES: Set<string> = new Set(['http', 'https', 'ws', 'wss']);

// 禁止访问的 IP 段（链路本地 / 元数据服务 / 回环陷阱等）
// [ipBytes, prefixLength]
interface BlockedCidr {
  cidr: number[];
  prefix: number;
}

const BLOCKED_CIDRS: BlockedCidr[] = [
  { cidr: [169, 254, 0, 0], prefix: 16 },   // 链路本地（含 AWS/GCP/Azure metadata）
  { cidr: [0, 0, 0, 0], prefix: 8 },         // 本网络
  { cidr: [255, 255, 255, 255], prefix: 32 }, // 广播
  { cidr: [100, 64, 0, 0], prefix: 10 },     // CGNAT
  { cidr: [192, 0, 0, 0], prefix: 24 },      // IETF 协议分配
  { cidr: [198, 18, 0, 0], prefix: 15 },     // 基准测试
  { cidr: [224, 0, 0, 0], prefix: 4 },       // 组播
  { cidr: [240, 0, 0, 0], prefix: 4 }        // 保留
];

export class UrlValidator {
  /**
   * 校验 URL 字符串是否安全可用。
   *
   * @param urlString 用户输入的 URL 字符串
   * @param allowLocalhost 是否允许 localhost / 127.0.0.1 / 内网私网地址（默认 true）
   * @return 校验通过返回 true；失败返回 false
   */
  static validate(urlString: string, allowLocalhost: boolean = true): boolean {
    let parsed: URL;
    try {
      parsed = new URL(urlString);
    } catch (e) {
      return false;
    }

    const scheme: string = (parsed.scheme ?? '').toLowerCase();
    if (!ALLOWED_SCHEMES.has(scheme)) return false;

    const host: string = (parsed.hostname ?? '').toLowerCase();
    if (host.length === 0) return false;

    // localhost 显式放行
    if (allowLocalhost && (host === 'localhost' || host === 'ip6-localhost' || host === '::1')) {
      return true;
    }

    // IPv4 字面量校验
    const ipv4: number[] | null = this.parseIPv4(host);
    if (ipv4 !== null) {
      return this.isIPv4Allowed(ipv4, allowLocalhost);
    }

    // IPv6 字面量
    if (host.includes(':')) {
      const lower: string = host.replace(/^\[/, '').replace(/\]$/, '').toLowerCase();
      // 链路本地 fe80::/10
      if (lower.startsWith('fe8') || lower.startsWith('fe9') ||
          lower.startsWith('fea') || lower.startsWith('feb')) {
        return false;
      }
      // 唯一本地地址 fc00::/7
      if (lower.startsWith('fc') || lower.startsWith('fd')) {
        return allowLocalhost;
      }
      // IPv4-mapped IPv6: ::ffff:a.b.c.d
      if (lower.startsWith('::ffff:')) {
        const ipv4Part: string = lower.substring('::ffff:'.length);
        const mappedIpv4: number[] | null = this.parseIPv4(ipv4Part);
        if (mappedIpv4 !== null) {
          return this.isIPv4Allowed(mappedIpv4, allowLocalhost);
        }
      }
      return true;
    }

    // 域名校验：禁止 .local / .internal（mDNS SSRF）
    if (host.endsWith('.local') || host.endsWith('.internal')) {
      return allowLocalhost;
    }
    return true;
  }

  private static parseIPv4(s: string): number[] | null {
    const parts: string[] = s.split('.');
    if (parts.length !== 4) return null;
    const bytes: number[] = [];
    for (let i: number = 0; i < 4; i++) {
      const p: string = parts[i];
      if (p.length === 0 || p.length > 3) return null;
      const v: number = parseInt(p, 10);
      if (isNaN(v) || v < 0 || v > 255) return null;
      bytes.push(v);
    }
    return bytes;
  }

  private static isIPv4Allowed(ip: number[], allowLocalhost: boolean): boolean {
    // 127.0.0.0/8 回环
    if (ip[0] === 127) return allowLocalhost;
    // 私网地址
    if (ip[0] === 10) return allowLocalhost;
    if (ip[0] === 172 && ip[1] >= 16 && ip[1] <= 31) return allowLocalhost;
    if (ip[0] === 192 && ip[1] === 168) return allowLocalhost;

    // 禁止的 IP 段
    for (const entry of BLOCKED_CIDRS) {
      if (this.inCIDR(ip, entry.cidr, entry.prefix)) return false;
    }
    return true;
  }

  private static inCIDR(ip: number[], cidr: number[], prefix: number): boolean {
    const fullBytes: number = Math.floor(prefix / 8);
    const remainder: number = prefix % 8;
    for (let i: number = 0; i < fullBytes; i++) {
      if (ip[i] !== cidr[i]) return false;
    }
    if (remainder > 0 && fullBytes < 4) {
      const mask: number = (0xFF << (8 - remainder)) & 0xFF;
      if ((ip[fullBytes] & mask) !== (cidr[fullBytes] & mask)) {
        return false;
      }
    }
    return true;
  }
}

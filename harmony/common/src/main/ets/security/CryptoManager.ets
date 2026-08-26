// CryptoManager（对标 android core/security/CryptoManager.kt）
// 端到端对称加密工具，用于 Agent ↔ Agent 对等/中继模式 E2E 消息加密。
//
// 设计说明：
//  - 使用 AES/GCM/NoPadding（AEAD，带完整性校验），密钥由用户口令经
//    PBKDF2WithHmacSHA256 派生，每次加密使用随机 IV 与随机 salt。
//  - 输出格式：`AH1:` + Base64( IV[12] ‖ salt[16] ‖ ciphertext )。
//  - decrypt 对「非 AH1: 前缀」或解密失败的内容返回 null，调用方据此
//    回退为明文展示，保证与不支持 E2E 的对端互通时不崩溃。
//  - PBKDF2 用 HMAC-SHA256 迭代实现（cryptoFramework 提供 HMAC-SHA256）。

import cryptoFramework from '@ohos.security.cryptoFramework';
import util from '@ohos.util';

const ALGORITHM: string = 'AES256|GCM|NoPadding';
const KEY_LENGTH: number = 32;         // 256-bit
const GCM_IV_LENGTH: number = 12;
const GCM_TAG_LENGTH: number = 16;     // 128-bit auth tag
const PBKDF2_ITERATIONS: number = 600000; // OWASP 2023 推荐
const PBKDF2_SALT_LENGTH: number = 16;
const PREFIX: string = 'AH1:';
const HMAC_ALG: string = 'SHA256';

export class CryptoManager {
  private static instance: CryptoManager | null = null;
  private base64Helper: util.Base64Helper = new util.Base64Helper();

  private constructor() { }

  static getInstance(): CryptoManager {
    if (CryptoManager.instance === null) {
      CryptoManager.instance = new CryptoManager();
    }
    return CryptoManager.instance;
  }

  /**
   * 加密明文，返回带 `AH1:` 前缀的可传输字符串。
   */
  async encrypt(plaintext: string, passphrase: string): Promise<string> {
    const iv: Uint8Array = this.generateRandom(GCM_IV_LENGTH);
    const salt: Uint8Array = this.generateRandom(PBKDF2_SALT_LENGTH);
    const key: Uint8Array = await this.deriveKeyPbkdf2(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH);

    const ciphertextWithTag: Uint8Array = await this.aesGcmEncrypt(key, iv, plaintext);

    // 拼接 IV ‖ salt ‖ ciphertext+tag，Base64 编码
    const payload: Uint8Array = this.concatUint8Array(this.concatUint8Array(iv, salt), ciphertextWithTag);
    const b64: string = this.uint8ArrayToBase64(payload);
    return PREFIX + b64;
  }

  /**
   * 解密 payload。若不是本工具加密的内容或口令错误导致校验失败，返回 null。
   */
  async decrypt(payload: string, passphrase: string): Promise<string | null> {
    if (!payload.startsWith(PREFIX)) return null;
    try {
      const raw: Uint8Array = this.base64ToUint8Array(payload.substring(PREFIX.length));
      const iv: Uint8Array = raw.slice(0, GCM_IV_LENGTH);
      const salt: Uint8Array = raw.slice(GCM_IV_LENGTH, GCM_IV_LENGTH + PBKDF2_SALT_LENGTH);
      const ciphertextWithTag: Uint8Array = raw.slice(GCM_IV_LENGTH + PBKDF2_SALT_LENGTH);

      const key: Uint8Array = await this.deriveKeyPbkdf2(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH);
      return await this.aesGcmDecrypt(key, iv, ciphertextWithTag);
    } catch (e) {
      return null;
    }
  }

  isEncrypted(payload: string): boolean {
    return payload.startsWith(PREFIX);
  }

  // ── AES-256-GCM 加解密 ──

  private async aesGcmEncrypt(key: Uint8Array, iv: Uint8Array, plaintext: string): Promise<Uint8Array> {
    const cipher: cryptoFramework.Sym = cryptoFramework.createSymInstance(ALGORITHM);
    const symKey: cryptoFramework.SymKey = cryptoFramework.createSymKey({ algName: 'AES', keyData: { data: key } });
    const params: cryptoFramework.GcmParamsSpec = {
      iv: { data: iv },
      aad: { data: this.stringToUint8Array('acc_e2e') },
      authTag: { data: new Uint8Array(GCM_TAG_LENGTH) }
    };
    await cipher.init(symKey, cryptoFramework.CryptoMode.ENCRYPT_MODE, params);

    const plainBytes: Uint8Array = this.stringToUint8Array(plaintext);
    const updateResult: cryptoFramework.DataBlob = await cipher.doUpdate({ data: plainBytes });
    const finalResult: cryptoFramework.DataBlob = await cipher.doFinal();

    // GCM: update 输出为密文，doFinal 输出为 auth tag
    const ciphertext: Uint8Array = updateResult.data ?? new Uint8Array(0);
    const tag: Uint8Array = finalResult.data ?? new Uint8Array(0);
    return this.concatUint8Array(ciphertext, tag);
  }

  private async aesGcmDecrypt(key: Uint8Array, iv: Uint8Array, ciphertextWithTag: Uint8Array): Promise<string> {
    // 拆分 ciphertext 和 auth tag
    const ciphertext: Uint8Array = ciphertextWithTag.slice(0, ciphertextWithTag.length - GCM_TAG_LENGTH);
    const tag: Uint8Array = ciphertextWithTag.slice(ciphertextWithTag.length - GCM_TAG_LENGTH);

    const cipher: cryptoFramework.Sym = cryptoFramework.createSymInstance(ALGORITHM);
    const symKey: cryptoFramework.SymKey = cryptoFramework.createSymKey({ algName: 'AES', keyData: { data: key } });
    const params: cryptoFramework.GcmParamsSpec = {
      iv: { data: iv },
      aad: { data: this.stringToUint8Array('acc_e2e') },
      authTag: { data: tag }
    };
    await cipher.init(symKey, cryptoFramework.CryptoMode.DECRYPT_MODE, params);

    const updateResult: cryptoFramework.DataBlob = await cipher.doUpdate({ data: ciphertext });
    const finalResult: cryptoFramework.DataBlob = await cipher.doFinal();

    const plainBytes: Uint8Array = updateResult.data ?? new Uint8Array(0);
    return this.uint8ArrayToString(plainBytes);
  }

  // ── PBKDF2-HMAC-SHA256 密钥派生 ──
  // DK = T1 = F(password, salt, c, 1)
  // F(P, S, c, i) = U1 XOR U2 XOR ... XOR Uc
  // U1 = HMAC(P, S || INT32_BE(i))
  // Uj = HMAC(P, U_{j-1})

  private async deriveKeyPbkdf2(passphrase: string, salt: Uint8Array, iterations: number, keyLength: number): Promise<Uint8Array> {
    const passphraseBytes: Uint8Array = this.stringToUint8Array(passphrase);
    const hLen: number = 32; // SHA-256 output length
    const numBlocks: number = Math.ceil(keyLength / hLen);
    const dk: Uint8Array = new Uint8Array(numBlocks * hLen);

    for (let block: number = 1; block <= numBlocks; block++) {
      // salt || INT32_BE(block)
      const saltWithIndex: Uint8Array = new Uint8Array(salt.length + 4);
      saltWithIndex.set(salt, 0);
      saltWithIndex[salt.length] = (block >>> 24) & 0xFF;
      saltWithIndex[salt.length + 1] = (block >>> 16) & 0xFF;
      saltWithIndex[salt.length + 2] = (block >>> 8) & 0xFF;
      saltWithIndex[salt.length + 3] = block & 0xFF;

      let u: Uint8Array = await this.hmacSha256(passphraseBytes, saltWithIndex);
      const t: Uint8Array = new Uint8Array(u);
      for (let j: number = 1; j < iterations; j++) {
        u = await this.hmacSha256(passphraseBytes, u);
        for (let k: number = 0; k < hLen; k++) {
          t[k] ^= u[k];
        }
      }
      dk.set(t, (block - 1) * hLen);
    }
    return dk.slice(0, keyLength);
  }

  /**
   * HMAC-SHA256(key, data)
   */
  private async hmacSha256(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
    const mac: cryptoFramework.Mac = cryptoFramework.createMac(HMAC_ALG);
    const keySpec: cryptoFramework.HMACKeySpec = { algName: 'HMAC', keyData: { data: key } };
    const symKey: cryptoFramework.SymKey = cryptoFramework.createSymKey(keySpec);
    await mac.init(symKey);
    await mac.update({ data: data });
    const result: cryptoFramework.DataBlob = await mac.doFinal({ data: new Uint8Array(0) });
    return result.data ?? new Uint8Array(0);
  }

  // ── 工具方法 ──

  private generateRandom(length: number): Uint8Array {
    const bytes: Uint8Array = new Uint8Array(length);
    for (let i: number = 0; i < length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
    return bytes;
  }

  private concatUint8Array(a: Uint8Array, b: Uint8Array): Uint8Array {
    const result: Uint8Array = new Uint8Array(a.length + b.length);
    result.set(a, 0);
    result.set(b, a.length);
    return result;
  }

  private stringToUint8Array(s: string): Uint8Array {
    const encoder: util.TextEncoder = util.TextEncoder.create('utf-8');
    return encoder.encodeInto(s);
  }

  private uint8ArrayToString(bytes: Uint8Array): string {
    const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
    return decoder.decodeWithStream(bytes);
  }

  private uint8ArrayToBase64(bytes: Uint8Array): string {
    return this.base64Helper.encodeToStringSync(bytes, util.Type.BASIC);
  }

  private base64ToUint8Array(b64: string): Uint8Array {
    return this.base64Helper.decodeSync(b64, util.Type.BASIC);
  }
}

// KeystoreManager（对标 android core/security/KeystoreManager.kt）
// HarmonyOS HUKS 硬件级加密管理器。
//
// 设计说明：
//  - 使用 HUKS（Universal Keystore）生成并存储 AES-256-GCM 密钥，密钥材料
//    由硬件安全模块保护，永不以明文形式离开安全区域。
//  - 加密数据可安全存储在 relationalStore 中，即使设备被 root 或数据库被
//    导出，攻击者也无法在没有 HUKS 授权的情况下解密。
//  - 输出格式：`AKS:` + Base64( IV[12] ‖ ciphertext )，与 Android/iOS 对齐。
//  - 异步 API（Promise），因为 HUKS 会话操作是异步的。
//
// 适用场景：加密存储 apiKey、e2eKey、token 等敏感凭据。

import huks from '@ohos.security.huks';
import util from '@ohos.util';

const KEY_ALIAS: string = 'agentcontrolcenter_master_key';
const GCM_IV_LENGTH: number = 12;
const GCM_TAG_LENGTH: number = 16;       // 128-bit auth tag
const PREFIX: string = 'AKS:';

export class KeystoreManager {
  private static instance: KeystoreManager | null = null;
  private base64Helper: util.Base64Helper = new util.Base64Helper();
  private random: util.Random = new util.Random();

  private constructor() { }

  static getInstance(): KeystoreManager {
    if (KeystoreManager.instance === null) {
      KeystoreManager.instance = new KeystoreManager();
    }
    return KeystoreManager.instance;
  }

  /**
   * 确保密钥已生成。首次调用时自动创建，后续调用直接读取。
   */
  private async ensureKey(): Promise<void> {
    const existOptions: huks.HuksOptions = {
      properties: []
    };
    try {
      await huks.getKeyItemProperties(KEY_ALIAS, existOptions);
      return; // 密钥已存在
    } catch (e) {
      // 密钥不存在，继续生成
    }

    const genOptions: huks.HuksOptions = {
      properties: [
        { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
        { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
        { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT | huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT },
        { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
        { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE }
      ]
    };
    await huks.createKeyItem(KEY_ALIAS, genOptions);
  }

  /**
   * 加密明文，返回带 `AKS:` 前缀的可存储字符串。
   * 已加密的内容（带 `AKS:` 前缀）会被直接返回，避免双重加密。
   */
  async encrypt(plaintext: string): Promise<string> {
    if (plaintext.length === 0) return plaintext;
    if (plaintext.startsWith(PREFIX)) return plaintext;

    await this.ensureKey();

    // 生成随机 IV (12 bytes)
    const iv: Uint8Array = this.generateRandom(GCM_IV_LENGTH);

    const initOptions: huks.HuksOptions = {
      properties: [
        { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
        { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
        { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT },
        { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
        { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
        { tag: huks.HuksTag.HUKS_TAG_NONCE, value: iv },
        { tag: huks.HuksTag.HUKS_TAG_ASSOCIATED_DATA, value: this.stringToUint8Array('acc_aad') }
      ]
    };

    const initResult: huks.HuksSessionHandle = await huks.initSession(KEY_ALIAS, initOptions);

    const updateOptions: huks.HuksOptions = {
      properties: [],
      inData: this.stringToUint8Array(plaintext)
    };
    await huks.updateSession(initResult.handle, updateOptions);

    const finishOptions: huks.HuksOptions = {
      properties: [
        { tag: huks.HuksTag.HUKS_TAG_KEY_ALIAS, value: this.stringToUint8Array(KEY_ALIAS) },
        { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
        { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
        { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
        { tag: huks.HuksTag.HUKS_TAG_NONCE, value: iv },
        { tag: huks.HuksTag.HUKS_TAG_ASSOCIATED_DATA, value: this.stringToUint8Array('acc_aad') }
      ]
    };
    const finishResult: huks.HuksReturnResult = await huks.finishSession(initResult.handle, finishOptions);

    // finishResult.outData 包含 ciphertext + GCM tag
    const ciphertextWithTag: Uint8Array = finishResult.outData ?? new Uint8Array(0);

    // 拼接 IV ‖ ciphertext+tag，Base64 编码
    const payload: Uint8Array = this.concatUint8Array(iv, ciphertextWithTag);
    const b64: string = this.uint8ArrayToBase64(payload);
    return PREFIX + b64;
  }

  /**
   * 解密 payload。若不是本工具加密的内容或解密失败，返回 null。
   */
  async decrypt(payload: string): Promise<string | null> {
    if (!payload.startsWith(PREFIX)) return null;
    try {
      const raw: Uint8Array = this.base64ToUint8Array(payload.substring(PREFIX.length));
      const iv: Uint8Array = raw.slice(0, GCM_IV_LENGTH);
      const ciphertextWithTag: Uint8Array = raw.slice(GCM_IV_LENGTH);

      const initOptions: huks.HuksOptions = {
        properties: [
          { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
          { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
          { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT },
          { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
          { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
          { tag: huks.HuksTag.HUKS_TAG_NONCE, value: iv },
          { tag: huks.HuksTag.HUKS_TAG_ASSOCIATED_DATA, value: this.stringToUint8Array('acc_aad') }
        ]
      };
      const initResult: huks.HuksSessionHandle = await huks.initSession(KEY_ALIAS, initOptions);

      const updateOptions: huks.HuksOptions = {
        properties: [],
        inData: ciphertextWithTag
      };
      await huks.updateSession(initResult.handle, updateOptions);

      const finishOptions: huks.HuksOptions = {
        properties: [
          { tag: huks.HuksTag.HUKS_TAG_KEY_ALIAS, value: this.stringToUint8Array(KEY_ALIAS) },
          { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
          { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
          { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
          { tag: huks.HuksTag.HUKS_TAG_NONCE, value: iv },
          { tag: huks.HuksTag.HUKS_TAG_ASSOCIATED_DATA, value: this.stringToUint8Array('acc_aad') }
        ]
      };
      const finishResult: huks.HuksReturnResult = await huks.finishSession(initResult.handle, finishOptions);
      const plainBytes: Uint8Array = finishResult.outData ?? new Uint8Array(0);
      return this.uint8ArrayToString(plainBytes);
    } catch (e) {
      return null;
    }
  }

  /**
   * 智能解密：若为 AKS 加密内容则解密，否则视为旧版明文直接返回。
   * 适用于从旧版明文存储平滑迁移到 HUKS 加密的场景。
   */
  async decryptOrRaw(value: string): Promise<string> {
    if (value.length === 0) return value;
    if (!value.startsWith(PREFIX)) return value;
    const decrypted: string | null = await this.decrypt(value);
    return decrypted ?? '';
  }

  /** 判断内容是否已通过 KeystoreManager 加密 */
  isEncrypted(value: string): boolean {
    return value.startsWith(PREFIX);
  }

  // ── 内部工具 ──

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

// 文件传输协议契约（对标 protocol/schemas/file-transfer-schema.json）
// 对应 Android FileTransfer 相关 model + iOS Models/FileTransfer.swift。
// v1：小文件（≤10MB）内联 Base64；v2：分块上传至 uploadUrl。

export enum FileTransferDirection {
  upload = 'upload',
  download = 'download'
}

export enum FileTransferStatus {
  pending = 'pending',
  uploading = 'uploading',
  completed = 'completed',
  failed = 'failed',
  cancelled = 'cancelled'
}

export interface FileMetadata {
  filename: string;     // required
  mimeType: string;     // required
  size: number;         // required，字节数，v1 上限 10485760
  checksum: string;     // required，SHA-256 十六进制 64 位
}

export interface FileTransferRequest {
  id: string;                          // required
  direction: FileTransferDirection;    // required
  metadata: FileMetadata;              // required
  data: string | null;                 // 默认 null，v1 内联 Base64
  uploadUrl: string | null;            // 默认 null，非空走 v2 分块
  status: FileTransferStatus;          // 默认 pending
}

export interface FileTransferResponse {
  id: string;                  // required
  fileUrl: string | null;     // 默认 null，v2 上传完成返回
  status: FileTransferStatus;
  error: string | null;       // 默认 null，failed 时设置
}

// v1 限制常量
export class FileTransferLimits {
  static readonly V1_MAX_SIZE: number = 10485760; // 10MB
  static readonly IMAGE_MAX_EDGE: number = 720;
  static readonly IMAGE_QUALITY: number = 85;
}

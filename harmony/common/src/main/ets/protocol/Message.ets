// Message 协议契约（对标 protocol/schemas/message-schema.json）
// 对应 Android Message.kt + iOS Models/Message.swift。

export enum MessageRole {
  User = 'User',
  Assistant = 'Assistant',
  System = 'System',
  Tool = 'Tool'
}

export enum MessageStatus {
  Sending = 'Sending',
  Sent = 'Sent',
  Received = 'Received',
  Failed = 'Failed'
}

export enum AttachmentType {
  image = 'image',
  file = 'file'
}

export interface Message {
  id: string;                          // required
  sessionId: string;                   // required
  role: MessageRole;                   // required
  content: string;                     // required
  timestamp: number;                   // required，Unix 毫秒
  status: MessageStatus;               // required
  metadataJson: string;                // 默认 '{}'，token 数/模型名等
  attachmentType: AttachmentType | null; // 默认 null
  attachmentData: string | null;       // 图片 Base64 / 文件路径，≤10MB
  attachmentName: string | null;       // 默认 null
  reaction: string;                    // 默认 ''，emoji 字符
  replyToId: string | null;            // 默认 null
}

export const newUserMessage = (
  id: string,
  sessionId: string,
  content: string,
  now: number
): Message => ({
  id,
  sessionId,
  role: MessageRole.User,
  content,
  timestamp: now,
  status: MessageStatus.Sending,
  metadataJson: '{}',
  attachmentType: null,
  attachmentData: null,
  attachmentName: null,
  reaction: '',
  replyToId: null
});

export const newAssistantMessage = (
  id: string,
  sessionId: string,
  content: string,
  now: number
): Message => ({
  id,
  sessionId,
  role: MessageRole.Assistant,
  content,
  timestamp: now,
  status: MessageStatus.Received,
  metadataJson: '{}',
  attachmentType: null,
  attachmentData: null,
  attachmentName: null,
  reaction: '',
  replyToId: null
});

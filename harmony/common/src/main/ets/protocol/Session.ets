// Session 协议契约（对标 protocol/schemas/session-schema.json）
// 对应 Android Session.kt + iOS Models/Session.swift。

export interface Session {
  id: string;                  // required
  title: string;               // required
  createdAt: number;           // required，Unix 毫秒
  updatedAt: number;          // required，Unix 毫秒
  isPinned: boolean;           // 默认 false
  messageCount: number;        // 默认 0，冗余字段用于列表展示
  summary: string;             // 默认 ''
}

export const newSession = (id: string, title: string, now: number): Session => ({
  id,
  title,
  createdAt: now,
  updatedAt: now,
  isPinned: false,
  messageCount: 0,
  summary: ''
});

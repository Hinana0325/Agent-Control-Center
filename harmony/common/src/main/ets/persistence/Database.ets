// AppDatabase（对标 android core/database/AppDatabase.kt）
// HarmonyOS relationalStore RDB 数据库管理器。
//
// 对齐 Android Room AppDatabase：8 张表（sessions / messages / agent_configs /
// activity_log / plugins / tasks / workflow_runs / marketplace_favorites）。
// 已创建 4 张表（sessions / messages / agent_configs / workflow_runs），其余表后续补充。
//
// 使用 @ohos.data.relationalStore（RDB），对应 Android Room + iOS SwiftData。
// 布尔值用 INTEGER(0/1)，浮点用 REAL，时间戳用 INTEGER（Unix 毫秒）。

import relationalStore from '@ohos.data.relationalStore';
import common from '@ohos.app.ability.common';

const DB_NAME: string = 'agentcontrolcenter.db';
const DB_VERSION: number = 1;

// 表创建 SQL（与 Android Room schema 对齐，SQLite 方言）
const CREATE_SESSIONS_SQL: string = `
  CREATE TABLE IF NOT EXISTS sessions (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    isPinned INTEGER NOT NULL DEFAULT 0,
    messageCount INTEGER NOT NULL DEFAULT 0,
    summary TEXT NOT NULL DEFAULT ''
  )`;

const CREATE_MESSAGES_SQL: string = `
  CREATE TABLE IF NOT EXISTS messages (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    status TEXT NOT NULL,
    metadataJson TEXT NOT NULL DEFAULT '{}',
    attachmentType TEXT,
    attachmentData TEXT,
    attachmentName TEXT,
    reaction TEXT NOT NULL DEFAULT '',
    replyToId TEXT,
    FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
  )`;

const CREATE_AGENT_CONFIGS_SQL: string = `
  CREATE TABLE IF NOT EXISTS agent_configs (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL DEFAULT 'Default Agent',
    type TEXT NOT NULL DEFAULT 'Hermes',
    serverUrl TEXT NOT NULL DEFAULT '',
    apiKey TEXT NOT NULL DEFAULT '',
    model TEXT NOT NULL DEFAULT '',
    systemPrompt TEXT NOT NULL DEFAULT '',
    temperature REAL NOT NULL DEFAULT 0.7,
    maxTokens INTEGER NOT NULL DEFAULT 4096,
    protocolType TEXT NOT NULL DEFAULT 'WebSocket'
  )`;

// H4: 工作流执行历史表（对标 v4.9.0 WorkflowRunEntity）
const CREATE_WORKFLOW_RUNS_SQL: string = `
  CREATE TABLE IF NOT EXISTS workflow_runs (
    id TEXT NOT NULL PRIMARY KEY,
    workflowId TEXT NOT NULL,
    workflowName TEXT NOT NULL,
    input TEXT NOT NULL DEFAULT '',
    output TEXT NOT NULL DEFAULT '',
    startedAt INTEGER NOT NULL,
    completedAt INTEGER,
    status TEXT NOT NULL,
    failedNodeIdsJson TEXT NOT NULL DEFAULT '[]',
    error TEXT,
    logsJson TEXT NOT NULL DEFAULT '[]'
  )`;

// 索引（对齐 Android Room @Index 注解）
const CREATE_INDEXES_SQL: string[] = [
  'CREATE INDEX IF NOT EXISTS idx_messages_sessionId_timestamp ON messages (sessionId, timestamp)',
  'CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages (timestamp)',
  'CREATE INDEX IF NOT EXISTS idx_messages_role ON messages (role)',
  'CREATE INDEX IF NOT EXISTS idx_workflow_runs_workflowId ON workflow_runs (workflowId)',
  'CREATE INDEX IF NOT EXISTS idx_workflow_runs_startedAt ON workflow_runs (startedAt)'
];

export class AppDatabase {
  private static instance: AppDatabase | null = null;
  private rdbStore: relationalStore.RdbStore | null = null;

  private constructor() { }

  /**
   * 初始化数据库。必须在 EntryAbility 启动时调用，传入 Context。
   * 创建表与索引（IF NOT EXISTS，幂等）。
   */
  static async init(context: common.Context): Promise<AppDatabase> {
    if (AppDatabase.instance !== null && AppDatabase.instance.rdbStore !== null) {
      return AppDatabase.instance;
    }
    const db: AppDatabase = AppDatabase.instance ?? new AppDatabase();
    AppDatabase.instance = db;

    const config: relationalStore.StoreConfig = {
      name: DB_NAME,
      securityLevel: relationalStore.SecurityLevel.S1
    };
    db.rdbStore = await relationalStore.getRdbStore(context, config);

    // 创建表
    await db.rdbStore.executeSql(CREATE_SESSIONS_SQL);
    await db.rdbStore.executeSql(CREATE_MESSAGES_SQL);
    await db.rdbStore.executeSql(CREATE_AGENT_CONFIGS_SQL);
    await db.rdbStore.executeSql(CREATE_WORKFLOW_RUNS_SQL);

    // 创建索引
    for (const sql of CREATE_INDEXES_SQL) {
      await db.rdbStore.executeSql(sql);
    }

    return db;
  }

  /**
   * 获取已初始化的数据库实例。
   * 调用前必须先 init()，否则抛异常。
   */
  static getInstance(): AppDatabase {
    if (AppDatabase.instance === null || AppDatabase.instance.rdbStore === null) {
      throw new Error('AppDatabase not initialized. Call AppDatabase.init(context) first.');
    }
    return AppDatabase.instance;
  }

  /**
   * 获取底层 RdbStore，供 DAO 使用。
   */
  getStore(): relationalStore.RdbStore {
    if (this.rdbStore === null) {
      throw new Error('RdbStore is null. Call AppDatabase.init(context) first.');
    }
    return this.rdbStore;
  }
}

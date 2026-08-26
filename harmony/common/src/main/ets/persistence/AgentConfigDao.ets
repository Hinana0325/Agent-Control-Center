// AgentConfigDao（对标 android core/database/dao/AgentConfigDao.kt）
// 对应 Android Room @Dao，方法签名对齐。

import relationalStore from '@ohos.data.relationalStore';
import { AppDatabase } from './Database';
import { AgentConfigEntity } from './Entities';

const TABLE: string = 'agent_configs';

export class AgentConfigDao {
  /**
   * 获取所有 Agent 配置。
   */
  async getAllConfigs(): Promise<AgentConfigEntity[]> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    return this.resultSetToEntities(resultSet);
  }

  /**
   * 按 ID 获取单个 Agent 配置。
   */
  async getConfigById(id: string): Promise<AgentConfigEntity | null> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    const entities: AgentConfigEntity[] = this.resultSetToEntities(resultSet);
    return entities.length > 0 ? entities[0] : null;
  }

  /**
   * 获取默认 Agent 配置（id = 'default'）。
   */
  async getDefaultConfig(): Promise<AgentConfigEntity | null> {
    return this.getConfigById('default');
  }

  /**
   * 插入或替换 Agent 配置（OnConflictStrategy.REPLACE 语义）。
   */
  async insertConfig(config: AgentConfigEntity): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = this.entityToValuesBucket(config);
    const conflictResolution: relationalStore.ConflictResolution = relationalStore.ConflictResolution.ON_CONFLICT_REPLACE;
    await store.insert(TABLE, values, conflictResolution);
  }

  /**
   * 按 ID 删除 Agent 配置。
   */
  async deleteConfig(id: string): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    await store.delete(predicates);
  }

  // ── 映射工具 ──

  private resultSetToEntities(resultSet: relationalStore.ResultSet): AgentConfigEntity[] {
    const entities: AgentConfigEntity[] = [];
    while (resultSet.goToNextRow()) {
      entities.push(this.rowToEntity(resultSet));
    }
    resultSet.close();
    return entities;
  }

  private rowToEntity(rs: relationalStore.ResultSet): AgentConfigEntity {
    return {
      id: rs.getString(rs.getColumnIndex('id')),
      name: rs.getString(rs.getColumnIndex('name')),
      type: rs.getString(rs.getColumnIndex('type')),
      serverUrl: rs.getString(rs.getColumnIndex('serverUrl')),
      apiKey: rs.getString(rs.getColumnIndex('apiKey')),
      model: rs.getString(rs.getColumnIndex('model')),
      systemPrompt: rs.getString(rs.getColumnIndex('systemPrompt')),
      temperature: rs.getDouble(rs.getColumnIndex('temperature')),
      maxTokens: rs.getLong(rs.getColumnIndex('maxTokens')),
      protocolType: rs.getString(rs.getColumnIndex('protocolType'))
    };
  }

  private entityToValuesBucket(config: AgentConfigEntity): relationalStore.ValuesBucket {
    return {
      'id': config.id,
      'name': config.name,
      'type': config.type,
      'serverUrl': config.serverUrl,
      'apiKey': config.apiKey,
      'model': config.model,
      'systemPrompt': config.systemPrompt,
      'temperature': config.temperature,
      'maxTokens': config.maxTokens,
      'protocolType': config.protocolType
    };
  }
}

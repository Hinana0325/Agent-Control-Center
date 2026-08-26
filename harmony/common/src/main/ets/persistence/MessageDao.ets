// MessageDao（对标 android core/database/dao/MessageDao.kt）
// 对应 Android Room @Dao，方法签名对齐。
// Flow<List> 改为 Promise<List>，suspend 改为 async。

import relationalStore from '@ohos.data.relationalStore';
import { AppDatabase } from './Database';
import { MessageEntity } from './Entities';

const TABLE: string = 'messages';

export class MessageDao {
  /**
   * 按 sessionId 获取消息，按 timestamp ASC 排序。
   */
  async getMessagesBySession(sessionId: string): Promise<MessageEntity[]> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('sessionId', sessionId);
    predicates.orderByAsc('timestamp');
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    return this.resultSetToEntities(resultSet);
  }

  /**
   * 按 ID 获取单条消息。
   */
  async getMessageById(id: string): Promise<MessageEntity | null> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    const entities: MessageEntity[] = this.resultSetToEntities(resultSet);
    return entities.length > 0 ? entities[0] : null;
  }

  /**
   * 插入或替换消息（OnConflictStrategy.REPLACE 语义）。
   */
  async insertMessage(message: MessageEntity): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = this.entityToValuesBucket(message);
    const conflictResolution: relationalStore.ConflictResolution = relationalStore.ConflictResolution.ON_CONFLICT_REPLACE;
    await store.insert(TABLE, values, conflictResolution);
  }

  /**
   * 批量插入消息。
   */
  async insertMessages(messages: MessageEntity[]): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const conflictResolution: relationalStore.ConflictResolution = relationalStore.ConflictResolution.ON_CONFLICT_REPLACE;
    for (const message of messages) {
      const values: relationalStore.ValuesBucket = this.entityToValuesBucket(message);
      await store.insert(TABLE, values, conflictResolution);
    }
  }

  /**
   * 删除指定 session 的所有消息。
   */
  async deleteMessagesBySession(sessionId: string): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('sessionId', sessionId);
    await store.delete(predicates);
  }

  /**
   * 按 ID 删除单条消息。
   */
  async deleteMessageById(id: string): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    await store.delete(predicates);
  }

  /**
   * 更新消息内容与状态。
   */
  async updateMessage(id: string, content: string, status: string): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = { 'content': content, 'status': status };
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    await store.update(values, predicates);
  }

  /**
   * 更新消息表情反应。
   */
  async updateReaction(id: string, reaction: string): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = { 'reaction': reaction };
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    await store.update(values, predicates);
  }

  /**
   * 获取最新一条消息（全局，不分 session）。
   */
  async getLastMessage(): Promise<MessageEntity | null> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.orderByDesc('timestamp');
    predicates.limitAs(1);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    const entities: MessageEntity[] = this.resultSetToEntities(resultSet);
    return entities.length > 0 ? entities[0] : null;
  }

  /**
   * 搜索消息（LIKE 查询，限制 50 条）。
   */
  async searchMessages(query: string): Promise<MessageEntity[]> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.like('content', `%${query}%`);
    predicates.orderByDesc('timestamp');
    predicates.limitAs(50);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    return this.resultSetToEntities(resultSet);
  }

  // ── 映射工具 ──

  private resultSetToEntities(resultSet: relationalStore.ResultSet): MessageEntity[] {
    const entities: MessageEntity[] = [];
    while (resultSet.goToNextRow()) {
      entities.push(this.rowToEntity(resultSet));
    }
    resultSet.close();
    return entities;
  }

  private rowToEntity(rs: relationalStore.ResultSet): MessageEntity {
    const attachmentTypeIdx: number = rs.getColumnIndex('attachmentType');
    const attachmentDataIdx: number = rs.getColumnIndex('attachmentData');
    const attachmentNameIdx: number = rs.getColumnIndex('attachmentName');
    const replyToIdIdx: number = rs.getColumnIndex('replyToId');
    return {
      id: rs.getString(rs.getColumnIndex('id')),
      sessionId: rs.getString(rs.getColumnIndex('sessionId')),
      role: rs.getString(rs.getColumnIndex('role')),
      content: rs.getString(rs.getColumnIndex('content')),
      timestamp: rs.getLong(rs.getColumnIndex('timestamp')),
      status: rs.getString(rs.getColumnIndex('status')),
      metadataJson: rs.getString(rs.getColumnIndex('metadataJson')),
      attachmentType: rs.isNull(attachmentTypeIdx) ? null : rs.getString(attachmentTypeIdx),
      attachmentData: rs.isNull(attachmentDataIdx) ? null : rs.getString(attachmentDataIdx),
      attachmentName: rs.isNull(attachmentNameIdx) ? null : rs.getString(attachmentNameIdx),
      reaction: rs.getString(rs.getColumnIndex('reaction')),
      replyToId: rs.isNull(replyToIdIdx) ? null : rs.getString(replyToIdIdx)
    };
  }

  private entityToValuesBucket(message: MessageEntity): relationalStore.ValuesBucket {
    return {
      'id': message.id,
      'sessionId': message.sessionId,
      'role': message.role,
      'content': message.content,
      'timestamp': message.timestamp,
      'status': message.status,
      'metadataJson': message.metadataJson,
      'attachmentType': message.attachmentType,
      'attachmentData': message.attachmentData,
      'attachmentName': message.attachmentName,
      'reaction': message.reaction,
      'replyToId': message.replyToId
    };
  }
}

// SessionDao（对标 android core/database/dao/SessionDao.kt）
// 对应 Android Room @Dao，方法签名对齐。
// Flow<List> 改为 Promise<List>，suspend 改为 async。

import relationalStore from '@ohos.data.relationalStore';
import { AppDatabase } from './Database';
import { SessionEntity } from './Entities';

const TABLE: string = 'sessions';

export class SessionDao {
  /**
   * 获取所有会话，按 isPinned DESC, updatedAt DESC 排序。
   */
  async getAllSessions(): Promise<SessionEntity[]> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.orderByDesc('isPinned');
    predicates.orderByDesc('updatedAt');
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    return this.resultSetToEntities(resultSet);
  }

  /**
   * 按 ID 获取单个会话。
   */
  async getSessionById(id: string): Promise<SessionEntity | null> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    const entities: SessionEntity[] = this.resultSetToEntities(resultSet);
    return entities.length > 0 ? entities[0] : null;
  }

  /**
   * 插入或替换会话（OnConflictStrategy.REPLACE 语义）。
   */
  async insertSession(session: SessionEntity): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = this.entityToValuesBucket(session);
    const conflictResolution: relationalStore.ConflictResolution = relationalStore.ConflictResolution.ON_CONFLICT_REPLACE;
    await store.insert(TABLE, values, conflictResolution);
  }

  /**
   * 更新会话。
   */
  async updateSession(session: SessionEntity): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = this.entityToValuesBucket(session);
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', session.id);
    await store.update(values, predicates);
  }

  /**
   * 按 ID 删除会话（级联删除消息）。
   */
  async deleteSessionById(id: string): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    await store.delete(predicates);
  }

  /**
   * 切换会话置顶状态。
   */
  async togglePin(id: string, isPinned: boolean): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = { 'isPinned': isPinned ? 1 : 0 };
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    await store.update(values, predicates);
  }

  /**
   * 递增消息计数并更新 updatedAt。
   */
  async incrementMessageCount(id: string, updatedAt: number): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    await store.executeSql(
      `UPDATE ${TABLE} SET messageCount = messageCount + 1, updatedAt = ? WHERE id = ?`,
      [updatedAt, id]
    );
  }

  /**
   * 重置消息计数并更新 updatedAt。
   */
  async resetMessageCount(id: string, updatedAt: number): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    await store.executeSql(
      `UPDATE ${TABLE} SET messageCount = 0, updatedAt = ? WHERE id = ?`,
      [updatedAt, id]
    );
  }

  // ── 映射工具 ──

  private resultSetToEntities(resultSet: relationalStore.ResultSet): SessionEntity[] {
    const entities: SessionEntity[] = [];
    while (resultSet.goToNextRow()) {
      entities.push(this.rowToEntity(resultSet));
    }
    resultSet.close();
    return entities;
  }

  private rowToEntity(rs: relationalStore.ResultSet): SessionEntity {
    return {
      id: rs.getString(rs.getColumnIndex('id')),
      title: rs.getString(rs.getColumnIndex('title')),
      createdAt: rs.getLong(rs.getColumnIndex('createdAt')),
      updatedAt: rs.getLong(rs.getColumnIndex('updatedAt')),
      isPinned: rs.getLong(rs.getColumnIndex('isPinned')) !== 0,
      messageCount: rs.getLong(rs.getColumnIndex('messageCount')),
      summary: rs.getString(rs.getColumnIndex('summary'))
    };
  }

  private entityToValuesBucket(session: SessionEntity): relationalStore.ValuesBucket {
    return {
      'id': session.id,
      'title': session.title,
      'createdAt': session.createdAt,
      'updatedAt': session.updatedAt,
      'isPinned': session.isPinned ? 1 : 0,
      'messageCount': session.messageCount,
      'summary': session.summary
    };
  }
}

// WorkflowRunDao（对标 android core/database/dao/WorkflowRunDao.kt）
// 工作流执行历史 DAO。每次 WorkflowEngine.execute() 落一条记录。

import relationalStore from '@ohos.data.relationalStore';
import { AppDatabase } from './Database';
import { WorkflowRunEntity } from './Entities';

const TABLE: string = 'workflow_runs';

export class WorkflowRunDao {
  /**
   * 插入执行历史记录（OnConflictStrategy.REPLACE 语义）。
   */
  async insertRun(run: WorkflowRunEntity): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const values: relationalStore.ValuesBucket = this.entityToValuesBucket(run);
    const conflictResolution: relationalStore.ConflictResolution =
      relationalStore.ConflictResolution.ON_CONFLICT_REPLACE;
    await store.insert(TABLE, values, conflictResolution);
  }

  /**
   * 更新执行结果：状态 / 完成时间 / 输出 / 错误 / 日志。
   */
  async updateStatus(id: string, status: string, completedAt: number | null,
    output: string, error: string | null, logsJson: string,
    failedNodeIdsJson: string): Promise<void> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    await store.executeSql(
      `UPDATE ${TABLE} SET status = ?, completedAt = ?, output = ?, error = ?, ` +
      `logsJson = ?, failedNodeIdsJson = ? WHERE id = ?`,
      [status, completedAt, output, error, logsJson, failedNodeIdsJson, id]
    );
  }

  /**
   * 获取最近的执行历史（按 startedAt 倒序，默认 50 条）。
   */
  async getRecentRuns(limit: number = 50): Promise<WorkflowRunEntity[]> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.orderByDesc('startedAt');
    predicates.limitAs(limit);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    return this.resultSetToEntities(resultSet);
  }

  /**
   * 获取指定工作流的执行历史（按 startedAt 倒序）。
   */
  async getRunsForWorkflow(workflowId: string): Promise<WorkflowRunEntity[]> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('workflowId', workflowId);
    predicates.orderByDesc('startedAt');
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    return this.resultSetToEntities(resultSet);
  }

  /**
   * 按 ID 获取单条执行历史。
   */
  async getRunById(id: string): Promise<WorkflowRunEntity | null> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.equalTo('id', id);
    const resultSet: relationalStore.ResultSet = await store.query(predicates, ['*']);
    const entities: WorkflowRunEntity[] = this.resultSetToEntities(resultSet);
    return entities.length > 0 ? entities[0] : null;
  }

  /**
   * 删除早于指定时间戳的历史记录，返回删除条数。
   */
  async deleteOlderThan(timestamp: number): Promise<number> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const predicates: relationalStore.RdbPredicates = new relationalStore.RdbPredicates(TABLE);
    predicates.lessThan('startedAt', timestamp);
    return await store.delete(predicates);
  }

  /**
   * 获取历史记录总数。
   */
  async getRunCount(): Promise<number> {
    const store: relationalStore.RdbStore = AppDatabase.getInstance().getStore();
    const resultSet: relationalStore.ResultSet =
      await store.querySql(`SELECT COUNT(*) AS cnt FROM ${TABLE}`);
    let count: number = 0;
    if (resultSet.goToNextRow()) {
      count = resultSet.getLong(resultSet.getColumnIndex('cnt'));
    }
    resultSet.close();
    return count;
  }

  // ── 映射工具 ──

  private resultSetToEntities(resultSet: relationalStore.ResultSet): WorkflowRunEntity[] {
    const entities: WorkflowRunEntity[] = [];
    while (resultSet.goToNextRow()) {
      entities.push(this.rowToEntity(resultSet));
    }
    resultSet.close();
    return entities;
  }

  private rowToEntity(rs: relationalStore.ResultSet): WorkflowRunEntity {
    const completedAtIdx: number = rs.getColumnIndex('completedAt');
    const errorIdx: number = rs.getColumnIndex('error');
    return {
      id: rs.getString(rs.getColumnIndex('id')),
      workflowId: rs.getString(rs.getColumnIndex('workflowId')),
      workflowName: rs.getString(rs.getColumnIndex('workflowName')),
      input: rs.getString(rs.getColumnIndex('input')),
      output: rs.getString(rs.getColumnIndex('output')),
      startedAt: rs.getLong(rs.getColumnIndex('startedAt')),
      completedAt: rs.isColumnNull(completedAtIdx) ? null : rs.getLong(completedAtIdx),
      status: rs.getString(rs.getColumnIndex('status')),
      failedNodeIdsJson: rs.getString(rs.getColumnIndex('failedNodeIdsJson')),
      error: rs.isColumnNull(errorIdx) ? null : rs.getString(errorIdx),
      logsJson: rs.getString(rs.getColumnIndex('logsJson'))
    };
  }

  private entityToValuesBucket(run: WorkflowRunEntity): relationalStore.ValuesBucket {
    return {
      'id': run.id,
      'workflowId': run.workflowId,
      'workflowName': run.workflowName,
      'input': run.input,
      'output': run.output,
      'startedAt': run.startedAt,
      'completedAt': run.completedAt,
      'status': run.status,
      'failedNodeIdsJson': run.failedNodeIdsJson,
      'error': run.error,
      'logsJson': run.logsJson
    };
  }
}

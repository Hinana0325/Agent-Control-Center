// WorkflowViewModel（对标 android features/workflow/WorkflowViewModel.kt）
// 连接 WorkflowEngine（DAG 执行 + 历史落库）与 UI。
// 回调驱动 @State：运行状态 / 日志 / 输出 / 历史列表。

import { Workflow, WorkflowEngine, WorkflowTemplates,
  WorkflowExecutionState } from 'common';
import { WorkflowRunEntity } from 'common';

export class WorkflowViewModel {
  private engine: WorkflowEngine = new WorkflowEngine();

  // UI 回调
  onRunningChanged?: (running: boolean) => void;
  onLogChanged?: (log: string) => void;
  onOutputChanged?: (output: string) => void;
  onRunsChanged?: (runs: WorkflowRunEntity[]) => void;

  async loadTemplates(): Promise<Workflow[]> {
    this.engine.onStateChanged = (state: WorkflowExecutionState): void => {
      if (this.onRunningChanged !== undefined) {
        this.onRunningChanged(state.isRunning);
      }
      if (this.onLogChanged !== undefined) {
        const logs: string[] = state.logs;
        const latest: string = logs.length > 0 ? logs[logs.length - 1] : '';
        this.onLogChanged(latest);
      }
      if (!state.isRunning && state.output.length > 0 && this.onOutputChanged !== undefined) {
        this.onOutputChanged(state.output);
      }
    };
    return WorkflowTemplates.allTemplates();
  }

  /** 加载执行历史（最近 50 条）。 */
  async loadHistory(): Promise<void> {
    const runs: WorkflowRunEntity[] = await this.engine.getHistory(null, 50);
    if (this.onRunsChanged !== undefined) {
      this.onRunsChanged(runs);
    }
  }

  /** 执行工作流并刷新历史。 */
  async execute(workflow: Workflow, input: string): Promise<void> {
    await this.engine.execute(workflow, input);
    await this.loadHistory();
  }
}

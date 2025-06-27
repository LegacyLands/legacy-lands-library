-- Indexes for task_results
CREATE INDEX IF NOT EXISTS idx_task_results_status ON task_results(status);
CREATE INDEX IF NOT EXISTS idx_task_results_method ON task_results(method);
CREATE INDEX IF NOT EXISTS idx_task_results_worker_id ON task_results(worker_id);
CREATE INDEX IF NOT EXISTS idx_task_results_created_at ON task_results(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_results_completed_at ON task_results(completed_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_results_updated_at ON task_results(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_results_queue_name ON task_results(queue_name);
CREATE INDEX IF NOT EXISTS idx_task_results_tags ON task_results USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_task_results_metadata ON task_results USING GIN(metadata);

-- Indexes for task_history
CREATE INDEX IF NOT EXISTS idx_task_history_task_id ON task_history(task_id);
CREATE INDEX IF NOT EXISTS idx_task_history_status ON task_history(status);
CREATE INDEX IF NOT EXISTS idx_task_history_executed_at ON task_history(executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_history_worker_id ON task_history(worker_id);
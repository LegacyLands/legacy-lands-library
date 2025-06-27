-- Task results table
CREATE TABLE IF NOT EXISTS task_results (
    task_id UUID PRIMARY KEY,
    method VARCHAR(255) NOT NULL,
    args JSONB NOT NULL,
    status VARCHAR(50) NOT NULL,
    result JSONB,
    error TEXT,
    worker_id VARCHAR(255),
    node_name VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}',
    priority INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    queue_name VARCHAR(255),
    tags TEXT[] NOT NULL DEFAULT '{}'
);

-- Task execution history table
CREATE TABLE IF NOT EXISTS task_history (
    task_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL,
    error TEXT,
    worker_id VARCHAR(255),
    worker_node VARCHAR(255),
    retry_attempt INTEGER NOT NULL DEFAULT 0,
    metrics JSONB NOT NULL DEFAULT '{}',
    PRIMARY KEY (task_id, execution_id),
    FOREIGN KEY (task_id) REFERENCES task_results(task_id) ON DELETE CASCADE
);
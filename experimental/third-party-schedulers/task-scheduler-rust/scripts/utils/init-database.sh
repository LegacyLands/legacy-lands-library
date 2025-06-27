#!/bin/bash
# Initialize the database with required tables

NAMESPACE=${NAMESPACE:-task-scheduler}

echo "🗃️ Initializing Task Scheduler Database"
echo "======================================"

# Get the migration SQL
MIGRATION_SQL=$(cat <<'EOF'
-- Create task_results table
CREATE TABLE IF NOT EXISTS task_results (
    task_id UUID PRIMARY KEY,
    method VARCHAR(255) NOT NULL,
    args JSONB NOT NULL,
    status VARCHAR(50) NOT NULL,
    result JSONB,
    error TEXT,
    worker_id VARCHAR(255),
    node_name VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    retry_count INTEGER DEFAULT 0,
    metadata JSONB DEFAULT '{}',
    priority INTEGER DEFAULT 50
);

-- Create indices for performance
CREATE INDEX IF NOT EXISTS idx_task_results_status ON task_results(status);
CREATE INDEX IF NOT EXISTS idx_task_results_method ON task_results(method);
CREATE INDEX IF NOT EXISTS idx_task_results_created_at ON task_results(created_at);
CREATE INDEX IF NOT EXISTS idx_task_results_worker_id ON task_results(worker_id);

-- Create task_history table for audit logs
CREATE TABLE IF NOT EXISTS task_history (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_data JSONB NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_task_history_task_id ON task_history(task_id);
CREATE INDEX IF NOT EXISTS idx_task_history_timestamp ON task_history(timestamp);

-- Create task_cancellations table
CREATE TABLE IF NOT EXISTS task_cancellations (
    task_id UUID PRIMARY KEY,
    cancellation_reason TEXT,
    cancelled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cancelled_by VARCHAR(255)
);

-- Create scheduled_tasks table
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    schedule_type VARCHAR(50) NOT NULL, -- 'delayed', 'cron', 'interval'
    schedule_spec TEXT NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_run_at TIMESTAMPTZ,
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_next_run ON scheduled_tasks(next_run_at) WHERE enabled = true;

-- Create task execution metrics view
CREATE OR REPLACE VIEW task_execution_metrics AS
SELECT 
    method,
    COUNT(*) as total_executions,
    COUNT(CASE WHEN status = 'completed' THEN 1 END) as successful_executions,
    COUNT(CASE WHEN status = 'failed' THEN 1 END) as failed_executions,
    AVG(duration_ms) FILTER (WHERE status = 'completed') as avg_duration_ms,
    MIN(duration_ms) FILTER (WHERE status = 'completed') as min_duration_ms,
    MAX(duration_ms) FILTER (WHERE status = 'completed') as max_duration_ms,
    AVG(retry_count) as avg_retry_count
FROM task_results
GROUP BY method;

-- Function to clean up old task results
CREATE OR REPLACE FUNCTION cleanup_old_task_results(retention_days INTEGER DEFAULT 30)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM task_results 
    WHERE completed_at < NOW() - INTERVAL '1 day' * retention_days
    AND status IN ('completed', 'failed', 'cancelled');
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO task_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO task_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO task_user;
EOF
)

# Run the migration
echo "Running database migrations..."
kubectl exec postgres-0 -n $NAMESPACE -- psql -U task_user -d task_scheduler <<< "$MIGRATION_SQL"

if [ $? -eq 0 ]; then
    echo "✅ Database initialized successfully!"
    
    # Show tables
    echo -e "\n📊 Database tables:"
    kubectl exec postgres-0 -n $NAMESPACE -- psql -U task_user -d task_scheduler -c "\dt"
    
    echo -e "\n📈 Views:"
    kubectl exec postgres-0 -n $NAMESPACE -- psql -U task_user -d task_scheduler -c "\dv"
else
    echo "❌ Failed to initialize database"
    exit 1
fi
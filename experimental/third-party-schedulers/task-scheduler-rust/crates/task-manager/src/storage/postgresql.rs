use async_trait::async_trait;
use sqlx::{postgres::PgPoolOptions, PgPool, Row};
use std::time::Duration;
use task_common::{
    error::{TaskError, TaskResult},
    models::{TaskInfo, TaskResult as TaskResultData, TaskStatus},
    Uuid,
};
use tracing::{debug, info};

use super::traits::{StorageBackend, StorageStats};

/// PostgreSQL storage backend for task management
pub struct PostgresqlStorage {
    pool: PgPool,
}

impl PostgresqlStorage {
    /// Create a new PostgreSQL storage backend
    pub async fn new(
        database_url: &str,
        max_connections: u32,
        min_connections: u32,
    ) -> TaskResult<Self> {
        info!("Connecting to PostgreSQL database");

        let pool = PgPoolOptions::new()
            .max_connections(max_connections)
            .min_connections(min_connections)
            .acquire_timeout(Duration::from_secs(30))
            .connect(database_url)
            .await
            .map_err(|e| TaskError::Storage(format!("Failed to connect to PostgreSQL: {}", e)))?;

        // Run migrations
        info!("Running database migrations");
        Self::run_migrations(&pool).await?;

        Ok(Self { pool })
    }

    /// Run database migrations
    async fn run_migrations(pool: &PgPool) -> TaskResult<()> {
        // Create tasks table
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS tasks (
                id UUID PRIMARY KEY,
                method VARCHAR(255) NOT NULL,
                args JSONB NOT NULL,
                dependencies UUID[] NOT NULL DEFAULT '{}',
                priority INTEGER NOT NULL DEFAULT 50,
                metadata JSONB NOT NULL DEFAULT '{}',
                status VARCHAR(50) NOT NULL,
                status_data JSONB NOT NULL DEFAULT '{}',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            "#,
        )
        .execute(pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to create tasks table: {}", e)))?;

        // Create task_results table
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS task_results (
                task_id UUID PRIMARY KEY,
                status VARCHAR(50) NOT NULL,
                result JSONB,
                error TEXT,
                metrics JSONB NOT NULL DEFAULT '{}',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            "#,
        )
        .execute(pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to create task_results table: {}", e)))?;

        // Create indexes - must be separate queries in PostgreSQL
        sqlx::query("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)")
            .execute(pool)
            .await
            .map_err(|e| TaskError::Storage(format!("Failed to create status index: {}", e)))?;
            
        sqlx::query("CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at)")
            .execute(pool)
            .await
            .map_err(|e| TaskError::Storage(format!("Failed to create created_at index: {}", e)))?;
            
        sqlx::query("CREATE INDEX IF NOT EXISTS idx_tasks_dependencies ON tasks USING GIN(dependencies)")
            .execute(pool)
            .await
            .map_err(|e| TaskError::Storage(format!("Failed to create dependencies index: {}", e)))?;

        info!("Database migrations completed successfully");
        Ok(())
    }
}

#[async_trait]
impl StorageBackend for PostgresqlStorage {
    async fn create_task(&self, task: &TaskInfo) -> TaskResult<()> {
        let status_json = serde_json::to_value(&task.status)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize status: {}", e)))?;
        
        let metadata_json = serde_json::to_value(&task.metadata)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize metadata: {}", e)))?;

        let args_json = serde_json::to_value(&task.args)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize args: {}", e)))?;

        let status_name = match &task.status {
            TaskStatus::Pending => "pending",
            TaskStatus::Queued => "queued",
            TaskStatus::WaitingDependencies => "waiting_dependencies",
            TaskStatus::Running { .. } => "running",
            TaskStatus::Succeeded { .. } => "succeeded",
            TaskStatus::Failed { .. } => "failed",
            TaskStatus::Cancelled { .. } => "cancelled",
        };

        sqlx::query(
            r#"
            INSERT INTO tasks (id, method, args, dependencies, priority, metadata, status, status_data, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            "#,
        )
        .bind(&task.id)
        .bind(&task.method)
        .bind(&args_json)
        .bind(&task.dependencies)
        .bind(task.priority)
        .bind(&metadata_json)
        .bind(status_name)
        .bind(&status_json)
        .bind(&task.created_at)
        .bind(&task.updated_at)
        .execute(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to create task: {}", e)))?;

        debug!("Created task {} in PostgreSQL", task.id);
        Ok(())
    }
    
    async fn create_tasks_batch(&self, tasks: &[TaskInfo]) -> TaskResult<()> {
        if tasks.is_empty() {
            return Ok(());
        }
        
        // Begin transaction for atomic batch insert
        let mut tx = self.pool.begin().await
            .map_err(|e| TaskError::Storage(format!("Failed to begin transaction: {}", e)))?;
        
        // Prepare batch values
        let mut query = String::from(
            "INSERT INTO tasks (id, method, args, dependencies, priority, metadata, status, status_data, created_at, updated_at) VALUES "
        );
        let mut values = Vec::new();
        let mut bind_index = 1;
        
        for (i, task) in tasks.iter().enumerate() {
            if i > 0 {
                query.push_str(", ");
            }
            
            // Add placeholders for this task
            query.push_str(&format!(
                "(${}, ${}, ${}, ${}, ${}, ${}, ${}, ${}, ${}, ${})",
                bind_index, bind_index + 1, bind_index + 2, bind_index + 3, bind_index + 4,
                bind_index + 5, bind_index + 6, bind_index + 7, bind_index + 8, bind_index + 9
            ));
            bind_index += 10;
            
            // Serialize values
            let status_json = serde_json::to_value(&task.status)
                .map_err(|e| TaskError::Storage(format!("Failed to serialize status: {}", e)))?;
            let metadata_json = serde_json::to_value(&task.metadata)
                .map_err(|e| TaskError::Storage(format!("Failed to serialize metadata: {}", e)))?;
            let args_json = serde_json::to_value(&task.args)
                .map_err(|e| TaskError::Storage(format!("Failed to serialize args: {}", e)))?;
            
            let status_name = match &task.status {
                TaskStatus::Pending => "pending",
                TaskStatus::Queued => "queued",
                TaskStatus::WaitingDependencies => "waiting_dependencies",
                TaskStatus::Running { .. } => "running",
                TaskStatus::Succeeded { .. } => "succeeded",
                TaskStatus::Failed { .. } => "failed",
                TaskStatus::Cancelled { .. } => "cancelled",
            };
            
            values.push((
                &task.id,
                &task.method,
                args_json,
                &task.dependencies,
                task.priority,
                metadata_json,
                status_name,
                status_json,
                &task.created_at,
                &task.updated_at,
            ));
        }
        
        // Execute batch insert using raw SQL
        let mut query_builder = sqlx::query(&query);
        for (id, method, args, deps, priority, metadata, status_name, status_data, created_at, updated_at) in &values {
            query_builder = query_builder
                .bind(id)
                .bind(method)
                .bind(args)
                .bind(deps)
                .bind(priority)
                .bind(metadata)
                .bind(status_name)
                .bind(status_data)
                .bind(created_at)
                .bind(updated_at);
        }
        
        query_builder.execute(&mut *tx).await
            .map_err(|e| TaskError::Storage(format!("Failed to batch insert tasks: {}", e)))?;
        
        // Commit transaction
        tx.commit().await
            .map_err(|e| TaskError::Storage(format!("Failed to commit batch insert: {}", e)))?;
        
        debug!("Batch created {} tasks in PostgreSQL", tasks.len());
        Ok(())
    }

    async fn get_task(&self, task_id: Uuid) -> TaskResult<Option<TaskInfo>> {
        let row = sqlx::query(
            r#"
            SELECT id, method, args, dependencies, priority, metadata, status, status_data, created_at, updated_at
            FROM tasks
            WHERE id = $1
            "#,
        )
        .bind(&task_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to get task: {}", e)))?;

        if let Some(row) = row {
            let status: TaskStatus = serde_json::from_value(row.get("status_data"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize status: {}", e)))?;

            let metadata = serde_json::from_value(row.get("metadata"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize metadata: {}", e)))?;

            let args = serde_json::from_value(row.get("args"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize args: {}", e)))?;

            let task = TaskInfo {
                id: row.get("id"),
                method: row.get("method"),
                args,
                dependencies: row.get("dependencies"),
                priority: row.get("priority"),
                metadata,
                status,
                created_at: row.get("created_at"),
                updated_at: row.get("updated_at"),
            };

            Ok(Some(task))
        } else {
            Ok(None)
        }
    }

    async fn update_task_status(&self, task_id: Uuid, status: TaskStatus) -> TaskResult<()> {
        let status_json = serde_json::to_value(&status)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize status: {}", e)))?;

        let status_name = match &status {
            TaskStatus::Pending => "pending",
            TaskStatus::Queued => "queued",
            TaskStatus::WaitingDependencies => "waiting_dependencies",
            TaskStatus::Running { .. } => "running",
            TaskStatus::Succeeded { .. } => "succeeded",
            TaskStatus::Failed { .. } => "failed",
            TaskStatus::Cancelled { .. } => "cancelled",
        };

        sqlx::query(
            r#"
            UPDATE tasks
            SET status = $1, status_data = $2, updated_at = NOW()
            WHERE id = $3
            "#,
        )
        .bind(status_name)
        .bind(&status_json)
        .bind(&task_id)
        .execute(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to update task status: {}", e)))?;

        debug!("Updated task {} status in PostgreSQL", task_id);
        Ok(())
    }

    async fn update_tasks_status_batch(&self, task_ids: &[Uuid], status: TaskStatus) -> TaskResult<()> {
        if task_ids.is_empty() {
            return Ok(());
        }
        
        let status_json = serde_json::to_value(&status)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize status: {}", e)))?;
        
        let status_name = match &status {
            TaskStatus::Pending => "pending",
            TaskStatus::Queued => "queued",
            TaskStatus::WaitingDependencies => "waiting_dependencies",  
            TaskStatus::Running { .. } => "running",
            TaskStatus::Succeeded { .. } => "succeeded",
            TaskStatus::Failed { .. } => "failed",
            TaskStatus::Cancelled { .. } => "cancelled",
        };
        
        let updated_at = task_common::Utc::now();
        
        // Use ANY() for batch update
        let result = sqlx::query(
            r#"
            UPDATE tasks
            SET status = $1, status_data = $2, updated_at = $3
            WHERE id = ANY($4)
            "#,
        )
        .bind(status_name)
        .bind(&status_json)
        .bind(&updated_at)
        .bind(&task_ids)
        .execute(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to batch update task statuses: {}", e)))?;
        
        let rows_affected = result.rows_affected();
        debug!("Batch updated {} task statuses to {:?} in PostgreSQL", rows_affected, status);
        
        if rows_affected != task_ids.len() as u64 {
            debug!("Warning: Expected to update {} tasks but only updated {}", task_ids.len(), rows_affected);
        }
        
        Ok(())
    }

    async fn update_task(&self, task: &TaskInfo) -> TaskResult<()> {
        let status_json = serde_json::to_value(&task.status)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize status: {}", e)))?;
        
        let metadata_json = serde_json::to_value(&task.metadata)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize metadata: {}", e)))?;

        let args_json = serde_json::to_value(&task.args)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize args: {}", e)))?;

        let status_name = match &task.status {
            TaskStatus::Pending => "pending",
            TaskStatus::Queued => "queued",
            TaskStatus::WaitingDependencies => "waiting_dependencies",
            TaskStatus::Running { .. } => "running",
            TaskStatus::Succeeded { .. } => "succeeded",
            TaskStatus::Failed { .. } => "failed",
            TaskStatus::Cancelled { .. } => "cancelled",
        };

        sqlx::query(
            r#"
            UPDATE tasks
            SET method = $1, args = $2, dependencies = $3, priority = $4, 
                metadata = $5, status = $6, status_data = $7, updated_at = $8
            WHERE id = $9
            "#,
        )
        .bind(&task.method)
        .bind(&args_json)
        .bind(&task.dependencies)
        .bind(task.priority)
        .bind(&metadata_json)
        .bind(status_name)
        .bind(&status_json)
        .bind(&task.updated_at)
        .bind(&task.id)
        .execute(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to update task: {}", e)))?;

        debug!("Updated task {} in PostgreSQL", task.id);
        Ok(())
    }

    async fn store_task_result(&self, result: &TaskResultData) -> TaskResult<()> {
        let metrics_json = serde_json::to_value(&result.metrics)
            .map_err(|e| TaskError::Storage(format!("Failed to serialize metrics: {}", e)))?;

        let result_json = result.result.as_ref()
            .map(|r| serde_json::to_value(r))
            .transpose()
            .map_err(|e| TaskError::Storage(format!("Failed to serialize result: {}", e)))?;

        let status_name = match &result.status {
            TaskStatus::Pending => "pending",
            TaskStatus::Queued => "queued",
            TaskStatus::WaitingDependencies => "waiting_dependencies",
            TaskStatus::Running { .. } => "running",
            TaskStatus::Succeeded { .. } => "succeeded",
            TaskStatus::Failed { .. } => "failed",
            TaskStatus::Cancelled { .. } => "cancelled",
        };

        sqlx::query(
            r#"
            INSERT INTO task_results (task_id, status, result, error, metrics)
            VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (task_id) DO UPDATE
            SET status = $2, result = $3, error = $4, metrics = $5
            "#,
        )
        .bind(&result.task_id)
        .bind(status_name)
        .bind(&result_json)
        .bind(&result.error)
        .bind(&metrics_json)
        .execute(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to store task result: {}", e)))?;

        debug!("Stored result for task {} in PostgreSQL", result.task_id);
        Ok(())
    }

    async fn get_task_result(&self, task_id: Uuid) -> TaskResult<Option<TaskResultData>> {
        let row = sqlx::query(
            r#"
            SELECT tr.task_id, tr.status, tr.result, tr.error, tr.metrics, t.status_data
            FROM task_results tr
            JOIN tasks t ON t.id = tr.task_id
            WHERE tr.task_id = $1
            "#,
        )
        .bind(&task_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to get task result: {}", e)))?;

        if let Some(row) = row {
            let status: TaskStatus = serde_json::from_value(row.get("status_data"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize status: {}", e)))?;

            let metrics = serde_json::from_value(row.get("metrics"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize metrics: {}", e)))?;

            let result_string: Option<String> = row.get("result");

            let result = TaskResultData {
                task_id: row.get("task_id"),
                status,
                result: result_string,
                error: row.get("error"),
                metrics,
            };

            Ok(Some(result))
        } else {
            Ok(None)
        }
    }

    async fn list_tasks(
        &self,
        status_filter: Option<TaskStatus>,
        limit: usize,
    ) -> TaskResult<Vec<TaskInfo>> {
        let query = if let Some(status) = status_filter {
            let status_name = match status {
                TaskStatus::Pending => "pending",
                TaskStatus::Queued => "queued",
                TaskStatus::WaitingDependencies => "waiting_dependencies",
                TaskStatus::Running { .. } => "running",
                TaskStatus::Succeeded { .. } => "succeeded",
                TaskStatus::Failed { .. } => "failed",
                TaskStatus::Cancelled { .. } => "cancelled",
            };

            sqlx::query(
                r#"
                SELECT id, method, args, dependencies, priority, metadata, status, status_data, created_at, updated_at
                FROM tasks
                WHERE status = $1
                ORDER BY created_at DESC
                LIMIT $2
                "#,
            )
            .bind(status_name)
            .bind(limit as i64)
        } else {
            sqlx::query(
                r#"
                SELECT id, method, args, dependencies, priority, metadata, status, status_data, created_at, updated_at
                FROM tasks
                ORDER BY created_at DESC
                LIMIT $1
                "#,
            )
            .bind(limit as i64)
        };

        let rows = query
            .fetch_all(&self.pool)
            .await
            .map_err(|e| TaskError::Storage(format!("Failed to list tasks: {}", e)))?;

        let mut tasks = Vec::new();
        for row in rows {
            let status: TaskStatus = serde_json::from_value(row.get("status_data"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize status: {}", e)))?;

            let metadata = serde_json::from_value(row.get("metadata"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize metadata: {}", e)))?;

            let args = serde_json::from_value(row.get("args"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize args: {}", e)))?;

            let task = TaskInfo {
                id: row.get("id"),
                method: row.get("method"),
                args,
                dependencies: row.get("dependencies"),
                priority: row.get("priority"),
                metadata,
                status,
                created_at: row.get("created_at"),
                updated_at: row.get("updated_at"),
            };

            tasks.push(task);
        }

        Ok(tasks)
    }

    async fn get_tasks_by_dependency(&self, dependency_id: Uuid) -> TaskResult<Vec<TaskInfo>> {
        let rows = sqlx::query(
            r#"
            SELECT id, method, args, dependencies, priority, metadata, status, status_data, created_at, updated_at
            FROM tasks
            WHERE $1 = ANY(dependencies)
            ORDER BY created_at DESC
            "#,
        )
        .bind(&dependency_id)
        .fetch_all(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to get tasks by dependency: {}", e)))?;

        let mut tasks = Vec::new();
        for row in rows {
            let status: TaskStatus = serde_json::from_value(row.get("status_data"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize status: {}", e)))?;

            let metadata = serde_json::from_value(row.get("metadata"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize metadata: {}", e)))?;

            let args = serde_json::from_value(row.get("args"))
                .map_err(|e| TaskError::Storage(format!("Failed to deserialize args: {}", e)))?;

            let task = TaskInfo {
                id: row.get("id"),
                method: row.get("method"),
                args,
                dependencies: row.get("dependencies"),
                priority: row.get("priority"),
                metadata,
                status,
                created_at: row.get("created_at"),
                updated_at: row.get("updated_at"),
            };

            tasks.push(task);
        }

        Ok(tasks)
    }

    async fn delete_task(&self, task_id: Uuid) -> TaskResult<()> {
        // Delete from task_results first (foreign key constraint)
        sqlx::query("DELETE FROM task_results WHERE task_id = $1")
            .bind(&task_id)
            .execute(&self.pool)
            .await
            .map_err(|e| TaskError::Storage(format!("Failed to delete task result: {}", e)))?;

        // Then delete from tasks
        sqlx::query("DELETE FROM tasks WHERE id = $1")
            .bind(&task_id)
            .execute(&self.pool)
            .await
            .map_err(|e| TaskError::Storage(format!("Failed to delete task: {}", e)))?;

        debug!("Deleted task {} from PostgreSQL", task_id);
        Ok(())
    }

    async fn cleanup_old_results(&self, retention_seconds: u64) -> TaskResult<usize> {
        let result = sqlx::query(
            r#"
            DELETE FROM task_results
            WHERE created_at < NOW() - INTERVAL '$1 seconds'
            "#,
        )
        .bind(retention_seconds as i64)
        .execute(&self.pool)
        .await
        .map_err(|e| TaskError::Storage(format!("Failed to cleanup old results: {}", e)))?;
        
        let deleted_count = result.rows_affected() as usize;
        info!("Cleaned up {} old task results from PostgreSQL", deleted_count);
        Ok(deleted_count)
    }

    async fn get_stats(&self) -> StorageStats {
        let total_tasks = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM tasks")
            .fetch_one(&self.pool)
            .await
            .unwrap_or(0) as usize;

        let total_results = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM task_results")
            .fetch_one(&self.pool)
            .await
            .unwrap_or(0) as usize;

        StorageStats {
            total_tasks,
            total_results,
            cache_size: 0, // Not applicable for PostgreSQL
        }
    }
}
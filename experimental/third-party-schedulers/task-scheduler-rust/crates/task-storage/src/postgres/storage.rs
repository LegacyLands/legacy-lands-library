use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde_json::Value;
use sqlx::{postgres::PgPoolOptions, PgPool, Row};
use std::collections::HashMap;
use std::time::Duration;
use tracing::{debug, info, instrument};
use uuid::Uuid;

use crate::{
    config::PostgresConfig,
    models::{
        PageInfo, StoredTaskResult, TaskExecutionHistory, TaskQuery,
        TaskStatus,
    },
    traits::{StorageError, StorageResult, TaskStorage},
};

/// PostgreSQL storage implementation
pub struct PostgresStorage {
    pool: PgPool,
}

impl PostgresStorage {
    /// Create new PostgreSQL storage
    pub async fn new(config: &PostgresConfig) -> StorageResult<Self> {
        info!("Connecting to PostgreSQL database");

        let pool = PgPoolOptions::new()
            .max_connections(config.max_connections)
            .acquire_timeout(Duration::from_secs(config.connect_timeout_seconds))
            .connect(&config.url)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        if config.run_migrations {
            info!("Running database migrations");
            super::migrations::run_migrations(&pool)
                .await
                .map_err(|e| StorageError::Database(e.to_string()))?;
        }

        Ok(Self { pool })
    }
}

#[async_trait]
impl TaskStorage for PostgresStorage {
    #[instrument(skip(self, result))]
    async fn store_result(&self, result: StoredTaskResult) -> StorageResult<()> {
        let query = r#"
            INSERT INTO task_results (
                task_id, method, args, status, result, error,
                worker_id, node_name, created_at, started_at, completed_at,
                duration_ms, retry_count, metadata, priority, updated_at,
                queue_name, tags
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18)
            ON CONFLICT (task_id) DO UPDATE SET
                status = EXCLUDED.status,
                result = EXCLUDED.result,
                error = EXCLUDED.error,
                started_at = COALESCE(task_results.started_at, EXCLUDED.started_at),
                completed_at = EXCLUDED.completed_at,
                duration_ms = EXCLUDED.duration_ms,
                retry_count = EXCLUDED.retry_count,
                updated_at = EXCLUDED.updated_at
        "#;

        let status_str = match result.status {
            TaskStatus::Pending => "pending",
            TaskStatus::Queued => "queued",
            TaskStatus::Running => "running",
            TaskStatus::Succeeded => "succeeded",
            TaskStatus::Failed => "failed",
            TaskStatus::Cancelled => "cancelled",
        };

        sqlx::query(query)
            .bind(result.task_id)
            .bind(&result.method)
            .bind(serde_json::to_value(&result.args).unwrap())
            .bind(status_str)
            .bind(&result.result)
            .bind(&result.error)
            .bind(&result.worker_id)
            .bind(&result.node_name)
            .bind(result.created_at)
            .bind(result.started_at)
            .bind(result.completed_at)
            .bind(result.duration_ms.map(|d| d as i64))
            .bind(result.retry_count as i32)
            .bind(serde_json::to_value(&result.metadata).unwrap())
            .bind(result.priority)
            .bind(result.updated_at)
            .bind(&result.queue_name)
            .bind(&result.tags)
            .execute(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        debug!("Stored result for task {}", result.task_id);
        Ok(())
    }

    #[instrument(skip(self))]
    async fn get_result(&self, task_id: Uuid) -> StorageResult<StoredTaskResult> {
        let query = r#"
            SELECT task_id, method, args, status, result, error,
                   worker_id, node_name, created_at, started_at, completed_at,
                   duration_ms, retry_count, metadata, priority, updated_at,
                   queue_name, tags
            FROM task_results
            WHERE task_id = $1
        "#;

        let row = sqlx::query(query)
            .bind(task_id)
            .fetch_optional(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?
            .ok_or(StorageError::NotFound)?;

        let status_str: String = row.get("status");
        let status = match status_str.as_str() {
            "pending" => TaskStatus::Pending,
            "queued" => TaskStatus::Queued,
            "running" => TaskStatus::Running,
            "succeeded" => TaskStatus::Succeeded,
            "failed" => TaskStatus::Failed,
            "cancelled" => TaskStatus::Cancelled,
            _ => {
                return Err(StorageError::Database(format!(
                    "Unknown status: {}",
                    status_str
                )))
            }
        };

        let duration_ms: Option<i64> = row.get("duration_ms");
        let metadata: Value = row.get("metadata");
        let args_value: Value = row.get("args");

        Ok(StoredTaskResult {
            task_id: row.get("task_id"),
            method: row.get("method"),
            args: serde_json::from_value(args_value).unwrap_or_default(),
            status,
            result: row.get("result"),
            error: row.get("error"),
            worker_id: row.get("worker_id"),
            node_name: row.get("node_name"),
            created_at: row.get("created_at"),
            started_at: row.get("started_at"),
            completed_at: row.get("completed_at"),
            duration_ms: duration_ms.map(|d| d as u64),
            retry_count: row.get::<i32, _>("retry_count") as u32,
            metadata: serde_json::from_value(metadata).unwrap_or_default(),
            priority: row.get("priority"),
            updated_at: row.get("updated_at"),
            queue_name: row.get("queue_name"),
            tags: row.get("tags"),
        })
    }

    #[instrument(skip(self))]
    async fn query_results(
        &self,
        query: TaskQuery,
        page_info: PageInfo,
    ) -> StorageResult<(Vec<StoredTaskResult>, u64)> {
        let mut conditions = Vec::new();
        let mut bind_count = 0;

        if let Some(_status) = &query.status {
            bind_count += 1;
            conditions.push(format!("status = ${}", bind_count));
        }

        if let Some(_method) = &query.method {
            bind_count += 1;
            conditions.push(format!("method = ${}", bind_count));
        }

        if let Some(_worker_id) = &query.worker_id {
            bind_count += 1;
            conditions.push(format!("worker_id = ${}", bind_count));
        }

        if let Some(_node_name) = &query.node_name {
            bind_count += 1;
            conditions.push(format!("node_name = ${}", bind_count));
        }

        if let Some(_created_after) = &query.created_after {
            bind_count += 1;
            conditions.push(format!("created_at >= ${}", bind_count));
        }

        if let Some(_created_before) = &query.created_before {
            bind_count += 1;
            conditions.push(format!("created_at <= ${}", bind_count));
        }

        if let Some(_queue_name) = &query.queue_name {
            bind_count += 1;
            conditions.push(format!("queue_name = ${}", bind_count));
        }

        if !query.tags.is_empty() {
            bind_count += 1;
            conditions.push(format!("tags && ${}", bind_count));
        }

        // Add metadata filtering
        for (key, _) in &query.metadata {
            bind_count += 1;
            conditions.push(format!("metadata->>'{}' = ${}", key, bind_count));
        }

        let where_clause = if conditions.is_empty() {
            String::new()
        } else {
            format!("WHERE {}", conditions.join(" AND "))
        };

        // Count total results
        let count_query = format!("SELECT COUNT(*) FROM task_results {}", where_clause);
        let mut count_query_builder = sqlx::query_scalar::<_, i64>(&count_query);

        // Bind parameters for count query
        if let Some(status) = &query.status {
            let status_str = match status {
                TaskStatus::Pending => "pending",
                TaskStatus::Queued => "queued",
                TaskStatus::Running => "running",
                TaskStatus::Succeeded => "succeeded",
                TaskStatus::Failed => "failed",
                TaskStatus::Cancelled => "cancelled",
            };
            count_query_builder = count_query_builder.bind(status_str);
        }

        if let Some(method) = &query.method {
            count_query_builder = count_query_builder.bind(method);
        }

        if let Some(worker_id) = &query.worker_id {
            count_query_builder = count_query_builder.bind(worker_id);
        }

        if let Some(node_name) = &query.node_name {
            count_query_builder = count_query_builder.bind(node_name);
        }

        if let Some(created_after) = &query.created_after {
            count_query_builder = count_query_builder.bind(created_after);
        }

        if let Some(created_before) = &query.created_before {
            count_query_builder = count_query_builder.bind(created_before);
        }

        if let Some(queue_name) = &query.queue_name {
            count_query_builder = count_query_builder.bind(queue_name);
        }

        if !query.tags.is_empty() {
            count_query_builder = count_query_builder.bind(&query.tags);
        }

        // Bind metadata values for count query
        for (_, value) in &query.metadata {
            count_query_builder = count_query_builder.bind(value);
        }

        let total_count = count_query_builder
            .fetch_one(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        // Query results with pagination
        let sort_field = query.sort_by.as_deref().unwrap_or("created_at");
        let sort_order = if query.sort_ascending { "ASC" } else { "DESC" };

        let results_query = format!(
            r#"
            SELECT task_id, method, args, status, result, error,
                   worker_id, node_name, created_at, started_at, completed_at,
                   duration_ms, retry_count, metadata, priority, updated_at,
                   queue_name, tags
            FROM task_results
            {}
            ORDER BY {} {}
            LIMIT ${}
            OFFSET ${}
            "#,
            where_clause,
            sort_field,
            sort_order,
            bind_count + 1,
            bind_count + 2
        );

        let mut results_query_builder = sqlx::query(&results_query);

        // Bind parameters again for results query
        if let Some(status) = &query.status {
            let status_str = match status {
                TaskStatus::Pending => "pending",
                TaskStatus::Queued => "queued",
                TaskStatus::Running => "running",
                TaskStatus::Succeeded => "succeeded",
                TaskStatus::Failed => "failed",
                TaskStatus::Cancelled => "cancelled",
            };
            results_query_builder = results_query_builder.bind(status_str);
        }

        if let Some(method) = &query.method {
            results_query_builder = results_query_builder.bind(method);
        }

        if let Some(worker_id) = &query.worker_id {
            results_query_builder = results_query_builder.bind(worker_id);
        }

        if let Some(node_name) = &query.node_name {
            results_query_builder = results_query_builder.bind(node_name);
        }

        if let Some(created_after) = &query.created_after {
            results_query_builder = results_query_builder.bind(created_after);
        }

        if let Some(created_before) = &query.created_before {
            results_query_builder = results_query_builder.bind(created_before);
        }

        if let Some(queue_name) = &query.queue_name {
            results_query_builder = results_query_builder.bind(queue_name);
        }

        if !query.tags.is_empty() {
            results_query_builder = results_query_builder.bind(&query.tags);
        }

        // Bind metadata values for results query
        for (_, value) in &query.metadata {
            results_query_builder = results_query_builder.bind(value);
        }

        // Add pagination parameters
        results_query_builder = results_query_builder
            .bind(page_info.page_size as i64)
            .bind((page_info.page * page_info.page_size) as i64);

        let rows = results_query_builder
            .fetch_all(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        let results = rows
            .into_iter()
            .map(|row| {
                let status_str: String = row.get("status");
                let status = match status_str.as_str() {
                    "pending" => TaskStatus::Pending,
                    "queued" => TaskStatus::Queued,
                    "running" => TaskStatus::Running,
                    "succeeded" => TaskStatus::Succeeded,
                    "failed" => TaskStatus::Failed,
                    "cancelled" => TaskStatus::Cancelled,
                    _ => TaskStatus::Failed,
                };

                let duration_ms: Option<i64> = row.get("duration_ms");
                let metadata: Value = row.get("metadata");
                let args_value: Value = row.get("args");

                StoredTaskResult {
                    task_id: row.get("task_id"),
                    method: row.get("method"),
                    args: serde_json::from_value(args_value).unwrap_or_default(),
                    status,
                    result: row.get("result"),
                    error: row.get("error"),
                    worker_id: row.get("worker_id"),
                    node_name: row.get("node_name"),
                    created_at: row.get("created_at"),
                    started_at: row.get("started_at"),
                    completed_at: row.get("completed_at"),
                    duration_ms: duration_ms.map(|d| d as u64),
                    retry_count: row.get::<i32, _>("retry_count") as u32,
                    metadata: serde_json::from_value(metadata).unwrap_or_default(),
                    priority: row.get("priority"),
                    updated_at: row.get("updated_at"),
                    queue_name: row.get("queue_name"),
                    tags: row.get("tags"),
                }
            })
            .collect();

        Ok((results, total_count as u64))
    }

    #[instrument(skip(self))]
    async fn cleanup_results(&self, older_than: DateTime<Utc>) -> StorageResult<u64> {
        let query = "DELETE FROM task_results WHERE completed_at < $1";

        let result = sqlx::query(query)
            .bind(older_than)
            .execute(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        Ok(result.rows_affected())
    }

    #[instrument(skip(self, history))]
    async fn store_history(&self, history: TaskExecutionHistory) -> StorageResult<()> {
        let query = r#"
            INSERT INTO task_history (
                task_id, execution_id, status, executed_at, completed_at,
                duration_ms, error, worker_id, worker_node, retry_attempt, metrics
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
        "#;

        let status_str = match history.status {
            TaskStatus::Pending => "pending",
            TaskStatus::Queued => "queued",
            TaskStatus::Running => "running",
            TaskStatus::Succeeded => "succeeded",
            TaskStatus::Failed => "failed",
            TaskStatus::Cancelled => "cancelled",
        };

        sqlx::query(query)
            .bind(history.task_id)
            .bind(history.execution_id)
            .bind(status_str)
            .bind(history.executed_at)
            .bind(history.completed_at)
            .bind(history.duration_ms as i64)
            .bind(&history.error)
            .bind(&history.worker_id)
            .bind(&history.worker_node)
            .bind(history.retry_attempt as i32)
            .bind(serde_json::to_value(&history.metrics).unwrap())
            .execute(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        Ok(())
    }

    #[instrument(skip(self))]
    async fn get_history(
        &self,
        task_id: Uuid,
        limit: Option<u32>,
    ) -> StorageResult<Vec<TaskExecutionHistory>> {
        let query = r#"
            SELECT task_id, execution_id, status, executed_at, completed_at,
                   duration_ms, error, worker_id, worker_node, retry_attempt, metrics
            FROM task_history
            WHERE task_id = $1
            ORDER BY executed_at DESC
            LIMIT $2
        "#;

        let rows = sqlx::query(query)
            .bind(task_id)
            .bind(limit.unwrap_or(100) as i64)
            .fetch_all(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        let history = rows
            .into_iter()
            .map(|row| {
                let status_str: String = row.get("status");
                let status = match status_str.as_str() {
                    "pending" => TaskStatus::Pending,
                    "queued" => TaskStatus::Queued,
                    "running" => TaskStatus::Running,
                    "succeeded" => TaskStatus::Succeeded,
                    "failed" => TaskStatus::Failed,
                    "cancelled" => TaskStatus::Cancelled,
                    _ => TaskStatus::Failed,
                };

                let metrics: Value = row.get("metrics");

                TaskExecutionHistory {
                    task_id: row.get("task_id"),
                    execution_id: row.get("execution_id"),
                    status,
                    executed_at: row.get("executed_at"),
                    completed_at: row.get("completed_at"),
                    duration_ms: row.get::<i64, _>("duration_ms") as u64,
                    error: row.get("error"),
                    worker_id: row.get("worker_id"),
                    worker_node: row.get("worker_node"),
                    retry_attempt: row.get::<i32, _>("retry_attempt") as u32,
                    metrics: serde_json::from_value(metrics).unwrap_or_default(),
                }
            })
            .collect();

        Ok(history)
    }

    #[instrument(skip(self))]
    async fn get_statistics(
        &self,
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
        group_by: Option<String>,
    ) -> StorageResult<HashMap<String, Value>> {
        let group_field = group_by.as_deref().unwrap_or("status");

        // Validate group_by field
        if !["status", "method", "worker_id", "node_name"].contains(&group_field) {
            return Err(StorageError::InvalidQuery(format!(
                "Invalid group_by field: {}",
                group_field
            )));
        }

        let query = format!(
            r#"
            SELECT 
                {} as group_key,
                COUNT(*) as count,
                COUNT(CASE WHEN status = 'succeeded' THEN 1 END) as succeeded,
                COUNT(CASE WHEN status = 'failed' THEN 1 END) as failed,
                AVG(duration_ms)::FLOAT8 as avg_duration_ms,
                MIN(duration_ms) as min_duration_ms,
                MAX(duration_ms) as max_duration_ms,
                PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY duration_ms)::FLOAT8 as median_duration_ms,
                PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)::FLOAT8 as p95_duration_ms,
                PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY duration_ms)::FLOAT8 as p99_duration_ms
            FROM task_results
            WHERE created_at >= $1 AND created_at <= $2
            GROUP BY {}
            ORDER BY count DESC
            "#,
            group_field, group_field
        );

        let rows = sqlx::query(&query)
            .bind(start_time)
            .bind(end_time)
            .fetch_all(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        let mut stats = HashMap::new();
        let groups: Vec<Value> = rows
            .into_iter()
            .map(|row| {
                let group_key: String = row.get("group_key");
                serde_json::json!({
                    "group": group_key,
                    "count": row.get::<i64, _>("count"),
                    "succeeded": row.get::<i64, _>("succeeded"),
                    "failed": row.get::<i64, _>("failed"),
                    "avg_duration_ms": row.get::<Option<f64>, _>("avg_duration_ms"),
                    "min_duration_ms": row.get::<Option<i64>, _>("min_duration_ms"),
                    "max_duration_ms": row.get::<Option<i64>, _>("max_duration_ms"),
                    "median_duration_ms": row.get::<Option<f64>, _>("median_duration_ms"),
                    "p95_duration_ms": row.get::<Option<f64>, _>("p95_duration_ms"),
                    "p99_duration_ms": row.get::<Option<f64>, _>("p99_duration_ms"),
                })
            })
            .collect();

        stats.insert("groups".to_string(), Value::Array(groups));
        stats.insert("start_time".to_string(), serde_json::json!(start_time));
        stats.insert("end_time".to_string(), serde_json::json!(end_time));
        stats.insert("group_by".to_string(), serde_json::json!(group_field));

        Ok(stats)
    }

    #[instrument(skip(self))]
    async fn health_check(&self) -> StorageResult<()> {
        sqlx::query("SELECT 1")
            .fetch_one(&self.pool)
            .await
            .map_err(|e| StorageError::Database(e.to_string()))?;

        Ok(())
    }
}

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use std::collections::HashMap;

/// Task status enum
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
}

impl Default for TaskStatus {
    fn default() -> Self {
        Self::Pending
    }
}

/// Task result structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    pub task_id: String,
    pub status: TaskStatus,
    pub result: Option<JsonValue>,
    pub error: Option<String>,
    pub execution_time_ms: i64,
    pub worker_id: String,
    pub completed_at: DateTime<Utc>,
    pub metadata: HashMap<String, String>,
}

/// Execution history entry
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionHistory {
    pub task_id: String,
    pub event_type: String,
    pub timestamp: DateTime<Utc>,
    pub worker_id: Option<String>,
    pub details: Option<String>,
    pub metadata: HashMap<String, String>,
}

/// Query filter for task results
#[derive(Debug, Clone, Default)]
pub struct QueryFilter {
    pub status: Option<TaskStatus>,
    pub worker_id: Option<String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub metadata: HashMap<String, String>,
    pub order_by: Option<String>,
    pub ascending: Option<bool>,
    pub limit: Option<usize>,
    pub offset: Option<usize>,
}

/// Storage error types
#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("Connection error: {0}")]
    ConnectionError(String),
    
    #[error("Query error: {0}")]
    QueryError(String),
    
    #[error("Serialization error: {0}")]
    SerializationError(String),
    
    #[error("Task not found: {0}")]
    TaskNotFound(String),
    
    #[error("Duplicate task: {0}")]
    DuplicateTask(String),
}

pub type StorageResult<T> = Result<T, StorageError>;

/// Storage trait for task management
#[async_trait]
pub trait Storage: Send + Sync {
    /// Create a new task
    async fn create_task(
        &self,
        task_id: &str,
        method: &str,
        args: Vec<JsonValue>,
        metadata: HashMap<String, String>,
    ) -> StorageResult<()>;

    /// Update task status
    async fn update_task_status(
        &self,
        task_id: &str,
        status: TaskStatus,
        worker_id: Option<&str>,
    ) -> StorageResult<()>;

    /// Get task status
    async fn get_task_status(&self, task_id: &str) -> StorageResult<TaskStatus>;

    /// Store task result
    async fn store_task_result(&self, result: TaskResult) -> StorageResult<()>;

    /// Get task result
    async fn get_task_result(&self, task_id: &str) -> StorageResult<Option<TaskResult>>;

    /// Query task results with filters
    async fn query_results(&self, filter: QueryFilter) -> StorageResult<Vec<TaskResult>>;

    /// Store execution history
    async fn store_execution_history(&self, history: ExecutionHistory) -> StorageResult<()>;

    /// Get execution history for a task
    async fn get_execution_history(&self, task_id: &str) -> StorageResult<Vec<ExecutionHistory>>;

    /// Cleanup old results
    async fn cleanup_old_results(&self, older_than: DateTime<Utc>) -> StorageResult<u64>;

    /// Get storage statistics
    async fn get_statistics(&self) -> StorageResult<HashMap<String, serde_json::Value>>;

    /// Health check
    async fn health_check(&self) -> StorageResult<()>;
}
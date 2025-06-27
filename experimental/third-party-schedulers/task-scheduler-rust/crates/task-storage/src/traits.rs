use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde_json::Value;
use std::collections::HashMap;
use uuid::Uuid;

use crate::models::{PageInfo, StoredTaskResult, TaskExecutionHistory, TaskQuery};

/// Storage error type
#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("Database error: {0}")]
    Database(String),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("Configuration error: {0}")]
    Configuration(String),

    #[error("Not found")]
    NotFound,

    #[error("Already exists")]
    AlreadyExists,

    #[error("Invalid query: {0}")]
    InvalidQuery(String),

    #[error("Not implemented: {0}")]
    NotImplemented(String),

    #[error("Connection failed: {0}")]
    ConnectionFailed(String),
}

pub type StorageResult<T> = Result<T, StorageError>;

/// Task storage trait
#[async_trait]
pub trait TaskStorage: Send + Sync {
    /// Store task result
    async fn store_result(&self, result: StoredTaskResult) -> StorageResult<()>;

    /// Get task result by ID
    async fn get_result(&self, task_id: Uuid) -> StorageResult<StoredTaskResult>;

    /// Query task results
    async fn query_results(
        &self,
        query: TaskQuery,
        page_info: PageInfo,
    ) -> StorageResult<(Vec<StoredTaskResult>, u64)>;

    /// Delete old results
    async fn cleanup_results(&self, older_than: DateTime<Utc>) -> StorageResult<u64>;

    /// Store task execution history
    async fn store_history(&self, history: TaskExecutionHistory) -> StorageResult<()>;

    /// Get task execution history
    async fn get_history(
        &self,
        task_id: Uuid,
        limit: Option<u32>,
    ) -> StorageResult<Vec<TaskExecutionHistory>>;

    /// Get aggregated statistics
    async fn get_statistics(
        &self,
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
        group_by: Option<String>,
    ) -> StorageResult<HashMap<String, Value>>;

    /// Check storage health
    async fn health_check(&self) -> StorageResult<()>;
}

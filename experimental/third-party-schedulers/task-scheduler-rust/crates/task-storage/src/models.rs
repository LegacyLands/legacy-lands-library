use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use uuid::Uuid;

/// Stored task result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredTaskResult {
    /// Task ID
    pub task_id: Uuid,

    /// Task method/type
    pub method: String,

    /// Task arguments
    pub args: Vec<Value>,

    /// Task status
    pub status: TaskStatus,

    /// Result data (if successful)
    pub result: Option<Value>,

    /// Error message (if failed)
    pub error: Option<String>,

    /// Worker that executed the task
    pub worker_id: Option<String>,

    /// Node where task was executed
    pub node_name: Option<String>,

    /// When the task was created
    pub created_at: DateTime<Utc>,

    /// When the task started executing
    pub started_at: Option<DateTime<Utc>>,

    /// When the task completed
    pub completed_at: Option<DateTime<Utc>>,

    /// Execution duration in milliseconds
    pub duration_ms: Option<u64>,

    /// Number of retries
    pub retry_count: u32,

    /// Task metadata
    pub metadata: HashMap<String, String>,

    /// Task priority
    pub priority: i32,

    /// Last update timestamp
    pub updated_at: DateTime<Utc>,

    /// Queue name
    pub queue_name: Option<String>,

    /// Task tags
    pub tags: Vec<String>,
}

/// Task result status
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TaskStatus {
    /// Task is pending
    Pending,

    /// Task is queued
    Queued,

    /// Task is running
    Running,

    /// Task succeeded
    Succeeded,

    /// Task failed
    Failed,

    /// Task was cancelled
    Cancelled,
}

/// Task execution history entry
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskExecutionHistory {
    /// Task ID
    pub task_id: Uuid,

    /// Execution ID
    pub execution_id: Uuid,

    /// Task status
    pub status: TaskStatus,

    /// When the task was executed
    pub executed_at: DateTime<Utc>,

    /// When the task completed
    pub completed_at: DateTime<Utc>,

    /// Execution duration in milliseconds
    pub duration_ms: u64,

    /// Error message (if failed)
    pub error: Option<String>,

    /// Worker ID
    pub worker_id: Option<String>,

    /// Worker node
    pub worker_node: Option<String>,

    /// Retry attempt number
    pub retry_attempt: u32,

    /// Execution metrics
    pub metrics: HashMap<String, Value>,
}

/// Task query parameters
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct TaskQuery {
    /// Filter by status
    pub status: Option<TaskStatus>,

    /// Filter by method
    pub method: Option<String>,

    /// Filter by worker
    pub worker_id: Option<String>,

    /// Filter by node
    pub node_name: Option<String>,

    /// Filter by time range (start)
    pub created_after: Option<DateTime<Utc>>,

    /// Filter by time range (end)
    pub created_before: Option<DateTime<Utc>>,

    /// Filter by metadata
    pub metadata: HashMap<String, String>,

    /// Filter by queue name
    pub queue_name: Option<String>,

    /// Filter by tags
    pub tags: Vec<String>,

    /// Sort field
    pub sort_by: Option<String>,

    /// Sort order (true = ascending)
    pub sort_ascending: bool,
}

/// Pagination info
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PageInfo {
    /// Page number (0-based)
    pub page: u32,

    /// Items per page
    pub page_size: u32,
}

impl Default for PageInfo {
    fn default() -> Self {
        Self {
            page: 0,
            page_size: 20,
        }
    }
}

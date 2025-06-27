use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

/// Task information structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskInfo {
    /// Unique task ID
    pub id: Uuid,

    /// Task method name
    pub method: String,

    /// Task arguments (JSON serialized)
    pub args: Vec<serde_json::Value>,

    /// Task dependencies
    pub dependencies: Vec<Uuid>,

    /// Task priority (0-100)
    pub priority: i32,

    /// Task metadata
    pub metadata: TaskMetadata,

    /// Task status
    pub status: TaskStatus,

    /// Creation timestamp
    pub created_at: DateTime<Utc>,

    /// Last updated timestamp
    pub updated_at: DateTime<Utc>,
}

/// Task metadata
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TaskMetadata {
    /// User who submitted the task
    pub user: Option<String>,

    /// Source application
    pub source: Option<String>,

    /// Custom labels
    pub labels: HashMap<String, String>,

    /// Custom annotations
    pub annotations: HashMap<String, String>,

    /// Retry configuration
    pub retry_config: Option<RetryConfig>,

    /// Resource requirements
    pub resources: Option<ResourceRequirements>,

    /// Timeout in seconds
    pub timeout_seconds: Option<u64>,

    /// Plugin configuration
    pub plugin: Option<PluginConfig>,
}

/// Task status
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub enum TaskStatus {
    /// Task is pending submission
    #[default]
    Pending,

    /// Task is queued for execution
    Queued,

    /// Task is waiting for dependencies to complete
    WaitingDependencies,

    /// Task is currently running
    Running {
        worker_id: String,
        started_at: DateTime<Utc>,
    },

    /// Task completed successfully
    Succeeded {
        completed_at: DateTime<Utc>,
        duration_ms: u64,
    },

    /// Task failed
    Failed {
        completed_at: DateTime<Utc>,
        error: String,
        retries: u32,
    },

    /// Task was cancelled
    Cancelled {
        cancelled_at: DateTime<Utc>,
        reason: String,
    },
}

/// Task execution result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    /// Task ID
    pub task_id: Uuid,

    /// Execution status
    pub status: TaskStatus,

    /// Result data (if succeeded)
    pub result: Option<serde_json::Value>,

    /// Error message (if failed)
    pub error: Option<String>,

    /// Execution metrics
    pub metrics: ExecutionMetrics,
}

/// Execution metrics
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ExecutionMetrics {
    /// Queue wait time in milliseconds
    pub queue_time_ms: u64,

    /// Execution time in milliseconds
    pub execution_time_ms: u64,

    /// CPU usage (percentage)
    pub cpu_usage: Option<f64>,

    /// Memory usage (bytes)
    pub memory_usage: Option<u64>,

    /// Number of retries
    pub retry_count: u32,

    /// Worker node name
    pub worker_node: Option<String>,
}

/// Retry configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetryConfig {
    /// Maximum number of retries
    pub max_retries: u32,

    /// Backoff strategy
    pub backoff_strategy: BackoffStrategy,

    /// Initial backoff duration in milliseconds
    pub initial_backoff_ms: u64,

    /// Maximum backoff duration in milliseconds
    pub max_backoff_ms: u64,

    /// Backoff multiplier
    pub backoff_multiplier: f64,
}

/// Backoff strategy for retries
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum BackoffStrategy {
    /// Fixed delay between retries
    Fixed,

    /// Exponential backoff
    Exponential,

    /// Linear backoff
    Linear,
}

/// Resource requirements
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceRequirements {
    /// CPU request (e.g., "100m", "2")
    pub cpu_request: Option<String>,

    /// CPU limit
    pub cpu_limit: Option<String>,

    /// Memory request (e.g., "128Mi", "2Gi")
    pub memory_request: Option<String>,

    /// Memory limit
    pub memory_limit: Option<String>,

    /// Ephemeral storage request
    pub ephemeral_storage_request: Option<String>,

    /// Ephemeral storage limit
    pub ephemeral_storage_limit: Option<String>,
}

/// Plugin configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PluginConfig {
    /// Plugin name
    pub name: String,

    /// Plugin version
    pub version: String,

    /// ConfigMap containing the plugin
    pub config_map: Option<String>,

    /// PVC containing the plugin
    pub pvc: Option<String>,

    /// Additional plugin configuration
    pub config: HashMap<String, String>,
}

impl Default for RetryConfig {
    fn default() -> Self {
        Self {
            max_retries: 3,
            backoff_strategy: BackoffStrategy::Exponential,
            initial_backoff_ms: 1000,
            max_backoff_ms: 60000,
            backoff_multiplier: 2.0,
        }
    }
}

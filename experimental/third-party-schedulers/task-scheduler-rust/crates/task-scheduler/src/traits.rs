use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use uuid::Uuid;

/// Scheduler error type
#[derive(Debug, thiserror::Error)]
pub enum SchedulerError {
    #[error("Scheduler error: {0}")]
    Internal(String),

    #[error("Invalid schedule: {0}")]
    InvalidSchedule(String),

    #[error("Task not found")]
    TaskNotFound,

    #[error("Task already exists")]
    TaskAlreadyExists,

    #[error("Scheduler full")]
    SchedulerFull,
}

pub type SchedulerResult<T> = Result<T, SchedulerError>;

/// Scheduled task
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduledTask {
    /// Task ID
    pub id: Uuid,

    /// Task method
    pub method: String,

    /// Task arguments
    pub args: Vec<Value>,

    /// Task priority (higher = more important)
    pub priority: i32,

    /// When to execute the task
    pub schedule: TaskSchedule,

    /// Maximum retries
    pub max_retries: u32,

    /// Timeout in seconds
    pub timeout_seconds: u64,

    /// Task metadata
    pub metadata: HashMap<String, String>,

    /// Whether the task is active
    pub active: bool,

    /// Created timestamp
    pub created_at: DateTime<Utc>,

    /// Last execution time
    pub last_executed_at: Option<DateTime<Utc>>,

    /// Next execution time
    pub next_execution_at: Option<DateTime<Utc>>,
}

/// Task schedule type
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum TaskSchedule {
    /// Execute immediately
    Immediate,

    /// Execute at a specific time
    At { time: DateTime<Utc> },

    /// Execute after a delay
    Delayed { delay_seconds: u64 },

    /// Execute on a cron schedule
    Cron {
        expression: String,
        timezone: Option<String>,
    },

    /// Execute at regular intervals
    Interval {
        interval_seconds: u64,
        start_time: Option<DateTime<Utc>>,
    },
}

/// Task scheduler trait
#[async_trait]
pub trait Scheduler: Send + Sync {
    /// Add a scheduled task
    async fn add_task(&self, task: ScheduledTask) -> SchedulerResult<()>;

    /// Remove a scheduled task
    async fn remove_task(&self, task_id: Uuid) -> SchedulerResult<()>;

    /// Get a scheduled task
    async fn get_task(&self, task_id: Uuid) -> SchedulerResult<Option<ScheduledTask>>;

    /// List all scheduled tasks
    async fn list_tasks(&self) -> SchedulerResult<Vec<ScheduledTask>>;

    /// Get tasks ready for execution
    async fn get_ready_tasks(&self, limit: usize) -> SchedulerResult<Vec<ScheduledTask>>;

    /// Mark task as executed
    async fn mark_executed(&self, task_id: Uuid, success: bool) -> SchedulerResult<()>;

    /// Update task schedule
    async fn update_schedule(&self, task_id: Uuid, schedule: TaskSchedule) -> SchedulerResult<()>;

    /// Pause a task
    async fn pause_task(&self, task_id: Uuid) -> SchedulerResult<()>;

    /// Resume a task
    async fn resume_task(&self, task_id: Uuid) -> SchedulerResult<()>;

    /// Get scheduler statistics
    async fn get_statistics(&self) -> SchedulerResult<SchedulerStats>;
}

/// Scheduler statistics
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulerStats {
    /// Total tasks
    pub total_tasks: u64,

    /// Active tasks
    pub active_tasks: u64,

    /// Paused tasks
    pub paused_tasks: u64,

    /// Tasks by schedule type
    pub tasks_by_type: HashMap<String, u64>,

    /// Tasks executed in the last hour
    pub tasks_executed_last_hour: u64,

    /// Next scheduled execution
    pub next_execution: Option<DateTime<Utc>>,
}

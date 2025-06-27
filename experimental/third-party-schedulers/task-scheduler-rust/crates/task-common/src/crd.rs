use chrono::{DateTime, Utc};
use kube::CustomResource;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Task Custom Resource Definition for Kubernetes
#[derive(CustomResource, Deserialize, Serialize, Clone, Debug, JsonSchema)]
#[kube(
    group = "taskscheduler.legacylands.io",
    version = "v1alpha1",
    kind = "Task",
    plural = "tasks",
    derive = "PartialEq",
    status = "TaskCrdStatus",
    namespaced,
    printcolumn = r#"{"name":"Phase", "type":"string", "jsonPath":".status.phase"}"#,
    printcolumn = r#"{"name":"Method", "type":"string", "jsonPath":".spec.method"}"#,
    printcolumn = r#"{"name":"Priority", "type":"integer", "jsonPath":".spec.priority"}"#,
    printcolumn = r#"{"name":"Age", "type":"date", "jsonPath":".metadata.creationTimestamp"}"#
)]
#[serde(rename_all = "camelCase")]
#[derive(PartialEq)]
pub struct TaskCrdSpec {
    /// Task method name
    pub method: String,

    /// Task parameters (JSON encoded)
    pub args: Vec<serde_json::Value>,

    /// Dependencies on other tasks (by name)
    #[serde(default)]
    pub dependencies: Vec<String>,

    /// Priority (0-100, higher is more important)
    #[serde(default = "default_priority")]
    pub priority: i32,

    /// Retry policy
    #[serde(default)]
    pub retry_policy: TaskRetryPolicy,

    /// Resource requirements
    #[serde(default)]
    pub resources: TaskResourceRequirements,

    /// Timeout in seconds
    #[serde(default = "default_timeout")]
    pub timeout_seconds: u64,

    /// Node selector for task execution
    #[serde(default)]
    pub node_selector: HashMap<String, String>,

    /// Plugin configuration
    pub plugin: Option<TaskPluginConfig>,

    /// Additional metadata
    #[serde(default)]
    pub metadata: HashMap<String, String>,
}

/// Task CRD status
#[derive(Deserialize, Serialize, Clone, Debug, Default, PartialEq, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct TaskCrdStatus {
    /// Current phase of the task
    #[serde(default)]
    pub phase: TaskPhase,

    /// Human-readable status message
    pub message: Option<String>,

    /// Start time
    pub start_time: Option<DateTime<Utc>>,

    /// Completion time
    pub completion_time: Option<DateTime<Utc>>,

    /// Kubernetes Job reference
    pub job_ref: Option<JobReference>,

    /// Result of the task (JSON encoded)
    pub result: Option<serde_json::Value>,

    /// Error message if failed
    pub error: Option<String>,

    /// Number of retries attempted
    #[serde(default)]
    pub retry_count: u32,

    /// Worker node that executed the task
    pub worker_node: Option<String>,

    /// Execution metrics
    #[serde(default)]
    pub metrics: TaskMetrics,

    /// Conditions
    #[serde(default)]
    pub conditions: Vec<TaskCondition>,
}

/// Task phase in Kubernetes
#[derive(Deserialize, Serialize, Clone, Debug, Default, PartialEq, JsonSchema)]
pub enum TaskPhase {
    #[default]
    Pending,
    Queued,
    Running,
    Succeeded,
    Failed,
    Cancelled,
}

/// Retry policy configuration
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct TaskRetryPolicy {
    /// Maximum number of retries
    #[serde(default = "default_max_retries")]
    pub max_retries: u32,

    /// Backoff strategy
    #[serde(default)]
    pub backoff_strategy: TaskBackoffStrategy,

    /// Initial backoff duration in seconds
    #[serde(default = "default_initial_backoff")]
    pub initial_backoff_seconds: u64,

    /// Maximum backoff duration in seconds
    #[serde(default = "default_max_backoff")]
    pub max_backoff_seconds: u64,

    /// Backoff multiplier
    #[serde(default = "default_backoff_multiplier")]
    pub backoff_multiplier: f64,
}

/// Backoff strategy
#[derive(Deserialize, Serialize, Clone, Debug, Default, PartialEq, JsonSchema)]
pub enum TaskBackoffStrategy {
    Fixed,
    #[default]
    Exponential,
    Linear,
}

/// Resource requirements for task execution
#[derive(Deserialize, Serialize, Clone, Debug, Default, PartialEq, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct TaskResourceRequirements {
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
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct TaskPluginConfig {
    /// Plugin name
    pub name: String,

    /// Plugin version
    pub version: String,

    /// ConfigMap containing the plugin
    pub config_map: Option<String>,

    /// PVC containing the plugin
    pub pvc: Option<String>,

    /// Additional plugin configuration
    #[serde(default)]
    pub config: HashMap<String, String>,
}

/// Reference to a Kubernetes Job
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct JobReference {
    /// Job name
    pub name: String,

    /// Job namespace
    pub namespace: String,

    /// Job UID
    pub uid: String,
}

/// Task execution metrics
#[derive(Deserialize, Serialize, Clone, Debug, Default, PartialEq, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct TaskMetrics {
    /// Queue wait time in milliseconds
    #[serde(default)]
    pub queue_time_ms: u64,

    /// Execution time in milliseconds
    #[serde(default)]
    pub execution_time_ms: u64,

    /// CPU usage (percentage)
    pub cpu_usage: Option<f64>,

    /// Memory usage (bytes)
    pub memory_usage: Option<u64>,
}

/// Task condition (similar to k8s Condition but with JsonSchema support)
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct TaskCondition {
    /// Type of condition
    pub r#type: String,

    /// Status of the condition (True, False, Unknown)
    pub status: String,

    /// Last time the condition transitioned
    pub last_transition_time: Option<DateTime<Utc>>,

    /// Reason for the condition's last transition
    pub reason: Option<String>,

    /// Human-readable message indicating details
    pub message: Option<String>,
}

// Default value functions
fn default_priority() -> i32 {
    50
}

fn default_timeout() -> u64 {
    3600 // 1 hour
}

fn default_max_retries() -> u32 {
    3
}

fn default_initial_backoff() -> u64 {
    1
}

fn default_max_backoff() -> u64 {
    300 // 5 minutes
}

fn default_backoff_multiplier() -> f64 {
    2.0
}

impl Default for TaskRetryPolicy {
    fn default() -> Self {
        Self {
            max_retries: default_max_retries(),
            backoff_strategy: TaskBackoffStrategy::default(),
            initial_backoff_seconds: default_initial_backoff(),
            max_backoff_seconds: default_max_backoff(),
            backoff_multiplier: default_backoff_multiplier(),
        }
    }
}

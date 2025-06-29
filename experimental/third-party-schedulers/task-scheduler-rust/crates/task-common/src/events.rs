use crate::models::{TaskInfo, TaskResult};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Event types for task lifecycle
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum TaskEvent {
    /// Task was created
    Created { task: Box<TaskInfo>, source: String },

    /// Task was queued for execution
    Queued {
        task_id: Uuid,
        queue_name: String,
        position: Option<u64>,
    },

    /// Task was assigned to a worker
    Assigned {
        task_id: Uuid,
        worker_id: String,
        worker_node: String,
    },

    /// Task execution started
    Started {
        task_id: Uuid,
        worker_id: String,
        timestamp: DateTime<Utc>,
    },

    /// Task execution progress update
    Progress {
        task_id: Uuid,
        percentage: f32,
        message: Option<String>,
    },

    /// Task completed successfully
    Completed {
        result: TaskResult,
        timestamp: DateTime<Utc>,
    },

    /// Task failed
    Failed {
        task_id: Uuid,
        error: String,
        retry_count: u32,
        will_retry: bool,
        timestamp: DateTime<Utc>,
    },

    /// Task is being retried
    Retrying {
        task_id: Uuid,
        attempt: u32,
        delay_seconds: u64,
        reason: String,
    },

    /// Task was cancelled
    Cancelled {
        task_id: Uuid,
        reason: String,
        cancelled_by: Option<String>,
        timestamp: DateTime<Utc>,
    },

    /// Task dependency completed
    DependencyCompleted {
        task_id: Uuid,
        dependency_id: Uuid,
        success: bool,
    },

    /// Worker heartbeat
    WorkerHeartbeat {
        worker_id: String,
        node_name: String,
        active_tasks: Vec<Uuid>,
        capacity: WorkerCapacity,
        timestamp: DateTime<Utc>,
    },

    /// Worker joined the cluster
    WorkerJoined {
        worker_id: String,
        node_name: String,
        capabilities: WorkerCapabilities,
        timestamp: DateTime<Utc>,
    },

    /// Worker left the cluster
    WorkerLeft {
        worker_id: String,
        reason: String,
        reassigned_tasks: Vec<Uuid>,
        timestamp: DateTime<Utc>,
    },
    
    /// Task rejected due to unsupported method
    UnsupportedMethod {
        task_id: Uuid,
        method: String,
        worker_id: String,
        timestamp: DateTime<Utc>,
    },
}

/// Worker capacity information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerCapacity {
    /// Maximum concurrent tasks
    pub max_tasks: u32,

    /// Currently running tasks
    pub running_tasks: u32,

    /// Available CPU (millicores)
    pub available_cpu: u64,

    /// Available memory (bytes)
    pub available_memory: u64,

    /// Load average
    pub load_average: f64,
}

/// Worker capabilities
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerCapabilities {
    /// Supported task types/methods
    pub supported_methods: Vec<String>,

    /// Supported plugins
    pub plugins: Vec<PluginInfo>,

    /// Hardware capabilities
    pub hardware: HardwareInfo,

    /// Software versions
    pub software: SoftwareInfo,
}

/// Plugin information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PluginInfo {
    pub name: String,
    pub version: String,
    pub methods: Vec<String>,
}

/// Hardware information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HardwareInfo {
    /// CPU cores
    pub cpu_cores: u32,

    /// Total memory (bytes)
    pub total_memory: u64,

    /// GPU availability
    pub has_gpu: bool,

    /// GPU details if available
    pub gpu_info: Option<String>,
}

/// Software information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SoftwareInfo {
    /// Worker version
    pub worker_version: String,

    /// Rust version
    pub rust_version: String,

    /// OS information
    pub os: String,

    /// Kernel version
    pub kernel: String,
}

/// Event envelope for message queue
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EventEnvelope {
    /// Event ID
    pub id: Uuid,

    /// Event timestamp
    pub timestamp: DateTime<Utc>,

    /// Event source
    pub source: String,

    /// Correlation ID for tracking
    pub correlation_id: Option<Uuid>,

    /// The actual event
    pub event: TaskEvent,

    /// Event metadata
    pub metadata: std::collections::HashMap<String, String>,
}

impl EventEnvelope {
    /// Create a new event envelope
    pub fn new(event: TaskEvent, source: String) -> Self {
        Self {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            source,
            correlation_id: None,
            event,
            metadata: std::collections::HashMap::new(),
        }
    }

    /// Set correlation ID
    pub fn with_correlation_id(mut self, id: Uuid) -> Self {
        self.correlation_id = Some(id);
        self
    }

    /// Add metadata
    pub fn with_metadata(mut self, key: String, value: String) -> Self {
        self.metadata.insert(key, value);
        self
    }
}

/// NATS subject patterns for events
pub mod subjects {
    /// Task lifecycle events
    pub const TASK_EVENTS: &str = "tasks.events";

    /// Task created
    pub const TASK_CREATED: &str = "tasks.events.created";

    /// Task queued
    pub const TASK_QUEUED: &str = "tasks.events.queued";

    /// Task assigned
    pub const TASK_ASSIGNED: &str = "tasks.events.assigned";

    /// Task started
    pub const TASK_STARTED: &str = "tasks.events.started";

    /// Task completed
    pub const TASK_COMPLETED: &str = "tasks.events.completed";

    /// Task failed
    pub const TASK_FAILED: &str = "tasks.events.failed";

    /// Task retrying
    pub const TASK_RETRYING: &str = "tasks.events.retrying";

    /// Task cancelled
    pub const TASK_CANCELLED: &str = "tasks.events.cancelled";
    
    /// Task has unsupported method
    pub const TASK_UNSUPPORTED_METHOD: &str = "tasks.events.unsupported_method";

    /// Worker events
    pub const WORKER_EVENTS: &str = "workers.events";

    /// Worker heartbeat
    pub const WORKER_HEARTBEAT: &str = "workers.events.heartbeat";

    /// Worker joined
    pub const WORKER_JOINED: &str = "workers.events.joined";

    /// Worker left
    pub const WORKER_LEFT: &str = "workers.events.left";

    /// Task queue for workers
    pub const TASK_QUEUE: &str = "tasks.queue";

    /// Task results stream
    pub const TASK_RESULTS: &str = "tasks.results";
}

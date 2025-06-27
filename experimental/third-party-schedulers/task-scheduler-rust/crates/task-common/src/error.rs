use thiserror::Error;

/// Common error types for the task scheduler system
#[derive(Error, Debug)]
pub enum TaskError {
    #[error("Task not found: {0}")]
    TaskNotFound(String),

    #[error("Task execution failed: {0}")]
    ExecutionFailed(String),

    #[error("Task timeout after {0} seconds")]
    Timeout(u64),

    #[error("Invalid task configuration: {0}")]
    InvalidConfiguration(String),

    #[error("Dependency failed: {0}")]
    DependencyFailed(String),

    #[error("Resource limit exceeded: {0}")]
    ResourceLimitExceeded(String),

    #[error("Plugin error: {0}")]
    PluginError(String),

    #[error("Queue error: {0}")]
    QueueError(String),

    #[error("Kubernetes API error: {0}")]
    KubernetesError(String),

    #[error("Serialization error: {0}")]
    SerializationError(String),

    #[error("Network error: {0}")]
    NetworkError(String),

    #[error("Internal error: {0}")]
    InternalError(String),

    #[error("Invalid arguments: {0}")]
    InvalidArguments(String),

    #[error("Method not found: {0}")]
    MethodNotFound(String),
}

/// Result type for task operations
pub type TaskResult<T> = Result<T, TaskError>;

impl From<kube::Error> for TaskError {
    fn from(err: kube::Error) -> Self {
        TaskError::KubernetesError(err.to_string())
    }
}

impl From<serde_json::Error> for TaskError {
    fn from(err: serde_json::Error) -> Self {
        TaskError::SerializationError(err.to_string())
    }
}

impl From<std::io::Error> for TaskError {
    fn from(err: std::io::Error) -> Self {
        TaskError::InternalError(err.to_string())
    }
}

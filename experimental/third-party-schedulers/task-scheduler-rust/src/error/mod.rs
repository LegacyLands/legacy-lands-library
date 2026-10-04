use thiserror::Error;

#[derive(Error, Debug, Clone)]
pub enum TaskError {
    #[error("Method not found: {0}")]
    MethodNotFound(String),

    #[error("Invalid arguments: {0}")]
    InvalidArguments(String),

    #[error("Missing dependency: {0}")]
    MissingDependency(String),

    #[error("Task execution failed: {0}")]
    ExecutionError(String),

    #[error("Dependency failed: {0}")]
    DependencyFailed(String),

    #[error("Timeout: {0}")]
    Timeout(String),

    #[error("Task cancelled: {0}")]
    Cancelled(String),

    /// Error reported by a peer node, carrying the original gRPC status code.
    #[error("{message}")]
    Remote { code: i32, message: String },
}

pub type Result<T> = std::result::Result<T, TaskError>;

/// Converts the return value of an async task into a task result,
/// allowing `#[async_task]` functions to return either `String` or `Result<String>`.
pub trait IntoTaskOutput {
    fn into_task_output(self) -> Result<String>;
}

impl IntoTaskOutput for String {
    fn into_task_output(self) -> Result<String> {
        Ok(self)
    }
}

impl IntoTaskOutput for Result<String> {
    fn into_task_output(self) -> Result<String> {
        self
    }
}

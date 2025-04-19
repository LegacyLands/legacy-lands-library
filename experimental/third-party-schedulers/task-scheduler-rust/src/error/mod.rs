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
}

pub type Result<T> = std::result::Result<T, TaskError>;

use task_common::{TaskError, TaskResult};
use serde_json::Value;
use tracing::{info, error};

/// Task that always fails for testing error metrics
pub fn fail_task(args: Vec<Value>) -> TaskResult<Value> {
    let reason = args.get(0)
        .and_then(|v| v.as_str())
        .unwrap_or("Intentional failure for testing");
    
    error!("Task failing with reason: {}", reason);
    
    // Simulate different types of errors
    match reason {
        r if r.contains("timeout") => Err(TaskError::Timeout(30)),
        r if r.contains("network") => Err(TaskError::ExecutionFailed("Network connection failed".to_string())),
        r if r.contains("validation") => Err(TaskError::InvalidArguments("Input validation failed".to_string())),
        _ => Err(TaskError::ExecutionFailed(format!("Task failed: {}", reason)))
    }
}

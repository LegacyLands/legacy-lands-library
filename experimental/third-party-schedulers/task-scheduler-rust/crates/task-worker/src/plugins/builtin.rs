use serde_json::{json, Value};
use std::future::Future;
use std::pin::Pin;
use task_common::error::{TaskError, TaskResult};
use tracing::{debug, error};

/// Builtin tasks container
pub struct BuiltinTasks;

impl Default for BuiltinTasks {
    fn default() -> Self {
        Self::new()
    }
}

impl BuiltinTasks {
    pub fn new() -> Self {
        Self
    }
}

// ===== Synchronous Tasks =====

/// Echo task - returns the input
pub fn echo(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing echo task with {} args", args.len());

    if args.is_empty() {
        return Err(TaskError::InvalidArguments(
            "Echo requires at least one argument".to_string(),
        ));
    }

    if args.len() == 1 {
        Ok(args[0].clone())
    } else {
        Ok(Value::Array(args))
    }
}

/// Add task - adds numbers
pub fn add(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing add task with {} args", args.len());

    if args.len() < 2 {
        return Err(TaskError::InvalidArguments(
            "Add requires at least two arguments".to_string(),
        ));
    }

    let mut sum = 0.0;

    for arg in args {
        match arg {
            Value::Number(n) => {
                if let Some(f) = n.as_f64() {
                    sum += f;
                } else {
                    return Err(TaskError::InvalidArguments(
                        "Invalid number format".to_string(),
                    ));
                }
            }
            _ => {
                return Err(TaskError::InvalidArguments(
                    "Add requires numeric arguments".to_string(),
                ))
            }
        }
    }

    Ok(json!(sum))
}

/// Multiply task - multiplies numbers
pub fn multiply(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing multiply task with {} args", args.len());

    if args.len() < 2 {
        return Err(TaskError::InvalidArguments(
            "Multiply requires at least two arguments".to_string(),
        ));
    }

    let mut product = 1.0;

    for arg in args {
        match arg {
            Value::Number(n) => {
                if let Some(f) = n.as_f64() {
                    product *= f;
                } else {
                    return Err(TaskError::InvalidArguments(
                        "Invalid number format".to_string(),
                    ));
                }
            }
            _ => {
                return Err(TaskError::InvalidArguments(
                    "Multiply requires numeric arguments".to_string(),
                ))
            }
        }
    }

    Ok(json!(product))
}

/// Concatenate strings
pub fn concat(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing concat task with {} args", args.len());

    if args.is_empty() {
        return Ok(json!(""));
    }

    let mut result = String::new();

    for arg in args {
        match arg {
            Value::String(s) => result.push_str(&s),
            _ => result.push_str(&arg.to_string()),
        }
    }

    Ok(json!(result))
}

/// Convert to uppercase
pub fn uppercase(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing uppercase task with {} args", args.len());

    if args.is_empty() {
        return Err(TaskError::InvalidArguments(
            "Uppercase requires at least one argument".to_string(),
        ));
    }

    let input = match &args[0] {
        Value::String(s) => s.clone(),
        _ => args[0].to_string(),
    };

    Ok(json!(input.to_uppercase()))
}

/// Convert to lowercase
pub fn lowercase(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing lowercase task with {} args", args.len());

    if args.is_empty() {
        return Err(TaskError::InvalidArguments(
            "Lowercase requires at least one argument".to_string(),
        ));
    }

    let input = match &args[0] {
        Value::String(s) => s.clone(),
        _ => args[0].to_string(),
    };

    Ok(json!(input.to_lowercase()))
}

// ===== Asynchronous Tasks =====

/// Sleep task - sleeps for specified milliseconds
pub fn sleep(args: Vec<Value>) -> Pin<Box<dyn Future<Output = TaskResult<Value>> + Send>> {
    Box::pin(async move {
        debug!("Executing sleep task with {} args", args.len());

        if args.is_empty() {
            return Err(TaskError::InvalidArguments(
                "Sleep requires duration in milliseconds".to_string(),
            ));
        }

        let duration_ms = match &args[0] {
            Value::Number(n) => n.as_u64().ok_or_else(|| {
                TaskError::InvalidArguments("Sleep duration must be a positive integer".to_string())
            })?,
            _ => {
                return Err(TaskError::InvalidArguments(
                    "Sleep duration must be a number".to_string(),
                ))
            }
        };

        tokio::time::sleep(tokio::time::Duration::from_millis(duration_ms)).await;

        Ok(json!({
            "slept_ms": duration_ms,
            "message": format!("Slept for {} ms", duration_ms)
        }))
    })
}

/// HTTP GET request
pub fn http_get(args: Vec<Value>) -> Pin<Box<dyn Future<Output = TaskResult<Value>> + Send>> {
    Box::pin(async move {
        debug!("Executing http_get task with {} args", args.len());

        if args.is_empty() {
            return Err(TaskError::InvalidArguments(
                "HTTP GET requires a URL".to_string(),
            ));
        }

        let url = match &args[0] {
            Value::String(s) => s.clone(),
            _ => {
                return Err(TaskError::InvalidArguments(
                    "URL must be a string".to_string(),
                ))
            }
        };

        // Simple HTTP client implementation
        // In production, you'd use reqwest or similar
        Ok(json!({
            "url": url,
            "status": "simulated",
            "message": "HTTP client not implemented in this demo"
        }))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_echo() {
        let result = echo(vec![json!("hello")]).unwrap();
        assert_eq!(result, json!("hello"));

        let result = echo(vec![json!("hello"), json!("world")]).unwrap();
        assert_eq!(result, json!(["hello", "world"]));
    }

    #[test]
    fn test_add() {
        let result = add(vec![json!(1), json!(2), json!(3)]).unwrap();
        assert_eq!(result, json!(6.0));

        let result = add(vec![json!(1.5), json!(2.5)]).unwrap();
        assert_eq!(result, json!(4.0));
    }

    #[test]
    fn test_concat() {
        let result = concat(vec![json!("hello"), json!(" "), json!("world")]).unwrap();
        assert_eq!(result, json!("hello world"));
    }

    #[test]
    fn test_uppercase() {
        let result = uppercase(vec![json!("hello")]).unwrap();
        assert_eq!(result, json!("HELLO"));
    }

    #[tokio::test]
    async fn test_sleep() {
        let start = std::time::Instant::now();
        let result = sleep(vec![json!(100)]).await.unwrap();
        let elapsed = start.elapsed();

        assert!(elapsed.as_millis() >= 100);
        assert!(result.is_object());
    }
}

/// Fail task - always fails for testing error metrics
pub fn fail_task(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing fail task with {} args", args.len());

    let reason = args.get(0)
        .and_then(|v| v.as_str())
        .unwrap_or("Intentional failure for testing");
    
    error!("Task failing with reason: {}", reason);
    
    // Simulate different types of errors with more specific messages
    let error_msg = match reason {
        r if r.contains("timeout") => {
            Err(TaskError::Timeout(30))
        }
        r if r.contains("network") => {
            Err(TaskError::ExecutionFailed("Network connection failed: unable to reach remote host".to_string()))
        }
        r if r.contains("validation") => {
            Err(TaskError::InvalidArguments("Input validation failed: required field missing".to_string()))
        }
        r if r.contains("permission") => {
            Err(TaskError::ExecutionFailed("Permission denied: insufficient privileges to access resource".to_string()))
        }
        r if r.contains("memory") => {
            Err(TaskError::ExecutionFailed("Out of memory: failed to allocate required resources".to_string()))
        }
        r if r.contains("database") => {
            Err(TaskError::ExecutionFailed("Database connection lost: unable to execute query".to_string()))
        }
        r if r.contains("concurrency") => {
            Err(TaskError::ExecutionFailed("Deadlock detected: concurrent operation conflict".to_string()))
        }
        r if r.contains("resource") => {
            Err(TaskError::ExecutionFailed("Resource limit exceeded: too many open files".to_string()))
        }
        _ => {
            Err(TaskError::ExecutionFailed(format!("Task failed: {}", reason)))
        }
    };
    
    error_msg
}

/// Init task - initializes a system or component with provided configuration
pub fn init(args: Vec<Value>) -> TaskResult<Value> {
    debug!("Executing init task with {} args", args.len());

    if args.is_empty() {
        return Ok(json!({
            "status": "initialized",
            "config": "default",
            "message": "System initialized with default configuration"
        }));
    }

    // Parse configuration from first argument
    let config = &args[0];
    
    Ok(json!({
        "status": "initialized",
        "config": config,
        "message": format!("System initialized with custom configuration"),
        "timestamp": chrono::Utc::now().to_rfc3339()
    }))
}

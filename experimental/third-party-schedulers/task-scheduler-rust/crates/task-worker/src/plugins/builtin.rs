use serde_json::{json, Value};
use std::future::Future;
use std::pin::Pin;
use task_common::error::{TaskError, TaskResult};
use tracing::debug;

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

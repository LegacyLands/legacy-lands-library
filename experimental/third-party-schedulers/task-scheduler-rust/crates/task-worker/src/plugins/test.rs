use super::*;
use crate::plugins::builtin::{add, concat, echo, http_get, lowercase, multiply, sleep, uppercase};
use serde_json::json;
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[cfg(test)]
mod plugin_manager_tests {
    use super::*;

    #[test]
    fn test_plugin_manager_creation() {
        let manager = PluginManager::new();
        
        // Verify builtin tasks are registered
        let methods = manager.list_methods();
        assert!(methods.contains(&"echo".to_string()));
        assert!(methods.contains(&"add".to_string()));
        assert!(methods.contains(&"multiply".to_string()));
        assert!(methods.contains(&"concat".to_string()));
        assert!(methods.contains(&"uppercase".to_string()));
        assert!(methods.contains(&"lowercase".to_string()));
        assert!(methods.contains(&"sleep".to_string()));
        assert!(methods.contains(&"http_get".to_string()));
    }

    #[test]
    fn test_register_sync_task() {
        let manager = PluginManager::new();
        
        // Define a test task
        fn test_task(args: Vec<Value>) -> TaskResult<Value> {
            Ok(json!({ "test": "result", "args": args }))
        }
        
        // Register the task
        manager.register_sync_task("test_task", test_task, Some("test_plugin".to_string()));
        
        // Verify it's registered
        let methods = manager.list_methods();
        assert!(methods.contains(&"test_task".to_string()));
        
        // Get task info
        let info = manager.get_task_info("test_task").unwrap();
        assert!(!info.is_async);
        assert_eq!(info.plugin_name, Some("test_plugin".to_string()));
    }

    #[test]
    fn test_register_async_task() {
        let manager = PluginManager::new();
        
        // Define a test async task
        fn test_async_task(args: Vec<Value>) -> Pin<Box<dyn Future<Output = TaskResult<Value>> + Send>> {
            Box::pin(async move {
                tokio::time::sleep(Duration::from_millis(10)).await;
                Ok(json!({ "async": true, "args": args }))
            })
        }
        
        // Register the task
        manager.register_async_task("test_async", test_async_task, None);
        
        // Verify it's registered
        let methods = manager.list_methods();
        assert!(methods.contains(&"test_async".to_string()));
        
        // Get task info
        let info = manager.get_task_info("test_async").unwrap();
        assert!(info.is_async);
        assert_eq!(info.plugin_name, None);
    }

    #[tokio::test]
    async fn test_execute_sync_task() {
        let manager = PluginManager::new();
        
        // Execute echo task
        let result = manager.execute_task("echo", vec![json!("test_value")], Duration::from_secs(5)).await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), json!("test_value"));
        
        // Execute add task
        let result = manager.execute_task("add", vec![json!(5), json!(3)], Duration::from_secs(5)).await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), json!(8.0));
    }

    #[tokio::test]
    async fn test_execute_async_task() {
        let manager = PluginManager::new();
        
        // Execute sleep task
        let start = std::time::Instant::now();
        let result = manager.execute_task("sleep", vec![json!(100)], Duration::from_secs(5)).await;
        assert!(result.is_ok());
        assert!(start.elapsed().as_millis() >= 100);
        
        let result_value = result.unwrap();
        assert!(result_value.is_object());
        assert_eq!(result_value["slept_ms"], json!(100));
    }

    #[tokio::test]
    async fn test_execute_unknown_method() {
        let manager = PluginManager::new();
        
        let result = manager.execute_task("unknown_method", vec![], Duration::from_secs(5)).await;
        assert!(result.is_err());
        
        match result.unwrap_err() {
            TaskError::MethodNotFound(method) => assert_eq!(method, "unknown_method"),
            _ => panic!("Expected MethodNotFound error"),
        }
    }

    #[tokio::test]
    async fn test_task_timeout() {
        let manager = PluginManager::new();
        
        // Execute sleep task with timeout shorter than sleep duration
        let result = manager.execute_task("sleep", vec![json!(1000)], Duration::from_millis(100)).await;
        assert!(result.is_err());
        
        match result.unwrap_err() {
            TaskError::Timeout(_) => (),
            _ => panic!("Expected Timeout error"),
        }
    }

    #[tokio::test]
    async fn test_concurrent_task_execution() {
        let manager = Arc::new(PluginManager::new());
        
        // Register a task that increments a counter
        fn counting_task(_args: Vec<Value>) -> TaskResult<Value> {
            // We need to use a global counter for this test
            static COUNTER: AtomicUsize = AtomicUsize::new(0);
            let count = COUNTER.fetch_add(1, Ordering::SeqCst);
            Ok(json!({ "count": count + 1 }))
        }
        
        manager.register_sync_task("count_task", counting_task, None);
        
        // Execute multiple tasks concurrently
        let mut handles = vec![];
        for _ in 0..10 {
            let manager_clone = manager.clone();
            let handle = tokio::spawn(async move {
                manager_clone.execute_task("count_task", vec![], Duration::from_secs(5)).await
            });
            handles.push(handle);
        }
        
        // Wait for all tasks
        let mut results = vec![];
        for handle in handles {
            let result = handle.await.unwrap().unwrap();
            results.push(result);
        }
        
        // Verify all tasks executed
        assert_eq!(results.len(), 10);
    }

    #[test]
    fn test_task_overwrite_warning() {
        let manager = PluginManager::new();
        
        // Register a task
        fn task_v1(_: Vec<Value>) -> TaskResult<Value> {
            Ok(json!("v1"))
        }
        
        manager.register_sync_task("overwrite_test", task_v1, None);
        
        // Register again with same name
        fn task_v2(_: Vec<Value>) -> TaskResult<Value> {
            Ok(json!("v2"))
        }
        
        // This should log a warning but not panic
        manager.register_sync_task("overwrite_test", task_v2, None);
        
        // Verify only one instance exists
        let methods = manager.list_methods();
        let count = methods.iter().filter(|m| *m == "overwrite_test").count();
        assert_eq!(count, 1);
    }

    #[tokio::test]
    async fn test_task_panic_handling() {
        let manager = PluginManager::new();
        
        // Register a task that panics
        fn panic_task(_: Vec<Value>) -> TaskResult<Value> {
            panic!("Task intentionally panicked!");
        }
        
        manager.register_sync_task("panic_task", panic_task, None);
        
        // Execute the panicking task
        let result = manager.execute_task("panic_task", vec![], Duration::from_secs(5)).await;
        assert!(result.is_err());
        
        match result.unwrap_err() {
            TaskError::ExecutionFailed(msg) => assert!(msg.contains("panicked")),
            _ => panic!("Expected ExecutionFailed error"),
        }
    }
}

#[cfg(test)]
mod builtin_task_tests {
    use super::*;
    
    #[test]
    fn test_echo_empty_args() {
        let result = echo(vec![]);
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::InvalidArguments(msg) => assert!(msg.contains("at least one argument")),
            _ => panic!("Expected InvalidArguments error"),
        }
    }
    
    #[test]
    fn test_add_invalid_args() {
        // Test with non-numeric arguments
        let result = add(vec![json!("not a number"), json!(5)]);
        assert!(result.is_err());
        
        // Test with too few arguments
        let result = add(vec![json!(5)]);
        assert!(result.is_err());
    }
    
    #[test]
    fn test_multiply_edge_cases() {
        // Test multiplication by zero
        let result = multiply(vec![json!(5), json!(0), json!(10)]).unwrap();
        assert_eq!(result, json!(0.0));
        
        // Test negative numbers
        let result = multiply(vec![json!(-2), json!(3)]).unwrap();
        assert_eq!(result, json!(-6.0));
    }
    
    #[test]
    fn test_concat_edge_cases() {
        // Test empty args
        let result = concat(vec![]).unwrap();
        assert_eq!(result, json!(""));
        
        // Test mixed types
        let result = concat(vec![json!("number: "), json!(42), json!(", bool: "), json!(true)]).unwrap();
        assert_eq!(result, json!("number: 42, bool: true"));
    }
    
    #[test]
    fn test_case_conversion() {
        // Test uppercase with unicode
        let result = uppercase(vec![json!("hello WORLD 你好")]).unwrap();
        assert_eq!(result, json!("HELLO WORLD 你好"));
        
        // Test lowercase with mixed case
        let result = lowercase(vec![json!("HeLLo WoRLD")]).unwrap();
        assert_eq!(result, json!("hello world"));
    }
    
    #[tokio::test]
    async fn test_sleep_invalid_duration() {
        // Test with negative number
        let result = sleep(vec![json!(-100)]).await;
        assert!(result.is_err());
        
        // Test with non-number
        let result = sleep(vec![json!("not a number")]).await;
        assert!(result.is_err());
        
        // Test with decimal (should fail as sleep expects integer)
        let result = sleep(vec![json!(50.7)]).await;
        assert!(result.is_err());
    }
    
    #[tokio::test]
    async fn test_http_get_validation() {
        // Test without URL
        let result = http_get(vec![]).await;
        assert!(result.is_err());
        
        // Test with non-string URL
        let result = http_get(vec![json!(123)]).await;
        assert!(result.is_err());
        
        // Test with valid URL (simulated)
        let result = http_get(vec![json!("https://example.com")]).await;
        assert!(result.is_ok());
        let value = result.unwrap();
        assert_eq!(value["url"], json!("https://example.com"));
        assert_eq!(value["status"], json!("simulated"));
    }
}
use task_worker::plugins::PluginManager;
use task_common::TaskError;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;

#[tokio::test]
async fn test_plugin_manager_builtin_tasks() {
    let plugin_manager = Arc::new(PluginManager::new());
    
    // Test echo task
    let result = plugin_manager.execute_task("echo", vec![json!("test message")], Duration::from_secs(30)).await;
    assert!(result.is_ok());
    assert_eq!(result.unwrap(), json!("test message"));
    
    // Test list methods
    let methods = plugin_manager.list_methods();
    assert!(methods.contains(&"echo".to_string()));
    assert!(methods.contains(&"sleep".to_string()));
    assert!(methods.contains(&"http_get".to_string()));
}

#[tokio::test]
async fn test_plugin_manager_register_custom_task() {
    let plugin_manager = Arc::new(PluginManager::new());
    
    // Register a custom synchronous task
    fn custom_task(args: Vec<Value>) -> Result<Value, TaskError> {
        let name = args.get(0)
            .and_then(|v| v.as_str())
            .unwrap_or("World");
        Ok(json!(format!("Hello, {}!", name)))
    }
    
    plugin_manager.register_sync_task("greet", custom_task, None);
    
    // Execute custom task
    let result = plugin_manager.execute_task("greet", vec![json!("Alice")], Duration::from_secs(30)).await;
    assert!(result.is_ok());
    assert_eq!(result.unwrap(), json!("Hello, Alice!"));
    
    // Verify it's in the methods list
    let methods = plugin_manager.list_methods();
    assert!(methods.contains(&"greet".to_string()));
}

#[tokio::test]
async fn test_plugin_manager_task_not_found() {
    let plugin_manager = Arc::new(PluginManager::new());
    
    let result = plugin_manager.execute_task("non_existent_task", vec![], Duration::from_secs(30)).await;
    assert!(result.is_err());
    match result.unwrap_err() {
        TaskError::MethodNotFound(method) => assert_eq!(method, "non_existent_task"),
        _ => panic!("Expected MethodNotFound error"),
    }
}

#[tokio::test]
async fn test_sleep_task() {
    let plugin_manager = Arc::new(PluginManager::new());
    
    let start = std::time::Instant::now();
    let result = plugin_manager.execute_task("sleep", vec![json!(100)], Duration::from_secs(30)).await; // 100ms
    let elapsed = start.elapsed();
    
    assert!(result.is_ok());
    let result_value = result.unwrap();
    assert_eq!(result_value["slept_ms"], 100);
    assert!(elapsed >= Duration::from_millis(100));
    assert!(elapsed < Duration::from_millis(200)); // Should not take too much longer
}

#[tokio::test]
async fn test_async_task_execution() {
    use std::pin::Pin;
    use std::future::Future;
    
    let plugin_manager = Arc::new(PluginManager::new());
    
    // Register an async task
    fn async_task(args: Vec<Value>) -> Pin<Box<dyn Future<Output = Result<Value, TaskError>> + Send>> {
        Box::pin(async move {
            tokio::time::sleep(Duration::from_millis(50)).await;
            Ok(json!({ "async": true, "args": args }))
        })
    }
    
    plugin_manager.register_async_task("async_test", async_task, None);
    
    let result = plugin_manager.execute_task("async_test", vec![json!(1), json!(2)], Duration::from_secs(30)).await;
    assert!(result.is_ok());
    assert_eq!(result.unwrap(), json!({ "async": true, "args": [1, 2] }));
}
use serde_json::json;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use task_worker::{
    config::{Config, OperationMode},
    plugins::PluginManager,
};

#[cfg(test)]
mod worker_integration_tests {
    use super::*;

    #[test]
    fn test_config_defaults() {
        let config = Config::default();
        
        assert_eq!(config.worker.max_concurrent_tasks, 10);
        assert_eq!(config.worker.metrics_address, "0.0.0.0:9001");
        assert!(matches!(config.worker.mode, OperationMode::Worker));
        assert_eq!(config.queue.nats_url, "nats://localhost:4222");
        assert_eq!(config.queue.batch_size, 10);
        assert_eq!(config.queue.fetch_timeout_seconds, 30);
        assert!(!config.kubernetes.enabled);
        assert_eq!(config.kubernetes.namespace, "task-scheduler");
        assert!(config.plugins.auto_load);
        assert!(config.observability.tracing_enabled);
        assert_eq!(config.observability.log_level, "info");
    }

    #[test]
    fn test_operation_mode_from_str() {
        use std::str::FromStr;
        
        assert!(matches!(
            OperationMode::from_str("worker").unwrap(),
            OperationMode::Worker
        ));
        assert!(matches!(
            OperationMode::from_str("job").unwrap(),
            OperationMode::Job
        ));
        assert!(matches!(
            OperationMode::from_str("WORKER").unwrap(),
            OperationMode::Worker
        ));
        assert!(OperationMode::from_str("invalid").is_err());
    }

    #[test]
    fn test_config_from_env() {
        // Set environment variables
        std::env::set_var("NATS_URL", "nats://test-server:4222");
        std::env::set_var("LOG_LEVEL", "debug");
        std::env::set_var("MAX_CONCURRENT_TASKS", "20");
        std::env::set_var("TASK_METHOD", "test_method"); // This triggers job mode
        
        let config = Config::from_env();
        
        assert_eq!(config.queue.nats_url, "nats://test-server:4222");
        assert_eq!(config.observability.log_level, "debug");
        assert_eq!(config.worker.max_concurrent_tasks, 20);
        assert!(matches!(config.worker.mode, OperationMode::Job));
        
        // Clean up
        std::env::remove_var("NATS_URL");
        std::env::remove_var("LOG_LEVEL");
        std::env::remove_var("MAX_CONCURRENT_TASKS");
        std::env::remove_var("TASK_METHOD");
    }

    #[tokio::test]
    async fn test_plugin_manager_integration() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Test builtin tasks are available
        let methods = plugin_manager.list_methods();
        assert!(methods.len() >= 8); // At least 8 builtin tasks
        
        // Test executing various builtin tasks
        let test_cases = vec![
            ("echo", vec![json!("hello world")], json!("hello world")),
            ("add", vec![json!(10), json!(20), json!(5)], json!(35.0)),
            ("multiply", vec![json!(3), json!(4), json!(2)], json!(24.0)),
            ("concat", vec![json!("hello"), json!(" "), json!("world")], json!("hello world")),
            ("uppercase", vec![json!("test")], json!("TEST")),
            ("lowercase", vec![json!("TEST")], json!("test")),
        ];
        
        for (method, args, expected) in test_cases {
            let result = plugin_manager
                .execute_task(method, args, Duration::from_secs(5))
                .await
                .unwrap();
            assert_eq!(result, expected, "Failed for method: {}", method);
        }
    }

    #[tokio::test]
    async fn test_async_task_execution() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Test sleep task
        let start = std::time::Instant::now();
        let result = plugin_manager
            .execute_task("sleep", vec![json!(200)], Duration::from_secs(5))
            .await
            .unwrap();
        let elapsed = start.elapsed();
        
        assert!(elapsed.as_millis() >= 200);
        assert!(result.is_object());
        assert_eq!(result["slept_ms"], json!(200));
    }

    #[tokio::test]
    async fn test_concurrent_plugin_execution() {
        let plugin_manager = Arc::new(PluginManager::new());
        let execution_count = Arc::new(AtomicUsize::new(0));
        
        // Create multiple concurrent executions
        let mut handles = vec![];
        
        for i in 0..20 {
            let pm = plugin_manager.clone();
            let count = execution_count.clone();
            
            let handle = tokio::spawn(async move {
                let method = if i % 2 == 0 { "add" } else { "multiply" };
                let args = vec![json!(i), json!(2)];
                
                let result = pm.execute_task(method, args, Duration::from_secs(5)).await;
                
                if result.is_ok() {
                    count.fetch_add(1, Ordering::SeqCst);
                }
                
                result
            });
            
            handles.push(handle);
        }
        
        // Wait for all tasks
        let mut results = vec![];
        for handle in handles {
            if let Ok(Ok(result)) = handle.await {
                results.push(result);
            }
        }
        
        // Verify all executions completed
        assert_eq!(execution_count.load(Ordering::SeqCst), 20);
        assert_eq!(results.len(), 20);
    }

    #[tokio::test]
    async fn test_error_handling() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Test invalid arguments
        let test_cases = vec![
            ("echo", vec![], "at least one argument"),
            ("add", vec![json!("not a number"), json!("another")], "Add requires numeric arguments"),
            ("multiply", vec![json!(5)], "at least two arguments"),
            ("uppercase", vec![], "at least one argument"),
            ("sleep", vec![json!("not a number")], "must be a number"),
            ("http_get", vec![], "requires a URL"),
        ];
        
        for (method, args, expected_error) in test_cases {
            let result = plugin_manager
                .execute_task(method, args, Duration::from_secs(5))
                .await;
            
            assert!(result.is_err(), "Expected error for method: {}", method);
            let error_msg = result.unwrap_err().to_string();
            assert!(
                error_msg.contains(expected_error),
                "Error message '{}' should contain '{}' for method: {}",
                error_msg,
                expected_error,
                method
            );
        }
    }

    #[tokio::test]
    async fn test_custom_task_registration() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Register custom sync task
        static CALL_COUNT: AtomicUsize = AtomicUsize::new(0);
        
        fn custom_sync_task(args: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
            CALL_COUNT.fetch_add(1, Ordering::SeqCst);
            Ok(json!({ "custom": true, "args": args }))
        }
        
        plugin_manager.register_sync_task("custom_sync", custom_sync_task, Some("test_plugin".to_string()));
        
        // Register custom async task
        fn custom_async_task(
            args: Vec<serde_json::Value>,
        ) -> std::pin::Pin<Box<dyn std::future::Future<Output = task_common::error::TaskResult<serde_json::Value>> + Send>> {
            Box::pin(async move {
                tokio::time::sleep(Duration::from_millis(50)).await;
                Ok(json!({ "async_custom": true, "args": args }))
            })
        }
        
        plugin_manager.register_async_task("custom_async", custom_async_task, Some("test_plugin".to_string()));
        
        // Verify registration
        let methods = plugin_manager.list_methods();
        assert!(methods.contains(&"custom_sync".to_string()));
        assert!(methods.contains(&"custom_async".to_string()));
        
        // Execute custom tasks
        let sync_result = plugin_manager
            .execute_task("custom_sync", vec![json!("test")], Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(sync_result["custom"], json!(true));
        assert_eq!(CALL_COUNT.load(Ordering::SeqCst), 1);
        
        let async_result = plugin_manager
            .execute_task("custom_async", vec![json!("async test")], Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(async_result["async_custom"], json!(true));
        
        // Verify task info
        let sync_info = plugin_manager.get_task_info("custom_sync").unwrap();
        assert!(!sync_info.is_async);
        assert_eq!(sync_info.plugin_name, Some("test_plugin".to_string()));
        
        let async_info = plugin_manager.get_task_info("custom_async").unwrap();
        assert!(async_info.is_async);
        assert_eq!(async_info.plugin_name, Some("test_plugin".to_string()));
    }

    #[tokio::test]
    async fn test_task_timeout_enforcement() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Execute task with very short timeout
        let result = plugin_manager
            .execute_task("sleep", vec![json!(1000)], Duration::from_millis(100))
            .await;
        
        assert!(result.is_err());
        match result.unwrap_err() {
            task_common::error::TaskError::Timeout(secs) => {
                assert_eq!(secs, 0); // 100ms rounds down to 0 seconds
            }
            _ => panic!("Expected Timeout error"),
        }
    }

    #[tokio::test]
    async fn test_plugin_manager_thread_safety() {
        let plugin_manager = Arc::new(PluginManager::new());
        let barrier = Arc::new(tokio::sync::Barrier::new(10));
        
        // Spawn multiple tasks that register and execute concurrently
        let mut handles = vec![];
        
        for i in 0..10 {
            let pm = plugin_manager.clone();
            let b = barrier.clone();
            
            let handle = tokio::spawn(async move {
                // Wait for all tasks to be ready
                b.wait().await;
                
                // Register a unique task
                let task_name = format!("concurrent_task_{}", i);
                fn task(args: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
                    Ok(json!({ "executed": true, "args": args }))
                }
                
                pm.register_sync_task(&task_name, task, None);
                
                // Execute the task
                let result = pm
                    .execute_task(&task_name, vec![json!(i)], Duration::from_secs(5))
                    .await;
                
                (task_name, result)
            });
            
            handles.push(handle);
        }
        
        // Collect results
        let mut successful_tasks = 0;
        for handle in handles {
            if let Ok((task_name, result)) = handle.await {
                if result.is_ok() {
                    successful_tasks += 1;
                    
                    // Verify task exists in the list
                    let methods = plugin_manager.list_methods();
                    assert!(methods.contains(&task_name));
                }
            }
        }
        
        assert_eq!(successful_tasks, 10);
    }
}
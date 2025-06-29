use serde_json::json;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use task_worker::plugins::PluginManager;

#[cfg(test)]
mod worker_lifecycle_tests {
    use super::*;

    /// Test plugin manager lifecycle with dynamic task registration and unregistration
    #[tokio::test]
    async fn test_plugin_lifecycle() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Initial state - only builtin tasks
        let initial_methods = plugin_manager.list_methods();
        let initial_count = initial_methods.len();
        assert!(initial_count >= 8); // At least 8 builtin tasks
        
        // Register multiple custom tasks
        let custom_tasks = vec![
            ("lifecycle_task_1", "plugin_a"),
            ("lifecycle_task_2", "plugin_a"),
            ("lifecycle_task_3", "plugin_b"),
            ("lifecycle_task_4", "plugin_b"),
            ("lifecycle_task_5", "plugin_c"),
        ];
        
        for (task_name, plugin_name) in &custom_tasks {
            fn test_task(_: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
                Ok(json!({ "executed": true }))
            }
            
            plugin_manager.register_sync_task(task_name, test_task, Some(plugin_name.to_string()));
        }
        
        // Verify all tasks are registered
        let all_methods = plugin_manager.list_methods();
        assert_eq!(all_methods.len(), initial_count + custom_tasks.len());
        
        for (task_name, _) in &custom_tasks {
            assert!(all_methods.contains(&task_name.to_string()));
            
            // Verify task info
            let info = plugin_manager.get_task_info(task_name).unwrap();
            assert!(!info.is_async);
            assert!(info.plugin_name.is_some());
        }
        
        // Execute some tasks to ensure they work
        for (task_name, _) in &custom_tasks[..3] {
            let result = plugin_manager
                .execute_task(task_name, vec![json!("test")], Duration::from_secs(5))
                .await;
            assert!(result.is_ok());
            assert_eq!(result.unwrap()["executed"], json!(true));
        }
        
        // Simulate plugin unload - remove plugin_a tasks
        let result = plugin_manager.unload_plugin("plugin_a").await;
        assert!(result.is_ok());
        
        // Verify plugin_a tasks are removed
        let methods_after_unload = plugin_manager.list_methods();
        assert!(!methods_after_unload.contains(&"lifecycle_task_1".to_string()));
        assert!(!methods_after_unload.contains(&"lifecycle_task_2".to_string()));
        
        // Verify other plugins' tasks remain
        assert!(methods_after_unload.contains(&"lifecycle_task_3".to_string()));
        assert!(methods_after_unload.contains(&"lifecycle_task_4".to_string()));
        assert!(methods_after_unload.contains(&"lifecycle_task_5".to_string()));
        
        // Verify unloaded tasks can't be executed
        let result = plugin_manager
            .execute_task("lifecycle_task_1", vec![], Duration::from_secs(5))
            .await;
        assert!(result.is_err());
        match result.unwrap_err() {
            task_common::error::TaskError::MethodNotFound(method) => {
                assert_eq!(method, "lifecycle_task_1");
            }
            _ => panic!("Expected MethodNotFound error"),
        }
    }

    /// Test concurrent registration and execution
    #[tokio::test]
    async fn test_concurrent_lifecycle_operations() {
        let plugin_manager = Arc::new(PluginManager::new());
        let registration_count = Arc::new(AtomicUsize::new(0));
        let execution_count = Arc::new(AtomicUsize::new(0));
        
        // Pre-register some test tasks to ensure we have something to execute
        for i in 0..5 {
            let task_name = format!("preregistered_task_{}", i);
            fn test_task(_: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
                Ok(json!({ "preregistered": true }))
            }
            plugin_manager.register_sync_task(&task_name, test_task, Some("test_plugin".to_string()));
        }
        
        // Spawn tasks that continuously register new methods
        let mut handles = vec![];
        
        // Registration tasks
        for i in 0..2 {
            let pm = plugin_manager.clone();
            let reg_count = registration_count.clone();
            
            let handle = tokio::spawn(async move {
                for task_num in 0..10 {
                    let task_name = format!("concurrent_task_{}_{}", i, task_num);
                    
                    fn concurrent_task(_: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
                        Ok(json!({ "concurrent": true }))
                    }
                    
                    pm.register_sync_task(&task_name, concurrent_task, Some(format!("concurrent_plugin_{}", i)));
                    reg_count.fetch_add(1, Ordering::SeqCst);
                    
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            });
            handles.push(handle);
        }
        
        // Execute tasks concurrently
        for _i in 0..3 {
            let pm = plugin_manager.clone();
            let exec_count = execution_count.clone();
            
            let handle = tokio::spawn(async move {
                for j in 0..20 {
                    let methods = pm.list_methods();
                    if !methods.is_empty() {
                        // Try to execute both preregistered and newly registered tasks
                        let method_name = if j < 10 {
                            // First, execute preregistered tasks
                            format!("preregistered_task_{}", j % 5)
                        } else {
                            // Then try some concurrent tasks
                            let idx = j % methods.len();
                            methods.get(idx).cloned().unwrap_or_else(|| "echo".to_string())
                        };
                        
                        let result = pm
                            .execute_task(&method_name, vec![json!("test")], Duration::from_secs(1))
                            .await;
                        
                        if result.is_ok() {
                            exec_count.fetch_add(1, Ordering::SeqCst);
                        }
                    }
                    
                    tokio::time::sleep(Duration::from_millis(5)).await;
                }
            });
            handles.push(handle);
        }
        
        // Wait for all tasks to complete
        for handle in handles {
            handle.await.unwrap();
        }
        
        // Verify we registered and executed tasks
        assert!(registration_count.load(Ordering::SeqCst) >= 20, 
                "Expected at least 20 registrations, got {}", 
                registration_count.load(Ordering::SeqCst));
        assert!(execution_count.load(Ordering::SeqCst) > 0, 
                "Expected some executions, got {}", 
                execution_count.load(Ordering::SeqCst));
        
        // Verify the plugin manager is still functional
        let final_methods = plugin_manager.list_methods();
        assert!(final_methods.len() > 8); // More than just builtin tasks
    }

    /// Test resource cleanup and error recovery
    #[tokio::test]
    async fn test_error_recovery_and_cleanup() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Register a task that sometimes fails
        static FAIL_COUNTER: AtomicUsize = AtomicUsize::new(0);
        
        fn unreliable_task(_: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
            let count = FAIL_COUNTER.fetch_add(1, Ordering::SeqCst);
            if count % 3 == 0 {
                Err(task_common::error::TaskError::ExecutionFailed("Simulated failure".to_string()))
            } else {
                Ok(json!({ "count": count }))
            }
        }
        
        plugin_manager.register_sync_task("unreliable", unreliable_task, None);
        
        // Execute the task multiple times
        let mut results = vec![];
        for _ in 0..10 {
            let result = plugin_manager
                .execute_task("unreliable", vec![], Duration::from_secs(5))
                .await;
            results.push(result);
        }
        
        // Verify we got both successes and failures
        let successes = results.iter().filter(|r| r.is_ok()).count();
        let failures = results.iter().filter(|r| r.is_err()).count();
        
        assert!(successes > 0);
        assert!(failures > 0);
        assert_eq!(successes + failures, 10);
        
        // Register a task that panics
        fn panicking_task(_: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
            panic!("Task panic test!");
        }
        
        plugin_manager.register_sync_task("panic_task", panicking_task, None);
        
        // Execute the panicking task - should handle the panic gracefully
        let result = plugin_manager
            .execute_task("panic_task", vec![], Duration::from_secs(5))
            .await;
        
        assert!(result.is_err());
        match result.unwrap_err() {
            task_common::error::TaskError::ExecutionFailed(msg) => {
                assert!(msg.contains("panicked"));
            }
            _ => panic!("Expected ExecutionFailed error"),
        }
        
        // Verify the plugin manager still works after panic
        let echo_result = plugin_manager
            .execute_task("echo", vec![json!("still working")], Duration::from_secs(5))
            .await;
        assert!(echo_result.is_ok());
        assert_eq!(echo_result.unwrap(), json!("still working"));
    }

    /// Test memory and resource management under load
    #[tokio::test]
    async fn test_resource_management_under_load() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Register a memory-intensive task
        fn memory_task(args: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
            let size = args.get(0)
                .and_then(|v| v.as_u64())
                .unwrap_or(1000) as usize;
            
            // Allocate some memory
            let data = vec![0u8; size];
            
            // Do some work with it
            let sum: u64 = data.iter().map(|&b| b as u64).sum();
            
            Ok(json!({ "size": size, "sum": sum }))
        }
        
        plugin_manager.register_sync_task("memory_task", memory_task, None);
        
        // Execute many tasks concurrently
        let mut handles = vec![];
        for i in 0..100 {
            let pm = plugin_manager.clone();
            let handle = tokio::spawn(async move {
                let size = (i % 10 + 1) * 1000; // Vary the memory size
                pm.execute_task("memory_task", vec![json!(size)], Duration::from_secs(5))
                    .await
            });
            handles.push(handle);
        }
        
        // Wait for all tasks
        let mut success_count = 0;
        for handle in handles {
            if let Ok(Ok(_)) = handle.await {
                success_count += 1;
            }
        }
        
        // Most tasks should succeed
        assert!(success_count > 90);
        
        // Register many tasks to test registration limits
        for i in 0..1000 {
            let task_name = format!("bulk_task_{}", i);
            fn bulk_task(_: Vec<serde_json::Value>) -> task_common::error::TaskResult<serde_json::Value> {
                Ok(json!({ "bulk": true }))
            }
            
            plugin_manager.register_sync_task(&task_name, bulk_task, None);
        }
        
        // Verify all tasks were registered
        let methods = plugin_manager.list_methods();
        assert!(methods.len() >= 1008); // 8+ builtin + 1000 bulk tasks
        
        // Clean up by unloading (this would work with real plugin system)
        // For now, just verify we can still execute tasks
        let result = plugin_manager
            .execute_task("bulk_task_500", vec![], Duration::from_secs(5))
            .await;
        assert!(result.is_ok());
    }

    /// Test task cancellation behavior
    #[tokio::test]
    async fn test_task_cancellation() {
        let plugin_manager = Arc::new(PluginManager::new());
        
        // Register a long-running async task
        fn long_running_task(
            _: Vec<serde_json::Value>,
        ) -> std::pin::Pin<Box<dyn std::future::Future<Output = task_common::error::TaskResult<serde_json::Value>> + Send>> {
            Box::pin(async move {
                // Simulate long work with multiple checkpoints
                for _ in 0..10 {
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    // In a real implementation, we'd check for cancellation here
                }
                Ok(json!({ "completed": true }))
            })
        }
        
        plugin_manager.register_async_task("long_task", long_running_task, None);
        
        // Execute with a timeout shorter than the task duration
        let result = plugin_manager
            .execute_task("long_task", vec![], Duration::from_millis(300))
            .await;
        
        // Should timeout
        assert!(result.is_err());
        match result.unwrap_err() {
            task_common::error::TaskError::Timeout(_) => (),
            _ => panic!("Expected Timeout error"),
        }
        
        // Verify system is still responsive after timeout
        let quick_result = plugin_manager
            .execute_task("echo", vec![json!("responsive")], Duration::from_secs(5))
            .await;
        assert!(quick_result.is_ok());
    }
}
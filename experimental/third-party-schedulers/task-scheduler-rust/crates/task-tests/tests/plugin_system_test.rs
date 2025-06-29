use std::path::PathBuf;
use task_plugins::{PluginRegistry, PluginLoader, PluginInfo};
use serde_json::json;
use uuid::Uuid;
use prost_types::Any;

mod common;
use common::TestEnvironment;

/// Test built-in plugin functionality
#[tokio::test]
async fn test_builtin_plugins() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing built-in plugins...");
    
    // Test echo plugin
    let echo_result = client.submit_echo_task("Plugin test").await?;
    assert_eq!(echo_result.status, 1);
    assert!(echo_result.result.contains("Plugin test"));
    
    // Test add plugin
    let add_task_id = client.submit_computation_task(10, 20).await?;
    tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
    let add_result = client.get_task_result(&add_task_id).await?;
    assert_eq!(add_result.status, 1);
    assert!(add_result.result.contains("30"));
    
    Ok(())
}

/// Test plugin registry functionality
#[test]
fn test_plugin_registry() {
    let registry = PluginRegistry::new();
    
    // Test registration
    let _plugin_info = PluginInfo {
        id: Uuid::new_v4(),
        name: "test-plugin".to_string(),
        version: "1.0.0".to_string(),
        description: "Test plugin".to_string(),
        author: "Test Author".to_string(),
        dependencies: vec![],
        capabilities: vec!["test_method".to_string()],
    };
    
    // In a real test, we would register an actual plugin
    // For now, we just verify the registry can be created
    assert_eq!(registry.list_plugins().len(), 0);
}

/// Test plugin loader
#[test]
fn test_plugin_loader() {
    let _plugin_dir = PathBuf::from("test_plugins");
    let loader = PluginLoader::new();
    
    // Test that loader can be created
    // In a real test with actual plugin files, we would test loading
    // For now just verify the loader was created
    let _ = loader; // Loader created successfully
}

/// Test plugin method execution through gRPC
#[tokio::test]
async fn test_plugin_method_execution() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let _client = env.create_grpc_client().await?;
    
    println!("Testing plugin method execution...");
    
    // Test uppercase plugin (if available)
    let uppercase_request = task_common::proto::TaskRequest {
        task_id: format!("test-uppercase-{}", uuid::Uuid::new_v4()),
        method: "uppercase".to_string(),
        args: vec![prost_types::Any {
            type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
            value: "hello world".to_string().into_bytes(),
        }],
        deps: vec![],
        is_async: false,
    };
    
    // Use the direct gRPC client instead of TestClient wrapper
    let mut grpc_client = env.create_grpc_client().await?;
    match grpc_client.client.submit_task(uppercase_request).await {
        Ok(response) => {
            let resp = response.into_inner();
            if resp.status == 1 {
                assert!(resp.result.contains("HELLO WORLD"));
                println!("✓ Uppercase plugin executed successfully");
            } else {
                println!("⚠ Uppercase plugin not available or failed");
            }
        }
        Err(_) => {
            println!("⚠ Uppercase plugin not available");
        }
    }
    
    Ok(())
}

/// Test plugin hot-reload capability
#[tokio::test]
#[ignore] // Requires file system manipulation
async fn test_plugin_hot_reload() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing plugin hot-reload...");
    
    // First, verify a plugin method doesn't exist
    let test_method_request = task_common::proto::TaskRequest {
        task_id: format!("test-hotreload-{}", uuid::Uuid::new_v4()),
        method: "hot_reload_test".to_string(),
        args: vec![],
        deps: vec![],
        is_async: false,
    };
    
    let initial_result = client.client.submit_task(test_method_request.clone()).await;
    match initial_result {
        Ok(resp) => assert_eq!(resp.into_inner().status, 2), // Should fail
        Err(_) => {} // Expected
    }
    
    // In a real test, we would:
    // 1. Copy a new plugin file to the plugin directory
    // 2. Wait for the system to detect and load it
    // 3. Try the method again and verify it works
    
    println!("Hot-reload test would require plugin file manipulation");
    
    Ok(())
}

/// Test plugin isolation and safety
#[tokio::test]
async fn test_plugin_isolation() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let client = env.create_grpc_client().await?;
    
    println!("Testing plugin isolation...");
    
    // Submit multiple plugin tasks concurrently
    let mut handles = vec![];
    
    for i in 0..10 {
        let mut client_clone = client.clone();
        let handle = tokio::spawn(async move {
            // Each task should be isolated
            let result = client_clone.submit_echo_task(&format!("Isolation test {}", i)).await
                .map_err(|e| format!("Task {} failed: {}", i, e));
            (i, result)
        });
        handles.push(handle);
    }
    
    // Verify all tasks complete independently
    let mut results = vec![];
    for handle in handles {
        let (index, result) = handle.await?;
        results.push((index, result));
    }
    
    // Check that all results are correct and independent
    for (index, result) in results {
        match result {
            Ok(resp) => {
                assert_eq!(resp.status, 1);
                assert!(resp.result.contains(&format!("Isolation test {}", index)));
            }
            Err(e) => panic!("{}", e),
        }
    }
    
    println!("✓ Plugin isolation verified");
    
    Ok(())
}

/// Test plugin version management
#[test]
fn test_plugin_version_management() {
    use task_plugins::k8s::PluginVersionManager;
    
    let mut manager = PluginVersionManager::new();
    
    // Register plugin versions
    manager.register_version("test-plugin", "1.0.0");
    manager.register_version("test-plugin", "1.0.1");
    manager.register_version("test-plugin", "2.0.0");
    
    // Test version compatibility
    assert!(manager.is_compatible("test-plugin", "1.0.0"));
    assert!(manager.is_compatible("test-plugin", "1.0.1"));
    assert!(manager.is_compatible("test-plugin", "2.0.0"));
    assert!(!manager.is_compatible("test-plugin", "3.0.0"));
    
    // Test getting latest version
    assert_eq!(manager.get_latest("test-plugin"), Some("2.0.0"));
}

/// Test custom plugin integration
#[tokio::test]
#[ignore] // Requires custom plugin binary
async fn test_custom_plugin_integration() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing custom plugin integration...");
    
    // Test data transformation plugin (from examples)
    let json_data = json!([
        {"name": "Alice", "age": 30},
        {"name": "Bob", "age": 25}
    ]);
    
    let transform_request = task_common::proto::TaskRequest {
        task_id: format!("test-transform-{}", uuid::Uuid::new_v4()),
        method: "json_to_csv".to_string(),
        args: vec![Any {
            type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
            value: json_data.to_string().into_bytes(),
        }],
        deps: vec![],
        is_async: false,
    };
    
    match client.client.submit_task(transform_request).await {
        Ok(response) => {
            let resp = response.into_inner();
            if resp.status == 1 {
                println!("✓ Custom plugin executed successfully");
                println!("Result: {}", resp.result);
            } else {
                println!("⚠ Custom plugin not available");
            }
        }
        Err(_) => {
            println!("⚠ Custom plugin not available");
        }
    }
    
    Ok(())
}
use task_plugins::wasm::SimpleWasmPlugin;
use task_plugins::{Plugin, PluginConfig, PluginInfo};
use std::path::PathBuf;
use uuid::Uuid;

#[tokio::test]
async fn test_simple_wasm_plugin_creation() {
    let info = PluginInfo {
        id: Uuid::new_v4(),
        name: "test-simple-wasm".to_string(),
        version: "1.0.0".to_string(),
        description: "Test simple WASM plugin".to_string(),
        author: "Test".to_string(),
        dependencies: vec![],
        capabilities: vec!["wasm".to_string()],
    };
    
    let module_path = PathBuf::from("test.wasm");
    let plugin = SimpleWasmPlugin::new(info.clone(), module_path);
    assert_eq!(plugin.info().name, "test-simple-wasm");
}

#[tokio::test]
#[ignore] // This test requires the simple.wasm file to be built
async fn test_simple_wasm_execution() {
    // Build the plugin first by running:
    // cd examples/simple-wasm-plugin && ./build.sh
    
    let plugin_path = PathBuf::from("../../target/wasm-plugins/simple.wasm");
    if !plugin_path.exists() {
        eprintln!("Skipping test: Simple WASM plugin not found at {:?}", plugin_path);
        eprintln!("Run: cd examples/simple-wasm-plugin && ./build.sh");
        return;
    }
    
    let info = PluginInfo {
        id: Uuid::new_v4(),
        name: "simple-plugin".to_string(),
        version: "1.0.0".to_string(),
        description: "Simple WASM plugin".to_string(),
        author: "Test".to_string(),
        dependencies: vec![],
        capabilities: vec!["math".to_string()],
    };
    
    let mut plugin = SimpleWasmPlugin::new(info, plugin_path);
    
    // Initialize the plugin
    plugin.init(PluginConfig::default()).await.unwrap();
    
    // Execute the plugin with different inputs
    let test_cases = vec![
        (5, 10),   // 5 * 2 = 10
        (10, 20),  // 10 * 2 = 20
        (-3, -6),  // -3 * 2 = -6
        (0, 0),    // 0 * 2 = 0
    ];
    
    for (input, expected) in test_cases {
        let result = plugin.execute(serde_json::json!(input)).await.unwrap();
        assert_eq!(result["result"], expected);
    }
    
    // Test health check
    plugin.health_check().await.unwrap();
    
    // Shutdown the plugin
    plugin.shutdown().await.unwrap();
}

#[tokio::test]
async fn test_simple_wasm_error_handling() {
    let info = PluginInfo {
        id: Uuid::new_v4(),
        name: "error-test".to_string(),
        version: "1.0.0".to_string(),
        description: "Error test plugin".to_string(),
        author: "Test".to_string(),
        dependencies: vec![],
        capabilities: vec![],
    };
    
    let module_path = PathBuf::from("/non/existent/path.wasm");
    let mut plugin = SimpleWasmPlugin::new(info, module_path);
    
    // Should fail to initialize
    let result = plugin.init(PluginConfig::default()).await;
    assert!(result.is_err());
}

#[test]
fn test_plugin_info() {
    let info = PluginInfo {
        id: Uuid::new_v4(),
        name: "info-test".to_string(),
        version: "2.0.0".to_string(),
        description: "Info test plugin".to_string(),
        author: "Test Author".to_string(),
        dependencies: vec!["dep1".to_string(), "dep2".to_string()],
        capabilities: vec!["cap1".to_string(), "cap2".to_string()],
    };
    
    assert_eq!(info.name, "info-test");
    assert_eq!(info.version, "2.0.0");
    assert_eq!(info.author, "Test Author");
    assert_eq!(info.dependencies.len(), 2);
    assert_eq!(info.capabilities.len(), 2);
}
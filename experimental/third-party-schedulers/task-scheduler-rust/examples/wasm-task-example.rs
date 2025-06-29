use task_plugins::wasm::SimpleWasmPlugin;
use task_plugins::{Plugin, PluginConfig, PluginInfo};
use std::path::PathBuf;
use uuid::Uuid;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize logging
    tracing_subscriber::fmt::init();
    
    println!("WebAssembly Task Plugin Example");
    println!("================================\n");
    
    // Create plugin info
    let info = PluginInfo {
        id: Uuid::new_v4(),
        name: "math-plugin".to_string(),
        version: "1.0.0".to_string(),
        description: "Mathematical operations in WebAssembly".to_string(),
        author: "Task Scheduler Team".to_string(),
        dependencies: vec![],
        capabilities: vec!["math".to_string(), "wasm".to_string()],
    };
    
    // Path to the compiled WASM module
    let module_path = PathBuf::from("target/wasm-plugins/simple.wasm");
    
    // Check if the WASM module exists
    if !module_path.exists() {
        eprintln!("Error: WASM module not found at {:?}", module_path);
        eprintln!("Please build the plugin first:");
        eprintln!("  cd examples/simple-wasm-plugin && ./build.sh");
        return Ok(());
    }
    
    // Create the WASM plugin
    let mut plugin = SimpleWasmPlugin::new(info, module_path);
    
    // Initialize the plugin
    println!("Initializing WASM plugin...");
    plugin.init(PluginConfig::default()).await?;
    println!("Plugin initialized successfully!\n");
    
    // Test cases
    let test_cases = vec![
        (5, "Calculate 5 * 2"),
        (10, "Calculate 10 * 2"),
        (42, "Calculate 42 * 2"),
        (-7, "Calculate -7 * 2"),
        (0, "Calculate 0 * 2"),
    ];
    
    println!("Running test cases:");
    println!("------------------");
    
    for (input, description) in test_cases {
        println!("\n{}", description);
        
        // Execute the plugin
        let result = plugin.execute(serde_json::json!(input)).await?;
        
        // Extract the result
        if let Some(output) = result.get("result").and_then(|v| v.as_i64()) {
            println!("  Input:  {}", input);
            println!("  Output: {}", output);
            println!("  ✓ Success!");
        } else {
            println!("  ✗ Failed to get result");
        }
    }
    
    // Health check
    println!("\nPerforming health check...");
    match plugin.health_check().await {
        Ok(()) => println!("✓ Plugin is healthy"),
        Err(e) => println!("✗ Health check failed: {}", e),
    }
    
    // Shutdown
    println!("\nShutting down plugin...");
    plugin.shutdown().await?;
    println!("Plugin shutdown complete.");
    
    println!("\n================================");
    println!("Example completed successfully!");
    
    Ok(())
}
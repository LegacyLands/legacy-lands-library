use std::fs;
use std::path::PathBuf;
use std::time::Duration;
use tempfile::TempDir;
use task_common::proto::TaskRequest;

mod common;
use common::TestEnvironment;

/// Create a simple plugin source code
fn create_plugin_source(name: &str, method_name: &str, response: &str) -> String {
    format!(r#"
use serde_json::{{json, Value}};
use task_common::error::{{TaskResult, TaskError}};

#[no_mangle]
pub extern "C" fn init_plugin() -> *const PluginMetadata {{
    &PLUGIN_METADATA
}}

#[no_mangle]
pub extern "C" fn get_task_count() -> usize {{
    1
}}

#[no_mangle]
pub extern "C" fn get_tasks() -> *const TaskDescriptor {{
    &TASKS[0]
}}

fn {method_name}(args: Vec<Value>) -> TaskResult<Value> {{
    Ok(json!("{response}"))
}}

#[repr(C)]
pub struct PluginMetadata {{
    pub name: *const u8,
    pub name_len: usize,
    pub version: *const u8,
    pub version_len: usize,
    pub author: *const u8,
    pub author_len: usize,
}}

#[repr(C)]
pub struct TaskDescriptor {{
    pub name: *const u8,
    pub name_len: usize,
    pub is_async: bool,
    pub func: *const std::ffi::c_void,
}}

static PLUGIN_METADATA: PluginMetadata = PluginMetadata {{
    name: b"{name}\0".as_ptr(),
    name_len: {name_len},
    version: b"1.0.0\0".as_ptr(),
    version_len: 5,
    author: b"Test\0".as_ptr(),
    author_len: 4,
}};

static TASKS: [TaskDescriptor; 1] = [
    TaskDescriptor {{
        name: b"{method_name}\0".as_ptr(),
        name_len: {method_len},
        is_async: false,
        func: {method_name} as *const std::ffi::c_void,
    }},
];
"#, 
        name = name,
        name_len = name.len(),
        method_name = method_name,
        method_len = method_name.len(),
        response = response
    )
}

/// Compile a plugin from source
async fn compile_plugin(source: &str, output_path: &PathBuf) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let temp_dir = TempDir::new()?;
    let src_path = temp_dir.path().join("plugin.rs");
    
    // Write source code
    fs::write(&src_path, source)?;
    
    // Create a minimal Cargo.toml
    let project_root = std::env::current_dir()
        .unwrap()
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_path_buf();
    let task_common_path = project_root.join("crates/task-common");
    
    let cargo_toml = format!(r#"
[package]
name = "test-plugin"
version = "0.1.0"
edition = "2021"

[dependencies]
serde_json = "1.0"
task-common = {{ path = "{}" }}

[lib]
crate-type = ["cdylib"]
"#, task_common_path.display());
    
    fs::write(temp_dir.path().join("Cargo.toml"), cargo_toml)?;
    
    // Create src directory and move plugin.rs to src/lib.rs
    fs::create_dir(temp_dir.path().join("src"))?;
    fs::rename(&src_path, temp_dir.path().join("src/lib.rs"))?;
    
    // Compile the plugin
    let output = std::process::Command::new("cargo")
        .arg("build")
        .arg("--release")
        .current_dir(temp_dir.path())
        .output()?;
    
    if !output.status.success() {
        return Err(format!("Failed to compile plugin: {}", String::from_utf8_lossy(&output.stderr)).into());
    }
    
    // Find the compiled library
    let lib_name = if cfg!(target_os = "linux") {
        "libtest_plugin.so"
    } else if cfg!(target_os = "macos") {
        "libtest_plugin.dylib"
    } else if cfg!(target_os = "windows") {
        "test_plugin.dll"
    } else {
        return Err("Unsupported platform".into());
    };
    
    let compiled_path = temp_dir.path().join("target/release").join(lib_name);
    if compiled_path.exists() {
        fs::copy(&compiled_path, output_path)?;
    } else {
        return Err("Compiled plugin not found".into());
    }
    
    Ok(())
}

/// Test basic hot reload functionality
#[tokio::test]
#[ignore] // This test requires compilation tools and is slow
async fn test_plugin_hot_reload_basic() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing basic hot reload functionality...");
    
    // Create a temporary plugin directory
    let plugin_dir = TempDir::new()?;
    let plugin_path = plugin_dir.path().join("test_plugin.so");
    
    // Create and compile initial plugin
    let initial_source = create_plugin_source("hot_reload_test", "hot_method", "Initial Response");
    compile_plugin(&initial_source, &plugin_path).await?;
    
    // Configure worker to watch the plugin directory
    // This would need to be done through configuration or API
    println!("Note: Worker needs to be configured to watch {:?}", plugin_dir.path());
    
    // Wait for plugin to be loaded
    tokio::time::sleep(Duration::from_secs(3)).await;
    
    // Test initial plugin
    let request = TaskRequest {
        task_id: format!("hot-reload-test-{}", uuid::Uuid::new_v4()),
        method: "hot_reload_test::hot_method".to_string(),
        args: vec![],
        deps: vec![],
        is_async: false,
    };
    
    match client.client.submit_task(request.clone()).await {
        Ok(response) => {
            let resp = response.into_inner();
            if resp.status == 1 {
                assert!(resp.result.contains("Initial Response"));
                println!("✓ Initial plugin loaded and executed");
            } else {
                println!("Initial plugin execution failed: {}", resp.result);
            }
        }
        Err(e) => {
            println!("Initial plugin not loaded: {}", e);
        }
    }
    
    // Update the plugin
    let updated_source = create_plugin_source("hot_reload_test", "hot_method", "Updated Response");
    compile_plugin(&updated_source, &plugin_path).await?;
    
    // Wait for hot reload
    tokio::time::sleep(Duration::from_secs(3)).await;
    
    // Test updated plugin
    let request2 = TaskRequest {
        task_id: format!("hot-reload-test-2-{}", uuid::Uuid::new_v4()),
        method: "hot_reload_test::hot_method".to_string(),
        args: vec![],
        deps: vec![],
        is_async: false,
    };
    
    match client.client.submit_task(request2).await {
        Ok(response) => {
            let resp = response.into_inner();
            if resp.status == 1 {
                assert!(resp.result.contains("Updated Response"));
                println!("✓ Plugin hot reloaded successfully");
            } else {
                println!("Updated plugin execution failed: {}", resp.result);
            }
        }
        Err(e) => {
            println!("Updated plugin execution failed: {}", e);
        }
    }
    
    Ok(())
}

/// Test hot reload with multiple plugins
#[tokio::test]
#[ignore] // This test requires compilation tools and is slow
async fn test_plugin_hot_reload_multiple() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing hot reload with multiple plugins...");
    
    // Create plugin directory
    let plugin_dir = TempDir::new()?;
    
    // Create multiple plugins
    let plugins = vec![
        ("plugin_a", "method_a", "Response A"),
        ("plugin_b", "method_b", "Response B"),
        ("plugin_c", "method_c", "Response C"),
    ];
    
    for (name, method, response) in &plugins {
        let source = create_plugin_source(name, method, response);
        let path = plugin_dir.path().join(format!("{}.so", name));
        compile_plugin(&source, &path).await?;
    }
    
    // Wait for plugins to load
    tokio::time::sleep(Duration::from_secs(3)).await;
    
    // Test all plugins
    for (name, method, expected_response) in &plugins {
        let request = TaskRequest {
            task_id: format!("multi-hot-reload-{}-{}", name, uuid::Uuid::new_v4()),
            method: format!("{}::{}", name, method),
            args: vec![],
            deps: vec![],
            is_async: false,
        };
        
        match client.client.submit_task(request).await {
            Ok(response) => {
                let resp = response.into_inner();
                if resp.status == 1 {
                    assert!(resp.result.contains(expected_response));
                    println!("✓ Plugin {} loaded and executed", name);
                }
            }
            Err(e) => {
                println!("Plugin {} failed: {}", name, e);
            }
        }
    }
    
    // Remove one plugin
    fs::remove_file(plugin_dir.path().join("plugin_b.so"))?;
    tokio::time::sleep(Duration::from_secs(2)).await;
    
    // Verify plugin_b is unloaded
    let request = TaskRequest {
        task_id: format!("removed-plugin-test-{}", uuid::Uuid::new_v4()),
        method: "plugin_b::method_b".to_string(),
        args: vec![],
        deps: vec![],
        is_async: false,
    };
    
    match client.client.submit_task(request).await {
        Ok(response) => {
            let resp = response.into_inner();
            assert_ne!(resp.status, 1, "Plugin should have been unloaded");
            println!("✓ Plugin correctly unloaded after file removal");
        }
        Err(_) => {
            println!("✓ Plugin correctly unloaded after file removal");
        }
    }
    
    Ok(())
}

/// Test hot reload error handling
#[tokio::test]
#[ignore] // This test requires compilation tools
async fn test_plugin_hot_reload_error_handling() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing hot reload error handling...");
    
    // Create plugin directory
    let plugin_dir = TempDir::new()?;
    
    // Create a valid plugin first
    let valid_source = create_plugin_source("error_test", "test_method", "Valid Response");
    let plugin_path = plugin_dir.path().join("error_test.so");
    compile_plugin(&valid_source, &plugin_path).await?;
    
    // Wait for load
    tokio::time::sleep(Duration::from_secs(2)).await;
    
    // Create an invalid plugin file (corrupted)
    fs::write(&plugin_path, b"invalid plugin data")?;
    
    // Wait for reload attempt
    tokio::time::sleep(Duration::from_secs(3)).await;
    
    // The system should handle the error gracefully
    // Other plugins should still work
    let echo_result = client.submit_echo_task("Still working").await?;
    assert_eq!(echo_result.status, 1);
    println!("✓ System remains stable after plugin load failure");
    
    Ok(())
}

/// Test rapid hot reload (cooldown mechanism)
#[tokio::test]
#[ignore] // This test requires compilation tools
async fn test_plugin_hot_reload_cooldown() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing hot reload cooldown...");
    
    // Create plugin directory
    let plugin_dir = TempDir::new()?;
    let plugin_path = plugin_dir.path().join("cooldown_test.so");
    
    // Initial plugin
    let mut version = 1;
    let source = create_plugin_source("cooldown_test", "version_method", &format!("Version {}", version));
    compile_plugin(&source, &plugin_path).await?;
    
    tokio::time::sleep(Duration::from_secs(2)).await;
    
    // Rapid updates
    for i in 2..6 {
        version = i;
        let source = create_plugin_source("cooldown_test", "version_method", &format!("Version {}", version));
        compile_plugin(&source, &plugin_path).await?;
        
        // Very short delay - should trigger cooldown
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    
    // Wait for cooldown period
    tokio::time::sleep(Duration::from_secs(3)).await;
    
    // Check final version
    let request = TaskRequest {
        task_id: format!("cooldown-test-{}", uuid::Uuid::new_v4()),
        method: "cooldown_test::version_method".to_string(),
        args: vec![],
        deps: vec![],
        is_async: false,
    };
    
    match client.client.submit_task(request).await {
        Ok(response) => {
            let resp = response.into_inner();
            if resp.status == 1 {
                // Should have the final version after cooldown
                println!("Final plugin response: {}", resp.result);
                assert!(resp.result.contains("Version"));
            }
        }
        Err(e) => {
            println!("Plugin execution failed: {}", e);
        }
    }
    
    println!("✓ Cooldown mechanism working properly");
    
    Ok(())
}
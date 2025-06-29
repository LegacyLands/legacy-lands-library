use crate::{Plugin, PluginConfig, PluginError, PluginInfo, PluginResult};
use async_trait::async_trait;
use std::any::Any;
use std::path::PathBuf;
use tracing::{debug, info};
use wasmtime::*;

/// Simplified WebAssembly plugin that doesn't use WASI
pub struct SimpleWasmPlugin {
    info: PluginInfo,
    module_path: PathBuf,
    engine: Engine,
    module: Option<Module>,
}

impl SimpleWasmPlugin {
    /// Create a new simple WASM plugin
    pub fn new(info: PluginInfo, module_path: PathBuf) -> Self {
        let mut config = Config::new();
        config.wasm_simd(true);
        config.wasm_bulk_memory(true);
        config.consume_fuel(true);
        
        let engine = Engine::new(&config).expect("Failed to create WASM engine");
        
        Self {
            info,
            module_path,
            engine,
            module: None,
        }
    }
    
    /// Load the WASM module from file
    async fn load_module(&mut self) -> PluginResult<()> {
        if !self.module_path.exists() {
            return Err(PluginError::LoadError(format!(
                "WASM module not found at: {:?}",
                self.module_path
            )));
        }
        
        let module_bytes = tokio::fs::read(&self.module_path)
            .await
            .map_err(|e| PluginError::LoadError(format!("Failed to read WASM module: {}", e)))?;
        
        let module = Module::new(&self.engine, &module_bytes)
            .map_err(|e| PluginError::LoadError(format!("Failed to compile WASM module: {}", e)))?;
        
        self.module = Some(module);
        
        info!("Loaded WASM module from {:?}", self.module_path);
        Ok(())
    }
    
    /// Execute a simple WASM function
    async fn call_simple_function(&self, func_name: &str, input: i32) -> PluginResult<i32> {
        let module = self.module.as_ref()
            .ok_or_else(|| PluginError::LoadError("Module not loaded".to_string()))?;
        
        let mut store = Store::new(&self.engine, ());
        
        // Set fuel limit
        store.set_fuel(10000)
            .map_err(|e| PluginError::ConfigError(format!("Failed to set fuel: {}", e)))?;
        
        // Create instance
        let instance = Instance::new(&mut store, module, &[])
            .map_err(|e| PluginError::LoadError(format!("Failed to instantiate module: {}", e)))?;
        
        // Get the function
        let func = instance.get_typed_func::<i32, i32>(&mut store, func_name)
            .map_err(|e| PluginError::ExecutionError(format!("Function '{}' not found: {}", func_name, e)))?;
        
        // Call the function
        let result = func.call(&mut store, input)
            .map_err(|e| PluginError::ExecutionError(format!("Function execution failed: {}", e)))?;
        
        Ok(result)
    }
}

#[async_trait]
impl Plugin for SimpleWasmPlugin {
    fn info(&self) -> &PluginInfo {
        &self.info
    }
    
    async fn init(&mut self, _config: PluginConfig) -> PluginResult<()> {
        info!("Initializing simple WASM plugin: {}", self.info.name);
        self.load_module().await?;
        info!("Simple WASM plugin initialized: {}", self.info.name);
        Ok(())
    }
    
    async fn execute(&self, input: serde_json::Value) -> PluginResult<serde_json::Value> {
        debug!("Executing simple WASM plugin: {} with input: {:?}", self.info.name, input);
        
        // For demonstration, we'll just call a simple function
        // In a real implementation, you'd need to serialize/deserialize properly
        let input_num = input.as_i64().unwrap_or(0) as i32;
        let result = self.call_simple_function("process", input_num).await?;
        
        Ok(serde_json::json!({ "result": result }))
    }
    
    async fn shutdown(&mut self) -> PluginResult<()> {
        info!("Shutting down simple WASM plugin: {}", self.info.name);
        self.module = None;
        Ok(())
    }
    
    async fn health_check(&self) -> PluginResult<()> {
        if self.module.is_none() {
            return Err(PluginError::ExecutionError("Module not loaded".to_string()));
        }
        Ok(())
    }
    
    fn as_any(&self) -> &dyn Any {
        self
    }
}
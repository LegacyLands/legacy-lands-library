use crate::{Plugin, PluginConfig, PluginError, PluginInfo, PluginResult};
use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::any::Any;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::{debug, error, info};
use uuid::Uuid;
use wasmtime::*;
use wasmtime_wasi::preview1::{WasiCtx, WasiCtxBuilder};

/// WebAssembly plugin configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmPluginConfig {
    /// Path to the WASM module file
    pub module_path: PathBuf,
    
    /// Memory limit in bytes (default: 100MB)
    pub memory_limit: Option<u64>,
    
    /// CPU time limit in milliseconds per execution (default: 5000ms)
    pub cpu_time_limit: Option<u64>,
    
    /// Enable WASI support
    pub enable_wasi: bool,
    
    /// Environment variables to pass to the WASM module
    pub env_vars: Vec<(String, String)>,
    
    /// Directories to allow access to (for WASI)
    pub allowed_dirs: Vec<PathBuf>,
    
    /// Custom host functions to expose
    pub host_functions: Vec<String>,
}

impl Default for WasmPluginConfig {
    fn default() -> Self {
        Self {
            module_path: PathBuf::new(),
            memory_limit: Some(100 * 1024 * 1024), // 100MB
            cpu_time_limit: Some(5000), // 5 seconds
            enable_wasi: true,
            env_vars: vec![],
            allowed_dirs: vec![],
            host_functions: vec![],
        }
    }
}

/// WebAssembly plugin implementation
pub struct WasmPlugin {
    info: PluginInfo,
    config: WasmPluginConfig,
    engine: Engine,
    module: Option<Module>,
    store: Arc<Mutex<Option<Store<WasiCtx>>>>,
    instance: Arc<Mutex<Option<Instance>>>,
}

impl WasmPlugin {
    /// Create a new WebAssembly plugin
    pub fn new(info: PluginInfo, wasm_config: WasmPluginConfig) -> Self {
        // Configure the engine with limits
        let mut config = Config::new();
        config.wasm_simd(true);
        config.wasm_bulk_memory(true);
        config.wasm_multi_value(true);
        config.wasm_reference_types(true);
        
        // Set memory limits
        if let Some(_limit) = wasm_config.memory_limit {
            config.memory_init_cow(false);
            // Memory limits are set per-store in newer wasmtime versions
        }
        
        let engine = Engine::new(&config).expect("Failed to create WASM engine");
        
        Self {
            info,
            config: wasm_config,
            engine,
            module: None,
            store: Arc::new(Mutex::new(None)),
            instance: Arc::new(Mutex::new(None)),
        }
    }
    
    /// Load the WASM module from file
    async fn load_module(&mut self) -> PluginResult<()> {
        if !self.config.module_path.exists() {
            return Err(PluginError::LoadError(format!(
                "WASM module not found at: {:?}",
                self.config.module_path
            )));
        }
        
        let module_bytes = tokio::fs::read(&self.config.module_path)
            .await
            .map_err(|e| PluginError::LoadError(format!("Failed to read WASM module: {}", e)))?;
        
        let module = Module::new(&self.engine, &module_bytes)
            .map_err(|e| PluginError::LoadError(format!("Failed to compile WASM module: {}", e)))?;
        
        self.module = Some(module);
        
        info!("Loaded WASM module from {:?}", self.config.module_path);
        Ok(())
    }
    
    /// Create a new store and instance
    async fn create_instance(&self) -> PluginResult<(Store<WasiCtx>, Instance)> {
        let module = self.module.as_ref()
            .ok_or_else(|| PluginError::LoadError("Module not loaded".to_string()))?;
        
        // Create WASI context
        let mut wasi_builder = WasiCtxBuilder::new();
        
        // Add environment variables
        for (key, value) in &self.config.env_vars {
            wasi_builder.env(key, value);
        }
        
        // Add allowed directories
        for dir in &self.config.allowed_dirs {
            if dir.exists() && dir.is_dir() {
                wasi_builder.preopened_dir(
                    dir.clone(),
                    dir.to_string_lossy(),
                    wasmtime_wasi::DirPerms::all(),
                    wasmtime_wasi::FilePerms::all(),
                )
                .map_err(|e| PluginError::ConfigError(format!("Failed to add directory: {}", e)))?;
            }
        }
        
        // Inherit stdio for debugging
        wasi_builder.inherit_stdio();
        
        let wasi_ctx = wasi_builder.build();
        let mut store = Store::new(&self.engine, wasi_ctx);
        
        // Set resource limits
        if let Some(cpu_limit) = self.config.cpu_time_limit {
            store.set_fuel(cpu_limit * 1000)
                .map_err(|e| PluginError::ConfigError(format!("Failed to set fuel limit: {}", e)))?;
            store.fuel_async_yield_interval(Some(10000))
                .map_err(|e| PluginError::ConfigError(format!("Failed to set fuel yield interval: {}", e)))?;
        }
        
        // Create linker and add WASI
        let mut linker = Linker::new(&self.engine);
        if self.config.enable_wasi {
            // For wasmtime 28.0, we need to use the synchronous version for core modules
            wasmtime_wasi::preview1::add_to_linker_async(&mut linker)
                .map_err(|e| PluginError::LoadError(format!("Failed to add WASI to linker: {}", e)))?;
        }
        
        // Add custom host functions
        self.add_host_functions(&mut linker)?;
        
        // Instantiate the module
        let instance = linker.instantiate_async(&mut store, module)
            .await
            .map_err(|e| PluginError::LoadError(format!("Failed to instantiate module: {}", e)))?;
        
        Ok((store, instance))
    }
    
    /// Add custom host functions
    fn add_host_functions(&self, linker: &mut Linker<WasiCtx>) -> PluginResult<()> {
        // Add logging function
        linker.func_wrap("host", "log", |mut caller: Caller<'_, WasiCtx>, ptr: i32, len: i32| {
            // Get memory export
            let mem_export = caller.get_export("memory");
            if mem_export.is_none() {
                return Err(anyhow::anyhow!("Memory export not found").into());
            }
            let memory = mem_export.unwrap().into_memory();
            if memory.is_none() {
                return Err(anyhow::anyhow!("Memory export is not a memory").into());
            }
            let memory = memory.unwrap();
            
            let data = memory.data(&caller);
            let message = std::str::from_utf8(&data[ptr as usize..(ptr + len) as usize])
                .map_err(|e| PluginError::ExecutionError(format!("Invalid UTF-8: {}", e)))?;
            
            info!("[WASM] {}", message);
            Ok(())
        })
        .map_err(|e| PluginError::ConfigError(format!("Failed to add log function: {}", e)))?;
        
        // Add more host functions as needed
        for func_name in &self.config.host_functions {
            match func_name.as_str() {
                "get_time" => {
                    linker.func_wrap("host", "get_time", || -> i64 {
                        chrono::Utc::now().timestamp_millis()
                    })
                    .map_err(|e| PluginError::ConfigError(format!("Failed to add get_time function: {}", e)))?;
                }
                _ => {
                    debug!("Unknown host function requested: {}", func_name);
                }
            }
        }
        
        Ok(())
    }
    
    /// Call a WASM function
    async fn call_function(&self, func_name: &str, input: serde_json::Value) -> PluginResult<serde_json::Value> {
        let (mut store, instance) = self.create_instance().await?;
        
        // Serialize input to JSON
        let input_json = serde_json::to_string(&input)
            .map_err(|e| PluginError::ExecutionError(format!("Failed to serialize input: {}", e)))?;
        
        // Get memory export
        let memory = instance.get_memory(&mut store, "memory")
            .ok_or_else(|| PluginError::ExecutionError("No memory export found".to_string()))?;
        
        // Allocate memory for input
        let alloc_func = instance.get_typed_func::<i32, i32>(&mut store, "alloc")
            .map_err(|e| PluginError::ExecutionError(format!("Failed to get alloc function: {}", e)))?;
        
        let input_len = input_json.len() as i32;
        let input_ptr = alloc_func.call_async(&mut store, input_len).await
            .map_err(|e| PluginError::ExecutionError(format!("Failed to allocate memory: {}", e)))?;
        
        // Write input to memory
        memory.write(&mut store, input_ptr as usize, input_json.as_bytes())
            .map_err(|e| PluginError::ExecutionError(format!("Failed to write to memory: {}", e)))?;
        
        // Get the function to call
        let func = instance.get_typed_func::<(i32, i32), i32>(&mut store, func_name)
            .map_err(|e| PluginError::ExecutionError(format!("Function '{}' not found: {}", func_name, e)))?;
        
        // Call the function
        let result_ptr = func.call_async(&mut store, (input_ptr, input_len)).await
            .map_err(|e| PluginError::ExecutionError(format!("Function execution failed: {}", e)))?;
        
        // Get result length
        let result_len_func = instance.get_typed_func::<i32, i32>(&mut store, "result_len")
            .map_err(|e| PluginError::ExecutionError(format!("Failed to get result_len function: {}", e)))?;
        
        let result_len = result_len_func.call_async(&mut store, result_ptr).await
            .map_err(|e| PluginError::ExecutionError(format!("Failed to get result length: {}", e)))?;
        
        // Read result from memory
        let mut result_bytes = vec![0u8; result_len as usize];
        memory.read(&store, result_ptr as usize, &mut result_bytes)
            .map_err(|e| PluginError::ExecutionError(format!("Failed to read result: {}", e)))?;
        
        // Free memory
        let free_func = instance.get_typed_func::<i32, ()>(&mut store, "free")
            .map_err(|e| PluginError::ExecutionError(format!("Failed to get free function: {}", e)))?;
        
        free_func.call_async(&mut store, input_ptr).await
            .map_err(|e| PluginError::ExecutionError(format!("Failed to free input memory: {}", e)))?;
        
        free_func.call_async(&mut store, result_ptr).await
            .map_err(|e| PluginError::ExecutionError(format!("Failed to free result memory: {}", e)))?;
        
        // Parse result
        let result_str = std::str::from_utf8(&result_bytes)
            .map_err(|e| PluginError::ExecutionError(format!("Invalid UTF-8 in result: {}", e)))?;
        
        let result = serde_json::from_str(result_str)
            .map_err(|e| PluginError::ExecutionError(format!("Failed to parse result JSON: {}", e)))?;
        
        Ok(result)
    }
}

#[async_trait]
impl Plugin for WasmPlugin {
    fn info(&self) -> &PluginInfo {
        &self.info
    }
    
    async fn init(&mut self, _config: PluginConfig) -> PluginResult<()> {
        info!("Initializing WASM plugin: {}", self.info.name);
        
        // Load the WASM module
        self.load_module().await?;
        
        // Verify the module exports required functions
        let module = self.module.as_ref().unwrap();
        let mut exports = module.exports();
        
        let required_exports = ["memory", "alloc", "free", "execute", "result_len"];
        for required_export in required_exports {
            let mut found = false;
            let mut exports = module.exports();
            while let Some(export) = exports.next() {
                if export.name() == required_export {
                    found = true;
                    break;
                }
            }
            if !found {
                return Err(PluginError::LoadError(format!(
                    "Required export '{}' not found in WASM module",
                    required_export
                )));
            }
        }
        
        // Call init function if it exists
        let (mut store, instance) = self.create_instance().await?;
        if let Ok(init_func) = instance.get_typed_func::<(), ()>(&mut store, "init") {
            init_func.call_async(&mut store, ()).await
                .map_err(|e| PluginError::LoadError(format!("Init function failed: {}", e)))?;
        }
        
        info!("WASM plugin initialized successfully: {}", self.info.name);
        Ok(())
    }
    
    async fn execute(&self, input: serde_json::Value) -> PluginResult<serde_json::Value> {
        debug!("Executing WASM plugin: {} with input: {:?}", self.info.name, input);
        
        let result = self.call_function("execute", input).await?;
        
        debug!("WASM plugin execution completed: {}", self.info.name);
        Ok(result)
    }
    
    async fn shutdown(&mut self) -> PluginResult<()> {
        info!("Shutting down WASM plugin: {}", self.info.name);
        
        // Call shutdown function if it exists
        if self.module.is_some() {
            let (mut store, instance) = self.create_instance().await?;
            if let Ok(shutdown_func) = instance.get_typed_func::<(), ()>(&mut store, "shutdown") {
                shutdown_func.call_async(&mut store, ()).await
                    .map_err(|e| error!("Shutdown function failed: {}", e))
                    .ok();
            }
        }
        
        // Clear the module
        self.module = None;
        *self.store.lock().await = None;
        *self.instance.lock().await = None;
        
        info!("WASM plugin shutdown complete: {}", self.info.name);
        Ok(())
    }
    
    async fn health_check(&self) -> PluginResult<()> {
        if self.module.is_none() {
            return Err(PluginError::ExecutionError("Module not loaded".to_string()));
        }
        
        // Try to create an instance as a health check
        self.create_instance().await?;
        
        Ok(())
    }
    
    fn as_any(&self) -> &dyn Any {
        self
    }
}
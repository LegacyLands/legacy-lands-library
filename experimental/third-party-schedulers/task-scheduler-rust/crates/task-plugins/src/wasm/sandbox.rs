use crate::{PluginError, PluginResult};
use std::collections::HashMap;
use std::path::PathBuf;
use std::time::Duration;
use tokio::time::timeout;
use tracing::{debug, info, warn};
use wasmtime::*;
use wasmtime_wasi::preview1::{WasiCtx, WasiCtxBuilder};

/// Security configuration for WASM sandbox
#[derive(Debug, Clone)]
pub struct SandboxConfig {
    /// Maximum memory in bytes (default: 100MB)
    pub max_memory: u64,
    
    /// Maximum CPU time per execution in milliseconds (default: 5000ms)
    pub max_cpu_time: u64,
    
    /// Maximum number of instances (default: 10)
    pub max_instances: usize,
    
    /// Enable network access (default: false)
    pub allow_network: bool,
    
    /// Enable filesystem access (default: false)  
    pub allow_filesystem: bool,
    
    /// Allowed environment variables
    pub allowed_env_vars: Vec<String>,
    
    /// Allowed directories (only if filesystem access is enabled)
    pub allowed_directories: Vec<PathBuf>,
    
    /// Resource limits
    pub resource_limits: ResourceLimits,
}

impl Default for SandboxConfig {
    fn default() -> Self {
        Self {
            max_memory: 100 * 1024 * 1024, // 100MB
            max_cpu_time: 5000, // 5 seconds
            max_instances: 10,
            allow_network: false,
            allow_filesystem: false,
            allowed_env_vars: vec![],
            allowed_directories: vec![],
            resource_limits: ResourceLimits::default(),
        }
    }
}

/// Resource limits for WASM execution
#[derive(Debug, Clone)]
pub struct ResourceLimits {
    /// Maximum number of open file descriptors
    pub max_open_files: u32,
    
    /// Maximum number of threads
    pub max_threads: u32,
    
    /// Maximum stack size in bytes
    pub max_stack_size: u64,
    
    /// Maximum number of function calls
    pub max_call_depth: u32,
}

impl Default for ResourceLimits {
    fn default() -> Self {
        Self {
            max_open_files: 10,
            max_threads: 1,
            max_stack_size: 1024 * 1024, // 1MB
            max_call_depth: 1000,
        }
    }
}

/// WebAssembly sandbox for secure plugin execution
pub struct WasmSandbox {
    config: SandboxConfig,
    engine: Engine,
}

impl WasmSandbox {
    /// Create a new WASM sandbox
    pub fn new(config: SandboxConfig) -> PluginResult<Self> {
        // Configure the engine with security settings
        let mut engine_config = Config::new();
        
        // Enable fuel for CPU time limiting
        engine_config.consume_fuel(true);
        
        // Memory configuration
        engine_config.memory_guard_size(0);
        engine_config.memory_init_cow(false);
        
        // Security features
        engine_config.wasm_simd(true);
        engine_config.wasm_bulk_memory(true);
        engine_config.wasm_reference_types(true);
        engine_config.wasm_multi_value(true);
        
        // Disable features that could be security risks
        engine_config.wasm_threads(false); // No threading for now
        engine_config.wasm_relaxed_simd(false);
        
        let engine = Engine::new(&engine_config)
            .map_err(|e| PluginError::ConfigError(format!("Failed to create engine: {}", e)))?;
        
        Ok(Self { config, engine })
    }
    
    /// Create a secure WASI context
    pub fn create_wasi_context(&self) -> PluginResult<WasiCtx> {
        let mut builder = WasiCtxBuilder::new();
        
        // Set up environment variables (filtered)
        for (key, value) in std::env::vars() {
            if self.config.allowed_env_vars.contains(&key) {
                builder.env(&key, &value);
            }
        }
        
        // Set up filesystem access
        if self.config.allow_filesystem {
            for dir in &self.config.allowed_directories {
                if dir.exists() && dir.is_dir() {
                    builder.preopened_dir(
                        dir.clone(),
                        dir.to_string_lossy(),
                        wasmtime_wasi::DirPerms::READ,
                        wasmtime_wasi::FilePerms::READ,
                    )
                    .map_err(|e| PluginError::ConfigError(format!("Failed to add directory: {}", e)))?;
                }
            }
        }
        
        // Set up stdio (null by default for security)
        // Use pipe for stdio
        let stdin = wasmtime_wasi::pipe::MemoryInputPipe::new(vec![]);
        let stdout = wasmtime_wasi::pipe::MemoryOutputPipe::new(
            self.config.resource_limits.max_open_files as usize,
        );
        let stderr = wasmtime_wasi::pipe::MemoryOutputPipe::new(
            self.config.resource_limits.max_open_files as usize,
        );
        
        builder.stdin(stdin);
        builder.stdout(stdout);
        builder.stderr(stderr);
        
        Ok(builder.build())
    }
    
    /// Create a secure store with resource limits
    pub fn create_store(&self, wasi_ctx: WasiCtx) -> PluginResult<Store<WasiCtx>> {
        let mut store = Store::new(&self.engine, wasi_ctx);
        
        // Set fuel limit (CPU time)
        store.set_fuel(self.config.max_cpu_time * 1000)
            .map_err(|e| PluginError::ConfigError(format!("Failed to set fuel: {}", e)))?;
        
        // Set epoch deadline for interruption
        store.set_epoch_deadline(10);
        
        Ok(store)
    }
    
    /// Execute a WASM module in the sandbox
    pub async fn execute_module(
        &self,
        module: &Module,
        func_name: &str,
        input: Vec<u8>,
    ) -> PluginResult<Vec<u8>> {
        // Create WASI context and store
        let wasi_ctx = self.create_wasi_context()?;
        let mut store = self.create_store(wasi_ctx)?;
        
        // Create linker
        let mut linker = Linker::new(&self.engine);
        
        // Add WASI to linker
        wasmtime_wasi::preview1::add_to_linker_async(&mut linker)
            .map_err(|e| PluginError::ConfigError(format!("Failed to add WASI: {}", e)))?;
        
        // Add security monitoring functions
        self.add_security_hooks(&mut linker)?;
        
        // Instantiate the module
        let instance = linker.instantiate_async(&mut store, module)
            .await
            .map_err(|e| PluginError::ExecutionError(format!("Failed to instantiate: {}", e)))?;
        
        // Get memory
        let memory = instance.get_memory(&mut store, "memory")
            .ok_or_else(|| PluginError::ExecutionError("No memory export".to_string()))?;
        
        // Allocate memory for input
        let alloc_func = instance.get_typed_func::<i32, i32>(&mut store, "alloc")
            .map_err(|e| PluginError::ExecutionError(format!("No alloc function: {}", e)))?;
        
        let input_len = input.len() as i32;
        let input_ptr = alloc_func.call_async(&mut store, input_len).await
            .map_err(|e| PluginError::ExecutionError(format!("Allocation failed: {}", e)))?;
        
        // Write input to memory
        memory.write(&mut store, input_ptr as usize, &input)
            .map_err(|e| PluginError::ExecutionError(format!("Memory write failed: {}", e)))?;
        
        // Get the function to execute
        let func = instance.get_typed_func::<(i32, i32), i32>(&mut store, func_name)
            .map_err(|e| PluginError::ExecutionError(format!("Function not found: {}", e)))?;
        
        // Execute with timeout
        let execution_future = func.call_async(&mut store, (input_ptr, input_len));
        let result_ptr = timeout(
            Duration::from_millis(self.config.max_cpu_time),
            execution_future
        )
        .await
        .map_err(|_| PluginError::ExecutionError("Execution timeout".to_string()))?
        .map_err(|e| PluginError::ExecutionError(format!("Execution failed: {}", e)))?;
        
        // Get result length
        let result_len_func = instance.get_typed_func::<i32, i32>(&mut store, "result_len")
            .map_err(|e| PluginError::ExecutionError(format!("No result_len function: {}", e)))?;
        
        let result_len = result_len_func.call_async(&mut store, result_ptr).await
            .map_err(|e| PluginError::ExecutionError(format!("Failed to get result length: {}", e)))?;
        
        // Read result
        let mut result = vec![0u8; result_len as usize];
        memory.read(&store, result_ptr as usize, &mut result)
            .map_err(|e| PluginError::ExecutionError(format!("Memory read failed: {}", e)))?;
        
        // Clean up
        let free_func = instance.get_typed_func::<i32, ()>(&mut store, "free")
            .map_err(|e| PluginError::ExecutionError(format!("No free function: {}", e)))?;
        
        free_func.call_async(&mut store, input_ptr).await.ok();
        free_func.call_async(&mut store, result_ptr).await.ok();
        
        Ok(result)
    }
    
    /// Add security monitoring hooks
    fn add_security_hooks(&self, linker: &mut Linker<WasiCtx>) -> PluginResult<()> {
        // Add a hook to monitor memory allocations
        linker.func_wrap("security", "check_alloc", |size: i32| {
            if size < 0 || size > 10 * 1024 * 1024 { // 10MB limit per allocation
                warn!("Suspicious allocation size: {}", size);
                return Err(anyhow::anyhow!("Allocation size limit exceeded").into());
            }
            Ok(())
        })
        .map_err(|e| PluginError::ConfigError(format!("Failed to add security hook: {}", e)))?;
        
        // Add more security hooks as needed
        
        Ok(())
    }
    
    /// Validate a WASM module for security
    pub fn validate_module(&self, module_bytes: &[u8]) -> PluginResult<()> {
        // Parse the module
        let module = Module::new(&self.engine, module_bytes)
            .map_err(|e| PluginError::LoadError(format!("Invalid module: {}", e)))?;
        
        // Check exports
        let mut exports = module.exports();
        let required_exports = ["memory", "alloc", "free"];
        
        for required in required_exports {
            if !exports.any(|e| e.name() == required) {
                return Err(PluginError::LoadError(format!(
                    "Missing required export: {}",
                    required
                )));
            }
        }
        
        // Check for suspicious exports
        let mut exports = module.exports();
        while let Some(export) = exports.next() {
            match export.name() {
                // Allowed exports
                "memory" | "alloc" | "free" | "execute" | "init" | "shutdown" | "result_len" => {}
                // Suspicious exports
                name if name.starts_with("_") => {
                    warn!("Suspicious export found: {}", name);
                }
                _ => {
                    debug!("Custom export found: {}", export.name());
                }
            }
        }
        
        // Check imports (ensure no unauthorized imports)
        let mut imports = module.imports();
        while let Some(import) = imports.next() {
            match (import.module(), import.name()) {
                // Allowed WASI imports
                ("wasi_snapshot_preview1", _) => {}
                // Allowed host imports
                ("host", "log") | ("host", "get_time") => {}
                ("security", _) => {}
                // Deny everything else
                (module, name) => {
                    return Err(PluginError::LoadError(format!(
                        "Unauthorized import: {}::{}",
                        module, name
                    )));
                }
            }
        }
        
        info!("Module validation passed");
        Ok(())
    }
    
    /// Get resource usage statistics
    pub fn get_stats(&self, store: &Store<WasiCtx>) -> HashMap<String, u64> {
        let mut stats = HashMap::new();
        
        // Get fuel consumption
        if let Ok(fuel) = store.get_fuel() {
            stats.insert("fuel_consumed".to_string(), self.config.max_cpu_time * 1000 - fuel);
        }
        
        // Add more stats as needed
        
        stats
    }
}
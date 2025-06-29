use crate::{PluginError, PluginInfo, PluginResult};
use crate::wasm::{WasmPlugin, WasmPluginConfig};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, info, warn};
use uuid::Uuid;
use wasmtime::Module;

/// WebAssembly plugin loader
pub struct WasmPluginLoader {
    /// Directory to scan for WASM plugins
    plugin_dir: PathBuf,
    
    /// Loaded plugins
    plugins: Arc<RwLock<HashMap<String, Arc<WasmPlugin>>>>,
    
    /// Default configuration for WASM plugins
    default_config: WasmPluginConfig,
}

impl WasmPluginLoader {
    /// Create a new WASM plugin loader
    pub fn new(plugin_dir: PathBuf) -> Self {
        Self {
            plugin_dir,
            plugins: Arc::new(RwLock::new(HashMap::new())),
            default_config: WasmPluginConfig::default(),
        }
    }
    
    /// Set default configuration for WASM plugins
    pub fn with_default_config(mut self, config: WasmPluginConfig) -> Self {
        self.default_config = config;
        self
    }
    
    /// Load all WASM plugins from the plugin directory
    pub async fn load_all(&self) -> PluginResult<Vec<String>> {
        info!("Loading WASM plugins from {:?}", self.plugin_dir);
        
        if !self.plugin_dir.exists() {
            tokio::fs::create_dir_all(&self.plugin_dir)
                .await
                .map_err(|e| PluginError::LoadError(format!("Failed to create plugin directory: {}", e)))?;
            return Ok(vec![]);
        }
        
        let mut loaded_plugins = Vec::new();
        let mut entries = tokio::fs::read_dir(&self.plugin_dir)
            .await
            .map_err(|e| PluginError::LoadError(format!("Failed to read plugin directory: {}", e)))?;
        
        while let Some(entry) = entries.next_entry().await
            .map_err(|e| PluginError::LoadError(format!("Failed to read directory entry: {}", e)))? {
            let path = entry.path();
            
            // Check if it's a WASM file
            if path.extension().and_then(|s| s.to_str()) == Some("wasm") {
                match self.load_plugin(&path).await {
                    Ok(name) => {
                        loaded_plugins.push(name);
                    }
                    Err(e) => {
                        warn!("Failed to load plugin {:?}: {}", path, e);
                    }
                }
            }
        }
        
        info!("Loaded {} WASM plugins", loaded_plugins.len());
        Ok(loaded_plugins)
    }
    
    /// Load a single WASM plugin
    pub async fn load_plugin(&self, path: &Path) -> PluginResult<String> {
        debug!("Loading WASM plugin from {:?}", path);
        
        // Read plugin metadata
        let info = self.read_plugin_info(path).await?;
        let plugin_name = info.name.clone();
        
        // Create plugin configuration
        let mut config = self.default_config.clone();
        config.module_path = path.to_path_buf();
        
        // Create the plugin
        let plugin = Arc::new(WasmPlugin::new(info, config));
        
        // Store the plugin
        {
            let mut plugins = self.plugins.write().await;
            plugins.insert(plugin_name.clone(), plugin);
        }
        
        info!("Loaded WASM plugin: {}", plugin_name);
        Ok(plugin_name)
    }
    
    /// Read plugin information from WASM module
    async fn read_plugin_info(&self, path: &Path) -> PluginResult<PluginInfo> {
        // For now, we'll derive info from the filename and module exports
        // In a real implementation, you might want to embed this info in the WASM module
        let filename = path.file_stem()
            .and_then(|s| s.to_str())
            .ok_or_else(|| PluginError::LoadError("Invalid filename".to_string()))?;
        
        // Try to read a companion JSON file with metadata
        let metadata_path = path.with_extension("json");
        if metadata_path.exists() {
            let metadata_content = tokio::fs::read_to_string(&metadata_path)
                .await
                .map_err(|e| PluginError::LoadError(format!("Failed to read metadata: {}", e)))?;
            
            let info: PluginInfo = serde_json::from_str(&metadata_content)
                .map_err(|e| PluginError::LoadError(format!("Failed to parse metadata: {}", e)))?;
            
            return Ok(info);
        }
        
        // Default plugin info
        Ok(PluginInfo {
            id: Uuid::new_v4(),
            name: filename.to_string(),
            version: "1.0.0".to_string(),
            description: format!("WebAssembly plugin: {}", filename),
            author: "Unknown".to_string(),
            dependencies: vec![],
            capabilities: vec!["wasm".to_string()],
        })
    }
    
    /// Get a loaded plugin by name
    pub async fn get_plugin(&self, name: &str) -> Option<Arc<WasmPlugin>> {
        let plugins = self.plugins.read().await;
        plugins.get(name).cloned()
    }
    
    /// Unload a plugin
    pub async fn unload_plugin(&self, name: &str) -> PluginResult<()> {
        let mut plugins = self.plugins.write().await;
        if let Some(plugin) = plugins.remove(name) {
            // The plugin will be dropped when all references are released
            info!("Unloaded WASM plugin: {}", name);
            Ok(())
        } else {
            Err(PluginError::NotFound(name.to_string()))
        }
    }
    
    /// List all loaded plugins
    pub async fn list_plugins(&self) -> Vec<String> {
        let plugins = self.plugins.read().await;
        plugins.keys().cloned().collect()
    }
    
    /// Hot reload a plugin
    pub async fn reload_plugin(&self, name: &str) -> PluginResult<()> {
        let path = {
            let plugins = self.plugins.read().await;
            if let Some(plugin) = plugins.get(name) {
                // Get the module path from the plugin
                // This requires adding a method to expose the config
                self.plugin_dir.join(format!("{}.wasm", name))
            } else {
                return Err(PluginError::NotFound(name.to_string()));
            }
        };
        
        // Unload the old plugin
        self.unload_plugin(name).await?;
        
        // Load the new version
        self.load_plugin(&path).await?;
        
        info!("Hot reloaded WASM plugin: {}", name);
        Ok(())
    }
    
    /// Watch for plugin changes and automatically reload
    pub async fn watch_for_changes(&self) -> PluginResult<()> {
        // This could be implemented using notify crate or similar
        // For now, it's a placeholder
        warn!("Plugin watching not yet implemented");
        Ok(())
    }
}
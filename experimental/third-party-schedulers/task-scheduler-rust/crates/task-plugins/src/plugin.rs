use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::any::Any;
use thiserror::Error;
use uuid::Uuid;

/// Plugin error types
#[derive(Debug, Error)]
pub enum PluginError {
    #[error("Plugin load error: {0}")]
    LoadError(String),

    #[error("Plugin execution error: {0}")]
    ExecutionError(String),

    #[error("Plugin not found: {0}")]
    NotFound(String),

    #[error("Plugin version incompatible: expected {expected}, got {actual}")]
    VersionIncompatible { expected: String, actual: String },

    #[error("Plugin configuration error: {0}")]
    ConfigError(String),
}

pub type PluginResult<T> = Result<T, PluginError>;

/// Plugin metadata
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PluginInfo {
    pub id: Uuid,
    pub name: String,
    pub version: String,
    pub description: String,
    pub author: String,
    pub dependencies: Vec<String>,
    pub capabilities: Vec<String>,
}

/// Plugin configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PluginConfig {
    pub enabled: bool,
    pub priority: i32,
    pub timeout_ms: u64,
    pub max_retries: u32,
    pub custom_config: serde_json::Value,
}

impl Default for PluginConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            priority: 0,
            timeout_ms: 30000,
            max_retries: 3,
            custom_config: serde_json::Value::Null,
        }
    }
}

/// Plugin trait that all plugins must implement
#[async_trait]
pub trait Plugin: Send + Sync {
    /// Get plugin information
    fn info(&self) -> &PluginInfo;

    /// Initialize the plugin
    async fn init(&mut self, config: PluginConfig) -> PluginResult<()>;

    /// Execute the plugin with given input
    async fn execute(&self, input: serde_json::Value) -> PluginResult<serde_json::Value>;

    /// Shutdown the plugin
    async fn shutdown(&mut self) -> PluginResult<()>;

    /// Health check
    async fn health_check(&self) -> PluginResult<()> {
        Ok(())
    }

    /// Get plugin state as Any for downcasting
    fn as_any(&self) -> &dyn Any;
}

/// Plugin lifecycle events
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PluginEvent {
    Loading {
        plugin_name: String,
    },
    Loaded {
        plugin_name: String,
    },
    Initializing {
        plugin_name: String,
    },
    Initialized {
        plugin_name: String,
    },
    Executing {
        plugin_name: String,
        task_id: Uuid,
    },
    Executed {
        plugin_name: String,
        task_id: Uuid,
        success: bool,
    },
    Shutting {
        plugin_name: String,
    },
    Shutdown {
        plugin_name: String,
    },
    Error {
        plugin_name: String,
        error: String,
    },
}

/// Plugin execution context
#[derive(Debug, Clone)]
pub struct PluginContext {
    pub task_id: Uuid,
    pub trace_id: String,
    pub span_id: String,
    pub metadata: serde_json::Value,
}

/// Plugin factory trait for creating plugin instances
pub trait PluginFactory: Send + Sync {
    /// Create a new plugin instance
    fn create(&self) -> Box<dyn Plugin>;
}

/// Macro to simplify plugin registration
#[macro_export]
macro_rules! declare_plugin {
    ($plugin_type:ty) => {
        #[no_mangle]
        pub extern "C" fn _plugin_create() -> *mut dyn $crate::Plugin {
            let plugin = Box::new(<$plugin_type>::default());
            Box::into_raw(plugin)
        }
    };
}

use dashmap::DashMap;
use parking_lot::RwLock;
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;
use task_common::error::{TaskError, TaskResult};
use tokio::time::timeout;
use tracing::{debug, info, warn};

pub mod builtin;
pub mod dynamic;
pub mod hot_reload;

#[cfg(test)]
mod test;

use builtin::BuiltinTasks;
use dynamic::DynamicLoader;

/// Task function types
pub type SyncTaskFn = fn(Vec<Value>) -> TaskResult<Value>;
pub type AsyncTaskFn =
    fn(
        Vec<Value>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = TaskResult<Value>> + Send>>;

/// Task metadata
#[derive(Clone)]
pub struct TaskInfo {
    /// Whether the task is async
    pub is_async: bool,

    /// Plugin name (if from plugin)
    pub plugin_name: Option<String>,

    /// Registration time
    pub registered_at: u64,
}

/// Plugin manager handles task registration and execution
pub struct PluginManager {
    /// Synchronous tasks
    sync_tasks: Arc<DashMap<String, (SyncTaskFn, TaskInfo)>>,

    /// Asynchronous tasks
    async_tasks: Arc<DashMap<String, (AsyncTaskFn, TaskInfo)>>,

    /// Dynamic plugin loader
    dynamic_loader: Arc<RwLock<DynamicLoader>>,

    /// Builtin tasks
    _builtin_tasks: Arc<BuiltinTasks>,
}

impl PluginManager {
    /// Create a new plugin manager
    pub fn new() -> Self {
        let mut manager = Self {
            sync_tasks: Arc::new(DashMap::new()),
            async_tasks: Arc::new(DashMap::new()),
            dynamic_loader: Arc::new(RwLock::new(DynamicLoader::new())),
            _builtin_tasks: Arc::new(BuiltinTasks::new()),
        };

        // Register builtin tasks
        manager.register_builtin_tasks();

        manager
    }

    /// Register builtin tasks
    fn register_builtin_tasks(&mut self) {
        info!("Registering builtin tasks");

        // Echo task
        self.register_sync_task("echo", builtin::echo, None);

        // Math tasks
        self.register_sync_task("add", builtin::add, None);
        self.register_sync_task("multiply", builtin::multiply, None);

        // String tasks
        self.register_sync_task("concat", builtin::concat, None);
        self.register_sync_task("uppercase", builtin::uppercase, None);
        self.register_sync_task("lowercase", builtin::lowercase, None);

        // Async tasks
        self.register_async_task("sleep", builtin::sleep, None);
        self.register_async_task("http_get", builtin::http_get, None);
        
        // Fail task for testing error metrics
        self.register_sync_task("fail", builtin::fail_task, None);
        
        // Init task
        self.register_sync_task("init", builtin::init, None);

        info!(
            "Registered {} builtin tasks",
            self.sync_tasks.len() + self.async_tasks.len()
        );
    }

    /// Register a synchronous task
    pub fn register_sync_task(&self, name: &str, func: SyncTaskFn, plugin_name: Option<String>) {
        let info = TaskInfo {
            is_async: false,
            plugin_name,
            registered_at: chrono::Utc::now().timestamp_millis() as u64,
        };

        if self
            .sync_tasks
            .insert(name.to_string(), (func, info))
            .is_some()
        {
            warn!("Sync task '{}' was overwritten", name);
        } else {
            debug!("Registered sync task '{}'", name);
        }
    }

    /// Register an asynchronous task
    pub fn register_async_task(&self, name: &str, func: AsyncTaskFn, plugin_name: Option<String>) {
        let info = TaskInfo {
            is_async: true,
            plugin_name,
            registered_at: chrono::Utc::now().timestamp_millis() as u64,
        };

        if self
            .async_tasks
            .insert(name.to_string(), (func, info))
            .is_some()
        {
            warn!("Async task '{}' was overwritten", name);
        } else {
            debug!("Registered async task '{}'", name);
        }
    }

    /// Load a dynamic plugin
    pub async fn load_plugin(&self, path: &str) -> TaskResult<Vec<String>> {
        info!("Loading plugin from {}", path);

        let mut loader = self.dynamic_loader.write();
        let tasks = loader.load_plugin(path, self)?;

        info!("Loaded {} tasks from plugin {}", tasks.len(), path);
        Ok(tasks)
    }

    /// Unload a dynamic plugin
    pub async fn unload_plugin(&self, name: &str) -> TaskResult<()> {
        info!("Unloading plugin {}", name);

        // Get tasks to remove
        let mut tasks_to_remove = Vec::new();

        for entry in self.sync_tasks.iter() {
            if let Some(ref plugin) = entry.value().1.plugin_name {
                if plugin == name {
                    tasks_to_remove.push(entry.key().clone());
                }
            }
        }

        for entry in self.async_tasks.iter() {
            if let Some(ref plugin) = entry.value().1.plugin_name {
                if plugin == name {
                    tasks_to_remove.push(entry.key().clone());
                }
            }
        }

        // Remove tasks
        for task in &tasks_to_remove {
            self.sync_tasks.remove(task);
            self.async_tasks.remove(task);
        }

        // Try to unload plugin from dynamic loader (if it was dynamically loaded)
        // If the plugin wasn't dynamically loaded, just ignore the error
        let mut loader = self.dynamic_loader.write();
        let _ = loader.unload_plugin(name);

        info!(
            "Unloaded plugin {} ({} tasks removed)",
            name,
            tasks_to_remove.len()
        );
        Ok(())
    }

    /// Execute a task
    pub async fn execute_task(
        &self,
        method: &str,
        args: Vec<Value>,
        timeout_duration: Duration,
    ) -> TaskResult<Value> {
        debug!("Executing task '{}' with {} args", method, args.len());

        // Check if it's an async task
        if let Some(entry) = self.async_tasks.get(method) {
            let func = entry.value().0;
            let future = func(args);

            match timeout(timeout_duration, future).await {
                Ok(result) => result,
                Err(_) => Err(TaskError::Timeout(timeout_duration.as_secs())),
            }
        } else if let Some(entry) = self.sync_tasks.get(method) {
            let func = entry.value().0;

            // Run sync task in blocking thread
            let result = tokio::task::spawn_blocking(move || func(args))
                .await
                .map_err(|e| TaskError::ExecutionFailed(format!("Task panicked: {}", e)))?;

            result
        } else {
            Err(TaskError::MethodNotFound(method.to_string()))
        }
    }

    /// List all registered methods
    pub fn list_methods(&self) -> Vec<String> {
        let mut methods = Vec::new();

        for entry in self.sync_tasks.iter() {
            methods.push(entry.key().clone());
        }

        for entry in self.async_tasks.iter() {
            methods.push(entry.key().clone());
        }

        methods.sort();
        methods
    }

    /// Get task information
    pub fn get_task_info(&self, method: &str) -> Option<TaskInfo> {
        if let Some(entry) = self.sync_tasks.get(method) {
            Some(entry.value().1.clone())
        } else {
            self.async_tasks
                .get(method)
                .map(|entry| entry.value().1.clone())
        }
    }
}

impl Default for PluginManager {
    fn default() -> Self {
        Self::new()
    }
}

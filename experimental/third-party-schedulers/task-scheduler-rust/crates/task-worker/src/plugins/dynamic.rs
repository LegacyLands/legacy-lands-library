use crate::plugins::{AsyncTaskFn, PluginManager, SyncTaskFn};
use libloading::{Library, Symbol};
use std::collections::HashMap;
use std::path::Path;
use task_common::error::{TaskError, TaskResult};
use tracing::{debug, info};

/// Plugin metadata
#[repr(C)]
pub struct PluginMetadata {
    pub name: *const u8,
    pub name_len: usize,
    pub version: *const u8,
    pub version_len: usize,
    pub author: *const u8,
    pub author_len: usize,
}

/// Task descriptor
#[repr(C)]
pub struct TaskDescriptor {
    pub name: *const u8,
    pub name_len: usize,
    pub is_async: bool,
    pub func: *const std::ffi::c_void,
}

/// Plugin interface
pub type InitPluginFn = unsafe extern "C" fn() -> *const PluginMetadata;
pub type GetTasksFn = unsafe extern "C" fn() -> *const TaskDescriptor;
pub type GetTaskCountFn = unsafe extern "C" fn() -> usize;

/// Loaded plugin information
struct LoadedPlugin {
    _library: Library,
    name: String,
    version: String,
    tasks: Vec<String>,
}

/// Dynamic plugin loader
pub struct DynamicLoader {
    /// Loaded plugins
    plugins: HashMap<String, LoadedPlugin>,
}

impl Default for DynamicLoader {
    fn default() -> Self {
        Self::new()
    }
}

impl DynamicLoader {
    /// Create a new dynamic loader
    pub fn new() -> Self {
        Self {
            plugins: HashMap::new(),
        }
    }

    /// Load a plugin from file
    pub fn load_plugin(&mut self, path: &str, manager: &PluginManager) -> TaskResult<Vec<String>> {
        let path = Path::new(path);

        if !path.exists() {
            return Err(TaskError::PluginError(format!(
                "Plugin file not found: {}",
                path.display()
            )));
        }

        info!("Loading plugin from {}", path.display());

        // Load the library
        let library = unsafe {
            Library::new(path)
                .map_err(|e| TaskError::PluginError(format!("Failed to load plugin: {}", e)))?
        };

        // Get plugin metadata
        let metadata = unsafe {
            let init_fn: Symbol<InitPluginFn> = library.get(b"init_plugin\0").map_err(|e| {
                TaskError::PluginError(format!("Plugin missing init_plugin: {}", e))
            })?;

            let metadata_ptr = init_fn();
            if metadata_ptr.is_null() {
                return Err(TaskError::PluginError(
                    "Plugin returned null metadata".to_string(),
                ));
            }

            &*metadata_ptr
        };

        // Extract plugin info
        let name = unsafe {
            std::str::from_utf8(std::slice::from_raw_parts(metadata.name, metadata.name_len))
                .map_err(|e| TaskError::PluginError(format!("Invalid plugin name: {}", e)))?
                .to_string()
        };

        let version = unsafe {
            std::str::from_utf8(std::slice::from_raw_parts(
                metadata.version,
                metadata.version_len,
            ))
            .map_err(|e| TaskError::PluginError(format!("Invalid plugin version: {}", e)))?
            .to_string()
        };

        info!("Plugin: {} v{}", name, version);

        // Check if already loaded
        if self.plugins.contains_key(&name) {
            return Err(TaskError::PluginError(format!(
                "Plugin {} already loaded",
                name
            )));
        }

        // Get tasks
        let (task_count, tasks_ptr) = unsafe {
            let count_fn: Symbol<GetTaskCountFn> =
                library.get(b"get_task_count\0").map_err(|e| {
                    TaskError::PluginError(format!("Plugin missing get_task_count: {}", e))
                })?;

            let tasks_fn: Symbol<GetTasksFn> = library
                .get(b"get_tasks\0")
                .map_err(|e| TaskError::PluginError(format!("Plugin missing get_tasks: {}", e)))?;

            (count_fn(), tasks_fn())
        };

        if tasks_ptr.is_null() {
            return Err(TaskError::PluginError(
                "Plugin returned null tasks".to_string(),
            ));
        }

        let mut task_names = Vec::new();

        // Register tasks
        for i in 0..task_count {
            let task = unsafe { &*tasks_ptr.add(i) };

            let task_name = unsafe {
                std::str::from_utf8(std::slice::from_raw_parts(task.name, task.name_len))
                    .map_err(|e| TaskError::PluginError(format!("Invalid task name: {}", e)))?
            };

            let full_name = format!("{}::{}", name, task_name);
            debug!("Registering task: {} (async: {})", full_name, task.is_async);

            if task.is_async {
                // Register async task
                let func_ptr = task.func as *const ();
                let func: AsyncTaskFn = unsafe { std::mem::transmute(func_ptr) };
                manager.register_async_task(&full_name, func, Some(name.clone()));
            } else {
                // Register sync task
                let func_ptr = task.func as *const ();
                let func: SyncTaskFn = unsafe { std::mem::transmute(func_ptr) };
                manager.register_sync_task(&full_name, func, Some(name.clone()));
            }

            task_names.push(full_name);
        }

        info!("Loaded {} tasks from plugin {}", task_names.len(), name);

        // Store plugin info
        let plugin_info = LoadedPlugin {
            _library: library,
            name: name.clone(),
            version,
            tasks: task_names.clone(),
        };

        self.plugins.insert(name, plugin_info);

        Ok(task_names)
    }

    /// Unload a plugin
    pub fn unload_plugin(&mut self, name: &str) -> TaskResult<()> {
        if let Some(plugin) = self.plugins.remove(name) {
            info!(
                "Unloading plugin: {} (had {} tasks)",
                name,
                plugin.tasks.len()
            );

            // The library will be dropped here, unloading the dynamic library
            drop(plugin);

            Ok(())
        } else {
            Err(TaskError::PluginError(format!("Plugin {} not found", name)))
        }
    }

    /// List loaded plugins
    pub fn list_plugins(&self) -> Vec<(String, String, usize)> {
        self.plugins
            .values()
            .map(|p| (p.name.clone(), p.version.clone(), p.tasks.len()))
            .collect()
    }
}

/// Example plugin structure (for documentation)
///
/// A plugin must export the following C functions:
///
/// ```c
/// #include <stddef.h>
/// #include <stdbool.h>
///
/// typedef struct {
///     const char* name;
///     size_t name_len;
///     const char* version;
///     size_t version_len;
///     const char* author;
///     size_t author_len;
/// } PluginMetadata;
///
/// typedef struct {
///     const char* name;
///     size_t name_len;
///     bool is_async;
///     void* func;
/// } TaskDescriptor;
///
/// // Plugin metadata
/// static const PluginMetadata PLUGIN_METADATA = {
///     .name = "example_plugin",
///     .name_len = 14,
///     .version = "1.0.0",
///     .version_len = 5,
///     .author = "Example Author",
///     .author_len = 14,
/// };
///
/// // Task implementations
/// const char* example_task(const char* args_json) {
///     // Implementation
///     return result_json;
/// }
///
/// // Task descriptors
/// static const TaskDescriptor TASKS[] = {
///     {
///         .name = "example_task",
///         .name_len = 12,
///         .is_async = false,
///         .func = (void*)example_task,
///     },
/// };
///
/// // Required exports
/// const PluginMetadata* init_plugin() {
///     return &PLUGIN_METADATA;
/// }
///
/// size_t get_task_count() {
///     return sizeof(TASKS) / sizeof(TaskDescriptor);
/// }
///
/// const TaskDescriptor* get_tasks() {
///     return TASKS;
/// }
/// ```
#[doc(hidden)]
pub struct _DynamicPluginExample;

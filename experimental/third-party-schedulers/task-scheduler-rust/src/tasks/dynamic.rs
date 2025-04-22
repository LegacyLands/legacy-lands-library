use crate::error::{Result, TaskError};
use crate::error_log;
use crate::info_log;
use crate::models::ArgValue;
use crate::tasks::REGISTRY;
use crate::warn_log;
use libloading::{Library, Symbol};
use parking_lot::{Mutex, RwLock};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::time::{SystemTime, UNIX_EPOCH};

pub type SyncTaskFnPtr = unsafe fn(Vec<ArgValue>) -> Result<String>;
pub type AsyncTaskFnPtr =
    unsafe fn(Vec<ArgValue>) -> Pin<Box<dyn std::future::Future<Output = String> + Send>>;
pub type TaskEntryInfo = (&'static str, bool, usize);

pub type InitPluginFnPtr = unsafe fn() -> &'static [TaskEntryInfo];

pub struct DynamicLibraryInfo {
    pub path: PathBuf,
    pub load_time: u64,
    pub library: Library,
    pub registered_tasks: Vec<String>,
}

pub struct DynamicTaskLoader {
    libraries: RwLock<HashMap<String, DynamicLibraryInfo>>,
    plugin_dir: PathBuf,
}

impl DynamicTaskLoader {
    pub fn new<P: AsRef<Path>>(plugin_dir: P) -> Self {
        let plugin_dir = plugin_dir.as_ref().to_path_buf();

        if !plugin_dir.exists() {
            std::fs::create_dir_all(&plugin_dir).unwrap_or_else(|e| {
                error_log!(
                    "Failed to create plugin directory: {}: {}",
                    plugin_dir.display(),
                    e
                );
            });
        }

        Self {
            libraries: RwLock::new(HashMap::new()),
            plugin_dir,
        }
    }

    fn get_current_timestamp() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64
    }

    fn extract_lib_name(file_name: &str) -> Option<String> {
        let file_name = Path::new(file_name);
        let stem = file_name.file_stem()?.to_str()?;

        let name = if stem.starts_with("lib") && stem.len() > 3 {
            &stem[3..]
        } else {
            stem
        };

        Some(name.to_string())
    }

    pub fn load_plugin(&self, name: &str) -> Result<Vec<String>> {
        {
            let libraries = self.libraries.read();
            if libraries.contains_key(name) {
                return Err(TaskError::ExecutionError(format!(
                    "Plugin '{}' is already loaded",
                    name
                )));
            }
        }

        #[cfg(target_os = "windows")]
        let lib_path = self.plugin_dir.join(format!("{}.dll", name));

        #[cfg(target_os = "macos")]
        let lib_path = self.plugin_dir.join(format!("lib{}.dylib", name));

        #[cfg(not(any(target_os = "windows", target_os = "macos")))]
        let lib_path = self.plugin_dir.join(format!("lib{}.so", name));

        info_log!("Loading dynamic library from: {}", lib_path.display());

        let lib = unsafe { Library::new(&lib_path) }.map_err(|e| {
            TaskError::ExecutionError(format!("Failed to load dynamic library '{}': {}", name, e))
        })?;

        let init_fn: Symbol<InitPluginFnPtr> = unsafe { lib.get(b"init_plugin") }.map_err(|e| {
            TaskError::ExecutionError(format!(
                "Plugin '{}' missing 'init_plugin' symbol: {}",
                name, e
            ))
        })?;

        let tasks = unsafe { init_fn() };
        let mut registered = Vec::new();
        let current_time = Self::get_current_timestamp();

        for &(task_name, is_async, ptr_addr) in tasks {
            let full_name = format!("{}::{}", name, task_name);

            if is_async {
                let async_fn_ptr = ptr_addr as *const ();
                let async_fn: AsyncTaskFnPtr = unsafe { std::mem::transmute(async_fn_ptr) };

                if REGISTRY.register_dynamic_async_task(&full_name, async_fn, current_time) {
                    registered.push(full_name);
                } else {
                    warn_log!(
                        "Task '{}' already exists and could not be registered",
                        full_name
                    );
                }
            } else {
                let sync_fn_ptr = ptr_addr as *const ();
                let sync_fn: SyncTaskFnPtr = unsafe { std::mem::transmute(sync_fn_ptr) };

                if REGISTRY.register_dynamic_sync_task(&full_name, sync_fn, current_time) {
                    registered.push(full_name);
                } else {
                    warn_log!(
                        "Task '{}' already exists and could not be registered",
                        full_name
                    );
                }
            }
        }

        let lib_info = DynamicLibraryInfo {
            path: lib_path,
            load_time: current_time,
            library: lib,
            registered_tasks: registered.clone(),
        };

        self.libraries.write().insert(name.to_string(), lib_info);

        if registered.is_empty() {
            warn_log!("No tasks were registered from plugin '{}'", name);
        } else {
            info_log!(
                "Successfully registered {} tasks from plugin '{}'",
                registered.len(),
                name
            );
        }

        Ok(registered)
    }

    pub fn unload_plugin(&self, name: &str) -> Result<Vec<String>> {
        let mut libraries = self.libraries.write();

        if let Some(lib_info) = libraries.remove(name) {
            for task_name in &lib_info.registered_tasks {
                REGISTRY.unregister_task(task_name);
            }

            info_log!(
                "Unloaded plugin '{}' with {} tasks",
                name,
                lib_info.registered_tasks.len()
            );
            Ok(lib_info.registered_tasks)
        } else {
            Err(TaskError::ExecutionError(format!(
                "Plugin '{}' is not loaded",
                name
            )))
        }
    }

    pub fn scan_and_load_all(&self) -> Result<HashMap<String, Vec<String>>> {
        let mut results = HashMap::new();

        if !self.plugin_dir.exists() || !self.plugin_dir.is_dir() {
            return Ok(results);
        }

        let entries = std::fs::read_dir(&self.plugin_dir).map_err(|e| {
            TaskError::ExecutionError(format!(
                "Failed to read plugin directory '{}': {}",
                self.plugin_dir.display(),
                e
            ))
        })?;

        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() {
                let extension = path.extension().and_then(|e| e.to_str());

                let is_lib = match extension {
                    #[cfg(target_os = "windows")]
                    Some("dll") => true,

                    #[cfg(target_os = "macos")]
                    Some("dylib") => true,

                    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
                    Some("so") => true,

                    _ => false,
                };

                if is_lib {
                    if let Some(file_name) = path.file_name().and_then(|f| f.to_str()) {
                        if let Some(lib_name) = Self::extract_lib_name(file_name) {
                            match self.load_plugin(&lib_name) {
                                Ok(tasks) => {
                                    results.insert(lib_name, tasks);
                                }
                                Err(e) => {
                                    error_log!("Failed to load plugin '{}': {}", lib_name, e);
                                }
                            }
                        }
                    }
                }
            }
        }

        info_log!(
            "Loaded {} plugins from {}",
            results.len(),
            self.plugin_dir.display()
        );
        Ok(results)
    }

    pub fn get_plugin_dir(&self) -> &Path {
        &self.plugin_dir
    }

    pub fn list_loaded_plugins(&self) -> Vec<(String, Vec<String>)> {
        let libraries = self.libraries.read();
        libraries
            .iter()
            .map(|(name, info)| (name.clone(), info.registered_tasks.clone()))
            .collect()
    }
}

pub static DYNAMIC_LOADER: once_cell::sync::Lazy<Mutex<Option<DynamicTaskLoader>>> =
    once_cell::sync::Lazy::new(|| Mutex::new(None));

pub fn init_dynamic_loader(plugin_dir: PathBuf) {
    let mut loader = DYNAMIC_LOADER.lock();
    *loader = Some(DynamicTaskLoader::new(plugin_dir));
}

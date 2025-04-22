use crate::info_log;
use lazy_static::lazy_static;
use std::collections::HashMap;
use std::sync::Mutex;

pub mod builtin;
pub mod dynamic;
mod registry;

pub use crate::tasks::taskscheduler::*;
pub use dynamic::DYNAMIC_LOADER;
pub use registry::*;

#[derive(Debug, Clone)]
pub struct RegistrationInfo {
    pub task_type: String,
    pub task_name: String,
}

lazy_static! {
    pub(crate) static ref PENDING_REGISTRATIONS: Mutex<Vec<RegistrationInfo>> =
        Mutex::new(Vec::new());
}

pub fn log_pending_registrations() {
    let registrations = {
        let mut pending = PENDING_REGISTRATIONS.lock().unwrap();
        std::mem::take(&mut *pending)
    };
    if !registrations.is_empty() {
        for reg in registrations {
            info_log!("Registered {} task: {}", reg.task_type, reg.task_name);
        }
    }
}

pub fn list_all_tasks() -> Vec<(String, String, bool, u64)> {
    let tasks = REGISTRY.list_all_tasks();
    tasks
        .into_iter()
        .map(|(name, (is_sync, is_dynamic, timestamp))| {
            let task_type = if is_sync { "sync" } else { "async" };
            (name, task_type.to_string(), is_dynamic, timestamp)
        })
        .collect()
}

pub fn list_loaded_plugins() -> Vec<(String, Vec<String>)> {
    if let Some(loader) = dynamic::DYNAMIC_LOADER.lock().as_ref() {
        loader.list_loaded_plugins()
    } else {
        Vec::new()
    }
}

pub fn load_plugin(name: &str) -> crate::error::Result<Vec<String>> {
    if let Some(loader) = dynamic::DYNAMIC_LOADER.lock().as_ref() {
        loader.load_plugin(name)
    } else {
        Err(crate::error::TaskError::ExecutionError(
            "Dynamic loader not initialized".to_string(),
        ))
    }
}

pub fn unload_plugin(name: &str) -> crate::error::Result<Vec<String>> {
    if let Some(loader) = dynamic::DYNAMIC_LOADER.lock().as_ref() {
        loader.unload_plugin(name)
    } else {
        Err(crate::error::TaskError::ExecutionError(
            "Dynamic loader not initialized".to_string(),
        ))
    }
}

pub fn reload_all_plugins() -> crate::error::Result<HashMap<String, Vec<String>>> {
    if let Some(loader) = dynamic::DYNAMIC_LOADER.lock().as_ref() {
        loader.scan_and_load_all()
    } else {
        Err(crate::error::TaskError::ExecutionError(
            "Dynamic loader not initialized".to_string(),
        ))
    }
}

pub mod taskscheduler {
    tonic::include_proto!("taskscheduler");
}

pub trait Task: Send + Sync {
    fn execute(&self, args: Vec<i32>) -> crate::error::Result<String>;
}

pub static REGISTRY: once_cell::sync::Lazy<TaskRegistry> =
    once_cell::sync::Lazy::new(TaskRegistry::default);

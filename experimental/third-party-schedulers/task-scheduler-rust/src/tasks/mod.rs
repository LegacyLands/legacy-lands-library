use crate::info_log;
use lazy_static::lazy_static;
use std::sync::Mutex;

pub mod builtin;
mod registry;

pub use crate::tasks::taskscheduler::*;
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

pub mod taskscheduler {
    tonic::include_proto!("taskscheduler");
}

pub trait Task: Send + Sync {
    fn execute(&self, args: Vec<i32>) -> crate::error::Result<String>;
}

pub static REGISTRY: once_cell::sync::Lazy<TaskRegistry> =
    once_cell::sync::Lazy::new(TaskRegistry::default);

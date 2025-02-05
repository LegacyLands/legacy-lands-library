pub mod builtin;
mod registry;

pub use crate::tasks::taskscheduler::*;
pub use registry::*;
pub use task_macro::task;

pub mod taskscheduler {
    tonic::include_proto!("taskscheduler");
}

pub trait Task: Send + Sync {
    fn execute(&self, args: Vec<i32>) -> crate::error::Result<String>;
}

pub static REGISTRY: once_cell::sync::Lazy<TaskRegistry> =
    once_cell::sync::Lazy::new(TaskRegistry::default);

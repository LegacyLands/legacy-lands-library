pub mod error;
pub mod models;
pub mod server;
pub mod tasks;

pub use models::{TaskInfo, TaskRequest, TaskResponse, TaskResult};
pub use server::TaskSchedulerService;
pub use tasks::{process_task, Task, TaskRegistry};
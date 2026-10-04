use crate::error::{Result as TaskResultType, TaskError};
use crate::models::wrappers::{
    BoolValue, BytesValue, DoubleValue, FloatValue, Int32Value, Int64Value, StringValue,
    UInt32Value, UInt64Value,
};
use crate::models::ArgValue;
use crate::tasks::taskscheduler::{ListValue, MapValue};
use crate::warn_log;
use dashmap::DashMap;
use prost::Message;
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;

pub type TaskFn = fn(Vec<ArgValue>) -> TaskResultType<String>;
pub type AsyncTaskFn =
    fn(Vec<ArgValue>) -> Pin<Box<dyn Future<Output = TaskResultType<String>> + Send>>;

pub type DynamicSyncTaskFn = unsafe fn(Vec<ArgValue>) -> TaskResultType<String>;
pub type DynamicAsyncTaskFn =
    unsafe fn(Vec<ArgValue>) -> Pin<Box<dyn Future<Output = String> + Send>>;

/// Callable entry of a registered task, resolved by method name.
#[derive(Clone, Copy)]
pub enum TaskHandler {
    Sync(TaskFn),
    Async(AsyncTaskFn),
    DynamicSync(DynamicSyncTaskFn),
    DynamicAsync(DynamicAsyncTaskFn),
}

impl TaskHandler {
    pub fn is_async(&self) -> bool {
        matches!(self, TaskHandler::Async(_) | TaskHandler::DynamicAsync(_))
    }

    pub fn is_dynamic(&self) -> bool {
        matches!(
            self,
            TaskHandler::DynamicSync(_) | TaskHandler::DynamicAsync(_)
        )
    }

    /// Invokes the task. Sync tasks run on the blocking thread pool so they never stall the runtime.
    pub async fn invoke(self, args: Vec<ArgValue>) -> TaskResultType<String> {
        match self {
            TaskHandler::Sync(func) => run_blocking(move || func(args)).await,
            TaskHandler::DynamicSync(func) => run_blocking(move || unsafe { func(args) }).await,
            TaskHandler::Async(func) => func(args).await,
            TaskHandler::DynamicAsync(func) => Ok(unsafe { func(args) }.await),
        }
    }
}

async fn run_blocking<F>(func: F) -> TaskResultType<String>
where
    F: FnOnce() -> TaskResultType<String> + Send + 'static,
{
    tokio::task::spawn_blocking(func)
        .await
        .map_err(|e| TaskError::ExecutionError(format!("Sync task panicked: {}", e)))?
}

#[derive(Clone, Copy)]
struct TaskEntry {
    handler: TaskHandler,
    register_time: u64,
}

pub struct TaskRegistry {
    tasks: DashMap<String, TaskEntry, ahash::RandomState>,
}

impl Default for TaskRegistry {
    fn default() -> Self {
        Self {
            tasks: DashMap::with_hasher(ahash::RandomState::new()),
        }
    }
}

impl TaskRegistry {
    pub fn register_sync_task(&self, name: &str, func: TaskFn) {
        self.register(name, TaskHandler::Sync(func), Self::get_current_timestamp());
    }

    pub fn register_async_task(&self, name: &str, func: AsyncTaskFn) {
        self.register(
            name,
            TaskHandler::Async(func),
            Self::get_current_timestamp(),
        );
    }

    pub fn register_dynamic_sync_task(
        &self,
        name: &str,
        func: DynamicSyncTaskFn,
        register_time: u64,
    ) -> bool {
        self.register_dynamic(name, TaskHandler::DynamicSync(func), register_time)
    }

    pub fn register_dynamic_async_task(
        &self,
        name: &str,
        func: DynamicAsyncTaskFn,
        register_time: u64,
    ) -> bool {
        self.register_dynamic(name, TaskHandler::DynamicAsync(func), register_time)
    }

    fn register(&self, name: &str, handler: TaskHandler, register_time: u64) {
        if let Some(old) = self.tasks.get(name).map(|entry| entry.register_time) {
            warn_log!(
                "Task name conflict: Task '{}' already exists (registered at: {} ms), will be overwritten (new time: {} ms)",
                name,
                old,
                register_time
            );
        }

        self.tasks.insert(
            name.to_string(),
            TaskEntry {
                handler,
                register_time,
            },
        );
    }

    fn register_dynamic(&self, name: &str, handler: TaskHandler, register_time: u64) -> bool {
        if let Some(old) = self.tasks.get(name).map(|entry| entry.register_time) {
            if register_time <= old {
                warn_log!(
                    "Task registration conflict: Dynamic task '{}' already exists with earlier registration time (existing: {} ms, attempted: {} ms), not overwriting",
                    name, old, register_time
                );
                return false;
            }
        }

        self.register(name, handler, register_time);
        true
    }

    pub fn unregister_task(&self, name: &str) {
        if let Some((_, entry)) = self.tasks.remove(name) {
            let task_type = if entry.handler.is_async() {
                "async"
            } else {
                "sync"
            };
            warn_log!("Unregistered {} task: {}", task_type, name);
        }
    }

    /// Returns the handler registered under the given method name, if any.
    pub fn resolve(&self, name: &str) -> Option<TaskHandler> {
        self.tasks.get(name).map(|entry| entry.handler)
    }

    pub fn contains(&self, name: &str) -> bool {
        self.tasks.contains_key(name)
    }

    pub fn method_names(&self) -> Vec<String> {
        let mut names: Vec<String> = self.tasks.iter().map(|entry| entry.key().clone()).collect();
        names.sort();
        names
    }

    fn get_current_timestamp() -> u64 {
        use std::time::{SystemTime, UNIX_EPOCH};
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64
    }

    /// Lists all tasks as `name -> (is_sync, is_dynamic, register_time)`.
    pub fn list_all_tasks(&self) -> HashMap<String, (bool, bool, u64)> {
        self.tasks
            .iter()
            .map(|entry| {
                (
                    entry.key().clone(),
                    (
                        !entry.handler.is_async(),
                        entry.handler.is_dynamic(),
                        entry.register_time,
                    ),
                )
            })
            .collect()
    }

    pub fn convert_args(args: &[prost_types::Any]) -> Result<Vec<ArgValue>, TaskError> {
        args.iter()
            .map(|any| match any.type_url.as_str() {
                "type.googleapis.com/google.protobuf.Int32Value" => {
                    Int32Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::Int32(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode int32: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.Int64Value" => {
                    Int64Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::Int64(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode int64: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.UInt32Value" => {
                    UInt32Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::UInt32(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode uint32: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.UInt64Value" => {
                    UInt64Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::UInt64(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode uint64: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.FloatValue" => {
                    FloatValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Float(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode float: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.DoubleValue" => {
                    DoubleValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Double(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode double: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.BoolValue" => {
                    BoolValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Bool(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode bool: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.StringValue" => {
                    StringValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::String(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode string: {}", e))
                        })
                }
                "type.googleapis.com/google.protobuf.BytesValue" => {
                    BytesValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Bytes(w.value))
                        .map_err(|e| {
                            TaskError::InvalidArguments(format!("Failed to decode bytes: {}", e))
                        })
                }
                "type.googleapis.com/taskscheduler.ListValue" => {
                    let list_val = ListValue::decode(any.value.as_slice()).map_err(|e| {
                        TaskError::InvalidArguments(format!("Failed to decode ListValue: {}", e))
                    })?;
                    let vals = Self::convert_args(&list_val.values)?;
                    Ok(ArgValue::Array(vals))
                }
                "type.googleapis.com/taskscheduler.MapValue" => {
                    let map_val = MapValue::decode(any.value.as_slice()).map_err(|e| {
                        TaskError::InvalidArguments(format!("Failed to decode MapValue: {}", e))
                    })?;
                    let mut map = std::collections::HashMap::new();
                    for (k, any_val) in map_val.fields {
                        let val_slice = std::slice::from_ref(&any_val);
                        let vals = Self::convert_args(val_slice)?;
                        if let Some(arg) = vals.into_iter().next() {
                            map.insert(k, arg);
                        } else {
                            return Err(TaskError::InvalidArguments(format!(
                                "Empty converted value for key '{}' in MapValue",
                                k
                            )));
                        }
                    }
                    Ok(ArgValue::Map(map))
                }
                _ => Err(TaskError::InvalidArguments(format!(
                    "Unsupported type: {}",
                    any.type_url
                ))),
            })
            .collect()
    }
}

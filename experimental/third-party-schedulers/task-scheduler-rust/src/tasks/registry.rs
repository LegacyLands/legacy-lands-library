use crate::error::{Result as TaskResultType, TaskError};
use crate::models::wrappers::{
    BoolValue, BytesValue, DoubleValue, FloatValue, Int32Value, Int64Value, StringValue,
    UInt32Value, UInt64Value,
};
use crate::models::ArgValue;
use crate::models::TaskResult;
use crate::tasks::taskscheduler::{self, ListValue, MapValue};
use crate::warn_log;
use dashmap::DashMap;
use lru::LruCache;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use prost::Message;
use std::collections::HashMap;
use std::future::Future;
use std::num::NonZeroUsize;
use std::pin::Pin;
use std::sync::Arc;

type TaskFn = fn(Vec<ArgValue>) -> TaskResultType<String>;
type AsyncTaskFn = fn(Vec<ArgValue>) -> Pin<Box<dyn std::future::Future<Output = String> + Send>>;

pub type DynamicSyncTaskFn = unsafe fn(Vec<ArgValue>) -> TaskResultType<String>;
pub type DynamicAsyncTaskFn =
    unsafe fn(Vec<ArgValue>) -> Pin<Box<dyn Future<Output = String> + Send>>;

const CACHE_SIZE: usize = 1000;

lazy_static::lazy_static! {
    static ref DYNAMIC_SYNC_FUNCTIONS: DashMap<String, DynamicSyncTaskFn> = DashMap::new();
    static ref DYNAMIC_ASYNC_FUNCTIONS: DashMap<String, DynamicAsyncTaskFn> = DashMap::new();
    static ref FUNCTION_REGISTER_TIMES: DashMap<String, u64> = DashMap::new();
}

#[derive(Clone, Copy)]
struct SyncTaskInfo {
    func: TaskFn,
    register_time: u64,
    dynamic_lib: bool,
}

#[derive(Clone, Copy)]
struct AsyncTaskInfo {
    func: AsyncTaskFn,
    register_time: u64,
    dynamic_lib: bool,
}

pub struct TaskRegistry {
    sync_tasks: DashMap<String, SyncTaskInfo, ahash::RandomState>,
    async_tasks: DashMap<String, AsyncTaskInfo, ahash::RandomState>,
    results_cache: Arc<[Mutex<LruCache<String, TaskResult>>; 32]>,
    cache_hasher: ahash::RandomState,
}

impl Default for TaskRegistry {
    fn default() -> Self {
        let caches = std::array::from_fn(|_| {
            Mutex::new(LruCache::new(NonZeroUsize::new(CACHE_SIZE / 32).unwrap()))
        });

        Self {
            sync_tasks: DashMap::with_hasher(ahash::RandomState::new()),
            async_tasks: DashMap::with_hasher(ahash::RandomState::new()),
            results_cache: Arc::new(caches),
            cache_hasher: ahash::RandomState::new(),
        }
    }
}

impl TaskRegistry {
    pub fn register_sync_task(&self, name: &str, func: TaskFn) {
        let current_time = Self::get_current_timestamp();
        if self.sync_tasks.contains_key(name) {
            let old_reg_time = self
                .sync_tasks
                .get(name)
                .map(|e| e.register_time)
                .unwrap_or(0);
            warn_log!(
                "Task name conflict: Sync task '{}' already exists (registered at: {} ms), will be overwritten (new time: {} ms)",
                name,
                old_reg_time,
                current_time
            );
        }

        self.sync_tasks.insert(
            name.to_string(),
            SyncTaskInfo {
                func,
                register_time: current_time,
                dynamic_lib: false,
            },
        );
    }

    pub fn register_async_task(&self, name: &str, func: AsyncTaskFn) {
        let current_time = Self::get_current_timestamp();
        if self.async_tasks.contains_key(name) {
            let old_reg_time = self
                .async_tasks
                .get(name)
                .map(|e| e.register_time)
                .unwrap_or(0);
            warn_log!(
                "Task name conflict: Async task '{}' already exists (registered at: {} ms), will be overwritten (new time: {} ms)",
                name,
                old_reg_time,
                current_time
            );
        }

        self.async_tasks.insert(
            name.to_string(),
            AsyncTaskInfo {
                func,
                register_time: current_time,
                dynamic_lib: false,
            },
        );
    }

    pub fn register_dynamic_sync_task(
        &self,
        name: &str,
        func: DynamicSyncTaskFn,
        register_time: u64,
    ) -> bool {
        if self.sync_tasks.contains_key(name) {
            let old_reg_time = self
                .sync_tasks
                .get(name)
                .map(|e| e.register_time)
                .unwrap_or(0);
            if register_time <= old_reg_time {
                warn_log!(
                    "Task registration conflict: Dynamic sync task '{}' already exists with earlier registration time (existing: {} ms, attempted: {} ms), not overwriting",
                    name, old_reg_time, register_time
                );
                return false;
            }

            warn_log!(
                "Task name conflict: Sync task '{}' already exists (registered at: {} ms), will be overwritten by dynamic task (registration time: {} ms)",
                name, old_reg_time, register_time
            );
        }

        DYNAMIC_SYNC_FUNCTIONS.insert(name.to_string(), func);
        FUNCTION_REGISTER_TIMES.insert(name.to_string(), register_time);

        self.sync_tasks.insert(
            name.to_string(),
            SyncTaskInfo {
                func: dynamic_sync_wrapper,
                register_time,
                dynamic_lib: true,
            },
        );

        true
    }

    pub fn register_dynamic_async_task(
        &self,
        name: &str,
        func: DynamicAsyncTaskFn,
        register_time: u64,
    ) -> bool {
        if self.async_tasks.contains_key(name) {
            let old_reg_time = self
                .async_tasks
                .get(name)
                .map(|e| e.register_time)
                .unwrap_or(0);
            if register_time <= old_reg_time {
                warn_log!(
                    "Task registration conflict: Dynamic async task '{}' already exists with earlier registration time (existing: {} ms, attempted: {} ms), not overwriting",
                    name, old_reg_time, register_time
                );
                return false;
            }

            warn_log!(
                "Task name conflict: Async task '{}' already exists (registered at: {} ms), will be overwritten by dynamic task (registration time: {} ms)",
                name, old_reg_time, register_time
            );
        }

        DYNAMIC_ASYNC_FUNCTIONS.insert(name.to_string(), func);
        FUNCTION_REGISTER_TIMES.insert(name.to_string(), register_time);

        self.async_tasks.insert(
            name.to_string(),
            AsyncTaskInfo {
                func: dynamic_async_wrapper,
                register_time,
                dynamic_lib: true,
            },
        );

        true
    }

    pub fn unregister_task(&self, name: &str) {
        let removed_sync = self.sync_tasks.remove(name);
        let removed_async = self.async_tasks.remove(name);

        DYNAMIC_SYNC_FUNCTIONS.remove(name);
        DYNAMIC_ASYNC_FUNCTIONS.remove(name);
        FUNCTION_REGISTER_TIMES.remove(name);

        if removed_sync.is_some() || removed_async.is_some() {
            let task_type = if removed_sync.is_some() {
                "sync"
            } else {
                "async"
            };
            warn_log!("Unregistered {} task: {}", task_type, name);
        }
    }

    fn get_current_timestamp() -> u64 {
        use std::time::{SystemTime, UNIX_EPOCH};
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64
    }

    pub fn list_all_tasks(&self) -> HashMap<String, (bool, bool, u64)> {
        let mut tasks = HashMap::new();

        for entry in self.sync_tasks.iter() {
            tasks.insert(
                entry.key().clone(),
                (true, entry.dynamic_lib, entry.register_time),
            );
        }

        for entry in self.async_tasks.iter() {
            tasks.insert(
                entry.key().clone(),
                (false, entry.dynamic_lib, entry.register_time),
            );
        }

        tasks
    }

    #[inline]
    fn get_cache_shard(&self, task_id: &str) -> &Mutex<LruCache<String, TaskResult>> {
        let idx = self.cache_hasher.hash_one(task_id) as usize % 32;
        &self.results_cache[idx]
    }

    fn convert_args(args: &[prost_types::Any]) -> Result<Vec<ArgValue>, TaskError> {
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

    pub async fn execute_task(&self, task: &taskscheduler::TaskRequest) -> TaskResultType<String> {
        let args_converted = Self::convert_args(&task.args)?;

        if !task.deps.is_empty() {
            for dep in &task.deps {
                let shard = self.get_cache_shard(dep);
                let cache = shard.lock();
                if !cache.contains(dep.as_str()) {
                    return Err(TaskError::MissingDependency(format!(
                        "Dependency '{}' not found or not completed",
                        dep
                    )));
                }
            }
        }

        let task_fn_result = if task.is_async {
            let async_func = self
                .async_tasks
                .get(&task.method)
                .map(|entry| entry.func)
                .ok_or_else(|| TaskError::MethodNotFound(task.method.clone()))?;
            async_func(args_converted).await
        } else {
            let sync_func = self
                .sync_tasks
                .get(&task.method)
                .map(|entry| entry.func)
                .ok_or_else(|| TaskError::MethodNotFound(task.method.clone()))?;
            sync_func(args_converted)?
        };

        let task_result_for_cache = TaskResult {
            status: 1,
            value: task_fn_result.clone(),
        };
        self.cache_task_result(task.task_id.clone(), task_result_for_cache)
            .await;

        Ok(task_fn_result)
    }

    pub async fn execute_tasks(
        &self,
        _tasks: Vec<taskscheduler::TaskRequest>,
    ) -> Vec<crate::models::TaskResult> {
        vec![]
    }

    pub async fn get_task_result(&self, task_id: &str) -> Option<TaskResult> {
        let mut cache = self.get_cache_shard(task_id).lock();
        cache.get(task_id).cloned()
    }

    pub async fn cache_task_result(&self, task_id: String, result: TaskResult) {
        let mut cache = self.get_cache_shard(&task_id).lock();
        cache.put(task_id, result);
    }
}

pub static REGISTRY: Lazy<TaskRegistry> = Lazy::new(TaskRegistry::default);

pub async fn process_task(req: &taskscheduler::TaskRequest) -> TaskResult {
    match REGISTRY.execute_task(req).await {
        Ok(value) => TaskResult { status: 1, value },
        Err(err) => {
            let (status, value) = match err {
                TaskError::MethodNotFound(m) => (2, format!("Method not found: {}", m)),
                TaskError::InvalidArguments(a) => (2, format!("Invalid arguments: {}", a)),
                TaskError::MissingDependency(d) => (2, format!("Missing dependency: {}", d)),
                TaskError::ExecutionError(e) => (2, format!("Task execution failed: {}", e)),
            };
            TaskResult { status, value }
        }
    }
}

fn dynamic_sync_wrapper(args: Vec<ArgValue>) -> TaskResultType<String> {
    for entry in DYNAMIC_SYNC_FUNCTIONS.iter() {
        let dynamic_fn = entry.value();
        match unsafe { dynamic_fn(args.clone()) } {
            Ok(result) => return Ok(result),
            Err(_) => continue,
        }
    }

    Err(TaskError::MethodNotFound(
        "Dynamic sync function not found or all functions failed".to_string(),
    ))
}

fn dynamic_async_wrapper(args: Vec<ArgValue>) -> Pin<Box<dyn Future<Output = String> + Send>> {
    let args_clone = args.clone();

    let mut function_list = Vec::new();
    for entry in DYNAMIC_ASYNC_FUNCTIONS.iter() {
        function_list.push((entry.key().clone(), *entry.value()));
    }

    Box::pin(async move {
        if !function_list.is_empty() {
            let (_, dynamic_fn) = &function_list[0];
            let future = unsafe { dynamic_fn(args_clone) };
            return future.await;
        }

        "Error: No dynamic async functions registered".to_string()
    })
}

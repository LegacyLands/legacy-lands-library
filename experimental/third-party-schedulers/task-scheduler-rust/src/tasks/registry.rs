use crate::error::{Result as TaskResultType, TaskError};
use crate::models::wrappers::{
    BoolValue, BytesValue, DoubleValue, FloatValue, Int32Value, Int64Value, StringValue,
    UInt32Value, UInt64Value,
};
use crate::models::ArgValue;
use crate::models::TaskResult;
use crate::tasks::taskscheduler::{self, ListValue, MapValue};
use dashmap::DashMap;
use lru::LruCache;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use prost::Message;
use std::num::NonZeroUsize;
use std::pin::Pin;
use std::sync::Arc;

type TaskFn = fn(Vec<ArgValue>) -> TaskResultType<String>;
type AsyncTaskFn = fn(Vec<ArgValue>) -> Pin<Box<dyn std::future::Future<Output = String> + Send>>;

const CACHE_SIZE: usize = 1000;

pub struct TaskRegistry {
    sync_tasks: DashMap<String, TaskFn, ahash::RandomState>,
    async_tasks: DashMap<String, AsyncTaskFn, ahash::RandomState>,
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
        self.sync_tasks.insert(name.to_string(), func);
    }

    pub fn register_async_task(&self, name: &str, func: AsyncTaskFn) {
        self.async_tasks.insert(name.to_string(), func);
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
                .map(|f| *f.value())
                .ok_or_else(|| TaskError::MethodNotFound(task.method.clone()))?;
            async_func(args_converted).await
        } else {
            let sync_func = self
                .sync_tasks
                .get(&task.method)
                .map(|f| *f.value())
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

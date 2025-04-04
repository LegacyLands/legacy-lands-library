use crate::models::wrappers::{
    DoubleValue, FloatValue, Int32Value, Int64Value, UInt32Value, UInt64Value,
    BoolValue, StringValue, BytesValue,
};
use crate::models::ArgValue;
use crate::models::TaskResult;
use crate::tasks::taskscheduler::{self, ListValue, MapValue};
use dashmap::DashMap;
use lru::LruCache;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use prost::Message;
use rayon::prelude::*;
use std::num::NonZeroUsize;
use std::pin::Pin;
use std::sync::Arc;

type TaskFn = fn(Vec<ArgValue>) -> String;
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

    fn convert_args(args: &[prost_types::Any]) -> Result<Vec<ArgValue>, String> {
        args.iter()
            .map(|any| match any.type_url.as_str() {
                "type.googleapis.com/google.protobuf.Int32Value" => {
                    Int32Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::Int32(w.value))
                        .map_err(|e| format!("Failed to decode int32: {}", e))
                }
                "type.googleapis.com/google.protobuf.Int64Value" => {
                    Int64Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::Int64(w.value))
                        .map_err(|e| format!("Failed to decode int64: {}", e))
                }
                "type.googleapis.com/google.protobuf.UInt32Value" => {
                    UInt32Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::UInt32(w.value))
                        .map_err(|e| format!("Failed to decode uint32: {}", e))
                }
                "type.googleapis.com/google.protobuf.UInt64Value" => {
                    UInt64Value::decode(any.value.as_slice())
                        .map(|w| ArgValue::UInt64(w.value))
                        .map_err(|e| format!("Failed to decode uint64: {}", e))
                }
                "type.googleapis.com/google.protobuf.FloatValue" => {
                    FloatValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Float(w.value))
                        .map_err(|e| format!("Failed to decode float: {}", e))
                }
                "type.googleapis.com/google.protobuf.DoubleValue" => {
                    DoubleValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Double(w.value))
                        .map_err(|e| format!("Failed to decode double: {}", e))
                }
                "type.googleapis.com/google.protobuf.BoolValue" => {
                    BoolValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Bool(w.value))
                        .map_err(|e| format!("Failed to decode bool: {}", e))
                }
                "type.googleapis.com/google.protobuf.StringValue" => {
                    StringValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::String(w.value))
                        .map_err(|e| format!("Failed to decode string: {}", e))
                }
                "type.googleapis.com/google.protobuf.BytesValue" => {
                    BytesValue::decode(any.value.as_slice())
                        .map(|w| ArgValue::Bytes(w.value))
                        .map_err(|e| format!("Failed to decode bytes: {}", e))
                }
                "type.googleapis.com/taskscheduler.ListValue" => {
                    let list_val = ListValue::decode(any.value.as_slice())
                        .map_err(|e| format!("Failed to decode ListValue: {}", e))?;
                    let vals = Self::convert_args(&list_val.values)?;
                    Ok(ArgValue::Array(vals))
                }
                "type.googleapis.com/taskscheduler.MapValue" => {
                    let map_val = MapValue::decode(any.value.as_slice())
                        .map_err(|e| format!("Failed to decode MapValue: {}", e))?;
                    let mut map = std::collections::HashMap::new();
                    for (k, any_val) in map_val.fields {
                        let vals = Self::convert_args(std::slice::from_ref(&any_val))?;
                        if let Some(arg) = vals.into_iter().next() {
                            map.insert(k, arg);
                        } else {
                            return Err(format!("Empty converted value for key '{}' in MapValue", k));
                        }
                    }
                    Ok(ArgValue::Map(map))
                }
                _ => Err(format!("Unsupported type: {}", any.type_url)),
            })
            .collect()
    }

    pub async fn execute_task(&self, task: &taskscheduler::TaskRequest) -> TaskResult {
        let args_converted = match Self::convert_args(&task.args) {
            Ok(v) => v,
            Err(e) => {
                return TaskResult {
                    status: 2,
                    value: e,
                }
            }
        };
        if !task.deps.is_empty() {
            for dep in &task.deps {
                let shard = self.get_cache_shard(dep);
                if !shard.lock().contains(dep.as_str()) {
                    return TaskResult {
                        status: 2,
                        value: format!("Dependency {} not completed", dep),
                    };
                }
            }
        }

        if task.is_async {
            if let Some(func) = self.async_tasks.get(&task.method).map(|f| *f.value()) {
                let result = func(args_converted.clone()).await;
                let task_result = TaskResult {
                    status: 1,
                    value: result,
                };
                if !task.deps.is_empty() {
                    let mut cache = self.get_cache_shard(&task.task_id).lock();
                    cache.put(task.task_id.clone(), task_result.clone());
                }
                return task_result;
            }
        } else {
            if let Some(func) = self.sync_tasks.get(&task.method).map(|f| *f.value()) {
                let result = func(args_converted.clone());
                let task_result = TaskResult {
                    status: 1,
                    value: result,
                };
                if !task.deps.is_empty() {
                    let mut cache = self.get_cache_shard(&task.task_id).lock();
                    cache.put(task.task_id.clone(), task_result.clone());
                }
                return task_result;
            }
        }

        TaskResult {
            status: 2,
            value: "Error: Method not found".to_string(),
        }
    }

    pub async fn execute_tasks(&self, tasks: Vec<taskscheduler::TaskRequest>) -> Vec<TaskResult> {
        let mut results = Vec::with_capacity(tasks.len());

        let (independent, dependent): (Vec<_>, Vec<_>) =
            tasks.into_par_iter().partition(|task| task.deps.is_empty());

        let independent_results: Vec<_> = independent
            .into_par_iter()
            .map(|task| {
                let result = futures::executor::block_on(self.execute_task(&task));
                (task.task_id.clone(), result)
            })
            .collect();

        for (task_id, result) in &independent_results {
            let shard = self.get_cache_shard(task_id);
            let mut cache = shard.lock();
            cache.put(task_id.to_string(), result.clone());
        }

        results.extend(independent_results.into_iter().map(|(_, r)| r));

        let mut remaining = dependent;
        while !remaining.is_empty() {
            let mut next_round = Vec::with_capacity(remaining.len());
            let mut executed = false;

            for task in remaining {
                let mut ready = true;
                for dep in &task.deps {
                    let shard = self.get_cache_shard(dep);
                    if !shard.lock().contains(dep.as_str()) {
                        ready = false;
                        break;
                    }
                }

                if ready {
                    let result = self.execute_task(&task).await;
                    let shard = self.get_cache_shard(&task.task_id);
                    shard.lock().put(task.task_id.to_string(), result.clone());
                    results.push(result);
                    executed = true;
                } else {
                    next_round.push(task);
                }
            }

            if !executed && !next_round.is_empty() {
                break;
            }
            remaining = next_round;
        }

        self.results_cache.par_iter().for_each(|shard| {
            shard.lock().clear();
        });

        results
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
    REGISTRY.execute_task(req).await
}

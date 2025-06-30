use async_trait::async_trait;
use dashmap::DashMap;
use lru::LruCache;
use parking_lot::Mutex;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use task_common::{
    error::{TaskError, TaskResult},
    models::{TaskInfo, TaskResult as TaskResultData, TaskStatus},
    Uuid,
};
use tracing::{debug, info};

use super::traits::{StorageBackend, StorageStats};

/// In-memory storage backend for tasks
pub struct MemoryStorage {
    /// In-memory storage for tasks
    tasks: Arc<DashMap<Uuid, TaskInfo>>,

    /// In-memory storage for task results
    results: Arc<DashMap<Uuid, TaskResultData>>,
    
    /// Timestamps for task results (for cleanup)
    result_timestamps: Arc<DashMap<Uuid, u64>>,

    /// LRU cache for frequently accessed tasks
    cache: Arc<Mutex<LruCache<Uuid, TaskInfo>>>,
}

impl MemoryStorage {
    /// Create a new memory storage
    pub fn new(cache_size: usize) -> Self {
        Self {
            tasks: Arc::new(DashMap::new()),
            results: Arc::new(DashMap::new()),
            result_timestamps: Arc::new(DashMap::new()),
            cache: Arc::new(Mutex::new(LruCache::new(cache_size.try_into().unwrap()))),
        }
    }
    
    /// Get current timestamp in seconds
    fn current_timestamp() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs()
    }
}

#[async_trait]
impl StorageBackend for MemoryStorage {
    async fn create_task(&self, task: &TaskInfo) -> TaskResult<()> {
        debug!("Creating task: {}", task.id);

        if self.tasks.contains_key(&task.id) {
            return Err(TaskError::InternalError(format!(
                "Task {} already exists",
                task.id
            )));
        }

        self.tasks.insert(task.id, task.clone());

        // Update cache
        self.cache.lock().put(task.id, task.clone());

        info!("Task {} created successfully", task.id);
        Ok(())
    }

    async fn get_task(&self, task_id: Uuid) -> TaskResult<Option<TaskInfo>> {
        // Check cache first
        if let Some(task) = self.cache.lock().get(&task_id) {
            debug!("Task {} found in cache", task_id);
            return Ok(Some(task.clone()));
        }

        // Check main storage
        if let Some(entry) = self.tasks.get(&task_id) {
            let task = entry.clone();

            // Update cache
            self.cache.lock().put(task_id, task.clone());

            debug!("Task {} found in storage", task_id);
            Ok(Some(task))
        } else {
            debug!("Task {} not found", task_id);
            Ok(None)
        }
    }

    async fn update_task_status(&self, task_id: Uuid, status: TaskStatus) -> TaskResult<()> {
        debug!("Updating task {} status to {:?}", task_id, status);

        if let Some(mut entry) = self.tasks.get_mut(&task_id) {
            entry.status = status;
            entry.updated_at = task_common::Utc::now();

            // Invalidate cache
            self.cache.lock().pop(&task_id);

            info!("Task {} status updated", task_id);
            Ok(())
        } else {
            Err(TaskError::TaskNotFound(task_id.to_string()))
        }
    }

    async fn update_task(&self, task: &TaskInfo) -> TaskResult<()> {
        debug!("Updating task: {}", task.id);

        self.tasks.insert(task.id, task.clone());

        // Invalidate cache
        self.cache.lock().pop(&task.id);

        info!("Task {} updated", task.id);
        Ok(())
    }

    async fn store_task_result(&self, result: &TaskResultData) -> TaskResult<()> {
        debug!("Storing result for task: {}", result.task_id);

        self.results.insert(result.task_id, result.clone());
        self.result_timestamps.insert(result.task_id, Self::current_timestamp());

        info!("Result stored for task {}", result.task_id);
        Ok(())
    }

    async fn get_task_result(&self, task_id: Uuid) -> TaskResult<Option<TaskResultData>> {
        Ok(self.results.get(&task_id).map(|entry| entry.clone()))
    }

    async fn list_tasks(
        &self,
        status_filter: Option<TaskStatus>,
        limit: usize,
    ) -> TaskResult<Vec<TaskInfo>> {
        let tasks: Vec<TaskInfo> = self
            .tasks
            .iter()
            .filter(|entry| {
                if let Some(ref filter) = status_filter {
                    std::mem::discriminant(&entry.status) == std::mem::discriminant(filter)
                } else {
                    true
                }
            })
            .take(limit)
            .map(|entry| entry.value().clone())
            .collect();

        Ok(tasks)
    }

    async fn get_tasks_by_dependency(&self, dependency_id: Uuid) -> TaskResult<Vec<TaskInfo>> {
        let tasks: Vec<TaskInfo> = self
            .tasks
            .iter()
            .filter(|entry| entry.dependencies.contains(&dependency_id))
            .map(|entry| entry.value().clone())
            .collect();

        Ok(tasks)
    }

    async fn delete_task(&self, task_id: Uuid) -> TaskResult<()> {
        self.tasks.remove(&task_id);
        self.results.remove(&task_id);
        self.result_timestamps.remove(&task_id);
        self.cache.lock().pop(&task_id);

        info!("Task {} deleted", task_id);
        Ok(())
    }

    async fn cleanup_old_results(&self, retention_seconds: u64) -> TaskResult<usize> {
        let current_time = Self::current_timestamp();
        let cutoff_time = current_time.saturating_sub(retention_seconds);
        
        let mut deleted_count = 0;
        let mut to_delete = Vec::new();
        
        // Find results older than retention period
        for entry in self.result_timestamps.iter() {
            let (task_id, timestamp) = entry.pair();
            if *timestamp < cutoff_time {
                to_delete.push(*task_id);
            }
        }
        
        // Delete old results
        for task_id in to_delete {
            self.results.remove(&task_id);
            self.result_timestamps.remove(&task_id);
            deleted_count += 1;
        }
        
        info!("Cleaned up {} old task results", deleted_count);
        Ok(deleted_count)
    }

    async fn get_stats(&self) -> StorageStats {
        StorageStats {
            total_tasks: self.tasks.len(),
            total_results: self.results.len(),
            cache_size: self.cache.lock().len(),
        }
    }
}

// Compatibility layer - keep the old TaskStorage name for now
pub type TaskStorage = MemoryStorage;

// Add convenience methods for backward compatibility
impl MemoryStorage {
    /// Get a task by ID (alias for get_task)
    pub async fn get(&self, task_id: &Uuid) -> Option<TaskInfo> {
        self.get_task(*task_id).await.ok().flatten()
    }
    
    /// Store a task (alias for create_task)
    pub async fn store(&self, task: &TaskInfo) -> TaskResult<()> {
        self.create_task(task).await
    }
    
    /// Update task status (alias for update_task_status)
    pub async fn update_status(&self, task_id: &Uuid, status: TaskStatus) -> TaskResult<()> {
        self.update_task_status(*task_id, status).await
    }
    
    /// List tasks with pagination
    pub async fn list(
        &self,
        status_filter: Option<TaskStatus>,
        _method_filter: Option<String>,
        offset: i64,
        limit: i64,
    ) -> TaskResult<Vec<TaskInfo>> {
        let mut tasks: Vec<TaskInfo> = self
            .tasks
            .iter()
            .filter(|entry| {
                if let Some(ref filter) = status_filter {
                    std::mem::discriminant(&entry.status) == std::mem::discriminant(filter)
                } else {
                    true
                }
            })
            .map(|entry| entry.value().clone())
            .collect();
            
        // Sort by priority (higher first) then by creation time
        tasks.sort_by(|a, b| {
            b.priority.cmp(&a.priority)
                .then_with(|| a.created_at.cmp(&b.created_at))
        });
        
        // Apply pagination
        let start = offset as usize;
        let end = (start + limit as usize).min(tasks.len());
        
        Ok(tasks[start..end].to_vec())
    }
}
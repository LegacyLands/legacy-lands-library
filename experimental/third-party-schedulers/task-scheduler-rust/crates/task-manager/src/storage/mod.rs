use dashmap::DashMap;
use lru::LruCache;
use parking_lot::Mutex;
use std::sync::Arc;
use task_common::{
    error::{TaskError, TaskResult},
    models::{TaskInfo, TaskResult as TaskResultData, TaskStatus},
    Uuid,
};
use tracing::{debug, info};

/// Storage backend for tasks
/// In production, this would be replaced with a proper database (PostgreSQL, MongoDB, etc.)
pub struct TaskStorage {
    /// In-memory storage for tasks
    tasks: Arc<DashMap<Uuid, TaskInfo>>,

    /// In-memory storage for task results
    results: Arc<DashMap<Uuid, TaskResultData>>,

    /// LRU cache for frequently accessed tasks
    cache: Arc<Mutex<LruCache<Uuid, TaskInfo>>>,
}

impl TaskStorage {
    /// Create a new task storage
    pub fn new(cache_size: usize) -> Self {
        Self {
            tasks: Arc::new(DashMap::new()),
            results: Arc::new(DashMap::new()),
            cache: Arc::new(Mutex::new(LruCache::new(cache_size.try_into().unwrap()))),
        }
    }

    /// Create a new task
    pub async fn create_task(&self, task: &TaskInfo) -> TaskResult<()> {
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

    /// Get a task by ID
    pub async fn get_task(&self, task_id: Uuid) -> TaskResult<Option<TaskInfo>> {
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

    /// Get a task by ID (alias for get_task)
    pub async fn get(&self, task_id: &Uuid) -> Option<TaskInfo> {
        self.get_task(*task_id).await.ok().flatten()
    }

    /// Update task status
    pub async fn update_task_status(&self, task_id: Uuid, status: TaskStatus) -> TaskResult<()> {
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

    /// Update task
    pub async fn update_task(&self, task: &TaskInfo) -> TaskResult<()> {
        debug!("Updating task: {}", task.id);

        self.tasks.insert(task.id, task.clone());

        // Invalidate cache
        self.cache.lock().pop(&task.id);

        info!("Task {} updated", task.id);
        Ok(())
    }

    /// Store task result
    pub async fn store_task_result(&self, result: &TaskResultData) -> TaskResult<()> {
        debug!("Storing result for task: {}", result.task_id);

        self.results.insert(result.task_id, result.clone());

        info!("Result stored for task {}", result.task_id);
        Ok(())
    }

    /// Get task result
    pub async fn get_task_result(&self, task_id: Uuid) -> TaskResult<Option<TaskResultData>> {
        Ok(self.results.get(&task_id).map(|entry| entry.clone()))
    }

    /// List tasks with optional filters
    pub async fn list_tasks(
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

    /// Get tasks by dependency
    pub async fn get_tasks_by_dependency(&self, dependency_id: Uuid) -> TaskResult<Vec<TaskInfo>> {
        let tasks: Vec<TaskInfo> = self
            .tasks
            .iter()
            .filter(|entry| entry.dependencies.contains(&dependency_id))
            .map(|entry| entry.value().clone())
            .collect();

        Ok(tasks)
    }

    /// Delete task
    pub async fn delete_task(&self, task_id: Uuid) -> TaskResult<()> {
        self.tasks.remove(&task_id);
        self.results.remove(&task_id);
        self.cache.lock().pop(&task_id);

        info!("Task {} deleted", task_id);
        Ok(())
    }

    /// Get storage statistics
    pub async fn get_stats(&self) -> StorageStats {
        StorageStats {
            total_tasks: self.tasks.len(),
            total_results: self.results.len(),
            cache_size: self.cache.lock().len(),
        }
    }
    
    // Convenience methods for tests
    
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

/// Storage statistics
#[derive(Debug, Clone)]
pub struct StorageStats {
    pub total_tasks: usize,
    pub total_results: usize,
    pub cache_size: usize,
}

#[cfg(test)]
mod tests {
    use super::*;
    use task_common::models::{TaskInfo, TaskMetadata, ExecutionMetrics};
    use chrono::Utc;

    fn create_test_task(id: Uuid) -> TaskInfo {
        TaskInfo {
            id,
            method: format!("test_method_{}", id),
            args: vec![serde_json::json!({"test": "data"})],
            dependencies: vec![],
            priority: 50,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Pending,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        }
    }

    #[tokio::test]
    async fn test_new() {
        let storage = TaskStorage::new(10);
        let stats = storage.get_stats().await;
        
        assert_eq!(stats.total_tasks, 0);
        assert_eq!(stats.total_results, 0);
        assert_eq!(stats.cache_size, 0);
    }

    #[tokio::test]
    async fn test_create_task_success() {
        let storage = TaskStorage::new(10);
        let task = create_test_task(Uuid::new_v4());
        
        let result = storage.create_task(&task).await;
        assert!(result.is_ok());
        
        let stats = storage.get_stats().await;
        assert_eq!(stats.total_tasks, 1);
        assert_eq!(stats.cache_size, 1);
    }

    #[tokio::test]
    async fn test_create_task_duplicate() {
        let storage = TaskStorage::new(10);
        let task = create_test_task(Uuid::new_v4());
        
        // First create should succeed
        storage.create_task(&task).await.unwrap();
        
        // Second create should fail
        let result = storage.create_task(&task).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::InternalError(msg) => {
                assert!(msg.contains("already exists"));
            }
            _ => panic!("Expected InternalError"),
        }
    }

    #[tokio::test]
    async fn test_get_task_found() {
        let storage = TaskStorage::new(10);
        let task = create_test_task(Uuid::new_v4());
        
        storage.create_task(&task).await.unwrap();
        
        let result = storage.get_task(task.id).await.unwrap();
        assert!(result.is_some());
        
        let retrieved = result.unwrap();
        assert_eq!(retrieved.id, task.id);
        assert_eq!(retrieved.method, task.method);
    }

    #[tokio::test]
    async fn test_get_task_not_found() {
        let storage = TaskStorage::new(10);
        let result = storage.get_task(Uuid::new_v4()).await.unwrap();
        assert!(result.is_none());
    }

    #[tokio::test]
    async fn test_get_task_cache_hit() {
        let storage = TaskStorage::new(10);
        let task = create_test_task(Uuid::new_v4());
        
        storage.create_task(&task).await.unwrap();
        
        // First get should populate cache
        storage.get_task(task.id).await.unwrap();
        
        // Clear the main storage to verify cache is used
        storage.tasks.remove(&task.id);
        
        // Should still find task in cache
        let result = storage.get_task(task.id).await.unwrap();
        assert!(result.is_some());
    }

    #[tokio::test]
    async fn test_update_task_status() {
        let storage = TaskStorage::new(10);
        let task = create_test_task(Uuid::new_v4());
        
        storage.create_task(&task).await.unwrap();
        
        let new_status = TaskStatus::Running {
            started_at: Utc::now(),
            worker_id: "worker1".to_string(),
        };
        
        let result = storage.update_task_status(task.id, new_status.clone()).await;
        assert!(result.is_ok());
        
        let updated = storage.get_task(task.id).await.unwrap().unwrap();
        assert_eq!(updated.status, new_status);
    }

    #[tokio::test]
    async fn test_update_task_status_not_found() {
        let storage = TaskStorage::new(10);
        let result = storage.update_task_status(
            Uuid::new_v4(),
            TaskStatus::Queued
        ).await;
        
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::TaskNotFound(_) => {}
            _ => panic!("Expected TaskNotFound error"),
        }
    }

    #[tokio::test]
    async fn test_update_task() {
        let storage = TaskStorage::new(10);
        let mut task = create_test_task(Uuid::new_v4());
        
        storage.create_task(&task).await.unwrap();
        
        // Update task
        task.method = "updated_method".to_string();
        task.priority = 100;
        
        let result = storage.update_task(&task).await;
        assert!(result.is_ok());
        
        let updated = storage.get_task(task.id).await.unwrap().unwrap();
        assert_eq!(updated.method, "updated_method");
        assert_eq!(updated.priority, 100);
    }

    #[tokio::test]
    async fn test_store_and_get_task_result() {
        let storage = TaskStorage::new(10);
        let task_id = Uuid::new_v4();
        
        let result = TaskResultData {
            task_id,
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 5000,
            },
            result: Some(serde_json::json!({"output": "test"})),
            error: None,
            metrics: ExecutionMetrics::default(),
        };
        
        storage.store_task_result(&result).await.unwrap();
        
        let retrieved = storage.get_task_result(task_id).await.unwrap();
        assert!(retrieved.is_some());
        
        let retrieved = retrieved.unwrap();
        assert_eq!(retrieved.task_id, task_id);
        assert_eq!(retrieved.result, result.result);
    }

    #[tokio::test]
    async fn test_list_tasks_no_filter() {
        let storage = TaskStorage::new(10);
        
        // Create tasks with different statuses
        let task1 = create_test_task(Uuid::new_v4());
        let mut task2 = create_test_task(Uuid::new_v4());
        task2.status = TaskStatus::Queued;
        let mut task3 = create_test_task(Uuid::new_v4());
        task3.status = TaskStatus::Running {
            started_at: Utc::now(),
            worker_id: "worker1".to_string(),
        };
        
        storage.create_task(&task1).await.unwrap();
        storage.create_task(&task2).await.unwrap();
        storage.create_task(&task3).await.unwrap();
        
        let tasks = storage.list_tasks(None, 10).await.unwrap();
        assert_eq!(tasks.len(), 3);
    }

    #[tokio::test]
    async fn test_list_tasks_with_filter() {
        let storage = TaskStorage::new(10);
        
        // Create tasks with different statuses
        let task1 = create_test_task(Uuid::new_v4());
        let mut task2 = create_test_task(Uuid::new_v4());
        task2.status = TaskStatus::Queued;
        let mut task3 = create_test_task(Uuid::new_v4());
        task3.status = TaskStatus::Queued;
        
        storage.create_task(&task1).await.unwrap();
        storage.create_task(&task2).await.unwrap();
        storage.create_task(&task3).await.unwrap();
        
        let tasks = storage.list_tasks(Some(TaskStatus::Queued), 10).await.unwrap();
        assert_eq!(tasks.len(), 2);
        
        for task in tasks {
            assert_eq!(task.status, TaskStatus::Queued);
        }
    }

    #[tokio::test]
    async fn test_list_tasks_limit() {
        let storage = TaskStorage::new(10);
        
        // Create 5 tasks
        for _ in 0..5 {
            let task = create_test_task(Uuid::new_v4());
            storage.create_task(&task).await.unwrap();
        }
        
        let tasks = storage.list_tasks(None, 3).await.unwrap();
        assert_eq!(tasks.len(), 3);
    }

    #[tokio::test]
    async fn test_get_tasks_by_dependency() {
        let storage = TaskStorage::new(10);
        let dep_id = Uuid::new_v4();
        
        // Create tasks with and without the dependency
        let mut task1 = create_test_task(Uuid::new_v4());
        task1.dependencies.push(dep_id);
        
        let mut task2 = create_test_task(Uuid::new_v4());
        task2.dependencies.push(dep_id);
        
        let task3 = create_test_task(Uuid::new_v4()); // No dependency
        
        storage.create_task(&task1).await.unwrap();
        storage.create_task(&task2).await.unwrap();
        storage.create_task(&task3).await.unwrap();
        
        let tasks = storage.get_tasks_by_dependency(dep_id).await.unwrap();
        assert_eq!(tasks.len(), 2);
        
        for task in tasks {
            assert!(task.dependencies.contains(&dep_id));
        }
    }

    #[tokio::test]
    async fn test_delete_task() {
        let storage = TaskStorage::new(10);
        let task = create_test_task(Uuid::new_v4());
        
        storage.create_task(&task).await.unwrap();
        
        // Store a result too
        let result = TaskResultData {
            task_id: task.id,
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 5000,
            },
            result: Some(serde_json::json!({"output": "test"})),
            error: None,
            metrics: ExecutionMetrics::default(),
        };
        storage.store_task_result(&result).await.unwrap();
        
        // Delete task
        storage.delete_task(task.id).await.unwrap();
        
        // Verify task and result are deleted
        assert!(storage.get_task(task.id).await.unwrap().is_none());
        assert!(storage.get_task_result(task.id).await.unwrap().is_none());
        
        // Verify cache is cleared
        assert_eq!(storage.cache.lock().len(), 0);
    }

    #[tokio::test]
    async fn test_cache_eviction() {
        let storage = TaskStorage::new(2); // Small cache
        
        let task1 = create_test_task(Uuid::new_v4());
        let task2 = create_test_task(Uuid::new_v4());
        let task3 = create_test_task(Uuid::new_v4());
        
        storage.create_task(&task1).await.unwrap();
        storage.create_task(&task2).await.unwrap();
        storage.create_task(&task3).await.unwrap();
        
        // Cache should only contain 2 items
        assert_eq!(storage.cache.lock().len(), 2);
        
        // Access task1 to make it recently used
        storage.get_task(task1.id).await.unwrap();
        
        // Create another task, should evict least recently used
        let task4 = create_test_task(Uuid::new_v4());
        storage.create_task(&task4).await.unwrap();
        
        // Cache should still have 2 items
        assert_eq!(storage.cache.lock().len(), 2);
    }

    #[tokio::test]
    async fn test_concurrent_operations() {
        let storage = Arc::new(TaskStorage::new(100));
        let mut handles = vec![];
        
        // Spawn multiple tasks creating and reading
        for _i in 0..10 {
            let storage_clone = storage.clone();
            let handle = tokio::spawn(async move {
                let task = create_test_task(Uuid::new_v4());
                storage_clone.create_task(&task).await.unwrap();
                
                // Update status
                storage_clone.update_task_status(
                    task.id,
                    TaskStatus::Queued
                ).await.unwrap();
                
                // Read back
                let retrieved = storage_clone.get_task(task.id).await.unwrap().unwrap();
                assert_eq!(retrieved.status, TaskStatus::Queued);
                
                task.id
            });
            handles.push(handle);
        }
        
        let task_ids: Vec<Uuid> = futures::future::join_all(handles)
            .await
            .into_iter()
            .map(|r| r.unwrap())
            .collect();
        
        // Verify all tasks were created
        let stats = storage.get_stats().await;
        assert_eq!(stats.total_tasks, 10);
        
        // Verify all tasks can be retrieved
        for id in task_ids {
            assert!(storage.get_task(id).await.unwrap().is_some());
        }
    }

    #[tokio::test]
    async fn test_cache_invalidation_on_update() {
        let storage = TaskStorage::new(10);
        let task = create_test_task(Uuid::new_v4());
        
        storage.create_task(&task).await.unwrap();
        
        // Get task to populate cache
        storage.get_task(task.id).await.unwrap();
        assert_eq!(storage.cache.lock().len(), 1);
        
        // Update status should invalidate cache
        storage.update_task_status(task.id, TaskStatus::Queued).await.unwrap();
        assert_eq!(storage.cache.lock().len(), 0);
        
        // Update task should also invalidate cache
        storage.get_task(task.id).await.unwrap(); // Re-populate cache
        assert_eq!(storage.cache.lock().len(), 1);
        
        let mut updated_task = task.clone();
        updated_task.priority = 100;
        storage.update_task(&updated_task).await.unwrap();
        assert_eq!(storage.cache.lock().len(), 0);
    }
}

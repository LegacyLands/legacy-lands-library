use async_trait::async_trait;
use redis::{aio::ConnectionManager, AsyncCommands, Client};
use std::sync::Arc;
use task_common::{
    error::{TaskError, TaskResult},
    models::{TaskInfo, TaskResult as TaskResultData, TaskStatus},
    Uuid,
};
use tracing::{debug, info};

use super::traits::{StorageBackend, StorageStats};

/// Redis cache configuration
#[derive(Debug, Clone)]
pub struct RedisCacheConfig {
    /// TTL for task info cache entries (in seconds)
    pub task_ttl: u64,
    /// TTL for task result cache entries (in seconds)
    pub result_ttl: u64,
    /// Key prefix for all Redis keys
    pub key_prefix: String,
}

impl Default for RedisCacheConfig {
    fn default() -> Self {
        Self {
            task_ttl: 3600,      // 1 hour
            result_ttl: 86400,   // 24 hours
            key_prefix: "task_scheduler".to_string(),
        }
    }
}

/// Redis-backed cache layer that wraps another storage backend
pub struct CachedStorageBackend {
    /// The underlying storage backend
    backend: Arc<dyn StorageBackend>,
    /// Redis connection manager
    redis: ConnectionManager,
    /// Cache configuration
    config: RedisCacheConfig,
}

impl CachedStorageBackend {
    /// Create a new cached storage backend
    pub async fn new(
        backend: Arc<dyn StorageBackend>,
        redis_url: &str,
        config: RedisCacheConfig,
    ) -> TaskResult<Self> {
        info!("Connecting to Redis cache at {}", redis_url);
        
        let client = Client::open(redis_url)
            .map_err(|e| TaskError::InternalError(format!("Failed to create Redis client: {}", e)))?;
        
        let redis = ConnectionManager::new(client)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to connect to Redis: {}", e)))?;
        
        info!("Redis cache connected successfully");
        
        Ok(Self {
            backend,
            redis,
            config,
        })
    }
    
    /// Generate Redis key for task info
    fn task_key(&self, task_id: Uuid) -> String {
        format!("{}:task:{}", self.config.key_prefix, task_id)
    }
    
    /// Generate Redis key for task result
    fn result_key(&self, task_id: Uuid) -> String {
        format!("{}:result:{}", self.config.key_prefix, task_id)
    }
    
    /// Generate Redis key for task list by status
    fn task_list_key(&self, status: Option<&TaskStatus>) -> String {
        match status {
            Some(s) => format!("{}:tasks:status:{:?}", self.config.key_prefix, s),
            None => format!("{}:tasks:all", self.config.key_prefix),
        }
    }
    
    /// Invalidate cache for a task
    async fn invalidate_task_cache(&self, task_id: Uuid) -> TaskResult<()> {
        let key = self.task_key(task_id);
        let mut conn = self.redis.clone();
        
        conn.del::<_, ()>(&key)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to invalidate cache: {}", e)))?;
        
        // Also invalidate all task lists
        self.invalidate_task_lists().await?;
        
        Ok(())
    }
    
    /// Invalidate all task list caches
    async fn invalidate_task_lists(&self) -> TaskResult<()> {
        let pattern = format!("{}:tasks:*", self.config.key_prefix);
        let mut conn = self.redis.clone();
        
        // Get all task list keys
        let keys: Vec<String> = conn
            .keys(&pattern)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to get cache keys: {}", e)))?;
        
        if !keys.is_empty() {
            conn.del::<_, ()>(keys)
                .await
                .map_err(|e| TaskError::InternalError(format!("Failed to delete cache keys: {}", e)))?;
        }
        
        Ok(())
    }
}

#[async_trait]
impl StorageBackend for CachedStorageBackend {
    async fn create_task(&self, task: &TaskInfo) -> TaskResult<()> {
        debug!("Creating task {} through cache", task.id);
        
        // First, create in the backend
        self.backend.create_task(task).await?;
        
        // Then cache the task
        let key = self.task_key(task.id);
        let mut conn = self.redis.clone();
        
        let serialized = bincode::serialize(task)
            .map_err(|e| TaskError::InternalError(format!("Failed to serialize task: {}", e)))?;
        
        conn.set_ex::<_, _, ()>(&key, serialized, self.config.task_ttl)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to cache task: {}", e)))?;
        
        // Invalidate task lists
        self.invalidate_task_lists().await?;
        
        Ok(())
    }
    
    async fn create_tasks_batch(&self, tasks: &[TaskInfo]) -> TaskResult<()> {
        debug!("Creating {} tasks through cache in batch", tasks.len());
        
        // First, create in the backend
        self.backend.create_tasks_batch(tasks).await?;
        
        // Then cache all tasks using Redis pipeline
        let mut conn = self.redis.clone();
        let mut pipe = redis::pipe();
        
        for task in tasks {
            let key = self.task_key(task.id);
            let serialized = bincode::serialize(task)
                .map_err(|e| TaskError::InternalError(format!("Failed to serialize task: {}", e)))?;
            
            pipe.set_ex(&key, serialized, self.config.task_ttl);
        }
        
        // Execute pipeline
        pipe.query_async::<()>(&mut conn)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to batch cache tasks: {}", e)))?;
        
        // Invalidate task lists once
        self.invalidate_task_lists().await?;
        
        Ok(())
    }
    
    async fn get_task(&self, task_id: Uuid) -> TaskResult<Option<TaskInfo>> {
        debug!("Getting task {} from cache", task_id);
        
        let key = self.task_key(task_id);
        let mut conn = self.redis.clone();
        
        // Try to get from cache first
        let cached: Option<Vec<u8>> = conn
            .get(&key)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to get from cache: {}", e)))?;
        
        if let Some(cached_data) = cached {
            // Cache hit
            debug!("Cache hit for task {}", task_id);
            let task = bincode::deserialize(&cached_data)
                .map_err(|e| TaskError::InternalError(format!("Failed to deserialize task: {}", e)))?;
            return Ok(Some(task));
        }
        
        // Cache miss, get from backend
        debug!("Cache miss for task {}, fetching from backend", task_id);
        let task = self.backend.get_task(task_id).await?;
        
        // Cache the result if found
        if let Some(ref task_info) = task {
            let serialized = bincode::serialize(task_info)
                .map_err(|e| TaskError::InternalError(format!("Failed to serialize task: {}", e)))?;
            
            conn.set_ex::<_, _, ()>(&key, serialized, self.config.task_ttl)
                .await
                .map_err(|e| TaskError::InternalError(format!("Failed to cache task: {}", e)))?;
        }
        
        Ok(task)
    }
    
    async fn update_task_status(&self, task_id: Uuid, status: TaskStatus) -> TaskResult<()> {
        debug!("Updating task {} status through cache", task_id);
        
        // Update in backend
        self.backend.update_task_status(task_id, status).await?;
        
        // Invalidate cache
        self.invalidate_task_cache(task_id).await?;
        
        Ok(())
    }
    
    async fn update_tasks_status_batch(&self, task_ids: &[Uuid], status: TaskStatus) -> TaskResult<()> {
        debug!("Batch updating {} task statuses through cache", task_ids.len());
        
        // Update in backend
        self.backend.update_tasks_status_batch(task_ids, status).await?;
        
        // Invalidate cache for all tasks using pipeline
        if !task_ids.is_empty() {
            let mut pipe = redis::pipe();
            for task_id in task_ids {
                let key = self.task_key(*task_id);
                pipe.del(&key);
            }
            
            let mut conn = self.redis.clone();
            let _: () = pipe.query_async(&mut conn)
                .await
                .map_err(|e| TaskError::InternalError(format!("Failed to invalidate cache batch: {}", e)))?;
        }
        
        // Invalidate task lists since statuses changed
        self.invalidate_task_lists().await?;
        
        Ok(())
    }
    
    async fn update_task(&self, task: &TaskInfo) -> TaskResult<()> {
        debug!("Updating task {} through cache", task.id);
        
        // Update in backend
        self.backend.update_task(task).await?;
        
        // Update cache
        let key = self.task_key(task.id);
        let mut conn = self.redis.clone();
        
        let serialized = bincode::serialize(task)
            .map_err(|e| TaskError::InternalError(format!("Failed to serialize task: {}", e)))?;
        
        conn.set_ex::<_, _, ()>(&key, serialized, self.config.task_ttl)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to update cache: {}", e)))?;
        
        // Invalidate task lists
        self.invalidate_task_lists().await?;
        
        Ok(())
    }
    
    async fn store_task_result(&self, result: &TaskResultData) -> TaskResult<()> {
        debug!("Storing result for task {} through cache", result.task_id);
        
        // Store in backend
        self.backend.store_task_result(result).await?;
        
        // Cache the result
        let key = self.result_key(result.task_id);
        let mut conn = self.redis.clone();
        
        let serialized = bincode::serialize(result)
            .map_err(|e| TaskError::InternalError(format!("Failed to serialize result: {}", e)))?;
        
        conn.set_ex::<_, _, ()>(&key, serialized, self.config.result_ttl)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to cache result: {}", e)))?;
        
        Ok(())
    }
    
    async fn get_task_result(&self, task_id: Uuid) -> TaskResult<Option<TaskResultData>> {
        debug!("Getting result for task {} from cache", task_id);
        
        let key = self.result_key(task_id);
        let mut conn = self.redis.clone();
        
        // Try to get from cache first
        let cached: Option<Vec<u8>> = conn
            .get(&key)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to get from cache: {}", e)))?;
        
        if let Some(cached_data) = cached {
            // Cache hit
            debug!("Cache hit for task result {}", task_id);
            let result = bincode::deserialize(&cached_data)
                .map_err(|e| TaskError::InternalError(format!("Failed to deserialize result: {}", e)))?;
            return Ok(Some(result));
        }
        
        // Cache miss, get from backend
        debug!("Cache miss for task result {}, fetching from backend", task_id);
        let result = self.backend.get_task_result(task_id).await?;
        
        // Cache the result if found
        if let Some(ref result_data) = result {
            let serialized = bincode::serialize(result_data)
                .map_err(|e| TaskError::InternalError(format!("Failed to serialize result: {}", e)))?;
            
            conn.set_ex::<_, _, ()>(&key, serialized, self.config.result_ttl)
                .await
                .map_err(|e| TaskError::InternalError(format!("Failed to cache result: {}", e)))?;
        }
        
        Ok(result)
    }
    
    async fn list_tasks(
        &self,
        status_filter: Option<TaskStatus>,
        limit: usize,
    ) -> TaskResult<Vec<TaskInfo>> {
        // For lists, we'll cache with a shorter TTL
        let key = self.task_list_key(status_filter.as_ref());
        let mut conn = self.redis.clone();
        
        // Try to get from cache
        let cached: Option<Vec<u8>> = conn
            .get(&key)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to get from cache: {}", e)))?;
        
        if let Some(cached_data) = cached {
            // Cache hit
            debug!("Cache hit for task list");
            let tasks: Vec<TaskInfo> = bincode::deserialize(&cached_data)
                .map_err(|e| TaskError::InternalError(format!("Failed to deserialize task list: {}", e)))?;
            
            // Apply limit
            return Ok(tasks.into_iter().take(limit).collect());
        }
        
        // Cache miss, get from backend
        debug!("Cache miss for task list, fetching from backend");
        let tasks = self.backend.list_tasks(status_filter, limit).await?;
        
        // Cache the result with a shorter TTL (5 minutes)
        let serialized = bincode::serialize(&tasks)
            .map_err(|e| TaskError::InternalError(format!("Failed to serialize task list: {}", e)))?;
        
        conn.set_ex::<_, _, ()>(&key, serialized, 300)
            .await
            .map_err(|e| TaskError::InternalError(format!("Failed to cache task list: {}", e)))?;
        
        Ok(tasks)
    }
    
    async fn get_tasks_by_dependency(&self, dependency_id: Uuid) -> TaskResult<Vec<TaskInfo>> {
        // For dependency queries, we don't cache as they're less frequent
        self.backend.get_tasks_by_dependency(dependency_id).await
    }
    
    async fn delete_task(&self, task_id: Uuid) -> TaskResult<()> {
        debug!("Deleting task {} through cache", task_id);
        
        // Delete from backend
        self.backend.delete_task(task_id).await?;
        
        // Remove from cache
        self.invalidate_task_cache(task_id).await?;
        
        // Also remove result if exists
        let result_key = self.result_key(task_id);
        let mut conn = self.redis.clone();
        let _: Option<()> = conn.del(&result_key).await.ok();
        
        Ok(())
    }
    
    async fn get_stats(&self) -> StorageStats {
        // Get stats from backend, cache stats are not included
        self.backend.get_stats().await
    }
    
    async fn cleanup_old_results(&self, retention_seconds: u64) -> TaskResult<usize> {
        debug!("Cleaning up old results through cache backend");
        
        // Cleanup in the underlying backend
        let deleted_count = self.backend.cleanup_old_results(retention_seconds).await?;
        
        // Note: Redis TTL will automatically clean up cached results,
        // so we don't need to manually clean the cache
        
        info!("Cleaned up {} old results from backend storage", deleted_count);
        Ok(deleted_count)
    }
}
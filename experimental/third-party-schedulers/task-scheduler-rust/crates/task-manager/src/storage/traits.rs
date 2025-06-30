use async_trait::async_trait;
use std::sync::Arc;
use task_common::{
    error::TaskResult,
    models::{TaskInfo, TaskResult as TaskResultData, TaskStatus},
    Uuid,
};
use tracing::{error, info, warn};

/// Storage backend trait for task management
#[async_trait]
pub trait StorageBackend: Send + Sync {
    /// Create a new task
    async fn create_task(&self, task: &TaskInfo) -> TaskResult<()>;
    
    /// Create multiple tasks in a batch
    async fn create_tasks_batch(&self, tasks: &[TaskInfo]) -> TaskResult<()> {
        // Default implementation: fall back to individual inserts
        for task in tasks {
            self.create_task(task).await?;
        }
        Ok(())
    }
    
    /// Get a task by ID
    async fn get_task(&self, task_id: Uuid) -> TaskResult<Option<TaskInfo>>;
    
    /// Update task status
    async fn update_task_status(&self, task_id: Uuid, status: TaskStatus) -> TaskResult<()>;
    
    /// Update multiple task statuses in a batch
    async fn update_tasks_status_batch(&self, task_ids: &[Uuid], status: TaskStatus) -> TaskResult<()> {
        // Default implementation: fall back to individual updates
        for task_id in task_ids {
            self.update_task_status(*task_id, status.clone()).await?;
        }
        Ok(())
    }
    
    /// Update task
    async fn update_task(&self, task: &TaskInfo) -> TaskResult<()>;
    
    /// Store task result
    async fn store_task_result(&self, result: &TaskResultData) -> TaskResult<()>;
    
    /// Get task result
    async fn get_task_result(&self, task_id: Uuid) -> TaskResult<Option<TaskResultData>>;
    
    /// List tasks with optional filters
    async fn list_tasks(
        &self,
        status_filter: Option<TaskStatus>,
        limit: usize,
    ) -> TaskResult<Vec<TaskInfo>>;
    
    /// Get tasks by dependency
    async fn get_tasks_by_dependency(&self, dependency_id: Uuid) -> TaskResult<Vec<TaskInfo>>;
    
    /// Delete task
    async fn delete_task(&self, task_id: Uuid) -> TaskResult<()>;
    
    /// Clean up old task results
    async fn cleanup_old_results(&self, retention_seconds: u64) -> TaskResult<usize>;
    
    /// Get storage statistics
    async fn get_stats(&self) -> StorageStats;
}

/// Storage statistics
#[derive(Debug, Clone)]
pub struct StorageStats {
    pub total_tasks: usize,
    pub total_results: usize,
    pub cache_size: usize,
}

/// Create a storage backend based on configuration
pub async fn create_storage_backend(config: &crate::config::Config) -> TaskResult<Arc<dyn StorageBackend>> {
    use crate::config::StorageBackend as ConfigBackend;
    
    // Create the base storage backend
    let base_storage: Arc<dyn StorageBackend> = match &config.storage.backend {
        ConfigBackend::Memory => {
            info!("Using in-memory storage backend");
            let storage = super::memory::MemoryStorage::new(config.storage.cache_size);
            Arc::new(storage)
        }
        ConfigBackend::PostgreSQL => {
            if let Some(postgres_url) = &config.storage.postgres_url {
                let max_connections = config.storage.max_connections.unwrap_or(100);
                let min_connections = config.storage.min_connections.unwrap_or(10);
                
                info!("PostgreSQL storage configuration: enable_binary_storage = {}", config.storage.enable_binary_storage);
                
                if config.storage.enable_binary_storage {
                    info!("Using PostgreSQL binary storage backend (BYTEA support)");
                    match super::postgresql_binary::PostgresqlBinaryStorage::new(
                        postgres_url,
                        max_connections,
                        min_connections,
                    ).await {
                        Ok(storage) => Arc::new(storage),
                        Err(e) => {
                            error!("Failed to create PostgreSQL binary storage: {}. Falling back to memory storage", e);
                            let storage = super::memory::MemoryStorage::new(config.storage.cache_size);
                            Arc::new(storage)
                        }
                    }
                } else {
                    info!("Using PostgreSQL storage backend");
                    match super::postgresql::PostgresqlStorage::new(
                        postgres_url,
                        max_connections,
                        min_connections,
                    ).await {
                        Ok(storage) => Arc::new(storage),
                        Err(e) => {
                            error!("Failed to create PostgreSQL storage: {}. Falling back to memory storage", e);
                            let storage = super::memory::MemoryStorage::new(config.storage.cache_size);
                            Arc::new(storage)
                        }
                    }
                }
            } else {
                error!("PostgreSQL backend selected but postgres_url not provided. Falling back to memory storage");
                let storage = super::memory::MemoryStorage::new(config.storage.cache_size);
                Arc::new(storage)
            }
        }
        ConfigBackend::MongoDB => {
            warn!("MongoDB backend has been removed, falling back to memory storage");
            let storage = super::memory::MemoryStorage::new(config.storage.cache_size);
            Arc::new(storage)
        }
    };
    
    // Optionally wrap with Redis caching layer
    if let Some(redis_url) = &config.storage.redis_url {
        info!("Enabling Redis caching layer");
        let cache_config = super::redis::RedisCacheConfig::default();
        match super::redis::CachedStorageBackend::new(base_storage.clone(), redis_url, cache_config).await {
            Ok(cached_storage) => {
                info!("Redis caching layer enabled successfully");
                Ok(Arc::new(cached_storage))
            }
            Err(e) => {
                warn!("Failed to enable Redis caching: {}. Using storage without cache", e);
                Ok(base_storage)
            }
        }
    } else {
        Ok(base_storage)
    }
}
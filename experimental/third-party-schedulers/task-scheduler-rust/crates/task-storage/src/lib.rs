pub mod config;
pub mod models;
pub mod postgres;
pub mod traits;
pub mod storage_traits;

pub use config::StorageConfig;
pub use traits::{StorageError, StorageResult, TaskStorage};

// Re-export storage traits
pub use storage_traits::{Storage, ExecutionHistory, QueryFilter, TaskResult, TaskStatus, StorageError as MongoStorageError, StorageResult as MongoStorageResult};

use std::sync::Arc;

/// Create a storage backend based on configuration
pub async fn create_storage(config: &StorageConfig) -> StorageResult<Arc<dyn TaskStorage>> {
    match config {
        StorageConfig::Postgres(pg_config) => {
            let storage = postgres::PostgresStorage::new(pg_config).await?;
            Ok(Arc::new(storage))
        }
    }
}

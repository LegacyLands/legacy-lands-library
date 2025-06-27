pub mod config;
pub mod models;
pub mod postgres;
pub mod traits;

#[cfg(feature = "mongodb")]
pub mod mongodb;

pub use config::StorageConfig;
pub use traits::{StorageError, StorageResult, TaskStorage};

use std::sync::Arc;

/// Create a storage backend based on configuration
pub async fn create_storage(config: &StorageConfig) -> StorageResult<Arc<dyn TaskStorage>> {
    match config {
        StorageConfig::Postgres(pg_config) => {
            let storage = postgres::PostgresStorage::new(pg_config).await?;
            Ok(Arc::new(storage))
        }
        #[cfg(feature = "mongodb")]
        StorageConfig::MongoDB(mongo_config) => {
            let storage = mongodb::MongoStorage::new(mongo_config).await?;
            Ok(Arc::new(storage))
        }
    }
}

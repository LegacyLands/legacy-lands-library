use serde::{Deserialize, Serialize};

/// Storage configuration
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum StorageConfig {
    /// PostgreSQL storage configuration
    Postgres(PostgresConfig),

    /// MongoDB storage configuration
    #[cfg(feature = "mongodb")]
    MongoDB(MongoDBConfig),
}

/// PostgreSQL configuration
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct PostgresConfig {
    /// Database connection URL
    pub url: String,

    /// Maximum number of connections in the pool
    #[serde(default = "default_max_connections")]
    pub max_connections: u32,

    /// Connection timeout in seconds
    #[serde(default = "default_connect_timeout")]
    pub connect_timeout_seconds: u64,

    /// Whether to run migrations on startup
    #[serde(default = "default_run_migrations")]
    pub run_migrations: bool,
}

/// MongoDB configuration
#[cfg(feature = "mongodb")]
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MongoDBConfig {
    /// MongoDB connection URL
    pub url: String,

    /// Database name
    pub database: String,

    /// Collection names
    #[serde(default)]
    pub collections: MongoCollections,
}

#[cfg(feature = "mongodb")]
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MongoCollections {
    /// Task results collection
    #[serde(default = "default_results_collection")]
    pub results: String,

    /// Task history collection
    #[serde(default = "default_history_collection")]
    pub history: String,

    /// Audit logs collection
    #[serde(default = "default_audit_collection")]
    pub audit: String,
}

#[cfg(feature = "mongodb")]
impl Default for MongoCollections {
    fn default() -> Self {
        Self {
            results: default_results_collection(),
            history: default_history_collection(),
            audit: default_audit_collection(),
        }
    }
}

fn default_max_connections() -> u32 {
    10
}

fn default_connect_timeout() -> u64 {
    30
}

fn default_run_migrations() -> bool {
    true
}

#[cfg(feature = "mongodb")]
fn default_results_collection() -> String {
    "task_results".to_string()
}

#[cfg(feature = "mongodb")]
fn default_history_collection() -> String {
    "task_history".to_string()
}

#[cfg(feature = "mongodb")]
fn default_audit_collection() -> String {
    "audit_logs".to_string()
}

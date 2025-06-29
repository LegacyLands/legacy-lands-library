use serde::{Deserialize, Serialize};

/// Storage configuration
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum StorageConfig {
    /// PostgreSQL storage configuration
    Postgres(PostgresConfig),
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

fn default_max_connections() -> u32 {
    10
}

fn default_connect_timeout() -> u64 {
    30
}

fn default_run_migrations() -> bool {
    true
}

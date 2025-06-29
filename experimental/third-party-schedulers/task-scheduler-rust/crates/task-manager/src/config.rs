use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Task Manager configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// Server configuration
    pub server: ServerConfig,

    /// Storage configuration
    pub storage: StorageConfig,

    /// Queue configuration
    pub queue: QueueConfig,

    /// Kubernetes configuration
    pub kubernetes: KubernetesConfig,

    /// Observability configuration
    pub observability: ObservabilityConfig,

    /// Security configuration
    pub security: SecurityConfig,
}

/// Server configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    /// gRPC server address
    #[serde(default = "default_grpc_address")]
    pub grpc_address: String,

    /// Metrics server address
    #[serde(default = "default_metrics_address")]
    pub metrics_address: String,

    /// Enable TLS
    #[serde(default)]
    pub tls_enabled: bool,

    /// TLS certificate path
    pub tls_cert_path: Option<PathBuf>,

    /// TLS key path
    pub tls_key_path: Option<PathBuf>,

    /// CA certificate path for mTLS
    pub tls_ca_path: Option<PathBuf>,

    /// Maximum concurrent requests
    #[serde(default = "default_max_concurrent_requests")]
    pub max_concurrent_requests: usize,
}

/// Storage configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageConfig {
    /// Storage backend type
    #[serde(default = "default_storage_backend")]
    pub backend: StorageBackend,

    /// Cache size
    #[serde(default = "default_cache_size")]
    pub cache_size: usize,

    /// PostgreSQL connection string (if using PostgreSQL)
    pub postgres_url: Option<String>,

    /// Redis connection string (if using Redis for caching)
    pub redis_url: Option<String>,
}

/// Storage backend type
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum StorageBackend {
    Memory,
    PostgreSQL,
    MongoDB,
}

/// Queue configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueueConfig {
    /// NATS server URL
    #[serde(default = "default_nats_url")]
    pub nats_url: String,

    /// Enable JetStream
    #[serde(default = "default_jetstream_enabled")]
    pub jetstream_enabled: bool,

    /// Queue subjects prefix
    #[serde(default = "default_queue_prefix")]
    pub queue_prefix: String,
}

/// Kubernetes configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KubernetesConfig {
    /// Namespace to watch
    #[serde(default = "default_namespace")]
    pub namespace: String,

    /// Enable Kubernetes integration
    #[serde(default)]
    pub enabled: bool,

    /// Kubeconfig path (if not in-cluster)
    pub kubeconfig_path: Option<PathBuf>,
}

/// Observability configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ObservabilityConfig {
    /// Enable tracing
    #[serde(default = "default_tracing_enabled")]
    pub tracing_enabled: bool,

    /// OTLP endpoint
    #[serde(default = "default_otlp_endpoint")]
    pub otlp_endpoint: String,

    /// Service name
    #[serde(default = "default_service_name")]
    pub service_name: String,

    /// Log level
    #[serde(default = "default_log_level")]
    pub log_level: String,

    /// Sampling ratio (0.0 to 1.0)
    #[serde(default = "default_sampling_ratio")]
    pub sampling_ratio: f64,
}

/// Security configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecurityConfig {
    /// Enable authentication
    #[serde(default = "default_auth_enabled")]
    pub auth_enabled: bool,

    /// Enable RBAC (Role-Based Access Control)
    #[serde(default = "default_rbac_enabled")]
    pub rbac_enabled: bool,

    /// Enable audit logging
    #[serde(default = "default_audit_enabled")]
    pub audit_enabled: bool,

    /// Audit log path
    #[serde(default = "default_audit_log_path")]
    pub audit_log_path: String,

    /// Minimum audit severity level
    #[serde(default = "default_audit_min_severity")]
    pub audit_min_severity: String,

    /// JWT secret for token validation (if using JWT)
    pub jwt_secret: Option<String>,

    /// Default admin user (for initial setup)
    pub default_admin_user: Option<String>,

    /// Default admin password (for initial setup)
    pub default_admin_password: Option<String>,

    /// Session timeout in seconds
    #[serde(default = "default_session_timeout")]
    pub session_timeout: u64,
}

// Default value functions
fn default_grpc_address() -> String {
    "0.0.0.0:50051".to_string()
}

fn default_metrics_address() -> String {
    "0.0.0.0:9000".to_string()
}

fn default_max_concurrent_requests() -> usize {
    1000
}

fn default_storage_backend() -> StorageBackend {
    StorageBackend::Memory
}

fn default_cache_size() -> usize {
    10000
}

fn default_nats_url() -> String {
    "nats://localhost:4222".to_string()
}

fn default_jetstream_enabled() -> bool {
    true
}

fn default_queue_prefix() -> String {
    "tasks".to_string()
}

fn default_namespace() -> String {
    "task-scheduler".to_string()
}

fn default_tracing_enabled() -> bool {
    true
}

fn default_otlp_endpoint() -> String {
    "http://localhost:4317".to_string()
}

fn default_service_name() -> String {
    "task-manager".to_string()
}

fn default_log_level() -> String {
    "info".to_string()
}

fn default_sampling_ratio() -> f64 {
    1.0
}

fn default_auth_enabled() -> bool {
    false
}

fn default_rbac_enabled() -> bool {
    false
}

fn default_audit_enabled() -> bool {
    true
}

fn default_audit_log_path() -> String {
    "/var/log/task-scheduler/audit.log".to_string()
}

fn default_audit_min_severity() -> String {
    "info".to_string()
}

fn default_session_timeout() -> u64 {
    3600 // 1 hour
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server: ServerConfig {
                grpc_address: default_grpc_address(),
                metrics_address: default_metrics_address(),
                tls_enabled: false,
                tls_cert_path: None,
                tls_key_path: None,
                tls_ca_path: None,
                max_concurrent_requests: default_max_concurrent_requests(),
            },
            storage: StorageConfig {
                backend: default_storage_backend(),
                cache_size: default_cache_size(),
                postgres_url: None,
                redis_url: None,
            },
            queue: QueueConfig {
                nats_url: default_nats_url(),
                jetstream_enabled: default_jetstream_enabled(),
                queue_prefix: default_queue_prefix(),
            },
            kubernetes: KubernetesConfig {
                namespace: default_namespace(),
                enabled: false,
                kubeconfig_path: None,
            },
            observability: ObservabilityConfig {
                tracing_enabled: default_tracing_enabled(),
                otlp_endpoint: default_otlp_endpoint(),
                service_name: default_service_name(),
                log_level: default_log_level(),
                sampling_ratio: default_sampling_ratio(),
            },
            security: SecurityConfig {
                auth_enabled: default_auth_enabled(),
                rbac_enabled: default_rbac_enabled(),
                audit_enabled: default_audit_enabled(),
                audit_log_path: default_audit_log_path(),
                audit_min_severity: default_audit_min_severity(),
                jwt_secret: None,
                default_admin_user: None,
                default_admin_password: None,
                session_timeout: default_session_timeout(),
            },
        }
    }
}

impl Config {
    /// Load configuration from file
    pub fn from_file(path: &PathBuf) -> Result<Self, Box<dyn std::error::Error>> {
        let content = std::fs::read_to_string(path)?;
        let config: Config = toml::from_str(&content)?;
        Ok(config)
    }

    /// Load configuration from environment variables
    pub fn from_env() -> Self {
        let mut config = Config::default();

        // Override with environment variables
        if let Ok(addr) = std::env::var("GRPC_ADDRESS") {
            config.server.grpc_address = addr;
        }

        if let Ok(url) = std::env::var("NATS_URL") {
            config.queue.nats_url = url;
        }

        if let Ok(level) = std::env::var("LOG_LEVEL") {
            config.observability.log_level = level;
        }

        if let Ok(endpoint) = std::env::var("OTLP_ENDPOINT") {
            config.observability.otlp_endpoint = endpoint;
        }

        config
    }
}

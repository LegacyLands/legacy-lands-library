use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::str::FromStr;

#[cfg(test)]
mod test;

/// Task Worker configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// Worker configuration
    #[serde(default)]
    pub worker: WorkerConfig,

    /// Queue configuration
    #[serde(default)]
    pub queue: QueueConfig,

    /// Kubernetes configuration
    #[serde(default)]
    pub kubernetes: KubernetesConfig,

    /// Plugin configuration
    #[serde(default)]
    pub plugins: PluginConfig,

    /// Observability configuration
    #[serde(default)]
    pub observability: ObservabilityConfig,
}

/// Worker configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerConfig {
    /// Worker ID (defaults to hostname-pid)
    pub worker_id: Option<String>,

    /// Maximum concurrent tasks
    #[serde(default = "default_max_concurrent_tasks")]
    pub max_concurrent_tasks: usize,

    /// Metrics server address
    #[serde(default = "default_metrics_address")]
    pub metrics_address: String,

    /// Operation mode (worker or job)
    #[serde(default = "default_mode")]
    pub mode: OperationMode,
}

/// Operation mode
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum OperationMode {
    /// Long-running worker mode
    Worker,

    /// Single job execution mode (for K8s Jobs)
    Job,
}

impl FromStr for OperationMode {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s.to_lowercase().as_str() {
            "worker" => Ok(OperationMode::Worker),
            "job" => Ok(OperationMode::Job),
            _ => Err(format!(
                "Invalid operation mode: {}. Expected 'worker' or 'job'",
                s
            )),
        }
    }
}

/// Queue configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueueConfig {
    /// NATS server URL
    #[serde(default = "default_nats_url")]
    pub nats_url: String,

    /// Queue fetch batch size
    #[serde(default = "default_batch_size")]
    pub batch_size: usize,

    /// Queue fetch timeout
    #[serde(default = "default_fetch_timeout")]
    pub fetch_timeout_seconds: u64,
}

/// Kubernetes configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KubernetesConfig {
    /// Enable Kubernetes job execution
    #[serde(default)]
    pub enabled: bool,

    /// Namespace for jobs
    #[serde(default = "default_namespace")]
    pub namespace: String,

    /// Worker image for jobs
    #[serde(default = "default_worker_image")]
    pub worker_image: String,

    /// Service account for jobs
    #[serde(default = "default_service_account")]
    pub service_account: String,

    /// Kubeconfig path (if not in-cluster)
    pub kubeconfig_path: Option<PathBuf>,
}

/// Plugin configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PluginConfig {
    /// Plugin directories
    #[serde(default)]
    pub plugin_dirs: Vec<PathBuf>,

    /// Auto-load plugins on startup
    #[serde(default = "default_auto_load")]
    pub auto_load: bool,

    /// Plugin file patterns
    #[serde(default = "default_plugin_patterns")]
    pub plugin_patterns: Vec<String>,
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

// Default value functions
fn default_max_concurrent_tasks() -> usize {
    10
}

fn default_metrics_address() -> String {
    "0.0.0.0:9001".to_string()
}

fn default_mode() -> OperationMode {
    OperationMode::Worker
}

fn default_nats_url() -> String {
    "nats://localhost:4222".to_string()
}

fn default_batch_size() -> usize {
    10
}

fn default_fetch_timeout() -> u64 {
    30
}

fn default_namespace() -> String {
    "task-scheduler".to_string()
}

fn default_worker_image() -> String {
    "task-worker:latest".to_string()
}

fn default_service_account() -> String {
    "task-worker".to_string()
}

fn default_auto_load() -> bool {
    true
}

fn default_plugin_patterns() -> Vec<String> {
    vec![
        "*.so".to_string(),
        "*.dll".to_string(),
        "*.dylib".to_string(),
    ]
}

fn default_tracing_enabled() -> bool {
    true
}

fn default_otlp_endpoint() -> String {
    "http://localhost:4317".to_string()
}

fn default_service_name() -> String {
    "task-worker".to_string()
}

fn default_log_level() -> String {
    "info".to_string()
}

fn default_sampling_ratio() -> f64 {
    1.0
}

impl Default for WorkerConfig {
    fn default() -> Self {
        Self {
            worker_id: None,
            max_concurrent_tasks: default_max_concurrent_tasks(),
            metrics_address: default_metrics_address(),
            mode: default_mode(),
        }
    }
}

impl Default for QueueConfig {
    fn default() -> Self {
        Self {
            nats_url: default_nats_url(),
            batch_size: default_batch_size(),
            fetch_timeout_seconds: default_fetch_timeout(),
        }
    }
}

impl Default for KubernetesConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            namespace: default_namespace(),
            worker_image: default_worker_image(),
            service_account: default_service_account(),
            kubeconfig_path: None,
        }
    }
}

impl Default for PluginConfig {
    fn default() -> Self {
        Self {
            plugin_dirs: vec![],
            auto_load: default_auto_load(),
            plugin_patterns: default_plugin_patterns(),
        }
    }
}

impl Default for ObservabilityConfig {
    fn default() -> Self {
        Self {
            tracing_enabled: default_tracing_enabled(),
            otlp_endpoint: default_otlp_endpoint(),
            service_name: default_service_name(),
            log_level: default_log_level(),
            sampling_ratio: default_sampling_ratio(),
        }
    }
}

impl Default for Config {
    fn default() -> Self {
        Self {
            worker: WorkerConfig::default(),
            queue: QueueConfig::default(),
            kubernetes: KubernetesConfig::default(),
            plugins: PluginConfig::default(),
            observability: ObservabilityConfig::default(),
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
        if let Ok(url) = std::env::var("NATS_URL") {
            config.queue.nats_url = url;
        }

        if let Ok(level) = std::env::var("LOG_LEVEL") {
            config.observability.log_level = level;
        }

        if let Ok(endpoint) = std::env::var("OTLP_ENDPOINT") {
            config.observability.otlp_endpoint = endpoint;
        }

        if let Ok(max) = std::env::var("MAX_CONCURRENT_TASKS") {
            if let Ok(max_tasks) = max.parse::<usize>() {
                config.worker.max_concurrent_tasks = max_tasks;
            }
        }

        // Check if running in Kubernetes Job mode
        if std::env::var("TASK_METHOD").is_ok() {
            config.worker.mode = OperationMode::Job;
        }

        config
    }
}

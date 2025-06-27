use super::*;
use std::fs;
use std::path::PathBuf;
use tempfile::TempDir;

#[cfg(test)]
mod config_tests {
    use super::*;

    #[test]
    fn test_default_config() {
        let config = Config::default();
        
        // Worker defaults
        assert!(config.worker.worker_id.is_none());
        assert_eq!(config.worker.max_concurrent_tasks, 10);
        assert_eq!(config.worker.metrics_address, "0.0.0.0:9001");
        assert!(matches!(config.worker.mode, OperationMode::Worker));
        
        // Queue defaults
        assert_eq!(config.queue.nats_url, "nats://localhost:4222");
        assert_eq!(config.queue.batch_size, 10);
        assert_eq!(config.queue.fetch_timeout_seconds, 30);
        
        // Kubernetes defaults
        assert!(!config.kubernetes.enabled);
        assert_eq!(config.kubernetes.namespace, "task-scheduler");
        assert_eq!(config.kubernetes.worker_image, "task-worker:latest");
        assert_eq!(config.kubernetes.service_account, "task-worker");
        assert!(config.kubernetes.kubeconfig_path.is_none());
        
        // Plugin defaults
        assert!(config.plugins.plugin_dirs.is_empty());
        assert!(config.plugins.auto_load);
        assert_eq!(config.plugins.plugin_patterns.len(), 3);
        assert!(config.plugins.plugin_patterns.contains(&"*.so".to_string()));
        assert!(config.plugins.plugin_patterns.contains(&"*.dll".to_string()));
        assert!(config.plugins.plugin_patterns.contains(&"*.dylib".to_string()));
        
        // Observability defaults
        assert!(config.observability.tracing_enabled);
        assert_eq!(config.observability.otlp_endpoint, "http://localhost:4317");
        assert_eq!(config.observability.service_name, "task-worker");
        assert_eq!(config.observability.log_level, "info");
        assert_eq!(config.observability.sampling_ratio, 1.0);
    }

    #[test]
    fn test_operation_mode_from_str() {
        // Valid modes
        assert!(matches!(
            OperationMode::from_str("worker").unwrap(),
            OperationMode::Worker
        ));
        assert!(matches!(
            OperationMode::from_str("job").unwrap(),
            OperationMode::Job
        ));
        
        // Case insensitive
        assert!(matches!(
            OperationMode::from_str("WORKER").unwrap(),
            OperationMode::Worker
        ));
        assert!(matches!(
            OperationMode::from_str("Job").unwrap(),
            OperationMode::Job
        ));
        
        // Invalid mode
        let result = OperationMode::from_str("invalid");
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Invalid operation mode"));
    }

    #[test]
    fn test_config_from_file() {
        let temp_dir = TempDir::new().unwrap();
        let config_path = temp_dir.path().join("config.toml");
        
        let config_content = r#"
[worker]
worker_id = "test-worker-001"
max_concurrent_tasks = 20
metrics_address = "127.0.0.1:9090"
mode = "job"

[queue]
nats_url = "nats://nats-server:4222"
batch_size = 15
fetch_timeout_seconds = 45

[kubernetes]
enabled = true
namespace = "custom-namespace"
worker_image = "my-worker:v1.0"
service_account = "custom-sa"
kubeconfig_path = "/path/to/kubeconfig"

[plugins]
plugin_dirs = ["/plugins/dir1", "/plugins/dir2"]
auto_load = false
plugin_patterns = ["*.so", "*.plugin"]

[observability]
tracing_enabled = false
otlp_endpoint = "http://otlp-collector:4317"
service_name = "custom-worker"
log_level = "debug"
sampling_ratio = 0.5
"#;
        
        fs::write(&config_path, config_content).unwrap();
        
        let config = Config::from_file(&config_path).unwrap();
        
        // Verify all values were loaded correctly
        assert_eq!(config.worker.worker_id, Some("test-worker-001".to_string()));
        assert_eq!(config.worker.max_concurrent_tasks, 20);
        assert_eq!(config.worker.metrics_address, "127.0.0.1:9090");
        assert!(matches!(config.worker.mode, OperationMode::Job));
        
        assert_eq!(config.queue.nats_url, "nats://nats-server:4222");
        assert_eq!(config.queue.batch_size, 15);
        assert_eq!(config.queue.fetch_timeout_seconds, 45);
        
        assert!(config.kubernetes.enabled);
        assert_eq!(config.kubernetes.namespace, "custom-namespace");
        assert_eq!(config.kubernetes.worker_image, "my-worker:v1.0");
        assert_eq!(config.kubernetes.service_account, "custom-sa");
        assert_eq!(
            config.kubernetes.kubeconfig_path,
            Some(PathBuf::from("/path/to/kubeconfig"))
        );
        
        assert_eq!(config.plugins.plugin_dirs.len(), 2);
        assert!(!config.plugins.auto_load);
        assert_eq!(config.plugins.plugin_patterns.len(), 2);
        
        assert!(!config.observability.tracing_enabled);
        assert_eq!(config.observability.otlp_endpoint, "http://otlp-collector:4317");
        assert_eq!(config.observability.service_name, "custom-worker");
        assert_eq!(config.observability.log_level, "debug");
        assert_eq!(config.observability.sampling_ratio, 0.5);
    }

    #[test]
    fn test_config_from_file_partial() {
        let temp_dir = TempDir::new().unwrap();
        let config_path = temp_dir.path().join("partial_config.toml");
        
        // Only specify some values, others should use defaults
        let config_content = r#"
[worker]
max_concurrent_tasks = 15

[queue]
nats_url = "nats://custom:4222"
"#;
        
        fs::write(&config_path, config_content).unwrap();
        
        let config = Config::from_file(&config_path).unwrap();
        
        // Check specified values
        assert_eq!(config.worker.max_concurrent_tasks, 15);
        assert_eq!(config.queue.nats_url, "nats://custom:4222");
        
        // Check defaults are applied for unspecified values
        assert!(config.worker.worker_id.is_none());
        assert_eq!(config.worker.metrics_address, "0.0.0.0:9001");
        assert!(matches!(config.worker.mode, OperationMode::Worker));
        assert_eq!(config.queue.batch_size, 10);
        assert!(!config.kubernetes.enabled);
        assert!(config.plugins.auto_load);
        assert!(config.observability.tracing_enabled);
    }

    #[test]
    fn test_config_from_env() {
        // Save current env values
        let saved_nats = std::env::var("NATS_URL").ok();
        let saved_log = std::env::var("LOG_LEVEL").ok();
        let saved_otlp = std::env::var("OTLP_ENDPOINT").ok();
        let saved_max = std::env::var("MAX_CONCURRENT_TASKS").ok();
        let saved_method = std::env::var("TASK_METHOD").ok();
        
        // Set test environment variables
        std::env::set_var("NATS_URL", "nats://env-server:4222");
        std::env::set_var("LOG_LEVEL", "trace");
        std::env::set_var("OTLP_ENDPOINT", "http://env-otlp:4317");
        std::env::set_var("MAX_CONCURRENT_TASKS", "25");
        std::env::set_var("TASK_METHOD", "test_method");
        
        let config = Config::from_env();
        
        // Verify environment overrides
        assert_eq!(config.queue.nats_url, "nats://env-server:4222");
        assert_eq!(config.observability.log_level, "trace");
        assert_eq!(config.observability.otlp_endpoint, "http://env-otlp:4317");
        assert_eq!(config.worker.max_concurrent_tasks, 25);
        assert!(matches!(config.worker.mode, OperationMode::Job));
        
        // Verify other values use defaults
        assert!(config.worker.worker_id.is_none());
        assert_eq!(config.worker.metrics_address, "0.0.0.0:9001");
        assert_eq!(config.queue.batch_size, 10);
        
        // Restore original env values
        match saved_nats {
            Some(val) => std::env::set_var("NATS_URL", val),
            None => std::env::remove_var("NATS_URL"),
        }
        match saved_log {
            Some(val) => std::env::set_var("LOG_LEVEL", val),
            None => std::env::remove_var("LOG_LEVEL"),
        }
        match saved_otlp {
            Some(val) => std::env::set_var("OTLP_ENDPOINT", val),
            None => std::env::remove_var("OTLP_ENDPOINT"),
        }
        match saved_max {
            Some(val) => std::env::set_var("MAX_CONCURRENT_TASKS", val),
            None => std::env::remove_var("MAX_CONCURRENT_TASKS"),
        }
        match saved_method {
            Some(val) => std::env::set_var("TASK_METHOD", val),
            None => std::env::remove_var("TASK_METHOD"),
        }
    }

    #[test]
    fn test_config_from_env_invalid_values() {
        // Use a struct to ensure cleanup on drop
        struct CleanupGuard {
            saved: Option<String>,
        }
        
        impl Drop for CleanupGuard {
            fn drop(&mut self) {
                match &self.saved {
                    Some(val) => std::env::set_var("MAX_CONCURRENT_TASKS", val),
                    None => std::env::remove_var("MAX_CONCURRENT_TASKS"),
                }
            }
        }
        
        // Save current value and ensure cleanup
        let _guard = CleanupGuard {
            saved: std::env::var("MAX_CONCURRENT_TASKS").ok(),
        };
        
        // First remove any existing value to ensure clean state
        std::env::remove_var("MAX_CONCURRENT_TASKS");
        
        // Set invalid value
        std::env::set_var("MAX_CONCURRENT_TASKS", "not_a_number");
        
        let config = Config::from_env();
        
        // Should use default when parsing fails
        assert_eq!(config.worker.max_concurrent_tasks, 10);
    }

    #[test]
    fn test_config_from_invalid_file() {
        let temp_dir = TempDir::new().unwrap();
        let config_path = temp_dir.path().join("invalid.toml");
        
        // Write invalid TOML
        let invalid_content = r#"
[worker
max_concurrent_tasks = 20
this is not valid toml
"#;
        
        fs::write(&config_path, invalid_content).unwrap();
        
        let result = Config::from_file(&config_path);
        assert!(result.is_err());
    }

    #[test]
    fn test_config_from_nonexistent_file() {
        let config_path = PathBuf::from("/nonexistent/path/config.toml");
        let result = Config::from_file(&config_path);
        assert!(result.is_err());
    }

    #[test]
    fn test_config_serialization() {
        let config = Config::default();
        
        // Serialize to TOML
        let serialized = toml::to_string(&config).unwrap();
        assert!(serialized.contains("[worker]"));
        assert!(serialized.contains("[queue]"));
        assert!(serialized.contains("[kubernetes]"));
        assert!(serialized.contains("[plugins]"));
        assert!(serialized.contains("[observability]"));
        
        // Deserialize back
        let deserialized: Config = toml::from_str(&serialized).unwrap();
        assert_eq!(deserialized.worker.max_concurrent_tasks, config.worker.max_concurrent_tasks);
        assert_eq!(deserialized.queue.nats_url, config.queue.nats_url);
    }

    #[test]
    fn test_operation_mode_serialization() {
        // Test Worker mode
        let mode = OperationMode::Worker;
        let serialized = serde_json::to_string(&mode).unwrap();
        assert_eq!(serialized, r#""worker""#);
        
        let deserialized: OperationMode = serde_json::from_str(&serialized).unwrap();
        assert!(matches!(deserialized, OperationMode::Worker));
        
        // Test Job mode
        let mode = OperationMode::Job;
        let serialized = serde_json::to_string(&mode).unwrap();
        assert_eq!(serialized, r#""job""#);
        
        let deserialized: OperationMode = serde_json::from_str(&serialized).unwrap();
        assert!(matches!(deserialized, OperationMode::Job));
    }

    #[test]
    fn test_config_edge_cases() {
        let temp_dir = TempDir::new().unwrap();
        let config_path = temp_dir.path().join("edge_cases.toml");
        
        // Test edge cases like empty strings, extreme values
        let config_content = r#"
[worker]
worker_id = ""
max_concurrent_tasks = 0
metrics_address = ""

[queue]
nats_url = ""
batch_size = 0
fetch_timeout_seconds = 0

[observability]
sampling_ratio = 0.0
"#;
        
        fs::write(&config_path, config_content).unwrap();
        
        let config = Config::from_file(&config_path).unwrap();
        
        // Empty strings should be preserved
        assert_eq!(config.worker.worker_id, Some("".to_string()));
        assert_eq!(config.worker.metrics_address, "");
        assert_eq!(config.queue.nats_url, "");
        
        // Zero values should be allowed
        assert_eq!(config.worker.max_concurrent_tasks, 0);
        assert_eq!(config.queue.batch_size, 0);
        assert_eq!(config.queue.fetch_timeout_seconds, 0);
        assert_eq!(config.observability.sampling_ratio, 0.0);
    }
}
use chrono::Utc;
use serde_json;
use std::collections::HashMap;
use task_common::models::*;
use uuid::Uuid;

#[cfg(test)]
mod task_info_tests {
    use super::*;

    #[test]
    fn test_task_info_creation() {
        let task_id = Uuid::new_v4();
        let now = Utc::now();

        let task_info = TaskInfo {
            id: task_id,
            method: "process_data".to_string(),
            args: vec![serde_json::json!("arg1"), serde_json::json!(42)],
            dependencies: vec![Uuid::new_v4(), Uuid::new_v4()],
            priority: 50,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Pending,
            created_at: now,
            updated_at: now,
        };

        assert_eq!(task_info.id, task_id);
        assert_eq!(task_info.method, "process_data");
        assert_eq!(task_info.args.len(), 2);
        assert_eq!(task_info.dependencies.len(), 2);
        assert_eq!(task_info.priority, 50);
        assert_eq!(task_info.status, TaskStatus::Pending);
    }

    #[test]
    fn test_task_info_serialization() {
        let task_info = TaskInfo {
            id: Uuid::new_v4(),
            method: "test_method".to_string(),
            args: vec![serde_json::json!({"key": "value"})],
            dependencies: vec![],
            priority: 75,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Queued,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        let serialized = serde_json::to_string(&task_info).unwrap();
        let deserialized: TaskInfo = serde_json::from_str(&serialized).unwrap();

        assert_eq!(deserialized.id, task_info.id);
        assert_eq!(deserialized.method, task_info.method);
        assert_eq!(deserialized.priority, task_info.priority);
        assert_eq!(deserialized.status, task_info.status);
    }

    #[test]
    fn test_task_info_with_complex_args() {
        let complex_args = vec![
            serde_json::json!(null),
            serde_json::json!(true),
            serde_json::json!(123),
            serde_json::json!("string"),
            serde_json::json!([1, 2, 3]),
            serde_json::json!({"nested": {"key": "value"}}),
        ];

        let task_info = TaskInfo {
            id: Uuid::new_v4(),
            method: "complex_method".to_string(),
            args: complex_args.clone(),
            dependencies: vec![],
            priority: 100,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Pending,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        assert_eq!(task_info.args.len(), 6);
        assert!(task_info.args[0].is_null());
        assert_eq!(task_info.args[1], serde_json::json!(true));
        assert_eq!(task_info.args[2], serde_json::json!(123));
    }
}

#[cfg(test)]
mod task_status_tests {
    use super::*;

    #[test]
    fn test_task_status_default() {
        let status = TaskStatus::default();
        assert_eq!(status, TaskStatus::Pending);
    }

    #[test]
    fn test_task_status_variants() {
        let pending = TaskStatus::Pending;
        let queued = TaskStatus::Queued;
        let waiting = TaskStatus::WaitingDependencies;

        assert_ne!(pending, queued);
        assert_ne!(queued, waiting);
        assert_ne!(pending, waiting);
    }

    #[test]
    fn test_task_status_running() {
        let started_at = Utc::now();
        let running = TaskStatus::Running {
            worker_id: "worker-123".to_string(),
            started_at,
        };

        match running {
            TaskStatus::Running {
                worker_id,
                started_at: ts,
            } => {
                assert_eq!(worker_id, "worker-123");
                assert_eq!(ts, started_at);
            }
            _ => panic!("Expected Running status"),
        }
    }

    #[test]
    fn test_task_status_succeeded() {
        let completed_at = Utc::now();
        let succeeded = TaskStatus::Succeeded {
            completed_at,
            duration_ms: 1500,
        };

        match succeeded {
            TaskStatus::Succeeded {
                completed_at: ts,
                duration_ms,
            } => {
                assert_eq!(ts, completed_at);
                assert_eq!(duration_ms, 1500);
            }
            _ => panic!("Expected Succeeded status"),
        }
    }

    #[test]
    fn test_task_status_failed() {
        let completed_at = Utc::now();
        let failed = TaskStatus::Failed {
            completed_at,
            error: "Connection timeout".to_string(),
            retries: 3,
        };

        match failed {
            TaskStatus::Failed {
                completed_at: ts,
                error,
                retries,
            } => {
                assert_eq!(ts, completed_at);
                assert_eq!(error, "Connection timeout");
                assert_eq!(retries, 3);
            }
            _ => panic!("Expected Failed status"),
        }
    }

    #[test]
    fn test_task_status_cancelled() {
        let cancelled_at = Utc::now();
        let cancelled = TaskStatus::Cancelled {
            cancelled_at,
            reason: "User requested cancellation".to_string(),
        };

        match cancelled {
            TaskStatus::Cancelled {
                cancelled_at: ts,
                reason,
            } => {
                assert_eq!(ts, cancelled_at);
                assert_eq!(reason, "User requested cancellation");
            }
            _ => panic!("Expected Cancelled status"),
        }
    }

    #[test]
    fn test_task_status_serialization() {
        let statuses = vec![
            TaskStatus::Pending,
            TaskStatus::Queued,
            TaskStatus::WaitingDependencies,
            TaskStatus::Running {
                worker_id: "worker-1".to_string(),
                started_at: Utc::now(),
            },
            TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 1000,
            },
            TaskStatus::Failed {
                completed_at: Utc::now(),
                error: "Test error".to_string(),
                retries: 2,
            },
            TaskStatus::Cancelled {
                cancelled_at: Utc::now(),
                reason: "Test cancellation".to_string(),
            },
        ];

        for status in statuses {
            let serialized = serde_json::to_string(&status).unwrap();
            let deserialized: TaskStatus = serde_json::from_str(&serialized).unwrap();
            assert_eq!(deserialized, status);
        }
    }

    #[test]
    fn test_valid_status_transitions() {
        // Valid transitions from Pending
        let transitions = vec![
            (TaskStatus::Pending, TaskStatus::Queued),
            (TaskStatus::Queued, TaskStatus::WaitingDependencies),
            (
                TaskStatus::Queued,
                TaskStatus::Running {
                    worker_id: "worker-1".to_string(),
                    started_at: Utc::now(),
                },
            ),
            (TaskStatus::WaitingDependencies, TaskStatus::Queued),
        ];

        // Just ensure they can be assigned
        for (_from, to) in transitions {
            let status = to;
            assert!(matches!(
                status,
                TaskStatus::Queued | TaskStatus::WaitingDependencies | TaskStatus::Running { .. }
            ));
        }
    }
}

#[cfg(test)]
mod task_metadata_tests {
    use super::*;

    #[test]
    fn test_task_metadata_default() {
        let metadata = TaskMetadata::default();

        assert!(metadata.user.is_none());
        assert!(metadata.source.is_none());
        assert!(metadata.labels.is_empty());
        assert!(metadata.annotations.is_empty());
        assert!(metadata.retry_config.is_none());
        assert!(metadata.resources.is_none());
        assert!(metadata.timeout_seconds.is_none());
        assert!(metadata.plugin.is_none());
    }

    #[test]
    fn test_task_metadata_with_all_fields() {
        let mut labels = HashMap::new();
        labels.insert("env".to_string(), "production".to_string());
        labels.insert("team".to_string(), "backend".to_string());

        let mut annotations = HashMap::new();
        annotations.insert(
            "description".to_string(),
            "Data processing task".to_string(),
        );

        let metadata = TaskMetadata {
            user: Some("user123".to_string()),
            source: Some("api-gateway".to_string()),
            labels,
            annotations,
            retry_config: Some(RetryConfig::default()),
            resources: Some(ResourceRequirements {
                cpu_request: Some("100m".to_string()),
                cpu_limit: Some("500m".to_string()),
                memory_request: Some("128Mi".to_string()),
                memory_limit: Some("512Mi".to_string()),
                ephemeral_storage_request: None,
                ephemeral_storage_limit: None,
            }),
            timeout_seconds: Some(300),
            plugin: Some(PluginConfig {
                name: "custom-plugin".to_string(),
                version: "1.0.0".to_string(),
                config_map: Some("plugin-config".to_string()),
                pvc: None,
                config: HashMap::new(),
            }),
        };

        assert_eq!(metadata.user, Some("user123".to_string()));
        assert_eq!(metadata.source, Some("api-gateway".to_string()));
        assert_eq!(metadata.labels.get("env"), Some(&"production".to_string()));
        assert_eq!(metadata.labels.get("team"), Some(&"backend".to_string()));
        assert!(metadata.retry_config.is_some());
        assert!(metadata.resources.is_some());
        assert_eq!(metadata.timeout_seconds, Some(300));
        assert!(metadata.plugin.is_some());
    }

    #[test]
    fn test_task_metadata_serialization() {
        let mut metadata = TaskMetadata::default();
        metadata.user = Some("test-user".to_string());
        metadata.timeout_seconds = Some(120);

        let serialized = serde_json::to_string(&metadata).unwrap();
        let deserialized: TaskMetadata = serde_json::from_str(&serialized).unwrap();

        assert_eq!(deserialized.user, metadata.user);
        assert_eq!(deserialized.timeout_seconds, metadata.timeout_seconds);
    }

    #[test]
    fn test_task_metadata_with_empty_collections() {
        let metadata = TaskMetadata {
            user: None,
            source: None,
            labels: HashMap::new(),
            annotations: HashMap::new(),
            retry_config: None,
            resources: None,
            timeout_seconds: None,
            plugin: None,
        };

        let serialized = serde_json::to_string(&metadata).unwrap();
        let deserialized: TaskMetadata = serde_json::from_str(&serialized).unwrap();

        assert!(deserialized.labels.is_empty());
        assert!(deserialized.annotations.is_empty());
    }
}

#[cfg(test)]
mod retry_config_tests {
    use super::*;

    #[test]
    fn test_retry_config_default() {
        let config = RetryConfig::default();

        assert_eq!(config.max_retries, 3);
        assert!(matches!(
            config.backoff_strategy,
            BackoffStrategy::Exponential
        ));
        assert_eq!(config.initial_backoff_ms, 1000);
        assert_eq!(config.max_backoff_ms, 60000);
        assert_eq!(config.backoff_multiplier, 2.0);
    }

    #[test]
    fn test_retry_config_custom() {
        let config = RetryConfig {
            max_retries: 5,
            backoff_strategy: BackoffStrategy::Linear,
            initial_backoff_ms: 500,
            max_backoff_ms: 30000,
            backoff_multiplier: 1.5,
        };

        assert_eq!(config.max_retries, 5);
        assert!(matches!(config.backoff_strategy, BackoffStrategy::Linear));
        assert_eq!(config.initial_backoff_ms, 500);
        assert_eq!(config.max_backoff_ms, 30000);
        assert_eq!(config.backoff_multiplier, 1.5);
    }

    #[test]
    fn test_backoff_strategies() {
        let strategies = vec![
            BackoffStrategy::Fixed,
            BackoffStrategy::Exponential,
            BackoffStrategy::Linear,
        ];

        for strategy in strategies {
            let config = RetryConfig {
                max_retries: 3,
                backoff_strategy: strategy.clone(),
                initial_backoff_ms: 1000,
                max_backoff_ms: 10000,
                backoff_multiplier: 2.0,
            };

            let serialized = serde_json::to_string(&config).unwrap();
            let deserialized: RetryConfig = serde_json::from_str(&serialized).unwrap();

            match (&deserialized.backoff_strategy, &strategy) {
                (BackoffStrategy::Fixed, BackoffStrategy::Fixed) => (),
                (BackoffStrategy::Exponential, BackoffStrategy::Exponential) => (),
                (BackoffStrategy::Linear, BackoffStrategy::Linear) => (),
                _ => panic!("Backoff strategy mismatch"),
            }
        }
    }

    #[test]
    fn test_retry_config_serialization() {
        let config = RetryConfig {
            max_retries: 10,
            backoff_strategy: BackoffStrategy::Fixed,
            initial_backoff_ms: 2000,
            max_backoff_ms: 120000,
            backoff_multiplier: 3.0,
        };

        let json = serde_json::to_string(&config).unwrap();
        let deserialized: RetryConfig = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.max_retries, config.max_retries);
        assert_eq!(deserialized.initial_backoff_ms, config.initial_backoff_ms);
        assert_eq!(deserialized.max_backoff_ms, config.max_backoff_ms);
        assert_eq!(deserialized.backoff_multiplier, config.backoff_multiplier);
    }

    #[test]
    fn test_retry_config_edge_cases() {
        // Zero retries
        let zero_retry = RetryConfig {
            max_retries: 0,
            backoff_strategy: BackoffStrategy::Exponential,
            initial_backoff_ms: 1000,
            max_backoff_ms: 60000,
            backoff_multiplier: 2.0,
        };
        assert_eq!(zero_retry.max_retries, 0);

        // Large values
        let large_values = RetryConfig {
            max_retries: u32::MAX,
            backoff_strategy: BackoffStrategy::Linear,
            initial_backoff_ms: u64::MAX,
            max_backoff_ms: u64::MAX,
            backoff_multiplier: f64::MAX,
        };
        assert_eq!(large_values.max_retries, u32::MAX);
        assert_eq!(large_values.initial_backoff_ms, u64::MAX);

        // Multiplier edge cases
        let multiplier_one = RetryConfig {
            max_retries: 3,
            backoff_strategy: BackoffStrategy::Exponential,
            initial_backoff_ms: 1000,
            max_backoff_ms: 10000,
            backoff_multiplier: 1.0,
        };
        assert_eq!(multiplier_one.backoff_multiplier, 1.0);
    }
}

#[cfg(test)]
mod resource_requirements_tests {
    use super::*;

    #[test]
    fn test_resource_requirements_all_fields() {
        let resources = ResourceRequirements {
            cpu_request: Some("100m".to_string()),
            cpu_limit: Some("1".to_string()),
            memory_request: Some("256Mi".to_string()),
            memory_limit: Some("1Gi".to_string()),
            ephemeral_storage_request: Some("1Gi".to_string()),
            ephemeral_storage_limit: Some("10Gi".to_string()),
        };

        assert_eq!(resources.cpu_request, Some("100m".to_string()));
        assert_eq!(resources.cpu_limit, Some("1".to_string()));
        assert_eq!(resources.memory_request, Some("256Mi".to_string()));
        assert_eq!(resources.memory_limit, Some("1Gi".to_string()));
        assert_eq!(resources.ephemeral_storage_request, Some("1Gi".to_string()));
        assert_eq!(resources.ephemeral_storage_limit, Some("10Gi".to_string()));
    }

    #[test]
    fn test_resource_requirements_partial_fields() {
        let resources = ResourceRequirements {
            cpu_request: Some("200m".to_string()),
            cpu_limit: None,
            memory_request: Some("512Mi".to_string()),
            memory_limit: None,
            ephemeral_storage_request: None,
            ephemeral_storage_limit: None,
        };

        assert_eq!(resources.cpu_request, Some("200m".to_string()));
        assert!(resources.cpu_limit.is_none());
        assert_eq!(resources.memory_request, Some("512Mi".to_string()));
        assert!(resources.memory_limit.is_none());
    }

    #[test]
    fn test_resource_requirements_all_none() {
        let resources = ResourceRequirements {
            cpu_request: None,
            cpu_limit: None,
            memory_request: None,
            memory_limit: None,
            ephemeral_storage_request: None,
            ephemeral_storage_limit: None,
        };

        assert!(resources.cpu_request.is_none());
        assert!(resources.cpu_limit.is_none());
        assert!(resources.memory_request.is_none());
        assert!(resources.memory_limit.is_none());
        assert!(resources.ephemeral_storage_request.is_none());
        assert!(resources.ephemeral_storage_limit.is_none());
    }

    #[test]
    fn test_resource_requirements_serialization() {
        let resources = ResourceRequirements {
            cpu_request: Some("500m".to_string()),
            cpu_limit: Some("2".to_string()),
            memory_request: Some("1Gi".to_string()),
            memory_limit: Some("4Gi".to_string()),
            ephemeral_storage_request: Some("5Gi".to_string()),
            ephemeral_storage_limit: Some("20Gi".to_string()),
        };

        let json = serde_json::to_string(&resources).unwrap();
        let deserialized: ResourceRequirements = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.cpu_request, resources.cpu_request);
        assert_eq!(deserialized.cpu_limit, resources.cpu_limit);
        assert_eq!(deserialized.memory_request, resources.memory_request);
        assert_eq!(deserialized.memory_limit, resources.memory_limit);
        assert_eq!(
            deserialized.ephemeral_storage_request,
            resources.ephemeral_storage_request
        );
        assert_eq!(
            deserialized.ephemeral_storage_limit,
            resources.ephemeral_storage_limit
        );
    }

    #[test]
    fn test_resource_requirements_various_formats() {
        // Test various Kubernetes resource formats
        let resources = ResourceRequirements {
            cpu_request: Some("0.5".to_string()),
            cpu_limit: Some("2000m".to_string()),
            memory_request: Some("128Mi".to_string()),
            memory_limit: Some("1073741824".to_string()), // 1Gi in bytes
            ephemeral_storage_request: Some("10Gi".to_string()),
            ephemeral_storage_limit: Some("100Gi".to_string()),
        };

        assert!(resources.cpu_request.is_some());
        assert!(resources.memory_limit.is_some());
    }
}

#[cfg(test)]
mod plugin_config_tests {
    use super::*;

    #[test]
    fn test_plugin_config_basic() {
        let plugin = PluginConfig {
            name: "data-processor".to_string(),
            version: "2.1.0".to_string(),
            config_map: None,
            pvc: None,
            config: HashMap::new(),
        };

        assert_eq!(plugin.name, "data-processor");
        assert_eq!(plugin.version, "2.1.0");
        assert!(plugin.config_map.is_none());
        assert!(plugin.pvc.is_none());
        assert!(plugin.config.is_empty());
    }

    #[test]
    fn test_plugin_config_with_config_map() {
        let plugin = PluginConfig {
            name: "custom-handler".to_string(),
            version: "1.0.0-beta".to_string(),
            config_map: Some("plugin-config-map".to_string()),
            pvc: None,
            config: HashMap::new(),
        };

        assert_eq!(plugin.config_map, Some("plugin-config-map".to_string()));
        assert!(plugin.pvc.is_none());
    }

    #[test]
    fn test_plugin_config_with_pvc() {
        let plugin = PluginConfig {
            name: "file-processor".to_string(),
            version: "3.0.0".to_string(),
            config_map: None,
            pvc: Some("plugin-storage-pvc".to_string()),
            config: HashMap::new(),
        };

        assert!(plugin.config_map.is_none());
        assert_eq!(plugin.pvc, Some("plugin-storage-pvc".to_string()));
    }

    #[test]
    fn test_plugin_config_with_custom_config() {
        let mut config = HashMap::new();
        config.insert(
            "endpoint".to_string(),
            "https://api.example.com".to_string(),
        );
        config.insert("timeout".to_string(), "30".to_string());
        config.insert("retry_enabled".to_string(), "true".to_string());

        let plugin = PluginConfig {
            name: "api-connector".to_string(),
            version: "1.2.3".to_string(),
            config_map: Some("api-config".to_string()),
            pvc: Some("api-cache".to_string()),
            config,
        };

        assert_eq!(plugin.config.len(), 3);
        assert_eq!(
            plugin.config.get("endpoint"),
            Some(&"https://api.example.com".to_string())
        );
        assert_eq!(plugin.config.get("timeout"), Some(&"30".to_string()));
        assert_eq!(
            plugin.config.get("retry_enabled"),
            Some(&"true".to_string())
        );
    }

    #[test]
    fn test_plugin_config_serialization() {
        let mut config = HashMap::new();
        config.insert("key1".to_string(), "value1".to_string());
        config.insert("key2".to_string(), "value2".to_string());

        let plugin = PluginConfig {
            name: "test-plugin".to_string(),
            version: "0.1.0".to_string(),
            config_map: Some("test-config-map".to_string()),
            pvc: Some("test-pvc".to_string()),
            config,
        };

        let json = serde_json::to_string(&plugin).unwrap();
        let deserialized: PluginConfig = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.name, plugin.name);
        assert_eq!(deserialized.version, plugin.version);
        assert_eq!(deserialized.config_map, plugin.config_map);
        assert_eq!(deserialized.pvc, plugin.pvc);
        assert_eq!(deserialized.config.len(), plugin.config.len());
    }

    #[test]
    fn test_plugin_config_empty_config() {
        let plugin = PluginConfig {
            name: "minimal-plugin".to_string(),
            version: "1.0.0".to_string(),
            config_map: None,
            pvc: None,
            config: HashMap::new(),
        };

        let json = serde_json::to_string(&plugin).unwrap();
        assert!(json.contains("\"config\":{}"));

        let deserialized: PluginConfig = serde_json::from_str(&json).unwrap();
        assert!(deserialized.config.is_empty());
    }
}

#[cfg(test)]
mod execution_metrics_tests {
    use super::*;

    #[test]
    fn test_execution_metrics_default() {
        let metrics = ExecutionMetrics::default();

        assert_eq!(metrics.queue_time_ms, 0);
        assert_eq!(metrics.execution_time_ms, 0);
        assert!(metrics.cpu_usage.is_none());
        assert!(metrics.memory_usage.is_none());
        assert_eq!(metrics.retry_count, 0);
        assert!(metrics.worker_node.is_none());
    }

    #[test]
    fn test_execution_metrics_with_all_fields() {
        let metrics = ExecutionMetrics {
            queue_time_ms: 1500,
            execution_time_ms: 3000,
            cpu_usage: Some(75.5),
            memory_usage: Some(1024 * 1024 * 512), // 512MB
            retry_count: 2,
            worker_node: Some("worker-node-01".to_string()),
        };

        assert_eq!(metrics.queue_time_ms, 1500);
        assert_eq!(metrics.execution_time_ms, 3000);
        assert_eq!(metrics.cpu_usage, Some(75.5));
        assert_eq!(metrics.memory_usage, Some(536870912));
        assert_eq!(metrics.retry_count, 2);
        assert_eq!(metrics.worker_node, Some("worker-node-01".to_string()));
    }

    #[test]
    fn test_execution_metrics_calculations() {
        let metrics = ExecutionMetrics {
            queue_time_ms: 500,
            execution_time_ms: 2500,
            cpu_usage: Some(50.0),
            memory_usage: Some(256 * 1024 * 1024), // 256MB
            retry_count: 1,
            worker_node: Some("worker-02".to_string()),
        };

        // Total time calculation
        let total_time_ms = metrics.queue_time_ms + metrics.execution_time_ms;
        assert_eq!(total_time_ms, 3000);

        // CPU usage validation
        assert!(metrics.cpu_usage.unwrap() >= 0.0 && metrics.cpu_usage.unwrap() <= 100.0);
    }

    #[test]
    fn test_execution_metrics_serialization() {
        let metrics = ExecutionMetrics {
            queue_time_ms: 1000,
            execution_time_ms: 5000,
            cpu_usage: Some(80.25),
            memory_usage: Some(1073741824), // 1GB
            retry_count: 3,
            worker_node: Some("worker-03".to_string()),
        };

        let json = serde_json::to_string(&metrics).unwrap();
        let deserialized: ExecutionMetrics = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.queue_time_ms, metrics.queue_time_ms);
        assert_eq!(deserialized.execution_time_ms, metrics.execution_time_ms);
        assert_eq!(deserialized.cpu_usage, metrics.cpu_usage);
        assert_eq!(deserialized.memory_usage, metrics.memory_usage);
        assert_eq!(deserialized.retry_count, metrics.retry_count);
        assert_eq!(deserialized.worker_node, metrics.worker_node);
    }

    #[test]
    fn test_execution_metrics_edge_cases() {
        // Zero values
        let zero_metrics = ExecutionMetrics {
            queue_time_ms: 0,
            execution_time_ms: 0,
            cpu_usage: Some(0.0),
            memory_usage: Some(0),
            retry_count: 0,
            worker_node: None,
        };
        assert_eq!(zero_metrics.queue_time_ms, 0);
        assert_eq!(zero_metrics.cpu_usage, Some(0.0));

        // Maximum values
        let max_metrics = ExecutionMetrics {
            queue_time_ms: u64::MAX,
            execution_time_ms: u64::MAX,
            cpu_usage: Some(100.0),
            memory_usage: Some(u64::MAX),
            retry_count: u32::MAX,
            worker_node: Some("worker-max".to_string()),
        };
        assert_eq!(max_metrics.queue_time_ms, u64::MAX);
        assert_eq!(max_metrics.retry_count, u32::MAX);
    }
}

#[cfg(test)]
mod task_result_tests {
    use super::*;

    #[test]
    fn test_task_result_success() {
        let task_id = Uuid::new_v4();
        let result = TaskResult {
            task_id,
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 2000,
            },
            result: Some(serde_json::json!({"output": "success", "count": 42})),
            error: None,
            metrics: ExecutionMetrics {
                queue_time_ms: 100,
                execution_time_ms: 2000,
                cpu_usage: Some(60.0),
                memory_usage: Some(128 * 1024 * 1024),
                retry_count: 0,
                worker_node: Some("worker-01".to_string()),
            },
        };

        assert_eq!(result.task_id, task_id);
        assert!(matches!(result.status, TaskStatus::Succeeded { .. }));
        assert!(result.result.is_some());
        assert!(result.error.is_none());
        assert_eq!(result.metrics.retry_count, 0);
    }

    #[test]
    fn test_task_result_failure() {
        let task_id = Uuid::new_v4();
        let error_msg = "Database connection failed";
        let result = TaskResult {
            task_id,
            status: TaskStatus::Failed {
                completed_at: Utc::now(),
                error: error_msg.to_string(),
                retries: 3,
            },
            result: None,
            error: Some(error_msg.to_string()),
            metrics: ExecutionMetrics {
                queue_time_ms: 500,
                execution_time_ms: 1500,
                cpu_usage: Some(30.0),
                memory_usage: Some(64 * 1024 * 1024),
                retry_count: 3,
                worker_node: Some("worker-02".to_string()),
            },
        };

        assert_eq!(result.task_id, task_id);
        assert!(matches!(result.status, TaskStatus::Failed { .. }));
        assert!(result.result.is_none());
        assert_eq!(result.error, Some(error_msg.to_string()));
        assert_eq!(result.metrics.retry_count, 3);
    }

    #[test]
    fn test_task_result_serialization() {
        let task_id = Uuid::new_v4();
        let result = TaskResult {
            task_id,
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 1000,
            },
            result: Some(serde_json::json!({"data": [1, 2, 3]})),
            error: None,
            metrics: ExecutionMetrics::default(),
        };

        let json = serde_json::to_string(&result).unwrap();
        let deserialized: TaskResult = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.task_id, result.task_id);
        assert_eq!(deserialized.result, result.result);
        assert_eq!(deserialized.error, result.error);
    }

    #[test]
    fn test_task_result_with_complex_result_data() {
        let task_id = Uuid::new_v4();
        let complex_result = serde_json::json!({
            "metadata": {
                "version": "1.0",
                "timestamp": "2024-01-01T00:00:00Z",
            },
            "data": {
                "items": [
                    {"id": 1, "name": "Item 1"},
                    {"id": 2, "name": "Item 2"},
                ],
                "total": 2,
                "nested": {
                    "deeply": {
                        "nested": {
                            "value": true
                        }
                    }
                }
            }
        });

        let result = TaskResult {
            task_id,
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 3000,
            },
            result: Some(complex_result.clone()),
            error: None,
            metrics: ExecutionMetrics::default(),
        };

        let json = serde_json::to_string(&result).unwrap();
        let deserialized: TaskResult = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.result, Some(complex_result));
    }
}

#[cfg(test)]
mod integration_tests {
    use super::*;

    #[test]
    fn test_complete_task_lifecycle() {
        let task_id = Uuid::new_v4();
        let mut labels = HashMap::new();
        labels.insert("priority".to_string(), "high".to_string());

        let mut plugin_config = HashMap::new();
        plugin_config.insert(
            "endpoint".to_string(),
            "https://api.example.com".to_string(),
        );

        // Create task with full metadata
        let task_info = TaskInfo {
            id: task_id,
            method: "process_batch".to_string(),
            args: vec![serde_json::json!({"batch_id": 123})],
            dependencies: vec![],
            priority: 90,
            metadata: TaskMetadata {
                user: Some("admin".to_string()),
                source: Some("batch-processor".to_string()),
                labels,
                annotations: HashMap::new(),
                retry_config: Some(RetryConfig {
                    max_retries: 5,
                    backoff_strategy: BackoffStrategy::Exponential,
                    initial_backoff_ms: 2000,
                    max_backoff_ms: 120000,
                    backoff_multiplier: 2.5,
                }),
                resources: Some(ResourceRequirements {
                    cpu_request: Some("500m".to_string()),
                    cpu_limit: Some("2".to_string()),
                    memory_request: Some("1Gi".to_string()),
                    memory_limit: Some("4Gi".to_string()),
                    ephemeral_storage_request: None,
                    ephemeral_storage_limit: None,
                }),
                timeout_seconds: Some(600),
                plugin: Some(PluginConfig {
                    name: "batch-plugin".to_string(),
                    version: "2.0.0".to_string(),
                    config_map: Some("batch-config".to_string()),
                    pvc: None,
                    config: plugin_config,
                }),
            },
            status: TaskStatus::Pending,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        // Serialize and deserialize
        let json = serde_json::to_string_pretty(&task_info).unwrap();
        let deserialized: TaskInfo = serde_json::from_str(&json).unwrap();

        // Verify all fields
        assert_eq!(deserialized.id, task_id);
        assert_eq!(deserialized.method, "process_batch");
        assert_eq!(deserialized.priority, 90);
        assert_eq!(deserialized.metadata.user, Some("admin".to_string()));
        assert_eq!(deserialized.metadata.timeout_seconds, Some(600));
        assert!(deserialized.metadata.retry_config.is_some());
        assert!(deserialized.metadata.resources.is_some());
        assert!(deserialized.metadata.plugin.is_some());

        // Create result for the task
        let task_result = TaskResult {
            task_id,
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 45000,
            },
            result: Some(serde_json::json!({"processed_items": 1000, "success": true})),
            error: None,
            metrics: ExecutionMetrics {
                queue_time_ms: 5000,
                execution_time_ms: 45000,
                cpu_usage: Some(85.5),
                memory_usage: Some(3 * 1024 * 1024 * 1024), // 3GB
                retry_count: 1,
                worker_node: Some("worker-gpu-01".to_string()),
            },
        };

        let result_json = serde_json::to_string_pretty(&task_result).unwrap();
        let deserialized_result: TaskResult = serde_json::from_str(&result_json).unwrap();

        assert_eq!(deserialized_result.task_id, task_id);
        assert!(matches!(
            deserialized_result.status,
            TaskStatus::Succeeded { .. }
        ));
        assert_eq!(deserialized_result.metrics.execution_time_ms, 45000);
    }
}

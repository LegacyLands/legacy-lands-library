//! Unit tests for core functionality

mod task_status_tests {
    use task_common::models::{TaskStatus, TaskInfo, TaskMetadata};
    use chrono::Utc;
    use uuid::Uuid;

    #[test]
    fn test_task_status_default() {
        let status = TaskStatus::default();
        assert!(matches!(status, TaskStatus::Pending));
    }

    #[test]
    fn test_task_status_equality() {
        let status1 = TaskStatus::Pending;
        let status2 = TaskStatus::Pending;
        assert_eq!(status1, status2);
        
        let running1 = TaskStatus::Running {
            worker_id: "worker-1".to_string(),
            started_at: Utc::now(),
        };
        
        let running2 = TaskStatus::Running {
            worker_id: "worker-1".to_string(),
            started_at: Utc::now(),
        };
        
        // Different timestamps make them not equal
        assert_ne!(running1, running2);
    }

    #[test]
    fn test_task_creation_with_defaults() {
        let now = Utc::now();
        let task = TaskInfo {
            id: Uuid::new_v4(),
            method: "test_method".to_string(),
            args: vec![serde_json::json!({"test": true})],
            dependencies: vec![],
            priority: 5,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Pending,
            created_at: now,
            updated_at: now,
        };
        
        assert_eq!(task.method, "test_method");
        assert_eq!(task.priority, 5);
        assert!(task.dependencies.is_empty());
        assert!(matches!(task.status, TaskStatus::Pending));
    }
}

mod event_tests {
    use task_common::events::TaskEvent;
    use task_common::models::{TaskInfo, TaskStatus, TaskMetadata, TaskResult, ExecutionMetrics};
    use chrono::Utc;
    use uuid::Uuid;

    #[test]
    fn test_task_event_creation() {
        let task = TaskInfo {
            id: Uuid::new_v4(),
            method: "test".to_string(),
            args: vec![],
            dependencies: vec![],
            priority: 5,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Pending,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        
        let event = TaskEvent::Created {
            task: Box::new(task.clone()),
            source: "test-source".to_string(),
        };
        
        match event {
            TaskEvent::Created { task: boxed_task, source } => {
                assert_eq!(boxed_task.id, task.id);
                assert_eq!(source, "test-source");
            }
            _ => panic!("Expected Created event"),
        }
    }

    #[test]
    fn test_task_event_variants() {
        let task_id = Uuid::new_v4();
        let now = Utc::now();
        
        // Test Started event
        let started_event = TaskEvent::Started {
            task_id,
            worker_id: "worker-1".to_string(),
            timestamp: now,
        };
        
        match started_event {
            TaskEvent::Started { task_id: id, worker_id, .. } => {
                assert_eq!(id, task_id);
                assert_eq!(worker_id, "worker-1");
            }
            _ => panic!("Expected Started event"),
        }
        
        // Test Completed event
        let result = TaskResult {
            task_id,
            status: TaskStatus::Succeeded {
                completed_at: now,
                duration_ms: 1000,
            },
            result: Some(serde_json::json!({"status": "ok"})),
            error: None,
            metrics: ExecutionMetrics::default(),
        };
        
        let completed_event = TaskEvent::Completed {
            result: result.clone(),
            timestamp: now,
        };
        
        match completed_event {
            TaskEvent::Completed { result: evt_result, .. } => {
                assert_eq!(evt_result.task_id, task_id);
            }
            _ => panic!("Expected Completed event"),
        }
        
        // Test Failed event
        let failed_event = TaskEvent::Failed {
            task_id,
            error: "Test error".to_string(),
            retry_count: 2,
            will_retry: true,
            timestamp: now,
        };
        
        match failed_event {
            TaskEvent::Failed { task_id: id, error, retry_count, will_retry, .. } => {
                assert_eq!(id, task_id);
                assert_eq!(error, "Test error");
                assert_eq!(retry_count, 2);
                assert!(will_retry);
            }
            _ => panic!("Expected Failed event"),
        }
    }
}

mod crd_tests {
    use task_common::crd::{Task, TaskCrdSpec, TaskCrdStatus, TaskPhase, TaskRetryPolicy, TaskResourceRequirements};
    use k8s_openapi::apimachinery::pkg::apis::meta::v1::ObjectMeta;
    use std::collections::HashMap;

    #[test]
    fn test_crd_task_creation() {
        let task = Task {
            metadata: ObjectMeta {
                name: Some("test-task".to_string()),
                namespace: Some("default".to_string()),
                ..Default::default()
            },
            spec: TaskCrdSpec {
                method: "test.method".to_string(),
                args: vec![serde_json::json!({"test": true})],
                priority: 5,
                dependencies: vec![],
                retry_policy: TaskRetryPolicy::default(),
                resources: TaskResourceRequirements::default(),
                plugin: None,
                timeout_seconds: 300,
                node_selector: HashMap::new(),
                metadata: HashMap::new(),
            },
            status: None,
        };
        
        assert_eq!(task.metadata.name, Some("test-task".to_string()));
        assert_eq!(task.spec.method, "test.method");
        assert_eq!(task.spec.priority, 5);
        assert_eq!(task.spec.timeout_seconds, 300);
    }

    #[test]
    fn test_crd_status_default() {
        let status = TaskCrdStatus::default();
        assert_eq!(status.phase, TaskPhase::Pending);
        assert_eq!(status.retry_count, 0);
        assert!(status.error.is_none());
        assert!(status.result.is_none());
        assert!(status.message.is_none());
        assert!(status.start_time.is_none());
        assert!(status.completion_time.is_none());
    }
    
    #[test]
    fn test_task_phase_variants() {
        let phases = vec![
            TaskPhase::Pending,
            TaskPhase::Queued,
            TaskPhase::Running,
            TaskPhase::Succeeded,
            TaskPhase::Failed,
            TaskPhase::Cancelled,
        ];
        
        // Test that default is Pending
        assert_eq!(TaskPhase::default(), TaskPhase::Pending);
        
        // Test equality
        for phase in phases {
            assert_eq!(phase, phase.clone());
        }
    }
}

mod plugin_tests {
    use task_plugins::k8s::PluginVersionManager;

    #[test]
    fn test_plugin_version_manager() {
        let mut manager = PluginVersionManager::new();
        
        // Register versions
        manager.register_version("test-plugin", "1.0.0");
        manager.register_version("test-plugin", "1.1.0");
        manager.register_version("test-plugin", "2.0.0");
        
        // Test latest version
        assert_eq!(manager.get_latest("test-plugin"), Some("2.0.0"));
        
        // Test compatibility
        assert!(manager.is_compatible("test-plugin", "1.1.0"));
        assert!(!manager.is_compatible("test-plugin", "3.0.0"));
        
        // Test non-existent plugin
        assert_eq!(manager.get_latest("non-existent"), None);
    }

    #[test]
    fn test_plugin_loader_creation() {
        use task_plugins::loader::PluginLoader;
        
        let loader = PluginLoader::new();
        // Just test that it creates successfully
        // In real tests, we would load actual plugins
        drop(loader);
    }
}

mod storage_tests {
    use task_storage::StorageError;

    #[test]
    fn test_storage_error_not_found() {
        let error = StorageError::NotFound;
        match error {
            StorageError::NotFound => {
                // Test error display
                assert_eq!(error.to_string(), "Not found");
            }
            _ => panic!("Expected NotFound error"),
        }
    }

    #[test]
    fn test_storage_error_database() {
        let error = StorageError::Database("Connection failed".to_string());
        match &error {
            StorageError::Database(msg) => {
                assert_eq!(msg, "Connection failed");
                assert!(error.to_string().contains("Database error"));
            }
            _ => panic!("Expected Database error"),
        }
    }

    #[test]
    fn test_storage_error_already_exists() {
        let error = StorageError::AlreadyExists;
        assert_eq!(error.to_string(), "Already exists");
    }
}

mod executor_tests {
    use task_common::models::ExecutionMetrics;

    #[test]
    fn test_execution_metrics_default() {
        let metrics = ExecutionMetrics::default();
        assert_eq!(metrics.queue_time_ms, 0);
        assert_eq!(metrics.execution_time_ms, 0);
        assert_eq!(metrics.retry_count, 0);
        assert!(metrics.cpu_usage.is_none());
        assert!(metrics.memory_usage.is_none());
        assert!(metrics.worker_node.is_none());
    }

    #[test]
    fn test_execution_metrics_with_values() {
        let metrics = ExecutionMetrics {
            queue_time_ms: 100,
            execution_time_ms: 500,
            cpu_usage: Some(25.5),
            memory_usage: Some(1024 * 1024 * 100), // 100MB
            retry_count: 2,
            worker_node: Some("worker-1".to_string()),
        };
        
        assert_eq!(metrics.queue_time_ms, 100);
        assert_eq!(metrics.execution_time_ms, 500);
        assert_eq!(metrics.cpu_usage, Some(25.5));
        assert_eq!(metrics.memory_usage, Some(104857600));
        assert_eq!(metrics.retry_count, 2);
        assert_eq!(metrics.worker_node, Some("worker-1".to_string()));
    }
}
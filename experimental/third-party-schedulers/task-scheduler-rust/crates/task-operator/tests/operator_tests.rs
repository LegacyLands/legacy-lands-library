use k8s_openapi::api::core::v1::{ConfigMap, PersistentVolumeClaim, Pod};
use k8s_openapi::api::batch::v1::Job;
use kube::{
    api::{Api, ListParams, Patch, PatchParams, PostParams},
    runtime::controller::{Action, Controller},
    Client, CustomResource, Resource, ResourceExt,
};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::time::{sleep, Duration};

// Mock CRD definitions for testing
#[derive(CustomResource, Debug, Clone, Deserialize, Serialize, JsonSchema)]
#[kube(
    group = "taskscheduler.io",
    version = "v1",
    kind = "Task",
    shortname = "tsk",
    status = "TaskStatus",
    namespaced
)]
pub struct TaskSpec {
    pub method: String,
    pub args: Vec<TaskArg>,
    pub dependencies: Option<Vec<String>>,
    pub priority: Option<i32>,
    pub queue_name: Option<String>,
    pub retry_config: Option<RetryConfig>,
    pub resources: Option<ResourceRequirements>,
    pub timeout: Option<u64>,
    pub metadata: Option<std::collections::HashMap<String, String>>,
    pub plugin: Option<PluginConfig>,
}

#[derive(Debug, Clone, Deserialize, Serialize, JsonSchema)]
pub struct TaskArg {
    pub r#type: String,
    pub value: String,
}

#[derive(Debug, Clone, Deserialize, Serialize, JsonSchema)]
pub struct RetryConfig {
    pub max_retries: u32,
    pub backoff_strategy: String,
    pub initial_backoff_ms: u64,
    pub max_backoff_ms: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize, JsonSchema)]
pub struct ResourceRequirements {
    pub cpu_request: Option<String>,
    pub cpu_limit: Option<String>,
    pub memory_request: Option<String>,
    pub memory_limit: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize, JsonSchema)]
pub struct PluginConfig {
    pub name: String,
    pub version: String,
    pub config_map: Option<String>,
    pub pvc: Option<String>,
    pub config: Option<std::collections::HashMap<String, String>>,
}

#[derive(Debug, Clone, Deserialize, Serialize, JsonSchema)]
pub struct TaskStatus {
    pub phase: String,
    pub task_id: Option<String>,
    pub worker_id: Option<String>,
    pub start_time: Option<String>,
    pub completion_time: Option<String>,
    pub result: Option<String>,
    pub error: Option<String>,
    pub retries: Option<u32>,
    pub last_updated: Option<String>,
}

// Mock controller context
struct Context {
    client: Client,
    task_manager_endpoint: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use kube::api::ObjectMeta;

    // Helper to create a test task
    fn create_test_task(name: &str) -> Task {
        Task {
            metadata: ObjectMeta {
                name: Some(name.to_string()),
                namespace: Some("default".to_string()),
                ..Default::default()
            },
            spec: TaskSpec {
                method: "test.method".to_string(),
                args: vec![TaskArg {
                    r#type: "string".to_string(),
                    value: "test".to_string(),
                }],
                dependencies: None,
                priority: Some(5),
                queue_name: Some("default".to_string()),
                retry_config: Some(RetryConfig {
                    max_retries: 3,
                    backoff_strategy: "exponential".to_string(),
                    initial_backoff_ms: 1000,
                    max_backoff_ms: 60000,
                }),
                resources: Some(ResourceRequirements {
                    cpu_request: Some("100m".to_string()),
                    cpu_limit: Some("500m".to_string()),
                    memory_request: Some("128Mi".to_string()),
                    memory_limit: Some("512Mi".to_string()),
                }),
                timeout: Some(300),
                metadata: None,
                plugin: None,
            },
            status: None,
        }
    }

    #[test]
    fn test_task_spec_serialization() {
        let task = create_test_task("test-task");
        
        // Serialize to JSON
        let json = serde_json::to_string_pretty(&task).unwrap();
        println!("Serialized task: {}", json);
        
        // Deserialize back
        let deserialized: Task = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized.metadata.name, task.metadata.name);
        assert_eq!(deserialized.spec.method, task.spec.method);
    }

    #[test]
    fn test_task_with_dependencies() {
        let mut task = create_test_task("dependent-task");
        task.spec.dependencies = Some(vec![
            "task-1".to_string(),
            "task-2".to_string(),
        ]);
        
        assert_eq!(task.spec.dependencies.as_ref().unwrap().len(), 2);
    }

    #[test]
    fn test_task_with_plugin_config() {
        let mut task = create_test_task("plugin-task");
        task.spec.plugin = Some(PluginConfig {
            name: "custom-processor".to_string(),
            version: "1.0.0".to_string(),
            config_map: Some("plugin-config".to_string()),
            pvc: None,
            config: Some(std::collections::HashMap::from([
                ("param1".to_string(), "value1".to_string()),
                ("param2".to_string(), "value2".to_string()),
            ])),
        });
        
        let plugin = task.spec.plugin.as_ref().unwrap();
        assert_eq!(plugin.name, "custom-processor");
        assert_eq!(plugin.config.as_ref().unwrap().len(), 2);
    }

    #[test]
    fn test_task_status_transitions() {
        let mut status = TaskStatus {
            phase: "Pending".to_string(),
            task_id: None,
            worker_id: None,
            start_time: None,
            completion_time: None,
            result: None,
            error: None,
            retries: None,
            last_updated: Some(chrono::Utc::now().to_rfc3339()),
        };
        
        // Transition to Queued
        status.phase = "Queued".to_string();
        status.task_id = Some("test-task-123".to_string());
        assert_eq!(status.phase, "Queued");
        
        // Transition to Running
        status.phase = "Running".to_string();
        status.worker_id = Some("worker-1".to_string());
        status.start_time = Some(chrono::Utc::now().to_rfc3339());
        assert_eq!(status.phase, "Running");
        
        // Transition to Succeeded
        status.phase = "Succeeded".to_string();
        status.completion_time = Some(chrono::Utc::now().to_rfc3339());
        status.result = Some("{\"output\": \"success\"}".to_string());
        assert_eq!(status.phase, "Succeeded");
    }

    #[test]
    fn test_resource_requirements_parsing() {
        let resources = ResourceRequirements {
            cpu_request: Some("100m".to_string()),
            cpu_limit: Some("2".to_string()),
            memory_request: Some("256Mi".to_string()),
            memory_limit: Some("1Gi".to_string()),
        };
        
        // Verify CPU values
        assert_eq!(resources.cpu_request.unwrap(), "100m");
        assert_eq!(resources.cpu_limit.unwrap(), "2");
        
        // Verify memory values
        assert_eq!(resources.memory_request.unwrap(), "256Mi");
        assert_eq!(resources.memory_limit.unwrap(), "1Gi");
    }

    #[test]
    fn test_retry_config_validation() {
        let retry_config = RetryConfig {
            max_retries: 5,
            backoff_strategy: "exponential".to_string(),
            initial_backoff_ms: 500,
            max_backoff_ms: 300000,
        };
        
        assert_eq!(retry_config.max_retries, 5);
        assert_eq!(retry_config.backoff_strategy, "exponential");
        assert!(retry_config.initial_backoff_ms < retry_config.max_backoff_ms);
    }
}

// Integration tests (require Kubernetes cluster)
#[cfg(all(test, feature = "integration"))]
mod integration_tests {
    use super::*;
    use futures::StreamExt;

    #[tokio::test]
    async fn test_create_task_in_cluster() {
        // Skip if not running in CI with k8s
        if std::env::var("KUBERNETES_SERVICE_HOST").is_err() {
            println!("Skipping integration test - not running in Kubernetes");
            return;
        }

        let client = Client::try_default().await.unwrap();
        let tasks: Api<Task> = Api::namespaced(client, "default");
        
        let task = create_test_task("integration-test-task");
        
        // Create the task
        let created = tasks
            .create(&PostParams::default(), &task)
            .await
            .unwrap();
        
        assert_eq!(created.metadata.name, task.metadata.name);
        
        // Clean up
        tasks
            .delete("integration-test-task", &Default::default())
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn test_watch_task_changes() {
        if std::env::var("KUBERNETES_SERVICE_HOST").is_err() {
            println!("Skipping integration test - not running in Kubernetes");
            return;
        }

        let client = Client::try_default().await.unwrap();
        let tasks: Api<Task> = Api::namespaced(client, "default");
        
        // Create a task
        let task = create_test_task("watch-test-task");
        let _created = tasks
            .create(&PostParams::default(), &task)
            .await
            .unwrap();
        
        // Watch for changes
        let lp = ListParams::default()
            .fields(&format!("metadata.name={}", "watch-test-task"))
            .timeout(10);
        
        let mut stream = tasks.watch(&lp, "0").await.unwrap().boxed();
        
        // Update the task
        let patch = serde_json::json!({
            "status": {
                "phase": "Running",
                "worker_id": "test-worker"
            }
        });
        
        tasks
            .patch_status(
                "watch-test-task",
                &PatchParams::default(),
                &Patch::Merge(patch),
            )
            .await
            .unwrap();
        
        // Verify we receive the update
        while let Some(event) = stream.next().await {
            if let Ok(event) = event {
                println!("Received event: {:?}", event);
                break;
            }
        }
        
        // Clean up
        tasks
            .delete("watch-test-task", &Default::default())
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn test_controller_reconciliation() {
        if std::env::var("KUBERNETES_SERVICE_HOST").is_err() {
            println!("Skipping integration test - not running in Kubernetes");
            return;
        }

        let client = Client::try_default().await.unwrap();
        
        // Mock reconcile function
        async fn reconcile(task: Arc<Task>, ctx: Arc<Context>) -> Result<Action, kube::Error> {
            let name = task.metadata.name.as_ref().unwrap();
            println!("Reconciling task: {}", name);
            
            // Check task status
            match task.status.as_ref().map(|s| s.phase.as_str()) {
                None | Some("Pending") => {
                    // Submit to task manager
                    println!("Submitting task {} to task manager", name);
                    // In real implementation, would call gRPC API
                    Ok(Action::requeue(Duration::from_secs(5)))
                }
                Some("Running") => {
                    // Check task progress
                    println!("Checking progress of task {}", name);
                    Ok(Action::requeue(Duration::from_secs(10)))
                }
                Some("Succeeded") | Some("Failed") => {
                    // Task completed
                    println!("Task {} completed", name);
                    Ok(Action::await_change())
                }
                _ => Ok(Action::await_change()),
            }
        }
        
        // Error handler
        fn error_policy(_task: Arc<Task>, _error: &kube::Error, _ctx: Arc<Context>) -> Action {
            Action::requeue(Duration::from_secs(30))
        }
        
        let context = Arc::new(Context {
            client: client.clone(),
            task_manager_endpoint: "localhost:50051".to_string(),
        });
        
        // Create controller
        let tasks: Api<Task> = Api::all(client.clone());
        let _controller = Controller::new(tasks, ListParams::default())
            .run(reconcile, error_policy, context)
            .for_each(|res| async move {
                match res {
                    Ok(o) => println!("Reconciled: {:?}", o),
                    Err(e) => println!("Reconcile error: {:?}", e),
                }
            });
        
        // Run for a short time in test
        sleep(Duration::from_secs(5)).await;
    }
}
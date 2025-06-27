use task_common::crd::{Task, TaskCrdSpec, TaskRetryPolicy};
use kube::{
    api::{Api, PostParams, ListParams, DeleteParams},
    Client, ResourceExt,
};
use k8s_openapi::api::batch::v1::Job;
use std::collections::HashMap;
use tokio::time::{timeout, Duration};

/// Test Task CRD creation and reconciliation
#[tokio::test]
async fn test_task_crd_lifecycle() -> Result<(), Box<dyn std::error::Error>> {
    // Create Kubernetes client
    let client = Client::try_default().await?;
    let namespace = std::env::var("TEST_NAMESPACE").unwrap_or_else(|_| "default".to_string());
    
    let tasks: Api<Task> = Api::namespaced(client.clone(), &namespace);
    let jobs: Api<Job> = Api::namespaced(client.clone(), &namespace);
    
    // Create a test task
    let task_name = format!("test-task-{}", uuid::Uuid::new_v4());
    let task = Task::new(&task_name, TaskCrdSpec {
        method: "test.k8s".to_string(),
        args: vec![serde_json::json!({"test": true})],
        dependencies: vec![],
        priority: 50,
        retry_policy: TaskRetryPolicy::default(),
        resources: Default::default(),
        timeout_seconds: 60,
        node_selector: HashMap::new(),
        plugin: None,
        metadata: HashMap::new(),
    });
    
    // Create the task
    let created = tasks.create(&PostParams::default(), &task).await?;
    assert_eq!(created.metadata.name, Some(task_name.clone()));
    
    // Wait for reconciliation (Job creation)
    let job_created = timeout(Duration::from_secs(30), async {
        loop {
            let job_list = jobs.list(&ListParams::default()).await?;
            if job_list.items.iter().any(|j| {
                j.metadata.labels.as_ref()
                    .and_then(|labels| labels.get("task.taskscheduler.legacylands.io/name"))
                    .map(|name| name == &task_name)
                    .unwrap_or(false)
            }) {
                return Ok::<_, Box<dyn std::error::Error>>(());
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
    }).await;
    
    assert!(job_created.is_ok(), "Job should be created for the task");
    
    // Clean up
    tasks.delete(&task_name, &DeleteParams::default()).await?;
    
    Ok(())
}

/// Test Task with dependencies
#[tokio::test]
async fn test_task_dependencies_k8s() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::try_default().await?;
    let namespace = std::env::var("TEST_NAMESPACE").unwrap_or_else(|_| "default".to_string());
    let tasks: Api<Task> = Api::namespaced(client, &namespace);
    
    // Create parent task
    let parent_name = format!("parent-task-{}", uuid::Uuid::new_v4());
    let parent_task = Task::new(&parent_name, TaskCrdSpec {
        method: "test.parent".to_string(),
        args: vec![serde_json::json!({"id": 1})],
        dependencies: vec![],
        priority: 50,
        retry_policy: TaskRetryPolicy::default(),
        resources: Default::default(),
        timeout_seconds: 30,
        node_selector: HashMap::new(),
        plugin: None,
        metadata: HashMap::new(),
    });
    
    let _parent = tasks.create(&PostParams::default(), &parent_task).await?;
    
    // Create child task with dependency
    let child_name = format!("child-task-{}", uuid::Uuid::new_v4());
    let child_task = Task::new(&child_name, TaskCrdSpec {
        method: "test.child".to_string(),
        args: vec![serde_json::json!({"parent": &parent_name})],
        dependencies: vec![parent_name.clone()],
        priority: 50,
        retry_policy: TaskRetryPolicy::default(),
        resources: Default::default(),
        timeout_seconds: 30,
        node_selector: HashMap::new(),
        plugin: None,
        metadata: HashMap::new(),
    });
    
    let _child = tasks.create(&PostParams::default(), &child_task).await?;
    
    // Wait and verify child doesn't start until parent completes
    let dependency_check = timeout(Duration::from_secs(60), async {
        loop {
            let parent = tasks.get(&parent_name).await?;
            let child = tasks.get(&child_name).await?;
            
            if let (Some(parent_status), Some(child_status)) = (&parent.status, &child.status) {
                // Check that child remains pending while parent is running
                if !parent_status.phase.is_terminal() && child_status.phase.is_pending() {
                    return Ok::<_, Box<dyn std::error::Error>>(());
                }
            }
            
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
    }).await;
    
    assert!(dependency_check.is_ok(), "Dependency should be respected");
    
    // Clean up
    let _ = tasks.delete(&parent_name, &DeleteParams::default()).await;
    let _ = tasks.delete(&child_name, &DeleteParams::default()).await;
    
    Ok(())
}

/// Test Task with resource requirements
#[tokio::test]
async fn test_task_resources() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::try_default().await?;
    let namespace = std::env::var("TEST_NAMESPACE").unwrap_or_else(|_| "default".to_string());
    
    let tasks: Api<Task> = Api::namespaced(client.clone(), &namespace);
    let jobs: Api<Job> = Api::namespaced(client, &namespace);
    
    // Create task with specific resource requirements
    let task_name = format!("resource-task-{}", uuid::Uuid::new_v4());
    let mut spec = TaskCrdSpec {
        method: "test.resource_intensive".to_string(),
        args: vec![serde_json::json!({"compute": "heavy"})],
        dependencies: vec![],
        priority: 80,
        retry_policy: TaskRetryPolicy::default(),
        resources: Default::default(),
        timeout_seconds: 120,
        node_selector: HashMap::new(),
        plugin: None,
        metadata: HashMap::new(),
    };
    
    // Set resource requirements
    spec.resources.cpu_request = Some("500m".to_string());
    spec.resources.cpu_limit = Some("2".to_string());
    spec.resources.memory_request = Some("1Gi".to_string());
    spec.resources.memory_limit = Some("4Gi".to_string());
    
    let task = Task::new(&task_name, spec);
    let _created = tasks.create(&PostParams::default(), &task).await?;
    
    // Verify Job has correct resource settings
    let job_check = timeout(Duration::from_secs(30), async {
        loop {
            let job_list = jobs.list(&ListParams::default()).await?;
            for job in job_list.items {
                if job.metadata.labels.as_ref()
                    .and_then(|labels| labels.get("task.taskscheduler.legacylands.io/name"))
                    .map(|name| name == &task_name)
                    .unwrap_or(false) 
                {
                    // Check resource requirements in pod template
                    if let Some(spec) = &job.spec {
                        if let Some(template) = &spec.template.spec {
                            if let Some(container) = template.containers.first() {
                                if let Some(resources) = &container.resources {
                                    // Verify resources are set correctly
                                    assert!(resources.requests.is_some());
                                    assert!(resources.limits.is_some());
                                    return Ok::<_, Box<dyn std::error::Error>>(());
                                }
                            }
                        }
                    }
                }
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
    }).await;
    
    assert!(job_check.is_ok(), "Job should have resource requirements");
    
    // Clean up
    tasks.delete(&task_name, &DeleteParams::default()).await?;
    
    Ok(())
}
use task_common::{
    queue::QueueManager,
    models::{TaskInfo, TaskMetadata, TaskStatus},
    error::TaskResult,
};
use std::time::Duration;
use tokio::time::timeout;
use uuid::Uuid;

/// Test basic task submission and execution workflow
#[tokio::test]
async fn test_basic_task_workflow() -> TaskResult<()> {
    // Initialize queue manager
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = QueueManager::new(&nats_url).await?;
    
    // Create a test task
    let task_id = Uuid::new_v4();
    let task = TaskInfo {
        id: task_id,
        method: "test.echo".to_string(),
        args: vec![serde_json::json!({"message": "Hello, World!"})],
        metadata: TaskMetadata::default(),
    };
    
    // Submit task
    queue.submit_task(task.clone()).await?;
    
    // Wait for task to be processed (with timeout)
    let result = timeout(Duration::from_secs(30), async {
        loop {
            if let Ok(status) = queue.get_task_status(&task_id).await {
                match status {
                    TaskStatus::Completed { .. } => return Ok(()),
                    TaskStatus::Failed { error, .. } => return Err(error),
                    _ => tokio::time::sleep(Duration::from_millis(100)).await,
                }
            }
        }
    }).await;
    
    assert!(result.is_ok(), "Task should complete within timeout");
    Ok(())
}

/// Test task with dependencies
#[tokio::test]
async fn test_task_dependencies() -> TaskResult<()> {
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = QueueManager::new(&nats_url).await?;
    
    // Create parent task
    let parent_id = Uuid::new_v4();
    let parent_task = TaskInfo {
        id: parent_id,
        method: "test.generate".to_string(),
        args: vec![serde_json::json!({"count": 5})],
        metadata: TaskMetadata::default(),
    };
    
    // Create dependent task
    let child_id = Uuid::new_v4();
    let mut child_metadata = TaskMetadata::default();
    child_metadata.dependencies = vec![parent_id];
    
    let child_task = TaskInfo {
        id: child_id,
        method: "test.process".to_string(),
        args: vec![serde_json::json!({"parent_id": parent_id})],
        metadata: child_metadata,
    };
    
    // Submit both tasks
    queue.submit_task(parent_task).await?;
    queue.submit_task(child_task).await?;
    
    // Verify child waits for parent
    let result = timeout(Duration::from_secs(60), async {
        loop {
            let parent_status = queue.get_task_status(&parent_id).await?;
            let child_status = queue.get_task_status(&child_id).await?;
            
            match (&parent_status, &child_status) {
                (TaskStatus::Completed { .. }, TaskStatus::Completed { .. }) => {
                    return Ok(());
                }
                (TaskStatus::Failed { .. }, _) | (_, TaskStatus::Failed { .. }) => {
                    return Err("Task failed".into());
                }
                _ => tokio::time::sleep(Duration::from_millis(500)).await,
            }
        }
    }).await;
    
    assert!(result.is_ok(), "Tasks should complete successfully");
    Ok(())
}

/// Test task cancellation
#[tokio::test]
async fn test_task_cancellation() -> TaskResult<()> {
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = QueueManager::new(&nats_url).await?;
    
    // Create a long-running task
    let task_id = Uuid::new_v4();
    let task = TaskInfo {
        id: task_id,
        method: "test.sleep".to_string(),
        args: vec![serde_json::json!({"duration_secs": 30})],
        metadata: TaskMetadata::default(),
    };
    
    // Submit task
    queue.submit_task(task).await?;
    
    // Wait for task to start
    tokio::time::sleep(Duration::from_secs(2)).await;
    
    // Cancel task
    queue.cancel_task(&task_id).await?;
    
    // Verify task is cancelled
    let result = timeout(Duration::from_secs(10), async {
        loop {
            if let Ok(status) = queue.get_task_status(&task_id).await {
                match status {
                    TaskStatus::Cancelled { .. } => return Ok(()),
                    _ => tokio::time::sleep(Duration::from_millis(100)).await,
                }
            }
        }
    }).await;
    
    assert!(result.is_ok(), "Task should be cancelled");
    Ok(())
}

/// Test retry mechanism
#[tokio::test]
async fn test_task_retry() -> TaskResult<()> {
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = QueueManager::new(&nats_url).await?;
    
    // Create a task that fails initially but succeeds on retry
    let task_id = Uuid::new_v4();
    let mut metadata = TaskMetadata::default();
    metadata.retry_policy.max_retries = 3;
    
    let task = TaskInfo {
        id: task_id,
        method: "test.flaky".to_string(),
        args: vec![serde_json::json!({"fail_count": 2})],
        metadata,
    };
    
    // Submit task
    queue.submit_task(task).await?;
    
    // Wait for task to complete (should succeed after retries)
    let result = timeout(Duration::from_secs(60), async {
        loop {
            if let Ok(status) = queue.get_task_status(&task_id).await {
                match status {
                    TaskStatus::Completed { .. } => return Ok(()),
                    TaskStatus::Failed { error, .. } => return Err(error),
                    _ => tokio::time::sleep(Duration::from_millis(500)).await,
                }
            }
        }
    }).await;
    
    assert!(result.is_ok(), "Task should succeed after retries");
    Ok(())
}
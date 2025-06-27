use task_common::models::{TaskInfo, TaskStatus, TaskMetadata};
use chrono::Utc;
use uuid::Uuid;

#[test]
fn test_task_creation() {
    let task = TaskInfo {
        id: Uuid::new_v4(),
        method: "test".to_string(),
        args: vec![serde_json::json!({"value": 42})],
        dependencies: vec![],
        priority: 5,
        metadata: TaskMetadata::default(),
        status: TaskStatus::Pending,
        created_at: Utc::now(),
        updated_at: Utc::now(),
    };
    
    assert_eq!(task.method, "test");
    assert_eq!(task.priority, 5);
    assert!(matches!(task.status, TaskStatus::Pending));
}

#[tokio::test]
async fn test_async_task() {
    let result = async {
        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
        42
    }.await;
    
    assert_eq!(result, 42);
}

#[test]
fn test_task_status_transitions() {
    let status = TaskStatus::Pending;
    assert!(matches!(status, TaskStatus::Pending));
    
    let running_status = TaskStatus::Running {
        worker_id: "worker-1".to_string(),
        started_at: Utc::now(),
    };
    
    if let TaskStatus::Running { worker_id, .. } = running_status {
        assert_eq!(worker_id, "worker-1");
    } else {
        panic!("Expected Running status");
    }
    
    let succeeded_status = TaskStatus::Succeeded {
        completed_at: Utc::now(),
        duration_ms: 1000,
    };
    
    if let TaskStatus::Succeeded { duration_ms, .. } = succeeded_status {
        assert_eq!(duration_ms, 1000);
    } else {
        panic!("Expected Succeeded status");
    }
}
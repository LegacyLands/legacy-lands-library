use chrono::{Duration, Utc};
use serde_json::json;
use serial_test::serial;
use std::collections::HashMap;
use std::env;
use std::sync::Arc;
use task_storage::{
    config::{PostgresConfig, StorageConfig},
    create_storage,
    models::{PageInfo, StoredTaskResult, TaskExecutionHistory, TaskQuery, TaskStatus},
    TaskStorage,
};
use uuid::Uuid;

/// Helper to create a test task result
fn create_test_task_result(method: &str, status: TaskStatus) -> StoredTaskResult {
    StoredTaskResult {
        task_id: Uuid::new_v4(),
        method: method.to_string(),
        args: vec![json!("arg1"), json!(42)],
        status,
        result: match status {
            TaskStatus::Succeeded => Some(json!({"output": "success"})),
            _ => None,
        },
        error: match status {
            TaskStatus::Failed => Some("Test error".to_string()),
            _ => None,
        },
        worker_id: Some("test-worker".to_string()),
        node_name: Some("test-node".to_string()),
        created_at: Utc::now(),
        started_at: Some(Utc::now()),
        completed_at: match status {
            TaskStatus::Succeeded | TaskStatus::Failed => Some(Utc::now()),
            _ => None,
        },
        duration_ms: match status {
            TaskStatus::Succeeded | TaskStatus::Failed => Some(1000),
            _ => None,
        },
        retry_count: 0,
        metadata: HashMap::from([
            ("env".to_string(), "test".to_string()),
            ("version".to_string(), "1.0".to_string()),
        ]),
        priority: 1,
        updated_at: Utc::now(),
        queue_name: Some("default".to_string()),
        tags: vec!["test".to_string(), "integration".to_string()],
    }
}

/// Helper to create test execution history
fn create_test_execution_history(task_id: Uuid, attempt: u32) -> TaskExecutionHistory {
    TaskExecutionHistory {
        task_id,
        execution_id: Uuid::new_v4(),
        status: TaskStatus::Succeeded,
        executed_at: Utc::now() - Duration::minutes(5),
        completed_at: Utc::now(),
        duration_ms: 300,
        error: None,
        worker_id: Some("test-worker".to_string()),
        worker_node: Some("test-node".to_string()),
        retry_attempt: attempt,
        metrics: HashMap::from([
            ("cpu_usage".to_string(), json!(50.5)),
            ("memory_mb".to_string(), json!(128)),
        ]),
    }
}

/// Create storage backend based on environment
async fn create_test_storage() -> Option<Arc<dyn TaskStorage>> {
    // Check for PostgreSQL configuration
    if let Ok(postgres_url) = env::var("TEST_POSTGRES_URL") {
        let config = StorageConfig::Postgres(PostgresConfig {
            url: postgres_url,
            max_connections: 5,
            connect_timeout_seconds: 30,
            run_migrations: true,
        });
        match create_storage(&config).await {
            Ok(storage) => return Some(storage),
            Err(e) => eprintln!("Failed to connect to PostgreSQL: {}", e),
        }
    }


    None
}

#[tokio::test]
#[serial]
async fn test_store_and_retrieve_task_result() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing. Set TEST_POSTGRES_URL or TEST_MONGODB_URL");
        return;
    };

    // Create and store a task result
    let task = create_test_task_result("test_method", TaskStatus::Succeeded);
    let task_id = task.task_id;
    
    storage.store_result(task.clone()).await.unwrap();

    // Retrieve the task result
    let retrieved = storage.get_result(task_id).await.unwrap();
    
    assert_eq!(retrieved.task_id, task_id);
    assert_eq!(retrieved.method, "test_method");
    assert_eq!(retrieved.status, TaskStatus::Succeeded);
    assert_eq!(retrieved.worker_id, task.worker_id);
    assert!(retrieved.result.is_some());
    assert!(retrieved.error.is_none());
}

#[tokio::test]
#[serial]
async fn test_query_results_by_status() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Store tasks with different statuses
    let task1 = create_test_task_result("method1", TaskStatus::Succeeded);
    let task2 = create_test_task_result("method2", TaskStatus::Failed);
    let task3 = create_test_task_result("method3", TaskStatus::Running);
    
    storage.store_result(task1).await.unwrap();
    storage.store_result(task2).await.unwrap();
    storage.store_result(task3).await.unwrap();

    // Query only succeeded tasks
    let query = TaskQuery {
        status: Some(TaskStatus::Succeeded),
        ..Default::default()
    };
    let (results, _total) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    assert!(!results.is_empty());
    assert!(results.iter().all(|r| r.status == TaskStatus::Succeeded));
}

#[tokio::test]
#[serial]
async fn test_query_results_by_method() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Store tasks with different methods
    let task1 = create_test_task_result("email.send", TaskStatus::Succeeded);
    let task2 = create_test_task_result("email.send", TaskStatus::Failed);
    let task3 = create_test_task_result("report.generate", TaskStatus::Succeeded);
    
    storage.store_result(task1).await.unwrap();
    storage.store_result(task2).await.unwrap();
    storage.store_result(task3).await.unwrap();

    // Query by method
    let query = TaskQuery {
        method: Some("email.send".to_string()),
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    assert!(results.len() >= 2);
    assert!(results.iter().all(|r| r.method == "email.send"));
}

#[tokio::test]
#[serial]
async fn test_query_results_by_worker() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Create tasks with different workers
    let mut task1 = create_test_task_result("method1", TaskStatus::Succeeded);
    task1.worker_id = Some("worker-1".to_string());
    
    let mut task2 = create_test_task_result("method2", TaskStatus::Succeeded);
    task2.worker_id = Some("worker-2".to_string());
    
    storage.store_result(task1).await.unwrap();
    storage.store_result(task2).await.unwrap();

    // Query by worker
    let query = TaskQuery {
        worker_id: Some("worker-1".to_string()),
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    assert!(!results.is_empty());
    assert!(results.iter().all(|r| r.worker_id.as_ref() == Some(&"worker-1".to_string())));
}

#[tokio::test]
#[serial]
async fn test_query_results_by_queue() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Create tasks with different queues
    let mut task1 = create_test_task_result("method1", TaskStatus::Succeeded);
    task1.queue_name = Some("priority".to_string());
    
    let mut task2 = create_test_task_result("method2", TaskStatus::Succeeded);
    task2.queue_name = Some("default".to_string());
    
    storage.store_result(task1).await.unwrap();
    storage.store_result(task2).await.unwrap();

    // Query by queue
    let query = TaskQuery {
        queue_name: Some("priority".to_string()),
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    assert!(!results.is_empty());
    assert!(results.iter().all(|r| r.queue_name.as_ref() == Some(&"priority".to_string())));
}

#[tokio::test]
#[serial]
async fn test_query_results_by_tags() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Create tasks with different tags
    let mut task1 = create_test_task_result("method1", TaskStatus::Succeeded);
    task1.tags = vec!["urgent".to_string(), "customer".to_string()];
    
    let mut task2 = create_test_task_result("method2", TaskStatus::Succeeded);
    task2.tags = vec!["batch".to_string(), "internal".to_string()];
    
    storage.store_result(task1).await.unwrap();
    storage.store_result(task2).await.unwrap();

    // Query by tags
    let query = TaskQuery {
        tags: vec!["urgent".to_string()],
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    assert!(!results.is_empty());
    assert!(results.iter().all(|r| r.tags.contains(&"urgent".to_string())));
}

#[tokio::test]
#[serial]
async fn test_query_results_by_date_range() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    let now = Utc::now();
    
    // Create tasks with different creation times
    let mut task1 = create_test_task_result("method1", TaskStatus::Succeeded);
    task1.created_at = now - Duration::hours(2);
    
    let mut task2 = create_test_task_result("method2", TaskStatus::Succeeded);
    task2.created_at = now - Duration::minutes(30);
    
    storage.store_result(task1).await.unwrap();
    storage.store_result(task2).await.unwrap();

    // Query by date range
    let query = TaskQuery {
        created_after: Some(now - Duration::hours(1)),
        created_before: Some(now),
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    // Should only find task2
    assert!(!results.is_empty());
    assert!(results.iter().all(|r| r.created_at > now - Duration::hours(1)));
}

#[tokio::test]
#[serial]
async fn test_query_results_with_metadata() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Create tasks with different metadata
    let mut task1 = create_test_task_result("method1", TaskStatus::Succeeded);
    task1.metadata.insert("customer_id".to_string(), "123".to_string());
    
    let mut task2 = create_test_task_result("method2", TaskStatus::Succeeded);
    task2.metadata.insert("customer_id".to_string(), "456".to_string());
    
    storage.store_result(task1).await.unwrap();
    storage.store_result(task2).await.unwrap();

    // Query by metadata
    let mut query_metadata = HashMap::new();
    query_metadata.insert("customer_id".to_string(), "123".to_string());
    
    let query = TaskQuery {
        metadata: query_metadata,
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    assert!(!results.is_empty());
    assert!(results.iter().all(|r| 
        r.metadata.get("customer_id") == Some(&"123".to_string())
    ));
}

#[tokio::test]
#[serial]
async fn test_pagination() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Store multiple tasks
    for i in 0..15 {
        let mut task = create_test_task_result(&format!("method_{}", i), TaskStatus::Succeeded);
        task.created_at = Utc::now() - Duration::minutes(i as i64);
        storage.store_result(task).await.unwrap();
    }

    // Test first page
    let page_info = PageInfo {
        page: 0,
        page_size: 5,
    };
    let (results1, total) = storage.query_results(TaskQuery::default(), page_info).await.unwrap();
    
    assert_eq!(results1.len(), 5);
    assert!(total >= 15);

    // Test second page
    let page_info = PageInfo {
        page: 1,
        page_size: 5,
    };
    let (results2, _) = storage.query_results(TaskQuery::default(), page_info).await.unwrap();
    
    assert_eq!(results2.len(), 5);
    
    // Ensure different results
    let ids1: Vec<_> = results1.iter().map(|r| r.task_id).collect();
    let ids2: Vec<_> = results2.iter().map(|r| r.task_id).collect();
    assert!(ids1.iter().all(|id| !ids2.contains(id)));
}

#[tokio::test]
#[serial]
async fn test_store_and_retrieve_execution_history() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // First create a task to satisfy foreign key constraint
    let task = create_test_task_result("history_test", TaskStatus::Succeeded);
    let task_id = task.task_id;
    storage.store_result(task).await.unwrap();
    
    // Store multiple execution history entries
    for i in 0..3 {
        let history = create_test_execution_history(task_id, i);
        storage.store_history(history).await.unwrap();
    }

    // Retrieve history
    let history = storage.get_history(task_id, None).await.unwrap();
    
    assert_eq!(history.len(), 3);
    assert!(history.iter().all(|h| h.task_id == task_id));
    
    // Test with limit
    let limited_history = storage.get_history(task_id, Some(2)).await.unwrap();
    assert!(limited_history.len() <= 2);
}

#[tokio::test]
#[serial]
async fn test_cleanup_old_results() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    let now = Utc::now();
    
    // Store old and new tasks
    let mut old_task = create_test_task_result("old_method", TaskStatus::Succeeded);
    old_task.created_at = now - Duration::days(31);
    old_task.updated_at = now - Duration::days(31);
    old_task.completed_at = Some(now - Duration::days(31));
    
    let new_task = create_test_task_result("new_method", TaskStatus::Succeeded);
    
    storage.store_result(old_task).await.unwrap();
    storage.store_result(new_task).await.unwrap();

    // Cleanup tasks older than 30 days
    let deleted = storage.cleanup_results(now - Duration::days(30)).await.unwrap();
    
    assert!(deleted >= 1);
    
    // Verify old task is gone
    let query = TaskQuery {
        method: Some("old_method".to_string()),
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    assert!(results.is_empty());
    
    // Verify new task still exists
    let query = TaskQuery {
        method: Some("new_method".to_string()),
        ..Default::default()
    };
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    assert!(!results.is_empty());
}

#[tokio::test]
#[serial]
async fn test_get_statistics() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    let now = Utc::now();
    
    // Store tasks with different statuses and methods
    for i in 0..5 {
        let status = if i % 2 == 0 { TaskStatus::Succeeded } else { TaskStatus::Failed };
        let method = if i < 3 { "email.send" } else { "report.generate" };
        let mut task = create_test_task_result(method, status);
        task.created_at = now - Duration::minutes(i as i64);
        storage.store_result(task).await.unwrap();
    }

    // Get statistics grouped by status
    let stats = storage.get_statistics(
        now - Duration::hours(1),
        now,
        Some("status".to_string())
    ).await.unwrap();
    
    assert!(!stats.is_empty());
    
    // Get statistics grouped by method
    let stats = storage.get_statistics(
        now - Duration::hours(1),
        now,
        Some("method".to_string())
    ).await.unwrap();
    
    assert!(!stats.is_empty());
}

#[tokio::test]
#[serial]
async fn test_health_check() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Health check should succeed
    storage.health_check().await.unwrap();
}

#[tokio::test]
#[serial]
async fn test_concurrent_updates() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    let task = create_test_task_result("concurrent_test", TaskStatus::Running);
    let task_id = task.task_id;
    
    // Store initial task
    storage.store_result(task).await.unwrap();

    // Simulate concurrent updates
    let storage1 = storage.clone();
    let storage2 = storage.clone();
    
    let handle1 = tokio::spawn(async move {
        let mut task = storage1.get_result(task_id).await.unwrap();
        task.status = TaskStatus::Succeeded;
        task.result = Some(json!({"result": "from_worker_1"}));
        task.completed_at = Some(Utc::now());
        storage1.store_result(task).await
    });
    
    let handle2 = tokio::spawn(async move {
        let mut task = storage2.get_result(task_id).await.unwrap();
        task.retry_count += 1;
        task.metadata.insert("retry_reason".to_string(), "timeout".to_string());
        storage2.store_result(task).await
    });

    // Wait for both updates
    let _ = handle1.await.unwrap();
    let _ = handle2.await.unwrap();
    
    // Verify final state
    let final_task = storage.get_result(task_id).await.unwrap();
    
    // One of the updates should have succeeded
    assert!(final_task.status == TaskStatus::Succeeded || final_task.retry_count > 0);
}

#[tokio::test]
#[serial]
async fn test_task_not_found() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    let non_existent_id = Uuid::new_v4();
    
    // Should return NotFound error
    let result = storage.get_result(non_existent_id).await;
    assert!(result.is_err());
    
    match result.unwrap_err() {
        task_storage::StorageError::NotFound => (),
        other => panic!("Expected NotFound error, got: {:?}", other),
    }
}

#[tokio::test]
#[serial]
async fn test_empty_query_results() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Query with very specific filter that shouldn't match anything
    let query = TaskQuery {
        method: Some("non_existent_method_xyz123".to_string()),
        ..Default::default()
    };
    
    let (results, total) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    assert!(results.is_empty());
    assert_eq!(total, 0);
}

#[tokio::test]
#[serial]
async fn test_sorting() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Store tasks with different priorities
    for i in 0..5 {
        let mut task = create_test_task_result(&format!("method_{}", i), TaskStatus::Succeeded);
        task.priority = i;
        task.created_at = Utc::now() - Duration::minutes(5 - i as i64);
        storage.store_result(task).await.unwrap();
    }

    // Query with sorting by priority descending
    let query = TaskQuery {
        sort_by: Some("priority".to_string()),
        sort_ascending: false,
        ..Default::default()
    };
    
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    // Verify descending order
    for i in 1..results.len() {
        assert!(results[i-1].priority >= results[i].priority);
    }
    
    // Query with sorting by created_at ascending
    let query = TaskQuery {
        sort_by: Some("created_at".to_string()),
        sort_ascending: true,
        ..Default::default()
    };
    
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    // Verify ascending order
    for i in 1..results.len() {
        assert!(results[i-1].created_at <= results[i].created_at);
    }
}

#[tokio::test]
#[serial]
async fn test_update_existing_task() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    // Create and store initial task
    let mut task = create_test_task_result("update_test", TaskStatus::Running);
    let task_id = task.task_id;
    storage.store_result(task.clone()).await.unwrap();

    // Update the task
    task.status = TaskStatus::Succeeded;
    task.result = Some(json!({"updated": true}));
    task.completed_at = Some(Utc::now());
    task.duration_ms = Some(2000);
    task.updated_at = Utc::now();
    
    storage.store_result(task).await.unwrap();

    // Verify update
    let updated = storage.get_result(task_id).await.unwrap();
    
    assert_eq!(updated.status, TaskStatus::Succeeded);
    assert!(updated.result.is_some());
    assert!(updated.completed_at.is_some());
    assert_eq!(updated.duration_ms, Some(2000));
}

#[tokio::test]
#[serial]
async fn test_complex_query() {
    let Some(storage) = create_test_storage().await else {
        eprintln!("No database configured for testing");
        return;
    };

    let now = Utc::now();
    
    // Create diverse set of tasks
    for i in 0..10 {
        let mut task = create_test_task_result(&format!("method_{}", i % 3), TaskStatus::Succeeded);
        task.queue_name = if i % 2 == 0 { Some("default".to_string()) } else { Some("priority".to_string()) };
        task.worker_id = Some(format!("worker-{}", i % 2));
        task.created_at = now - Duration::minutes(i as i64);
        
        if i % 3 == 0 {
            task.tags.push("special".to_string());
        }
        
        storage.store_result(task).await.unwrap();
    }

    // Complex query with multiple filters
    let query = TaskQuery {
        status: Some(TaskStatus::Succeeded),
        queue_name: Some("priority".to_string()),
        tags: vec!["special".to_string()],
        created_after: Some(now - Duration::hours(1)),
        sort_by: Some("created_at".to_string()),
        sort_ascending: false,
        ..Default::default()
    };
    
    let (results, _) = storage.query_results(query, PageInfo::default()).await.unwrap();
    
    // Verify all filters are applied
    for result in &results {
        assert_eq!(result.status, TaskStatus::Succeeded);
        assert_eq!(result.queue_name.as_ref(), Some(&"priority".to_string()));
        assert!(result.tags.contains(&"special".to_string()));
        assert!(result.created_at > now - Duration::hours(1));
    }
}
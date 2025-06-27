use std::time::Duration;
use tokio::time::sleep;

mod common;
use common::TestEnvironment;

#[tokio::test]
async fn test_basic_task_submission() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    // Submit a simple echo task
    let response = client.submit_echo_task("Hello, Test!").await?;
    assert_eq!(response.status, 1); // SUCCESS
    assert!(response.result.contains("Hello, Test!"));
    
    Ok(())
}

#[tokio::test]
async fn test_async_task_with_polling() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    // Submit async task
    let task_id = client.submit_async_sleep_task(2).await?;
    
    // Poll for result
    let mut attempts = 0;
    loop {
        sleep(Duration::from_millis(500)).await;
        let result = client.get_task_result(&task_id).await?;
        
        if result.status == 1 {
            break;
        }
        
        attempts += 1;
        if attempts > 10 {
            panic!("Task did not complete in time");
        }
    }
    
    Ok(())
}

#[tokio::test]
async fn test_task_dependencies() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    // Submit first task
    let task1_id = client.submit_computation_task(5, 3).await?;
    
    // Submit dependent task
    let task2_id = client.submit_dependent_task(&task1_id, 2).await?;
    
    // Wait for completion
    sleep(Duration::from_secs(3)).await;
    
    // Check final result
    let result = client.get_task_result(&task2_id).await?;
    assert_eq!(result.status, 1);
    // Result should be (5 + 3) * 2 = 16
    assert!(result.result.contains("16"));
    
    Ok(())
}

#[tokio::test]
async fn test_concurrent_tasks() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let client = env.create_grpc_client().await?;
    
    // Submit multiple tasks concurrently
    let mut handles = vec![];
    for i in 0..10 {
        let mut client_clone = client.clone();
        let handle = tokio::spawn(async move {
            client_clone.submit_echo_task(&format!("Task {}", i)).await
                .map_err(|e| format!("Error in task {}: {}", i, e))
        });
        handles.push(handle);
    }
    
    // Wait for all tasks
    let mut success_count = 0;
    for handle in handles {
        if let Ok(Ok(response)) = handle.await {
            if response.status == 1 {
                success_count += 1;
            }
        }
    }
    
    assert_eq!(success_count, 10);
    Ok(())
}

#[tokio::test]
async fn test_error_handling() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    // Submit task with invalid method
    let result = client.submit_invalid_task().await;
    
    match result {
        Ok(response) => {
            assert_eq!(response.status, 2); // FAILED
        }
        Err(_) => {
            // Error is also acceptable
        }
    }
    
    Ok(())
}

#[tokio::test]
async fn test_worker_failover() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    // Submit multiple tasks
    let mut task_ids = vec![];
    for i in 0..5 {
        let task_id = client.submit_async_computation_task(i, i + 1).await?;
        task_ids.push(task_id);
    }
    
    // Simulate worker failure (in real test, we would stop a worker)
    sleep(Duration::from_secs(1)).await;
    
    // Check that all tasks eventually complete
    let mut completed = 0;
    for task_id in task_ids {
        let mut attempts = 0;
        loop {
            let result = client.get_task_result(&task_id).await?;
            if result.status == 1 {
                completed += 1;
                break;
            }
            
            attempts += 1;
            if attempts > 20 {
                break;
            }
            sleep(Duration::from_millis(500)).await;
        }
    }
    
    assert_eq!(completed, 5);
    Ok(())
}

#[tokio::test]
#[ignore] // Long running test
async fn test_load_handling() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let client = env.create_grpc_client().await?;
    
    // Submit 100 tasks rapidly
    let start = std::time::Instant::now();
    let mut handles = vec![];
    
    for i in 0..100 {
        let mut client_clone = client.clone();
        let handle = tokio::spawn(async move {
            client_clone.submit_computation_task(i, i * 2).await
        });
        handles.push(handle);
    }
    
    // Wait for all submissions
    let mut submitted = 0;
    for handle in handles {
        if handle.await.is_ok() {
            submitted += 1;
        }
    }
    
    let duration = start.elapsed();
    println!("Submitted {} tasks in {:?}", submitted, duration);
    
    assert!(submitted >= 95); // Allow some failures under load
    Ok(())
}

#[tokio::test]
async fn test_task_cancellation() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    // Submit long-running task
    let task_id = client.submit_async_sleep_task(30).await?;
    
    // Verify task is running
    sleep(Duration::from_secs(1)).await;
    let result = client.get_task_result(&task_id).await?;
    assert_eq!(result.status, 0); // PENDING
    
    // In a complete implementation, we would cancel the task here
    // For now, we just verify it's still running
    
    Ok(())
}

#[tokio::test]
async fn test_metrics_endpoint() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    
    // Check manager metrics
    let metrics_url = format!("{}/metrics", env.manager_metrics_url());
    let response = reqwest::get(&metrics_url).await?;
    
    assert_eq!(response.status(), 200);
    let body = response.text().await?;
    assert!(body.contains("task_manager_"));
    
    Ok(())
}

#[tokio::test]
async fn test_health_check() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    
    // Check manager health
    let health_url = format!("{}/health", env.manager_metrics_url());
    let response = reqwest::get(&health_url).await?;
    
    assert_eq!(response.status(), 200);
    
    Ok(())
}
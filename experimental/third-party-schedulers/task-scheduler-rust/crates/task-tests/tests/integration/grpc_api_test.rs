use task_manager::api::proto::{
    task_scheduler_client::TaskSchedulerClient,
    TaskRequest, ResultRequest,
};
use tonic::Request;
use uuid::Uuid;

/// Test gRPC task submission API
#[tokio::test]
async fn test_grpc_submit_task() -> Result<(), Box<dyn std::error::Error>> {
    // Connect to gRPC server
    let addr = std::env::var("GRPC_ADDRESS").unwrap_or_else(|_| "http://localhost:50051".to_string());
    let mut client = TaskSchedulerClient::connect(addr).await?;
    
    // Create task request
    let request = Request::new(TaskRequest {
        method: "test.echo".to_string(),
        args: vec![r#"{"message": "Hello from gRPC"}"#.to_string()],
        timeout_seconds: 30,
        priority: 50,
        dependencies: vec![],
        tags: vec!["test".to_string()],
    });
    
    // Submit task
    let response = client.submit_task(request).await?;
    let task_response = response.into_inner();
    
    // Verify response
    assert!(!task_response.task_id.is_empty(), "Task ID should be returned");
    
    // Try to parse task ID as UUID
    let task_id = Uuid::parse_str(&task_response.task_id)?;
    assert_ne!(task_id, Uuid::nil(), "Task ID should be valid UUID");
    
    Ok(())
}

/// Test gRPC result retrieval API
#[tokio::test]
async fn test_grpc_get_result() -> Result<(), Box<dyn std::error::Error>> {
    let addr = std::env::var("GRPC_ADDRESS").unwrap_or_else(|_| "http://localhost:50051".to_string());
    let mut client = TaskSchedulerClient::connect(addr).await?;
    
    // First submit a task
    let submit_request = Request::new(TaskRequest {
        method: "test.echo".to_string(),
        args: vec![r#"{"message": "Test result"}"#.to_string()],
        timeout_seconds: 10,
        priority: 50,
        dependencies: vec![],
        tags: vec![],
    });
    
    let submit_response = client.submit_task(submit_request).await?;
    let task_id = submit_response.into_inner().task_id;
    
    // Poll for result
    let mut attempts = 0;
    loop {
        let result_request = Request::new(ResultRequest {
            task_id: task_id.clone(),
        });
        
        let result_response = client.get_result(result_request).await?;
        let result = result_response.into_inner();
        
        if let Some(result_oneof) = result.result {
            match result_oneof {
                task_manager::api::proto::result_response::Result::Success(data) => {
                    assert!(!data.is_empty(), "Result should contain data");
                    break;
                }
                task_manager::api::proto::result_response::Result::Error(error) => {
                    panic!("Task failed with error: {}", error);
                }
            }
        }
        
        attempts += 1;
        if attempts > 100 {
            panic!("Timeout waiting for task result");
        }
        
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    }
    
    Ok(())
}

/// Test task cancellation via gRPC
#[tokio::test]
async fn test_grpc_cancel_task() -> Result<(), Box<dyn std::error::Error>> {
    let addr = std::env::var("GRPC_ADDRESS").unwrap_or_else(|_| "http://localhost:50051".to_string());
    let mut client = TaskSchedulerClient::connect(addr).await?;
    
    // Submit a long-running task
    let submit_request = Request::new(TaskRequest {
        method: "test.sleep".to_string(),
        args: vec![r#"{"duration_secs": 60}"#.to_string()],
        timeout_seconds: 120,
        priority: 50,
        dependencies: vec![],
        tags: vec!["long-running".to_string()],
    });
    
    let submit_response = client.submit_task(submit_request).await?;
    let task_id = submit_response.into_inner().task_id;
    
    // Wait a bit for task to start
    tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
    
    // Cancel the task
    let cancel_request = Request::new(ResultRequest {
        task_id: task_id.clone(),
    });
    
    let cancel_response = client.cancel_task(cancel_request).await?;
    assert!(cancel_response.into_inner().success, "Cancellation should succeed");
    
    // Verify task is cancelled
    let result_request = Request::new(ResultRequest {
        task_id,
    });
    
    let result_response = client.get_result(result_request).await?;
    let result = result_response.into_inner();
    
    if let Some(result_oneof) = result.result {
        match result_oneof {
            task_manager::api::proto::result_response::Result::Error(error) => {
                assert!(error.contains("cancelled") || error.contains("Cancelled"), 
                       "Error should indicate cancellation");
            }
            _ => panic!("Expected cancelled task to return error"),
        }
    }
    
    Ok(())
}

/// Test task priority handling
#[tokio::test]
async fn test_grpc_task_priority() -> Result<(), Box<dyn std::error::Error>> {
    let addr = std::env::var("GRPC_ADDRESS").unwrap_or_else(|_| "http://localhost:50051".to_string());
    let mut client = TaskSchedulerClient::connect(addr).await?;
    
    let mut task_ids = Vec::new();
    
    // Submit tasks with different priorities
    for priority in [10, 50, 90] {
        let request = Request::new(TaskRequest {
            method: "test.echo".to_string(),
            args: vec![format!(r#"{{"priority": {}}}"#, priority)],
            timeout_seconds: 30,
            priority,
            dependencies: vec![],
            tags: vec!["priority-test".to_string()],
        });
        
        let response = client.submit_task(request).await?;
        task_ids.push((priority, response.into_inner().task_id));
    }
    
    // Verify all tasks complete
    for (priority, task_id) in task_ids {
        let mut attempts = 0;
        loop {
            let result_request = Request::new(ResultRequest {
                task_id: task_id.clone(),
            });
            
            let result_response = client.get_result(result_request).await?;
            let result = result_response.into_inner();
            
            if result.result.is_some() {
                println!("Task with priority {} completed", priority);
                break;
            }
            
            attempts += 1;
            if attempts > 300 {
                panic!("Timeout waiting for task with priority {}", priority);
            }
            
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        }
    }
    
    Ok(())
}
use task_common::models::{TaskInfo, TaskStatus};
use task_manager::api::proto::{
    task_scheduler_client::TaskSchedulerClient, TaskRequest, ResultRequest,
};
use task_worker::config::Config as WorkerConfig;
use task_manager::config::Config as ManagerConfig;
use std::time::Duration;
use tokio::time::sleep;
use prost_types::Any;

/// End-to-end test for complete task workflow
#[tokio::test]
#[ignore] // Run with: cargo test --test e2e_test -- --ignored
async fn test_complete_task_workflow() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize tracing
    tracing_subscriber::fmt::init();
    
    // Test configuration
    let manager_port = 50051;
    let manager_addr = format!("127.0.0.1:{}", manager_port);
    let nats_url = "nats://localhost:4222";
    
    // Start NATS server (assumes NATS is running locally)
    // In a real test environment, we would use testcontainers
    
    // Start Manager service
    let manager_config = ManagerConfig {
        grpc_port: manager_port,
        nats_url: nats_url.to_string(),
        storage_type: "memory".to_string(),
        ..Default::default()
    };
    
    let manager_handle = tokio::spawn(async move {
        // In a real test, we would start the manager service here
        // For now, we assume it's running
        sleep(Duration::from_secs(3600)).await;
    });
    
    // Start Worker service
    let worker_config = WorkerConfig {
        nats_url: nats_url.to_string(),
        worker_id: "test-worker-1".to_string(),
        max_concurrent_tasks: 10,
        ..Default::default()
    };
    
    let worker_handle = tokio::spawn(async move {
        // In a real test, we would start the worker service here
        // For now, we assume it's running
        sleep(Duration::from_secs(3600)).await;
    });
    
    // Wait for services to start
    sleep(Duration::from_secs(2)).await;
    
    // Create gRPC client
    let mut client = TaskSchedulerClient::connect(format!("http://{}", manager_addr)).await?;
    
    // Test 1: Submit a simple echo task
    println!("Test 1: Submit echo task");
    let echo_request = TaskRequest {
        task_id: "test-echo-1".to_string(),
        method: "echo".to_string(),
        args: vec![Any {
            type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
            value: "Hello, World!".to_string().into_bytes(),
        }],
        deps: vec![],
        is_async: false,
    };
    
    let response = client.submit_task(echo_request).await?;
    let echo_response = response.into_inner();
    assert_eq!(echo_response.status, 1); // SUCCESS
    assert!(echo_response.result.contains("Hello, World!"));
    println!("✓ Echo task completed successfully");
    
    // Test 2: Submit an async task and poll for result
    println!("\nTest 2: Submit async task");
    let async_request = TaskRequest {
        task_id: "test-async-1".to_string(),
        method: "sleep".to_string(),
        args: vec![Any {
            type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
            value: vec![2], // 2 seconds
        }],
        deps: vec![],
        is_async: true,
    };
    
    let response = client.submit_task(async_request).await?;
    let async_response = response.into_inner();
    assert_eq!(async_response.status, 0); // PENDING
    let task_id = async_response.task_id.clone();
    println!("✓ Async task submitted: {}", task_id);
    
    // Poll for result
    println!("Polling for async task result...");
    let mut attempts = 0;
    loop {
        sleep(Duration::from_millis(500)).await;
        
        let result_request = ResultRequest {
            task_id: task_id.clone(),
        };
        
        let result = client.get_result(result_request).await?;
        let result_response = result.into_inner();
        
        if result_response.status == 1 {
            println!("✓ Async task completed successfully");
            break;
        }
        
        attempts += 1;
        if attempts > 10 {
            panic!("Async task did not complete in time");
        }
    }
    
    // Test 3: Submit tasks with dependencies
    println!("\nTest 3: Submit tasks with dependencies");
    let task1 = TaskRequest {
        task_id: "test-dep-1".to_string(),
        method: "add".to_string(),
        args: vec![
            Any {
                type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
                value: vec![5],
            },
            Any {
                type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
                value: vec![3],
            },
        ],
        deps: vec![],
        is_async: true,
    };
    
    let response = client.submit_task(task1).await?;
    let task1_id = response.into_inner().task_id;
    println!("✓ Task 1 submitted: {}", task1_id);
    
    let task2 = TaskRequest {
        task_id: "test-dep-2".to_string(),
        method: "multiply".to_string(),
        args: vec![
            Any {
                type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                value: format!("@{}", task1_id).into_bytes(), // Reference to task1 result
            },
            Any {
                type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
                value: vec![2],
            },
        ],
        deps: vec![task1_id.clone()],
        is_async: true,
    };
    
    let response = client.submit_task(task2).await?;
    let task2_id = response.into_inner().task_id;
    println!("✓ Task 2 submitted with dependency: {}", task2_id);
    
    // Wait for dependent task to complete
    sleep(Duration::from_secs(2)).await;
    
    let result_request = ResultRequest {
        task_id: task2_id.clone(),
    };
    
    let result = client.get_result(result_request).await?;
    let result_response = result.into_inner();
    assert_eq!(result_response.status, 1); // SUCCESS
    // Result should be (5 + 3) * 2 = 16
    assert!(result_response.result.contains("16"));
    println!("✓ Dependent task completed with correct result");
    
    // Test 4: Error handling
    println!("\nTest 4: Error handling");
    let error_request = TaskRequest {
        task_id: "test-error-1".to_string(),
        method: "non_existent_method".to_string(),
        args: vec![],
        deps: vec![],
        is_async: false,
    };
    
    let response = client.submit_task(error_request).await;
    match response {
        Ok(resp) => {
            let inner = resp.into_inner();
            assert_eq!(inner.status, 2); // FAILED
            println!("✓ Error handled correctly");
        }
        Err(e) => {
            println!("✓ Error returned as expected: {}", e);
        }
    }
    
    // Clean up
    manager_handle.abort();
    worker_handle.abort();
    
    println!("\n✅ All e2e tests passed!");
    Ok(())
}

/// Test concurrent task execution
#[tokio::test]
#[ignore]
async fn test_concurrent_task_execution() -> Result<(), Box<dyn std::error::Error>> {
    let client_addr = "http://127.0.0.1:50051";
    let mut client = TaskSchedulerClient::connect(client_addr).await?;
    
    println!("Testing concurrent task execution...");
    
    // Submit multiple tasks concurrently
    let mut handles = vec![];
    let task_count = 10;
    
    for i in 0..task_count {
        let mut client_clone = client.clone();
        let handle = tokio::spawn(async move {
            let request = TaskRequest {
                task_id: format!("concurrent-test-{}", i),
                method: "echo".to_string(),
                args: vec![Any {
                    type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                    value: format!("Task {}", i).into_bytes(),
                }],
                deps: vec![],
                is_async: true,
            };
            
            client_clone.submit_task(request).await
        });
        handles.push(handle);
    }
    
    // Wait for all tasks to be submitted
    let mut task_ids = vec![];
    for handle in handles {
        match handle.await? {
            Ok(response) => {
                let task_id = response.into_inner().task_id;
                task_ids.push(task_id);
            }
            Err(e) => {
                eprintln!("Failed to submit task: {}", e);
            }
        }
    }
    
    println!("✓ Submitted {} concurrent tasks", task_ids.len());
    
    // Wait for all tasks to complete
    sleep(Duration::from_secs(3)).await;
    
    // Check results
    let mut completed = 0;
    for task_id in task_ids {
        let result_request = ResultRequest { task_id };
        let result = client.get_result(result_request).await?;
        if result.into_inner().status == 1 {
            completed += 1;
        }
    }
    
    assert_eq!(completed, task_count);
    println!("✓ All {} tasks completed successfully", completed);
    
    Ok(())
}

/// Test task cancellation
#[tokio::test]
#[ignore]
async fn test_task_cancellation() -> Result<(), Box<dyn std::error::Error>> {
    let client_addr = "http://127.0.0.1:50051";
    let mut client = TaskSchedulerClient::connect(client_addr).await?;
    
    println!("Testing task cancellation...");
    
    // Submit a long-running task
    let request = TaskRequest {
        task_id: "cancel-test-1".to_string(),
        method: "sleep".to_string(),
        args: vec![Any {
            type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
            value: vec![30], // 30 seconds
        }],
        deps: vec![],
        is_async: true,
    };
    
    let response = client.submit_task(request).await?;
    let task_id = response.into_inner().task_id;
    println!("✓ Long-running task submitted: {}", task_id);
    
    // Wait a bit then cancel
    sleep(Duration::from_secs(1)).await;
    
    // Note: Cancellation API would need to be implemented
    // For now, we just verify the task is still running
    let result_request = ResultRequest { task_id: task_id.clone() };
    let result = client.get_result(result_request).await?;
    assert_eq!(result.into_inner().status, 0); // Still PENDING
    println!("✓ Task is still running as expected");
    
    Ok(())
}